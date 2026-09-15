package dev.servereer.machineconstruct.quarry;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code quarry:} section of a machine config — turns a machine into an
 * Interdimensional Quarry. Immutable, shared; per-machine state lives in
 * {@link QuarryData}.
 */
public final class QuarrySpec {

    /** One weighted entry in a mining/fishing table. {@code treasure} entries get boosted by Luck of the Sea. */
    public static final class LootEntry {
        public final ItemStack template;   // amount 1
        public final double weight;
        public final int min, max;
        public final boolean treasure;
        LootEntry(ItemStack template, double weight, int min, int max, boolean treasure) {
            this.template = template; this.weight = weight; this.min = min; this.max = max; this.treasure = treasure;
        }
        LootEntry(ItemStack template, double weight, int min, int max) { this(template, weight, min, max, false); }
        public double avg() { return (min + max) / 2.0; }
    }

    /** A weighted table: a mineable dimension, or the fishing "sea". */
    public static final class Table {
        public final String name;
        public final List<LootEntry> loot;
        public final double totalWeight;
        Table(String name, List<LootEntry> loot) {
            this.name = name; this.loot = loot;
            double t = 0; for (LootEntry e : loot) t += e.weight; this.totalWeight = t;
        }
    }

    private final long cycleMillis;
    private final long vaultCap;
    private final int maxPerPlayer;
    private final int miningDuraPerCycle;
    private final int fishingDuraPerCycle;
    private final List<Table> dimensions;
    private final Table fishing;
    // lava + heat (Q6)
    private final long lavaCap;
    private final long lavaPerCycle;
    private final double lavaHeatPerCycle;
    private final double lavaSellPrice;     // money per lava unit
    private final long unitsPerBucket;      // tank units consumed to fill one lava bucket
    private final double heatMax;
    private final double coolPerSec;
    // depletion + surge (Q6b)
    private final double depletePerCycle;
    private final double veinFloor;
    private final double regenPerSec;
    private final double surgeChancePerMin;
    private final long surgeDurationMillis;
    private final double surgeMult;
    private final ContractConfig contracts;
    private final LootChestsConfig lootChests;

    private QuarrySpec(long cycleMillis, long vaultCap, int maxPerPlayer, int miningDuraPerCycle,
                       int fishingDuraPerCycle, List<Table> dimensions, Table fishing,
                       long lavaCap, long lavaPerCycle, double lavaHeatPerCycle, double lavaSellPrice,
                       long unitsPerBucket, double heatMax, double coolPerSec,
                       double depletePerCycle, double veinFloor, double regenPerSec,
                       double surgeChancePerMin, long surgeDurationMillis, double surgeMult,
                       ContractConfig contracts, LootChestsConfig lootChests) {
        this.cycleMillis = cycleMillis;
        this.vaultCap = vaultCap;
        this.maxPerPlayer = maxPerPlayer;
        this.miningDuraPerCycle = miningDuraPerCycle;
        this.fishingDuraPerCycle = fishingDuraPerCycle;
        this.dimensions = dimensions;
        this.fishing = fishing;
        this.lavaCap = lavaCap;
        this.lavaPerCycle = lavaPerCycle;
        this.lavaHeatPerCycle = lavaHeatPerCycle;
        this.lavaSellPrice = lavaSellPrice;
        this.unitsPerBucket = unitsPerBucket;
        this.heatMax = heatMax;
        this.coolPerSec = coolPerSec;
        this.depletePerCycle = depletePerCycle;
        this.veinFloor = veinFloor;
        this.regenPerSec = regenPerSec;
        this.surgeChancePerMin = surgeChancePerMin;
        this.surgeDurationMillis = surgeDurationMillis;
        this.surgeMult = surgeMult;
        this.contracts = contracts;
        this.lootChests = lootChests;
    }

