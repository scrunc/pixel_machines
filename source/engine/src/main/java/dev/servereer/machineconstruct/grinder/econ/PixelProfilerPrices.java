package dev.servereer.machineconstruct.grinder.econ;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Bridge to PixelProfiler's server shop (/shop, /pishop). When enabled, MachineConstruct's
 * auto-sell / vault valuation prefers the price PixelProfiler's shop lists for an item over
 * EconomyShopGUI or custom prices — so a single shop drives every machine's payout, and
 * editing a price with {@code /pishop} propagates automatically.
 *
 * <p>Rather than take a hard dependency on (or require a restart of) PixelProfiler, this reads its
 * files straight from disk. Three sources, lowest priority first (later overrides earlier):
 * <ol>
 *   <li>{@code plugins/PixelProfiler/shops/**.yml} — every shop item with a native
 *       {@code trade.sell.money} offer contributes a <b>per-unit</b> price ({@code money / amount});
 *       if a material is sold in several shops the highest wins. Custom-model-data / custom-stack
 *       entries are skipped (they aren't plain vanilla items). This is the base "anchor" price.</li>
 *   <li>{@code plugins/PixelProfiler/dynamic_prices.yml} — the live market price computed hourly by
 *       PixelProfiler's dynamic-pricing engine; overrides the shop base.</li>
 *   <li>{@code plugins/PixelProfiler/prices.yml} — the hand-edited manual override table; wins over
 *       everything.</li>
 * </ol>
 * Editing any of these (then {@code /pixelprofiler reload}, or the engine's hourly tick) flows through
 * here on the next refresh.
 *
 * <p>The cache is a plain {@code Material -> price} snapshot rebuilt on a timer (see
 * {@link #refresh}). It's shared across all {@link PriceService} instances and read lock-free via
 * a volatile reference, so a stale read during a swap is harmless. When PixelProfiler isn't present
 * (its shops folder is missing) or the bridge is disabled, the cache is empty and every lookup
 * returns {@link Double#NaN} — callers then fall back to their normal price source.
 */
public final class PixelProfilerPrices {

    private PixelProfilerPrices() {}

    private static volatile Map<Material, Double> cache = Map.of();
    private static volatile boolean enabled;
    private static volatile File pluginDir;
    private static int lastCount = -1;

    /** Wire up the bridge from config (called once at enable). {@code pluginDir} = plugins/PixelProfiler. */
    public static void configure(File pluginDir, boolean enabled) {
        PixelProfilerPrices.pluginDir = pluginDir;
        PixelProfilerPrices.enabled = enabled;
        if (!enabled) cache = Map.of();
    }

    /** True if the bridge is turned on (regardless of whether PixelProfiler is currently present). */
    public static boolean enabled() { return enabled; }

    /** How many item prices are currently cached from PixelProfiler. */
    public static int count() { return cache.size(); }

    /**
     * The shop's sell price for one {@code mat}, or {@link Double#NaN} if PixelProfiler doesn't
     * sell it / the bridge is off. Positive results should override other price sources.
     */
    public static double price(Material mat) {
        if (mat == null) return Double.NaN;
        Double p = cache.get(mat);
        return p == null ? Double.NaN : p;
    }

    /** Re-read PixelProfiler's price files and swap in a fresh snapshot. Never throws. */
    public static void refresh(Logger log) {
        if (!enabled) { cache = Map.of(); return; }
        File dir = pluginDir;
        if (dir == null || !dir.isDirectory()) { swap(Map.of(), log, true); return; }   // PixelProfiler absent
        Map<Material, Double> next = new HashMap<>();
        try {
            // 1. shop trade.sell.money offers (lower priority)
            File shops = new File(dir, "shops");
            if (shops.isDirectory()) {
                Files.walk(shops.toPath())
                        .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .forEach(p -> scanShopFile(p.toFile(), next));
            }
            // 2. engine-computed live market prices — override the shop scan
            scanPriceBook(new File(dir, "dynamic_prices.yml"), next);
            // 3. manual dynamic price table — the hard override, wins over everything
            scanPriceBook(new File(dir, "prices.yml"), next);
        } catch (Exception e) {
            if (log != null) log.warning("[pixelprofiler-pricing] failed to read prices: " + e.getMessage());
            return;   // keep the previous snapshot rather than blanking prices on a transient error
        }
        swap(next, log, false);
    }

    private static void swap(Map<Material, Double> next, Logger log, boolean absent) {
        cache = next;
        if (log != null && next.size() != lastCount) {
            if (absent) log.info("[pixelprofiler-pricing] PixelProfiler folder not found — bridge idle.");
            else log.info("[pixelprofiler-pricing] loaded " + next.size() + " price(s) from PixelProfiler (prices.yml + shops).");
            lastCount = next.size();
        }
    }

    /** Overlay the dynamic price table (prices.yml: {@code MATERIAL -> per-unit price}) — these win. */
    private static void scanPriceBook(File f, Map<Material, Double> out) {
        if (!f.isFile()) return;
        YamlConfiguration y;
        try { y = YamlConfiguration.loadConfiguration(f); } catch (Exception e) { return; }
        ConfigurationSection sec = y.getConfigurationSection("prices");
        if (sec == null) sec = y;   // tolerate a flat file
        for (String key : sec.getKeys(false)) {
            if (sec.isConfigurationSection(key)) continue;
            Material mat = Material.matchMaterial(key);
            if (mat == null) continue;
            double v = sec.getDouble(key, 0);
            if (v > 0) out.put(mat, v);   // put, not merge: the dynamic table is authoritative
        }
    }

    /** Pull every {@code trade.sell.money} offer out of one shop menu file into {@code out} (max wins). */
    private static void scanShopFile(File f, Map<Material, Double> out) {
        YamlConfiguration y;
        try { y = YamlConfiguration.loadConfiguration(f); } catch (Exception e) { return; }
        ConfigurationSection items = y.getConfigurationSection("items");
        if (items == null) return;
        for (String key : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(key);
            if (s == null) continue;
            // Skip custom items: a stored ItemStack, or a plain material dressed up with a model.
            if (s.isItemStack("item")) continue;
            if (s.getInt("custom-model-data", s.getInt("model-data", s.getInt("model_data", 0))) > 0) continue;
            ConfigurationSection trade = s.getConfigurationSection("trade");
            if (trade == null) continue;
            ConfigurationSection sell = trade.getConfigurationSection("sell");
            if (sell == null) continue;
            double money = sell.getDouble("money", 0);
            if (money <= 0) continue;
            Material mat = Material.matchMaterial(s.getString("material", ""));
            if (mat == null) continue;
            int amount = Math.max(1, s.getInt("amount", 1));
            double perUnit = money / amount;
            out.merge(mat, perUnit, Math::max);
        }
    }
}
