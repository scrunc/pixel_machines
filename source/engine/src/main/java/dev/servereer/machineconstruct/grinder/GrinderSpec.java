package dev.servereer.machineconstruct.grinder;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code grinder:} section of a machine config — everything that turns a
 * machine into a Dimensional Grinder. Immutable, shared across instances; the
 * per-machine mutable state lives in {@link GrinderData}.
 *
 * <p>A grinder holds a tier-limited number of <b>slots</b> ({@code slots[tier]}),
 * each a stack of one mob capped at {@code stack_cap[tier]}; adding past a slot's
 * cap overflows into the next free slot. Per-slot loot/XP buffers are capped by
 * the {@code cap} formula (base × per_tier × cores). All config-driven (ADR 0008).
 */
public final class GrinderSpec {

    /** What happens to production once a spawner's buffer is full. */
    public enum Overflow { STOP, WASTE }

    /** A special "capacity core" item the player can install to raise the cap. */
    public static final class CapCore {
        public final Material item;
        public final double itemsBonus;   // multiplier contribution, e.g. 0.5 = +50% per core
        public final double expBonus;
        CapCore(Material item, double itemsBonus, double expBonus) {
            this.item = item; this.itemsBonus = itemsBonus; this.expBonus = expBonus;
        }
    }

    /** A buyable spawner entry in the internal catalog. */
    public static final class CatalogEntry {
        public final String type;        // entity name, upper-case
        public final double price;
        public final String permission;  // null = none
        CatalogEntry(String type, double price, String permission) {
            this.type = type; this.price = price; this.permission = permission;
        }
    }

    /** Economy / shop-bridge configuration (mirrors SmartSpawner). */
    public static final class Economy {
        public final String currency;          // VAULT | EXCELLENT
        public final String coinsCurrency;     // for coins-engine style currencies (unused unless set)
        public final String priceSourceMode;   // SHOP_ONLY | SHOP_PRIORITY | CUSTOM_ONLY | CUSTOM_PRIORITY
        public final boolean shopEnabled;
        public final String preferredPlugin;   // auto | EconomyShopGUI | ShopGUIPlus | zShop | ExcellentShop
        public final boolean customEnabled;
        public final String pricesFile;
        public final double defaultPrice;
        Economy(String currency, String coinsCurrency, String priceSourceMode, boolean shopEnabled,
                String preferredPlugin, boolean customEnabled, String pricesFile, double defaultPrice) {
            this.currency = currency; this.coinsCurrency = coinsCurrency; this.priceSourceMode = priceSourceMode;
            this.shopEnabled = shopEnabled; this.preferredPlugin = preferredPlugin;
            this.customEnabled = customEnabled; this.pricesFile = pricesFile; this.defaultPrice = defaultPrice;
        }
    }

    private final String lootFile;
    private final long delayMillis;
    private final double minMobs;
    private final double maxMobs;
    private final boolean requirePlayerRange;
    private final int range;

    private final double baseItems;
    private final double baseExp;
    private final double[] perTierMult;   // index = tier-1
    private final Overflow overflow;
    private final boolean allowStacking;
    private final int[] slots;            // slots available per tier (index = tier-1)
    private final long[] stackCap;        // per-slot max stack per tier (index = tier-1)
    private final int maxPerPlayer;       // how many of this grinder a player may place (0 = unlimited)
    private final Map<Material, CapCore> capCores;

    private final Map<String, CatalogEntry> catalog;
    private final Economy economy;

    private GrinderSpec(String lootFile, long delayMillis, double minMobs, double maxMobs,
                        boolean requirePlayerRange, int range, double baseItems, double baseExp,
                        double[] perTierMult, Overflow overflow, boolean allowStacking,
                        int[] slots, long[] stackCap, int maxPerPlayer, Map<Material, CapCore> capCores,
                        Map<String, CatalogEntry> catalog, Economy economy) {
        this.lootFile = lootFile;
        this.delayMillis = delayMillis;
        this.minMobs = minMobs;
        this.maxMobs = maxMobs;
        this.requirePlayerRange = requirePlayerRange;
        this.range = range;
        this.baseItems = baseItems;
        this.baseExp = baseExp;
        this.perTierMult = perTierMult;
        this.overflow = overflow;
        this.allowStacking = allowStacking;
        this.slots = slots;
        this.stackCap = stackCap;
        this.maxPerPlayer = maxPerPlayer;
        this.capCores = capCores;
        this.catalog = catalog;
        this.economy = economy;
    }