    public long cycleMillis() { return cycleMillis; }
    public long vaultCap() { return vaultCap; }
    public int maxPerPlayer() { return maxPerPlayer; }
    public int miningDuraPerCycle() { return miningDuraPerCycle; }
    public int fishingDuraPerCycle() { return fishingDuraPerCycle; }
    public int dimensionCount() { return dimensions.size(); }
    public Table fishing() { return fishing; }
    public long lavaCap() { return lavaCap; }
    public long lavaPerCycle() { return lavaPerCycle; }
    public double lavaHeatPerCycle() { return lavaHeatPerCycle; }
    public double lavaSellPrice() { return lavaSellPrice; }
    public long unitsPerBucket() { return unitsPerBucket; }
    public double heatMax() { return heatMax; }
    public double coolPerSec() { return coolPerSec; }
    public double depletePerCycle() { return depletePerCycle; }
    public double veinFloor() { return veinFloor; }
    public double regenPerSec() { return regenPerSec; }
    public double surgeChancePerMin() { return surgeChancePerMin; }
    public long surgeDurationMillis() { return surgeDurationMillis; }
    public double surgeMult() { return surgeMult; }
    public ContractConfig contracts() { return contracts; }
    public LootChestsConfig lootChests() { return lootChests; }

    /** A vanilla loot table the quarry can "crack" (e.g. minecraft:chests/simple_dungeon). */
    public static final class ChestTable {
        public final org.bukkit.NamespacedKey key;
        public final double weight;
        ChestTable(org.bukkit.NamespacedKey key, double weight) { this.key = key; this.weight = weight; }
    }

    /** The {@code loot_chests:} section — a chance per mining cycle to drop a whole vanilla chest's loot. */
    public static final class LootChestsConfig {
        public final boolean enabled;
        public final double chancePerCycle;
        public final int maxPerAccrual;     // safety cap so offline catch-up can't roll thousands
        public final List<ChestTable> tables;
        public final double totalWeight;
        LootChestsConfig(boolean enabled, double chancePerCycle, int maxPerAccrual, List<ChestTable> tables) {
            this.enabled = enabled; this.chancePerCycle = chancePerCycle; this.maxPerAccrual = maxPerAccrual;
            this.tables = tables;
            double t = 0; for (ChestTable c : tables) t += c.weight; this.totalWeight = t;
        }
    }

    /** Configurable contract economy (the {@code contracts:} section). */
    public static final class ContractConfig {
        public final long throughputBase, throughputPer;
        public final long bountyBase, bountyPer, bountyMin, bountyMax;
        public final java.util.Set<Material> exclude;
        public final long rpBase, rpPer, rpTargetDiv;
        public final double moneyBase, moneyTargetMult;
        ContractConfig(long throughputBase, long throughputPer, long bountyBase, long bountyPer,
                       long bountyMin, long bountyMax, java.util.Set<Material> exclude,
                       long rpBase, long rpPer, long rpTargetDiv, double moneyBase, double moneyTargetMult) {
            this.throughputBase = throughputBase; this.throughputPer = throughputPer;
            this.bountyBase = bountyBase; this.bountyPer = bountyPer;
            this.bountyMin = bountyMin; this.bountyMax = bountyMax; this.exclude = exclude;
            this.rpBase = rpBase; this.rpPer = rpPer; this.rpTargetDiv = rpTargetDiv;
            this.moneyBase = moneyBase; this.moneyTargetMult = moneyTargetMult;
        }
    }

    public Table dimension(int i) {
        if (dimensions.isEmpty()) return null;
        return dimensions.get(Math.max(0, Math.min(i, dimensions.size() - 1)));
    }

    public String dimensionName(int i) {
        Table d = dimension(i);
        return d == null ? "—" : d.name;
    }

    // --- parse --------------------------------------------------------------

