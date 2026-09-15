package dev.servereer.machineconstruct.tradinghall;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code tradinghall:} section of a machine config — turns a machine into a
 * Virtual Trading Hall (ADR 0011). Immutable, shared; per-machine state lives in
 * {@link TradingHallData}.
 *
 * <p>Professions are defined here: each carries a <b>station block recipe</b> (the blocks
 * a player feeds to build that profession's slot) and <b>trade pools</b> per level
 * (Novice=1 … Master=5). Refresh rolls trades from the matching pool; trading levels the
 * trader up to unlock higher tiers.
 */
public final class TradingHallSpec {

    /**
     * One trade offer TEMPLATE (materialized into a live trade per-roll). Supports vanilla-style
     * randomness: {@code randomEnchant} re-rolls the sell item's enchant (book → random stored
     * enchant; gear → random applicable enchants) each time, and {@code buyMin..buyMax} is the
     * primary-ingredient price range (random amount per roll).
     */
    public static final class Offer {
        public final ItemStack buyA;        // primary ingredient (amount = buyMin)
        public final ItemStack buyB;        // optional secondary ingredient (null = none)
        public final ItemStack sell;        // result (base item; enchant applied at materialize if randomEnchant)
        public final int maxUses;
        public final int villagerXp;
        public final float priceMultiplier;
        public final boolean randomEnchant; // sell gets a random enchant (book/gear) per roll
        public final int buyMin, buyMax;    // primary-buy amount range (equal = fixed)

        Offer(ItemStack buyA, ItemStack buyB, ItemStack sell, int maxUses, int villagerXp,
              float priceMultiplier, boolean randomEnchant, int buyMin, int buyMax) {
            this.buyA = buyA; this.buyB = buyB; this.sell = sell;
            this.maxUses = maxUses; this.villagerXp = villagerXp; this.priceMultiplier = priceMultiplier;
            this.randomEnchant = randomEnchant; this.buyMin = buyMin; this.buyMax = Math.max(buyMin, buyMax);
        }
    }

    /** A buildable profession: its station block recipe + per-level trade pools. */
    public static final class Profession {
        public final String id;                       // config key, e.g. "librarian"
        public final String display;                  // shown name
        public final Villager.Profession vanilla;     // for the head/icon + flavour (may be NONE)
        public final List<ItemStack> station;         // blocks required to build the station (one slot)
        public final Map<Integer, List<Offer>> pools; // level (1..5) → offers
        public final int rollPerLevel;                // how many offers to roll into each unlocked level

        Profession(String id, String display, Villager.Profession vanilla,
                   List<ItemStack> station, Map<Integer, List<Offer>> pools, int rollPerLevel) {
            this.id = id; this.display = display; this.vanilla = vanilla;
            this.station = station; this.pools = pools; this.rollPerLevel = rollPerLevel;
        }

        public int maxLevel() {
            int max = 1;
            for (int lvl : pools.keySet()) max = Math.max(max, lvl);
            return max;
        }
    }

    private final int baseSlots;          // slots at tier 1
    private final int slotsPerTier;       // station slots added per stall upgrade
    private final int maxTier;            // upgrade ceiling (0 = unbounded — upgrade as much as you want)
    private final int captureRadius;      // villager vacuum radius (blocks)
    private final int rosterCap;          // max blank villagers stored awaiting a station
    private final long vaultCapBase;      // quantum-vault mass cap at tier 1 (limited)
    private final long vaultCapPerTier;   // vault cap added per stall upgrade
    private final int restockSeconds;     // timed restock interval (resets trade uses)
    private final boolean pullFromVault;  // default: trades may source ingredients from the vault
    private final List<ItemStack> upgrade;      // block recipe fed to upgrade the stall one tier
    private final List<ItemStack> stockUpgrade; // items fed to a single trader for +1 stock level
    private final Map<String, Profession> professions;

    private TradingHallSpec(int baseSlots, int slotsPerTier, int maxTier, int captureRadius, int rosterCap,
                            long vaultCapBase, long vaultCapPerTier, int restockSeconds, boolean pullFromVault,
                            List<ItemStack> upgrade, List<ItemStack> stockUpgrade, Map<String, Profession> professions) {
        this.baseSlots = baseSlots; this.slotsPerTier = slotsPerTier; this.maxTier = maxTier;
        this.captureRadius = captureRadius; this.rosterCap = rosterCap;
        this.vaultCapBase = vaultCapBase; this.vaultCapPerTier = vaultCapPerTier;
        this.restockSeconds = restockSeconds; this.pullFromVault = pullFromVault;
        this.upgrade = upgrade; this.stockUpgrade = stockUpgrade; this.professions = professions;
    }

    public int baseSlots() { return baseSlots; }
    public int slotsPerTier() { return slotsPerTier; }
    public int maxTier() { return maxTier; }
    public int captureRadius() { return captureRadius; }
    public int rosterCap() { return rosterCap; }
    public int restockSeconds() { return restockSeconds; }
    public boolean pullFromVault() { return pullFromVault; }
    public List<ItemStack> upgrade() { return upgrade; }
    public boolean canUpgrade(int tier) { return !upgrade.isEmpty() && (maxTier <= 0 || tier < maxTier); }
    public List<ItemStack> stockUpgrade() { return stockUpgrade; }
    public boolean canStockUpgrade() { return !stockUpgrade.isEmpty(); }
    public Map<String, Profession> professions() { return professions; }
    public Profession profession(String id) { return id == null ? null : professions.get(id.toLowerCase(Locale.ROOT)); }

    /** Station slots at a given tier: {@code baseSlots + (tier-1)*slotsPerTier}. */
    public int slotsForTier(int tier) {
        int t = Math.max(1, maxTier > 0 ? Math.min(maxTier, tier) : tier);
        return baseSlots + (t - 1) * slotsPerTier;
    }

    /** Vault mass cap at a given tier (0 base = unlimited). */
    public long vaultCapForTier(int tier) {
        if (vaultCapBase <= 0) return 0;
        int t = Math.max(1, maxTier > 0 ? Math.min(maxTier, tier) : tier);
        return vaultCapBase + (long) (t - 1) * vaultCapPerTier;
    }

    /** Parse a {@code tradinghall:} section, or return null if absent. Falls back to sane defaults. */
    public static TradingHallSpec parse(ConfigurationSection sec) {
        if (sec == null) return null;
        int baseSlots = sec.getInt("slots", 20);
        int slotsPerTier = sec.getInt("slots-per-tier", 10);
        int maxTier = sec.getInt("max-tier", 0);          // 0 = unbounded (upgrade as much as you want)
        int captureRadius = sec.getInt("capture-radius", 5);
        int rosterCap = sec.getInt("roster-cap", 64);
        long vaultCapBase = sec.getLong("vault-cap", 3456L);          // ~1 double chest of items (limited)
        long vaultCapPerTier = sec.getLong("vault-cap-per-tier", vaultCapBase);
        int restockSeconds = sec.getInt("restock-seconds", 600);
        boolean pullFromVault = sec.getBoolean("pull-from-vault", true);
        List<ItemStack> upgrade = parseItemList(sec.getStringList("upgrade"));
        List<ItemStack> stockUpgrade = parseItemList(sec.getStringList("stock-upgrade"));

        Map<String, Profession> profs = new LinkedHashMap<>();
        ConfigurationSection ps = sec.getConfigurationSection("professions");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                ConfigurationSection p = ps.getConfigurationSection(key);
                if (p == null) continue;
                Profession prof = parseProfession(key.toLowerCase(Locale.ROOT), p);
                if (prof != null) profs.put(prof.id, prof);
            }
        }
        return new TradingHallSpec(baseSlots, slotsPerTier, maxTier, captureRadius, rosterCap,
                vaultCapBase, vaultCapPerTier, restockSeconds, pullFromVault, upgrade, stockUpgrade, profs);
    }

    private static Profession parseProfession(String id, ConfigurationSection p) {
        String display = p.getString("name", capitalize(id));
        Villager.Profession vanilla = matchProfession(p.getString("vanilla", id));
        List<ItemStack> station = parseItemList(p.getStringList("station"));
        int rollPerLevel = p.getInt("roll-per-level", 0); // 0 = use the whole pool for the level

        Map<Integer, List<Offer>> pools = new LinkedHashMap<>();
        ConfigurationSection tp = p.getConfigurationSection("trade_pools");
        if (tp != null) {
            for (String lvlKey : tp.getKeys(false)) {
                int lvl;
                try { lvl = Integer.parseInt(lvlKey); } catch (NumberFormatException e) { continue; }
                List<Offer> offers = new ArrayList<>();
                for (Map<?, ?> raw : tp.getMapList(lvlKey)) {
                    Offer o = parseOffer(raw);
                    if (o != null) offers.add(o);
                }
                if (!offers.isEmpty()) pools.put(lvl, offers);
            }
        }
        if (station.isEmpty() && pools.isEmpty()) return null; // nothing usable
        return new Profession(id, display, vanilla, station, pools, rollPerLevel);
    }

    private static Offer parseOffer(Map<?, ?> raw) {
        ItemStack buyA = parseItem(raw.get("buy"));
        ItemStack buyB = parseItem(raw.get("buy2"));
        ItemStack sell = parseItem(raw.get("sell"));
        if (buyA == null || sell == null) return null;
        int maxUses = intOf(raw.get("maxUses"), 16);
        int xp = intOf(raw.get("xp"), 2);
        float priceMult = (float) dblOf(raw.get("price"), 0.05);
        boolean rnd = isRandomEnchant(raw.get("sell"));
        int[] range = amountRange(raw.get("buy"));
        int min = range != null ? range[0] : buyA.getAmount();
        int max = range != null ? range[1] : buyA.getAmount();
        return new Offer(buyA, buyB, sell, maxUses, xp, priceMult, rnd, min, max);
    }

    /** True if a sell-spec map asks for a random enchant ({@code enchant: random}). */
    private static boolean isRandomEnchant(Object sellRaw) {
        if (!(sellRaw instanceof Map<?, ?> m)) return false;
        Object e = m.get("enchant");
        return e != null && "random".equalsIgnoreCase(String.valueOf(e).trim());
    }

    /** Extract a "min-max" amount range from a "MAT min-max" buy string, else null (fixed). */
    private static int[] amountRange(Object spec) {
        if (!(spec instanceof String s)) return null;
        String[] p = s.trim().split("\\s+");
        if (p.length < 2 || !p[1].contains("-")) return null;
        String[] mm = p[1].split("-", 2);
        try { return new int[]{ Math.max(1, Integer.parseInt(mm[0])), Math.max(1, Integer.parseInt(mm[1])) }; }
        catch (NumberFormatException e) { return null; }
    }

    // --- item parsing ---
    //   "MATERIAL AMOUNT"                              (plain, vanilla)
    //   { item: <mat>, amount: n, enchant: "name lvl" | [..] }   (e.g. enchanted books / tools)

    static List<ItemStack> parseItemList(List<String> entries) {
        List<ItemStack> out = new ArrayList<>();
        if (entries == null) return out;
        for (String e : entries) {
            ItemStack it = parseItemString(e);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** Parse an offer item: a "MAT AMT" string, or a map with item/amount/enchant. */
    static ItemStack parseItem(Object spec) {
        if (spec instanceof String s) return parseItemString(s);
        if (spec instanceof Map<?, ?> m) return parseItemMap(m);
        return null;
    }

    static ItemStack parseItemString(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.trim().split("\\s+");
        Material mat = Material.matchMaterial(parts[0].toUpperCase(Locale.ROOT));
        if (mat == null) return null;
        int amount = 1;
        if (parts.length > 1) {
            String tok = parts[1].contains("-") ? parts[1].split("-", 2)[0] : parts[1]; // range → take the min
            try { amount = Math.max(1, Integer.parseInt(tok)); } catch (NumberFormatException ignored) {}
        }
        return new ItemStack(mat, amount);
    }

    private static ItemStack parseItemMap(Map<?, ?> m) {
        Material mat = Material.matchMaterial(String.valueOf(m.get("item")).toUpperCase(Locale.ROOT));
        if (mat == null) return null;
        ItemStack it = new ItemStack(mat, intOf(m.get("amount"), 1));
        Object ench = m.get("enchant");
        // "random" is resolved at materialize time (per-roll) — leave the base item here.
        if (ench instanceof List<?> list) { for (Object e : list) applyEnchant(it, String.valueOf(e)); }
        else if (ench != null && !"random".equalsIgnoreCase(String.valueOf(ench).trim())) applyEnchant(it, String.valueOf(ench));
        return it;
    }

    /** Apply "name level" — stored on enchanted books, applied (unsafe) on other items. */
    private static void applyEnchant(ItemStack it, String spec) {
        if (spec == null || spec.isBlank()) return;
        String[] parts = spec.trim().split("\\s+");
        Enchantment ench = resolveEnchant(parts[0]);
        if (ench == null) return;
        int lvl = 1;
        if (parts.length > 1) { try { lvl = Math.max(1, Integer.parseInt(parts[1])); } catch (NumberFormatException ignored) {} }
        if (it.getType() == Material.ENCHANTED_BOOK && it.getItemMeta() instanceof EnchantmentStorageMeta esm) {
            esm.addStoredEnchant(ench, lvl, true);
            it.setItemMeta(esm);
        } else {
            it.addUnsafeEnchantment(ench, lvl);
        }
    }

    @SuppressWarnings("deprecation")
    private static Enchantment resolveEnchant(String name) {
        if (name == null) return null;
        try { return Enchantment.getByKey(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT))); }
        catch (Throwable t) { return null; }
    }

    private static Villager.Profession matchProfession(String name) {
        if (name == null) return Villager.Profession.NONE;
        // Throwable, not just IllegalArgumentException: Profession may be a registry interface
        // (no valueOf) on newer runtimes — never let an icon lookup break the whole parse.
        try { return Villager.Profession.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Throwable t) { return Villager.Profession.NONE; }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? def : Integer.parseInt(o.toString().trim()); } catch (NumberFormatException e) { return def; }
    }
    private static double dblOf(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? def : Double.parseDouble(o.toString().trim()); } catch (NumberFormatException e) { return def; }
    }
    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