    // --- accessors ----------------------------------------------------------

    public String lootFile() { return lootFile; }
    public long delayMillis() { return delayMillis; }
    public double avgMobs() { return (minMobs + maxMobs) / 2.0; }
    public boolean requirePlayerRange() { return requirePlayerRange; }
    public int range() { return range; }
    public Overflow overflow() { return overflow; }
    public boolean allowStacking() { return allowStacking; }
    public int maxPerPlayer() { return maxPerPlayer; }
    public Map<Material, CapCore> capCores() { return capCores; }
    public Map<String, CatalogEntry> catalog() { return catalog; }
    public Economy economy() { return economy; }

    /** Slots available at a tier (mob stacks the grinder can hold). */
    public int slots(int tier) {
        if (slots.length == 0) return 10;
        return slots[Math.min(Math.max(1, tier) - 1, slots.length - 1)];
    }

    /** The largest slot count across all tiers — the size of the slot grid in the menu. */
    public int maxSlotsEver() {
        int m = 1;
        for (int s : slots) m = Math.max(m, s);
        return m;
    }

    /** Per-slot stack cap at a tier (how many spawners can merge into one slot). */
    public long stackCap(int tier) {
        long base = stackCap.length == 0 ? 64 : stackCap[Math.min(Math.max(1, tier) - 1, stackCap.length - 1)];
        return allowStacking ? base : 1;
    }

    /** Item-buffer cap for a spawner: base × tier × installed cores. */
    public double itemCap(int tier, Map<Material, Integer> coresInstalled) {
        return baseItems * tierMult(tier) * coreMult(coresInstalled, true);
    }

    /** XP cap for a spawner: base × tier × installed cores. */
    public double expCap(int tier, Map<Material, Integer> coresInstalled) {
        return baseExp * tierMult(tier) * coreMult(coresInstalled, false);
    }

    private double tierMult(int tier) {
        if (perTierMult.length == 0) return 1.0;
        int i = Math.max(1, tier) - 1;
        return perTierMult[Math.min(i, perTierMult.length - 1)];
    }

    private double coreMult(Map<Material, Integer> coresInstalled, boolean items) {
        if (coresInstalled == null || coresInstalled.isEmpty()) return 1.0;
        double mult = 1.0;
        for (Map.Entry<Material, Integer> e : coresInstalled.entrySet()) {
            CapCore core = capCores.get(e.getKey());
            if (core == null) continue;
            double bonus = items ? core.itemsBonus : core.expBonus;
            mult += bonus * Math.max(0, e.getValue());
        }
        return mult;
    }

    // --- parse --------------------------------------------------------------

    /** Parse a {@code grinder:} section, or null if absent. Never throws. */
    public static GrinderSpec parse(ConfigurationSection sec) {
        if (sec == null) return null;
        String lootFile = sec.getString("loot_file", "grinder/spawners_settings.yml");
        long delay = parseTimeMillis(sec.getString("delay", "25s"));
        double minMobs = sec.getDouble("min_mobs", 1);
        double maxMobs = sec.getDouble("max_mobs", 4);
        boolean reqRange = sec.getBoolean("require_player_range", false);
        int range = sec.getInt("range", 16);

        ConfigurationSection cap = sec.getConfigurationSection("cap");
        double baseItems = cap == null ? 27648 : cap.getDouble("base_items", 27648);
        double baseExp = cap == null ? 5000 : cap.getDouble("base_exp", 5000);
        double[] perTier = cap == null ? new double[]{1.0} : toDoubleArray(cap.getDoubleList("per_tier_mult"));
        if (perTier.length == 0) perTier = new double[]{1.0};
        Map<Material, CapCore> cores = parseCores(cap);

        Overflow overflow = "waste".equalsIgnoreCase(sec.getString("overflow", "stop"))
                ? Overflow.WASTE : Overflow.STOP;
        boolean allowStacking = sec.getBoolean("allow_stacking", true);

        int[] slots = toIntArray(sec.getIntegerList("slots"));
        if (slots.length == 0) slots = new int[]{10, 10, 10};   // all slots open from tier 1; upgrades only raise stack cap
        long[] stackCap = toLongArray(sec.getLongList("stack_cap"));
        if (stackCap.length == 0) stackCap = new long[]{64, 128, 256};
        int maxPerPlayer = sec.getInt("max_per_player", 1);

        Map<String, CatalogEntry> catalog = parseCatalog(sec.getConfigurationSection("catalog"));
        Economy economy = parseEconomy(sec.getConfigurationSection("economy"));

        return new GrinderSpec(lootFile, delay, minMobs, maxMobs, reqRange, range,
                baseItems, baseExp, perTier, overflow, allowStacking, slots, stackCap, maxPerPlayer,
                cores, catalog, economy);
    }