    public static QuarrySpec parse(ConfigurationSection sec) {
        if (sec == null) return null;
        long cycle = parseTimeMillis(sec.getString("cycle", "3s"));
        long vaultCap = Math.max(1, sec.getLong("vault_cap", 50_000));
        int maxPerPlayer = sec.getInt("max_per_player", 1);

        ConfigurationSection mining = sec.getConfigurationSection("mining");
        int miningDura = mining == null ? 2 : Math.max(0, mining.getInt("durability_per_cycle", 2));
        ConfigurationSection fishingSec = sec.getConfigurationSection("fishing");
        int fishingDura = fishingSec == null ? 1 : Math.max(0, fishingSec.getInt("durability_per_cycle", 1));

        List<Table> dims = parseTables(mining == null ? null : mining.getConfigurationSection("dimensions"));
        if (dims.isEmpty()) dims = defaultDimensions();

        List<LootEntry> seaLoot = parseLoot(fishingSec == null ? null : fishingSec.getConfigurationSection("loot"));
        Table fishing = seaLoot.isEmpty() ? defaultSea() : new Table("Dimensional Sea", seaLoot);

        ConfigurationSection lavaSec = sec.getConfigurationSection("lava");
        long lavaCap = lavaSec == null ? 100_000 : Math.max(1, lavaSec.getLong("cap", 100_000));
        long lavaPerCycle = lavaSec == null ? 100 : Math.max(1, lavaSec.getLong("per_cycle", 100));
        double lavaHeat = lavaSec == null ? 8.0 : Math.max(0.0, lavaSec.getDouble("heat_per_cycle", 8.0));
        double lavaPrice = lavaSec == null ? 0.01 : Math.max(0.0, lavaSec.getDouble("sell_price", 0.01));
        long perBucket = lavaSec == null ? 1000 : Math.max(1, lavaSec.getLong("units_per_bucket", 1000));

        ConfigurationSection heatSec = sec.getConfigurationSection("heat");
        double heatMax = heatSec == null ? 100.0 : Math.max(1.0, heatSec.getDouble("max", 100.0));
        double cool = heatSec == null ? 1.5 : Math.max(0.0, heatSec.getDouble("cool_per_sec", 1.5));

        ConfigurationSection depSec = sec.getConfigurationSection("depletion");
        double deplete = depSec == null ? 0.0006 : Math.max(0.0, depSec.getDouble("per_cycle", 0.0006));
        double floor = depSec == null ? 0.30 : Math.max(0.0, Math.min(1.0, depSec.getDouble("floor", 0.30)));
        double regen = depSec == null ? 0.005 : Math.max(0.0, depSec.getDouble("regen_per_sec", 0.005));

        ConfigurationSection surgeSec = sec.getConfigurationSection("surge");
        double surgeChance = surgeSec == null ? 0.05 : Math.max(0.0, surgeSec.getDouble("chance_per_min", 0.05));
        long surgeDur = (surgeSec == null ? 60L : Math.max(1L, surgeSec.getLong("duration_sec", 60))) * 1000L;
        double surgeMult = surgeSec == null ? 2.0 : Math.max(1.0, surgeSec.getDouble("mult", 2.0));

        ContractConfig contracts = parseContracts(sec.getConfigurationSection("contracts"));
        LootChestsConfig lootChests = parseLootChests(sec.getConfigurationSection("loot_chests"));

        return new QuarrySpec(cycle, vaultCap, maxPerPlayer, miningDura, fishingDura, dims, fishing,
                lavaCap, lavaPerCycle, lavaHeat, lavaPrice, perBucket, heatMax, cool,
                deplete, floor, regen, surgeChance, surgeDur, surgeMult, contracts, lootChests);
    }

