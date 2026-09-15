package dev.servereer.machineconstruct.grinder.econ;

import dev.servereer.machineconstruct.grinder.GrinderSpec;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Resolves a sell price for an item, honouring SmartSpawner's price-source modes
 * (SHOP_ONLY / SHOP_PRIORITY / CUSTOM_ONLY / CUSTOM_PRIORITY). Custom prices come
 * from {@code item_prices.yml} (material → price); shop-plugin integration is a
 * pluggable hook (see {@link #shopPrice}) that returns NaN until a shop adapter
 * is wired — until then SHOP_* modes fall back to custom prices, matching s4's
 * reality (Vault + custom, no shop GUI).
 */
public final class PriceService {

    private final Map<Material, Double> customPrices = new HashMap<>();
    private final double defaultPrice;
    private final String mode;
    private final boolean customEnabled;

    private PriceService(double defaultPrice, String mode, boolean customEnabled) {
        this.defaultPrice = defaultPrice;
        this.mode = mode == null ? "CUSTOM_PRIORITY" : mode.toUpperCase();
        this.customEnabled = customEnabled;
    }

    /**
     * A config-free price service that sells purely at the shop plugin's price (EconomyShopGUI), or
     * nothing if no shop is present. Used as the Factory District's auto-sell fallback when its machine
     * config declares no {@code economy:} section.
     */
    public static PriceService shopOnly() {
        return new PriceService(0.0, "SHOP_ONLY", false);
    }

    /** Load custom prices from the pack folder per the spec's economy config (grinder). */
    public static PriceService load(File packFolder, GrinderSpec.Economy econ, Logger log) {
        PriceService ps = new PriceService(econ.defaultPrice, econ.priceSourceMode, econ.customEnabled);
        if (econ.customEnabled && econ.pricesFile != null) loadPrices(ps, new File(packFolder, econ.pricesFile), log);
        return ps;
    }

    /** Load from a raw {@code economy:} section (any machine with a sell button). */
    public static PriceService load(File packFolder, ConfigurationSection econ, Logger log) {
        String mode = econ.getString("price_source_mode", "CUSTOM_PRIORITY");
        ConfigurationSection custom = econ.getConfigurationSection("custom_prices");
        boolean customEnabled = custom == null || custom.getBoolean("enabled", true);
        String file = (custom == null) ? "grinder/item_prices.yml" : custom.getString("file", "grinder/item_prices.yml");
        double def = (custom == null) ? 1.0 : custom.getDouble("default_price", 1.0);
        PriceService ps = new PriceService(def, mode, customEnabled);
        if (customEnabled && file != null) loadPrices(ps, new File(packFolder, file), log);
        return ps;
    }

    private static void loadPrices(PriceService ps, File f, Logger log) {
        if (!f.isFile()) { if (log != null) log.warning("[econ] prices file not found: " + f); return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection prices = y.getConfigurationSection("prices");
        if (prices == null) prices = y;   // tolerate a flat file
        for (String key : prices.getKeys(false)) {
            Material mat = Material.matchMaterial(key);
            if (mat != null) ps.customPrices.put(mat, prices.getDouble(key, 0));
        }
        if (log != null) log.info("[econ] loaded " + ps.customPrices.size() + " custom price(s) from " + f.getName());
    }

    /** The per-item sell price (for amount 1), or 0 if unsellable. */
    public double price(ItemStack item) {
        if (item == null) return 0;
        // Only PLAIN vanilla items sell — a named/lored/enchanted/custom-NBT item must never be valued or
        // auto-sold at its bare material's price (e.g. an imported "special" iron ingot). Withdrawal still works.
        if (!isPlain(item)) return 0;
        // PixelProfiler's server shop is authoritative when it prices the item: if /shop sells
        // it, use that price regardless of this machine's mode, so one shop drives every payout.
        double pp = PixelProfilerPrices.price(item.getType());
        if (!Double.isNaN(pp) && pp > 0) return pp;
        double shop = shopPrice(item);
        double custom = customPrice(item.getType());
        return switch (mode) {
            case "SHOP_ONLY" -> Double.isNaN(shop) ? 0 : shop;
            case "CUSTOM_ONLY" -> custom;
            case "SHOP_PRIORITY" -> !Double.isNaN(shop) ? shop : custom;
            default /* CUSTOM_PRIORITY */ -> custom > 0 ? custom : (Double.isNaN(shop) ? 0 : shop);
        };
    }

    /** A plain vanilla item = identical to a fresh stack of its material (no name/lore/enchants/CMD/NBT). */
    private static boolean isPlain(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (!it.hasItemMeta()) return true;   // fast path: no meta = plain (the common case)
        return it.isSimilar(new ItemStack(it.getType()));
    }

    private double customPrice(Material mat) {
        if (!customEnabled) return 0;
        Double p = customPrices.get(mat);
        if (p != null) return p;
        return defaultPrice;   // 0.0 disables selling unconfigured items
    }

    /**
     * Shop-plugin price hook — reflects into EconomyShopGUI's API (no hard dependency).
     * Returns the shop's sell price for one of {@code item}, or NaN if EconomyShopGUI
     * isn't present or doesn't sell it (callers fall back to custom prices).
     */
    private double shopPrice(ItemStack item) {
        java.lang.reflect.Method m = esgSellMethod();
        if (m == null) return Double.NaN;
        try {
            ItemStack one = item.clone();
            one.setAmount(1);
            Object r = m.invoke(null, one);
            if (r instanceof Number num) {
                double v = num.doubleValue();
                return v >= 0 ? v : Double.NaN;   // ESG returns null/negative when not sellable
            }
        } catch (Throwable ignored) { /* shop not ready / item not in shop → fall back */ }
        return Double.NaN;
    }

    // Resolved once: EconomyShopGUIHook.getItemSellPrice(ItemStack) → Double. null = ESG absent.
    private static volatile java.lang.reflect.Method esgSell;
    private static volatile boolean esgResolved;

    private static java.lang.reflect.Method esgSellMethod() {
        if (!esgResolved) synchronized (PriceService.class) {
            if (!esgResolved) {
                try {
                    Class<?> hook = Class.forName("me.gypopo.economyshopgui.api.EconomyShopGUIHook");
                    esgSell = hook.getMethod("getItemSellPrice", ItemStack.class);
                } catch (Throwable t) {
                    esgSell = null;   // EconomyShopGUI not installed
                }
                esgResolved = true;
            }
        }
        return esgSell;
    }
}