    private static Map<Material, CapCore> parseCores(ConfigurationSection cap) {
        Map<Material, CapCore> cores = new HashMap<>();
        if (cap == null) return cores;
        for (Map<?, ?> m : cap.getMapList("special_items")) {
            Object it = m.get("item");
            if (it == null) continue;
            Material mat = Material.matchMaterial(String.valueOf(it));
            if (mat == null) continue;
            double ib = m.get("items_bonus") instanceof Number n ? n.doubleValue() : 0;
            double eb = m.get("exp_bonus") instanceof Number n ? n.doubleValue() : 0;
            cores.put(mat, new CapCore(mat, ib, eb));
        }
        return cores;
    }

    private static Map<String, CatalogEntry> parseCatalog(ConfigurationSection sec) {
        Map<String, CatalogEntry> catalog = new LinkedHashMap<>();
        if (sec == null) return catalog;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection e = sec.getConfigurationSection(key);
            String type = key.toUpperCase(Locale.ROOT);
            double price = e == null ? 0 : e.getDouble("price", 0);
            String perm = e == null ? null : e.getString("permission");
            boolean enabled = e == null || e.getBoolean("enabled", true);
            if (enabled) catalog.put(type, new CatalogEntry(type, price, perm));
        }
        return catalog;
    }

    private static Economy parseEconomy(ConfigurationSection sec) {
        if (sec == null) {
            return new Economy("VAULT", "coins", "CUSTOM_PRIORITY", true, "auto", true, "grinder/item_prices.yml", 1.0);
        }
        String currency = sec.getString("currency", "VAULT");
        String coins = sec.getString("coins_currency", "coins");
        String mode = sec.getString("price_source_mode", "SHOP_PRIORITY");
        ConfigurationSection shop = sec.getConfigurationSection("shop_integration");
        boolean shopEnabled = shop == null || shop.getBoolean("enabled", true);
        String preferred = shop == null ? "auto" : shop.getString("preferred_plugin", "auto");
        ConfigurationSection custom = sec.getConfigurationSection("custom_prices");
        boolean customEnabled = custom == null || custom.getBoolean("enabled", true);
        String file = custom == null ? "grinder/item_prices.yml" : custom.getString("file", "grinder/item_prices.yml");
        double def = custom == null ? 1.0 : custom.getDouble("default_price", 1.0);
        return new Economy(currency, coins, mode, shopEnabled, preferred, customEnabled, file, def);
    }

    private static double[] toDoubleArray(java.util.List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static int[] toIntArray(java.util.List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static long[] toLongArray(java.util.List<Long> list) {
        long[] a = new long[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    /** Parse "25s" / "5m" / "1h" / "1d_2h" into milliseconds. Defaults to 25s on failure. */
    public static long parseTimeMillis(String s) {
        if (s == null || s.isBlank()) return 25_000L;
        long total = 0;
        boolean any = false;
        for (String part : s.trim().toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            int i = 0;
            while (i < part.length() && (Character.isDigit(part.charAt(i)))) i++;
            if (i == 0) continue;
            try {
                long n = Long.parseLong(part.substring(0, i));
                String unit = part.substring(i);
                long mult = switch (unit) {
                    case "s" -> 1_000L;
                    case "m" -> 60_000L;
                    case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L;
                    case "w" -> 604_800_000L;
                    default -> 1_000L;
                };
                total += n * mult;
                any = true;
            } catch (NumberFormatException ignored) { }
        }
        return any ? total : 25_000L;
    }
}