    private static LootChestsConfig parseLootChests(ConfigurationSection s) {
        boolean enabled = s != null && s.getBoolean("enabled", true);
        double chance = s == null ? 0.0 : Math.max(0.0, s.getDouble("chance_per_cycle", 0.0));
        int maxPer = s == null ? 5 : Math.max(1, s.getInt("max_per_accrual", 5));
        List<ChestTable> tables = new ArrayList<>();
        List<Map<?, ?>> raw = s == null ? List.of() : s.getMapList("tables");
        for (Map<?, ?> m : raw) {
            Object t = m.get("table");
            if (t == null) continue;
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(t.toString().toLowerCase(Locale.ROOT));
            if (key == null) continue;
            double w = 1.0;
            Object wo = m.get("weight");
            if (wo instanceof Number num) w = num.doubleValue();
            if (w > 0) tables.add(new ChestTable(key, w));
        }
        if (tables.isEmpty()) {   // sensible default spread of vanilla chest tables
            for (String def : new String[]{"chests/simple_dungeon", "chests/abandoned_mineshaft",
                    "chests/stronghold_corridor", "chests/buried_treasure", "chests/nether_bridge",
                    "chests/bastion_treasure", "chests/end_city_treasure", "chests/ancient_city"}) {
                org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.minecraft(def);
                tables.add(new ChestTable(key, 10));
            }
        }
        return new LootChestsConfig(enabled, chance, maxPer, tables);
    }

    private static ContractConfig parseContracts(ConfigurationSection s) {
        ConfigurationSection tp = s == null ? null : s.getConfigurationSection("throughput");
        long tpBase = tp == null ? 2000 : Math.max(1, tp.getLong("base", 2000));
        long tpPer = tp == null ? 1500 : Math.max(0, tp.getLong("per_contract", 1500));

        ConfigurationSection bo = s == null ? null : s.getConfigurationSection("bounty");
        long boBase = bo == null ? 256 : Math.max(1, bo.getLong("base", 256));
        long boPer = bo == null ? 64 : Math.max(0, bo.getLong("per_contract", 64));
        long boMin = bo == null ? 8 : Math.max(1, bo.getLong("min", 8));
        long boMax = bo == null ? 1024 : Math.max(1, bo.getLong("max", 1024));

        java.util.Set<Material> exclude = new java.util.HashSet<>();
        List<String> exList = bo == null ? List.of() : bo.getStringList("exclude");
        if (bo != null && exList.isEmpty() && !bo.contains("exclude"))   // default exclude list
            exList = List.of("NETHER_STAR", "HEART_OF_THE_SEA", "ANCIENT_DEBRIS", "NETHERITE_SCRAP", "ENCHANTED_BOOK", "NAME_TAG");
        else if (bo == null)
            exList = List.of("NETHER_STAR", "HEART_OF_THE_SEA", "ANCIENT_DEBRIS", "NETHERITE_SCRAP", "ENCHANTED_BOOK", "NAME_TAG");
        for (String n : exList) { Material mm = Material.matchMaterial(n); if (mm != null) exclude.add(mm); }

        ConfigurationSection rw = s == null ? null : s.getConfigurationSection("reward");
        long rpBase = rw == null ? 100 : Math.max(0, rw.getLong("rp_base", 100));
        long rpPer = rw == null ? 50 : Math.max(0, rw.getLong("rp_per_contract", 50));
        long rpDiv = rw == null ? 10 : Math.max(1, rw.getLong("rp_target_div", 10));
        double moneyBase = rw == null ? 500 : Math.max(0, rw.getDouble("money_base", 500));
        double moneyMult = rw == null ? 3.0 : Math.max(0, rw.getDouble("money_target_mult", 3.0));

        return new ContractConfig(tpBase, tpPer, boBase, boPer, boMin, boMax, exclude,
                rpBase, rpPer, rpDiv, moneyBase, moneyMult);
    }

    private static List<Table> parseTables(ConfigurationSection sec) {
        List<Table> tables = new ArrayList<>();
        if (sec == null) return tables;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection d = sec.getConfigurationSection(key);
            if (d == null) continue;
            List<LootEntry> loot = parseLoot(d.getConfigurationSection("loot"));
            if (!loot.isEmpty()) tables.add(new Table(d.getString("name", pretty(key)), loot));
        }
        return tables;
    }

    private static List<LootEntry> parseLoot(ConfigurationSection lootSec) {
        List<LootEntry> loot = new ArrayList<>();
        if (lootSec == null) return loot;
        for (String matName : lootSec.getKeys(false)) {
            Material mat = Material.matchMaterial(matName);
            if (mat == null || !mat.isItem()) continue;
            ConfigurationSection l = lootSec.getConfigurationSection(matName);
            double w = l == null ? 1 : l.getDouble("weight", 1);
            int[] r = parseRange(l == null ? "1" : l.getString("amount", "1"));
            boolean treasure = l != null && l.getBoolean("treasure", false);
            if (w > 0) loot.add(new LootEntry(new ItemStack(mat), w, r[0], r[1], treasure));
        }
        return loot;
    }

    private static List<Table> defaultDimensions() {
        List<Table> dims = new ArrayList<>();
        List<LootEntry> stone = new ArrayList<>();
        stone.add(new LootEntry(new ItemStack(Material.COBBLESTONE), 50, 1, 3));
        stone.add(new LootEntry(new ItemStack(Material.STONE), 20, 1, 2));
        stone.add(new LootEntry(new ItemStack(Material.COAL), 12, 1, 2));
        stone.add(new LootEntry(new ItemStack(Material.RAW_COPPER), 8, 1, 2));
        stone.add(new LootEntry(new ItemStack(Material.RAW_IRON), 5, 1, 1));
        stone.add(new LootEntry(new ItemStack(Material.RAW_GOLD), 2, 1, 1));
        stone.add(new LootEntry(new ItemStack(Material.DIAMOND), 1, 1, 1));
        dims.add(new Table("Stone Layer", stone));
        return dims;
    }

    private static Table defaultSea() {
        List<LootEntry> loot = new ArrayList<>();
        loot.add(new LootEntry(new ItemStack(Material.COD), 40, 1, 2));
        loot.add(new LootEntry(new ItemStack(Material.SALMON), 25, 1, 2));
        loot.add(new LootEntry(new ItemStack(Material.PUFFERFISH), 8, 1, 1));
        loot.add(new LootEntry(new ItemStack(Material.TROPICAL_FISH), 6, 1, 1));
        loot.add(new LootEntry(new ItemStack(Material.STICK), 5, 1, 2));        // junk
        loot.add(new LootEntry(new ItemStack(Material.LILY_PAD), 4, 1, 1));     // junk
        loot.add(new LootEntry(new ItemStack(Material.NAUTILUS_SHELL), 4, 1, 1, true));     // treasure
        loot.add(new LootEntry(new ItemStack(Material.ENCHANTED_BOOK), 3, 1, 1, true));
        loot.add(new LootEntry(new ItemStack(Material.NAME_TAG), 2, 1, 1, true));
        loot.add(new LootEntry(new ItemStack(Material.HEART_OF_THE_SEA), 1, 1, 1, true));
        return new Table("Dimensional Sea", loot);
    }

    private static int[] parseRange(String s) {
        try {
            String t = s.trim();
            int dash = t.indexOf('-', t.startsWith("-") ? 1 : 0);
            if (dash < 0) { int v = Integer.parseInt(t); return new int[]{v, v}; }
            int a = Integer.parseInt(t.substring(0, dash).trim());
            int b = Integer.parseInt(t.substring(dash + 1).trim());
            return new int[]{Math.min(a, b), Math.max(a, b)};
        } catch (Exception e) { return new int[]{1, 1}; }
    }

    private static String pretty(String key) {
        String[] parts = key.toLowerCase(Locale.ROOT).split("[_ ]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return sb.toString().trim();
    }

    public static long parseTimeMillis(String s) {
        if (s == null || s.isBlank()) return 3_000L;
        long total = 0; boolean any = false;
        for (String part : s.trim().toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            int i = 0;
            while (i < part.length() && Character.isDigit(part.charAt(i))) i++;
            if (i == 0) continue;
            try {
                long n = Long.parseLong(part.substring(0, i));
                long mult = switch (part.substring(i)) {
                    case "s" -> 1_000L; case "m" -> 60_000L; case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L; default -> 1_000L;
                };
                total += n * mult; any = true;
            } catch (NumberFormatException ignored) { }
        }
        return any ? total : 3_000L;
    }
}
