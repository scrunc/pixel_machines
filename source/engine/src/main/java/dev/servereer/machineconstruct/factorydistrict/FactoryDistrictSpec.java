package dev.servereer.machineconstruct.factorydistrict;

import dev.servereer.machineconstruct.core.Heads;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code factory:} section of a machine config — turns a machine into a Virtual
 * Factory District (ADR 0013). Immutable, shared; per-machine state lives in
 * {@link FactoryDistrictData}.
 *
 * <p>Each <b>farm</b> carries a build recipe (blocks + captured creatures) and a production
 * profile (outputs per cycle, optional per-cycle inputs for vault-mediated chaining). A built
 * farm produces on an offline time-accrual clock (Grinder-style) into the Quantum Vault.
 */
public final class FactoryDistrictSpec {

    /**
     * One per-cycle reward (ADR 0014 reward model). A reward is an item, an MMOItems item, or a
     * console command — each with a {@code chance} (0–1) and an amount range ({@code min..max}).
     * Item/MMOItem rewards deposit into the vault; commands run with %player% (owner) + %amount%.
     */
    public static final class Reward {
        public enum Kind { ITEM, MMOITEM, COMMAND }
        public final Kind kind;
        public final ItemStack item;     // ITEM: base item (amount overwritten by the roll)
        public final String mmoType;     // MMOITEM: type id
        public final String mmoId;       // MMOITEM: item id
        public final String command;     // COMMAND: console command (%player%, %amount%)
        public final int min, max;       // amount range (stack size, or %amount% for commands)
        public final double chance;      // 0..1 per cycle (independent-chance mode)
        public final int weight;         // relative weight (weighted-table mode, output-rolls > 0)

        private Reward(Kind kind, ItemStack item, String mmoType, String mmoId, String command,
                       int min, int max, double chance, int weight) {
            this.kind = kind; this.item = item; this.mmoType = mmoType; this.mmoId = mmoId;
            this.command = command; this.min = Math.max(0, min); this.max = Math.max(this.min, max);
            this.chance = Math.max(0, Math.min(1, chance)); this.weight = Math.max(1, weight);
        }
        static Reward item(ItemStack it, int min, int max, double chance, int weight) { return new Reward(Kind.ITEM, it, null, null, null, min, max, chance, weight); }
        static Reward mmo(String t, String id, int min, int max, double chance, int weight) { return new Reward(Kind.MMOITEM, null, t, id, null, min, max, chance, weight); }
        static Reward command(String cmd, int min, int max, double chance, int weight) { return new Reward(Kind.COMMAND, null, null, null, cmd, min, max, chance, weight); }

        public Reward scaled(double mult) {   // size scaling: scale the amount range, keep chance/weight
            int nmin = (int) Math.round(min * mult), nmax = (int) Math.round(max * mult);
            return new Reward(kind, item, mmoType, mmoId, command, nmin, nmax, chance, weight);
        }
    }

    /** A convert-upgrade: morph this farm's slot into {@code to} for a cost, optionally gated (ADR 0016). */
    public static final class Upgrade {
        public final String to;            // target farm id
        public final String name;          // button label
        public final List<ItemStack> blocks;
        public final List<MmoCost> mmoBlocks;
        public final Map<String, Long> requiresProduced;   // log-key (UPPER material) → min this slot must have produced
        public final List<String> requiresBuilding;        // farm ids that must exist in the district
        Upgrade(String to, String name, List<ItemStack> blocks, List<MmoCost> mmoBlocks,
                Map<String, Long> requiresProduced, List<String> requiresBuilding) {
            this.to = to; this.name = name; this.blocks = blocks; this.mmoBlocks = mmoBlocks;
            this.requiresProduced = requiresProduced; this.requiresBuilding = requiresBuilding;
        }
    }

    /** A build size ("small".."huge"): multipliers scaling cost, creatures, and output. */
    public static final class Size {
        public final String id;
        public final String display;
        public final double cost;       // block + emerald/diamond cost multiplier
        public final double creatures;  // creature-count multiplier
        public final double output;     // production multiplier (per cycle)

        Size(String id, String display, double cost, double creatures, double output) {
            this.id = id; this.display = display; this.cost = cost; this.creatures = creatures; this.output = output;
        }
    }

    /** An MMOItems build ingredient ({@code <TYPE> <ID>} × amount), matched in inventory/vault by identity. */
    public static final class MmoCost {
        public final String type, id;
        public final int amount;
        MmoCost(String type, String id, int amount) { this.type = type; this.id = id; this.amount = Math.max(1, amount); }
        public MmoCost scaled(double mult) { return new MmoCost(type, id, (int) Math.max(1, Math.ceil(amount * mult))); }
    }

    /** A catalog category that farms are grouped under in the build GUI. */
    public static final class Category {
        public final String id;
        public final String display;
        public final ItemStack iconItem;   // material or textured player head

        Category(String id, String display, ItemStack iconItem) {
            this.id = id; this.display = display; this.iconItem = iconItem;
        }
    }

    /** Build an icon ItemStack from a {@code head:} texture (base64/url/name) or an {@code icon:} material. */
    static ItemStack iconOf(ConfigurationSection sec, Material fallback) {
        String head = sec.getString("head");
        if (head != null && !head.isBlank()) {
            try { ItemStack h = Heads.create(head.trim()); if (h != null) return h; } catch (Throwable ignored) {}
        }
        Material m = Material.matchMaterial(String.valueOf(sec.getString("icon", fallback.name())).toUpperCase(Locale.ROOT));
        return new ItemStack(m == null ? fallback : m);
    }

    /** One possible machine a generator can spawn, with a relative weight. */
    public static final class Outcome {
        public final String farmId;
        public final int weight;
        Outcome(String farmId, int weight) { this.farmId = farmId; this.weight = Math.max(1, weight); }
    }

    /** Optional "this machine breeds other machines" profile (ADR 0014). */
    public static final class Generation {
        public final int everySeconds;     // attempt interval
        public final double chance;        // success probability per attempt
        public final int count;            // offspring spawned per success (x1/x2/x3…)
        public final int max;              // total offspring this machine may ever spawn
        public final List<Outcome> outcomes;
        public final boolean workerScaled; // strike chance scales with assigned workers (prospecting)
        Generation(int everySeconds, double chance, int count, int max, List<Outcome> outcomes, boolean workerScaled) {
            this.everySeconds = Math.max(1, everySeconds); this.chance = Math.max(0, Math.min(1, chance));
            this.count = Math.max(1, count); this.max = Math.max(1, max); this.outcomes = outcomes; this.workerScaled = workerScaled;
        }
    }

    /** Optional "this structure breeds creatures into the roster over time" profile (ADR 0018, housing). */
    public static final class Breeding {
        public final EntityType type;      // what it breeds into the capture roster
        public final int everySeconds;     // attempt interval
        public final double chance;        // success probability per attempt
        public final int count;            // creatures added per success
        public final int max;              // lifetime creatures this structure may breed (0 = unlimited, up to roster cap)
        public final boolean workerScaled; // breed chance scales with assigned workers
        Breeding(EntityType type, int everySeconds, double chance, int count, int max, boolean workerScaled) {
            this.type = type; this.everySeconds = Math.max(1, everySeconds); this.chance = Math.max(0, Math.min(1, chance));
            this.count = Math.max(1, count); this.max = Math.max(0, max); this.workerScaled = workerScaled;
        }
    }

    /** How a fused (★) crafting machine scales. */
    public enum StackMode { RATE, MULTI }

    /** An author-defined crafting recipe: vault inputs → vault outputs (supports byproducts). */
    public static final class CraftRecipe {
        public final String id;
        public final String display;
        public final Map<Material, Integer> inputs;
        public final Map<Material, Integer> outputs;
        CraftRecipe(String id, String display, Map<Material, Integer> inputs, Map<Material, Integer> outputs) {
            this.id = id; this.display = display; this.inputs = inputs; this.outputs = outputs;
        }
    }

    /** A buildable farm: its build cost (blocks + creatures) + production profile. */
    public static final class Farm {
        public final String id;
        public final String display;
        public final Material icon;                       // GUI icon material (fallback)
        public final ItemStack iconItem;                  // rendered icon (material or textured head)
        public final int workersPerLevel;                 // worker capacity added per house upgrade level
        public final String categoryId;                   // which category it lists under
        public final List<ItemStack> blocks;              // vanilla block cost (inventory + vault)
        public final List<MmoCost> mmoBlocks;             // MMOItems build cost (inventory + vault)
        public final Map<EntityType, Integer> creatures;  // creature cost (from the roster)
        public final int cycleSeconds;                    // seconds per production cycle
        public final List<Reward> output;                 // produced per cycle → vault (chance/range/cmd/mmo)
        public final List<ItemStack> inputs;              // OPTIONAL per-cycle inputs / fuel pulled from vault
        public final List<ItemStack> demolishRefund;      // OPTIONAL items returned on demolish
        public final Generation gen;                      // OPTIONAL machine-generates-machine profile (null = off)
        // ── colony layer (ADR 0015) ──
        public final List<String> requires;               // farm ids that must be built+staffed to operate
        public final int workers;                          // workers to assign to operate (0 = none)
        public final int providesWorkers;                  // a house: worker slots added to the pool (×(1+level))
        public final int maxCount;                         // build limit per district (0 = unlimited)
        public final String raisesMaxFarm;                 // an outpost: which farm's cap it raises (null = none)
        public final int raisesMaxBy;                      // …by how much
        public final List<ItemStack> upgradeCost;          // OPTIONAL per-level upgrade cost (empty = not upgradeable)
        public final int maxLevel;                         // 0 = unlimited; else cap on level upgrades
        public final int outputRolls;                      // 0 = independent chance; >0 = weighted-table picks/cycle
        public final int depleteCycles;                    // 0 = never; else exhausted after producing N cycles
        public final List<ItemStack> scrap;                // payout when demolishing an exhausted farm
        public final List<Upgrade> upgrades;               // convert-upgrades (morph into another farm)
        // ── mining-district layer (ADR 0016) ──
        public final String tier;                          // optional display label ("Tier 2/4")
        public final double richnessMin, richnessMax;      // per-slot output-richness roll (1,1 = none)
        public final int wpCyclesPerTier, wpMaxTier;       // worker proficiency: cycles/tier, tier cap
        public final double wpBonusPerTier;                // …output bonus per tier
        public final double hazardChance;                  // per-cycle cave-in chance (0 = none)
        public final List<ItemStack> hazardRepair;         // repair cost to clear a broken machine
        public final boolean buildable;                    // false = only reachable via convert/generate (e.g. mines)
        // ── farming layer (ADR 0017): reversible soil + boosters ──
        public final double soilStart;                     // soil nutrient capacity (0 = no soil mechanic)
        public final double soilPerCycle;                  // nutrient consumed per production cycle
        public final double soilDepletedMult;              // output multiplier once soil hits 0 (until restored)
        public final double soilRegenPerCycle;             // legume/fallow: ADDS this much soil per cycle instead of consuming
        public final ItemStack fertilizerItem;             // auto-consumed from the vault to restore soil (null = none)
        public final double fertilizerRestores;            // soil restored per fertilizer item consumed
        public final double boostsOutput;                  // apiary: district-wide output ×multiplier per built+staffed instance (1.0 = none)
        // ── housing layer (ADR 0018) ──
        public final Breeding breeding;                    // population growth: breeds creatures into the roster (null = off)
        public final double boostsWorkers;                 // civic: district-wide worker-capacity ×multiplier per built instance (1.0 = none)
        // ── crafting layer (ADR 0021): vault recipe processor — set post-construction by the parser ──
        public List<CraftRecipe> recipes = List.of();      // selectable input→output recipes (empty = none)
        public StackMode stackMode = StackMode.RATE;       // fused (★) behaviour: RATE = one recipe ×N, MULTI = N recipes at once
        public boolean craftsVanilla = false;              // a vanilla crafting bench (GUI, vault materials)
        public boolean isCrafter() { return craftsVanilla || !recipes.isEmpty(); }

        Farm(String id, String display, Material icon, ItemStack iconItem, int workersPerLevel, String categoryId,
             List<ItemStack> blocks, List<MmoCost> mmoBlocks,
             Map<EntityType, Integer> creatures, int cycleSeconds, List<Reward> output, List<ItemStack> inputs,
             List<ItemStack> demolishRefund, Generation gen, List<String> requires, int workers, int providesWorkers,
             int maxCount, String raisesMaxFarm, int raisesMaxBy, List<ItemStack> upgradeCost, int maxLevel,
             int outputRolls, int depleteCycles, List<ItemStack> scrap, List<Upgrade> upgrades,
             String tier, double richnessMin, double richnessMax, int wpCyclesPerTier, double wpBonusPerTier,
             int wpMaxTier, double hazardChance, List<ItemStack> hazardRepair, boolean buildable,
             double soilStart, double soilPerCycle, double soilDepletedMult, double soilRegenPerCycle,
             ItemStack fertilizerItem, double fertilizerRestores, double boostsOutput,
             Breeding breeding, double boostsWorkers) {
            this.id = id; this.display = display; this.icon = icon; this.categoryId = categoryId;
            this.iconItem = iconItem; this.workersPerLevel = workersPerLevel; this.maxLevel = Math.max(0, maxLevel);
            this.blocks = blocks; this.mmoBlocks = mmoBlocks; this.creatures = creatures;
            this.cycleSeconds = Math.max(1, cycleSeconds); this.output = output; this.inputs = inputs;
            this.demolishRefund = demolishRefund; this.gen = gen;
            this.requires = requires; this.workers = Math.max(0, workers); this.providesWorkers = Math.max(0, providesWorkers);
            this.maxCount = Math.max(0, maxCount); this.raisesMaxFarm = raisesMaxFarm; this.raisesMaxBy = Math.max(0, raisesMaxBy);
            this.upgradeCost = upgradeCost;
            this.outputRolls = Math.max(0, outputRolls); this.depleteCycles = Math.max(0, depleteCycles);
            this.scrap = scrap; this.upgrades = upgrades;
            this.tier = tier; this.richnessMin = richnessMin; this.richnessMax = Math.max(richnessMin, richnessMax);
            this.wpCyclesPerTier = Math.max(0, wpCyclesPerTier); this.wpBonusPerTier = Math.max(0, wpBonusPerTier);
            this.wpMaxTier = Math.max(0, wpMaxTier); this.hazardChance = Math.max(0, Math.min(1, hazardChance));
            this.hazardRepair = hazardRepair; this.buildable = buildable;
            this.soilStart = Math.max(0, soilStart); this.soilPerCycle = Math.max(0, soilPerCycle);
            this.soilDepletedMult = Math.max(0, Math.min(1, soilDepletedMult)); this.soilRegenPerCycle = Math.max(0, soilRegenPerCycle);
            this.fertilizerItem = fertilizerItem; this.fertilizerRestores = Math.max(0, fertilizerRestores);
            this.boostsOutput = Math.max(1.0, boostsOutput);
            this.breeding = breeding; this.boostsWorkers = Math.max(1.0, boostsWorkers);
        }

        public boolean hasRichness() { return richnessMax > richnessMin || richnessMin != 1.0; }
        public boolean hasProficiency() { return wpCyclesPerTier > 0 && wpMaxTier > 0 && wpBonusPerTier > 0; }
        public boolean hasHazard() { return hazardChance > 0; }
        /** This farm uses the reversible-soil mechanic (consumes or regenerates nutrients). */
        public boolean hasSoil() { return soilStart > 0 || soilRegenPerCycle > 0; }
        public boolean regensSoil() { return soilRegenPerCycle > 0; }
        public boolean hasFertilizer() { return fertilizerItem != null && fertilizerRestores > 0; }
        public boolean boostsOutput() { return boostsOutput > 1.0; }
        public boolean breeds() { return breeding != null; }
        public boolean boostsWorkers() { return boostsWorkers > 1.0; }
        public boolean isHouse() { return providesWorkers > 0; }
        public boolean upgradeable() { return upgradeCost != null && !upgradeCost.isEmpty(); }
        /** Whether this farm can still be level-upgraded at the given current level. */
        public boolean canUpgrade(int level) { return upgradeable() && (maxLevel <= 0 || level < maxLevel); }
        public boolean depletes() { return depleteCycles > 0; }
    }

    private final int baseSlots;
    private final int slotsPerTier;
    private final int maxTier;            // 0 = unbounded
    private final int captureRadius;      // creature vacuum radius (blocks)
    private final int rosterCap;          // max total creatures stored in the roster
    private final boolean populationWorkers;  // ADR 0022: workers come from bred population housed in houses (false = instant capacity)
    private final long vaultCapBase;
    private final long vaultCapPerTier;
    private final int maxAccrueHours;     // cap on offline catch-up (0 = uncapped)
    private final List<ItemStack> upgrade;          // block recipe fed to upgrade one tier
    private final Map<String, Size> sizes;          // build sizes (small..huge); never empty
    private final Map<String, Category> categories; // build categories; never empty
    private final Map<String, Farm> factories;

    private FactoryDistrictSpec(int baseSlots, int slotsPerTier, int maxTier, int captureRadius, int rosterCap,
                                boolean populationWorkers,
                                long vaultCapBase, long vaultCapPerTier, int maxAccrueHours,
                                List<ItemStack> upgrade, Map<String, Size> sizes,
                                Map<String, Category> categories, Map<String, Farm> factories) {
        this.baseSlots = baseSlots; this.slotsPerTier = slotsPerTier; this.maxTier = maxTier;
        this.captureRadius = captureRadius; this.rosterCap = rosterCap;
        this.populationWorkers = populationWorkers;
        this.vaultCapBase = vaultCapBase; this.vaultCapPerTier = vaultCapPerTier;
        this.maxAccrueHours = maxAccrueHours; this.upgrade = upgrade; this.sizes = sizes;
        this.categories = categories; this.factories = factories;
    }

    public Map<String, Category> categories() { return categories; }
    public Category category(String id) { return id == null ? null : categories.get(id.toLowerCase(Locale.ROOT)); }

    /** Farms listed under a category, in catalog order. */
    public List<Farm> farmsIn(String categoryId) {
        List<Farm> out = new ArrayList<>();
        for (Farm f : factories.values()) if (f.categoryId.equalsIgnoreCase(categoryId)) out.add(f);
        return out;
    }

    /** Buildable farms in a category (excludes convert/generate-only ones like mines). */
    public List<Farm> buildableFarmsIn(String categoryId) {
        List<Farm> out = new ArrayList<>();
        for (Farm f : farmsIn(categoryId)) if (f.buildable) out.add(f);
        return out;
    }

    public Map<String, Size> sizes() { return sizes; }
    public Size size(String id) {
        if (id != null) { Size s = sizes.get(id.toLowerCase(Locale.ROOT)); if (s != null) return s; }
        return sizes.values().iterator().next();   // fall back to the first (smallest) size
    }

    public int baseSlots() { return baseSlots; }
    public int slotsPerTier() { return slotsPerTier; }
    public int maxTier() { return maxTier; }
    public int captureRadius() { return captureRadius; }
    public int rosterCap() { return rosterCap; }
    public boolean populationWorkers() { return populationWorkers; }
    public int maxAccrueHours() { return maxAccrueHours; }
    public List<ItemStack> upgrade() { return upgrade; }
    public boolean canUpgrade(int tier) { return !upgrade.isEmpty() && (maxTier <= 0 || tier < maxTier); }
    public Map<String, Farm> factories() { return factories; }
    public Farm farm(String id) { return id == null ? null : factories.get(id.toLowerCase(Locale.ROOT)); }

    /** Slots at a given tier: {@code baseSlots + (tier-1)*slotsPerTier}. */
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

    /** Parse a {@code factory:} section, or return null if absent. */
    public static FactoryDistrictSpec parse(ConfigurationSection sec) { return parse(sec, null); }

    /**
     * Parse a {@code factory:} section, merging external catalog files (from the
     * {@code factory_district/} folder — built-in farms + admin-scanned farms; each a root with
     * {@code categories:}/{@code factories:}). Earlier sources win on id collisions.
     */
    public static FactoryDistrictSpec parse(ConfigurationSection sec, java.util.List<ConfigurationSection> catalogs) {
        if (sec == null) return null;
        int baseSlots = sec.getInt("slots", 6);
        int slotsPerTier = sec.getInt("slots-per-tier", 3);
        int maxTier = sec.getInt("max-tier", 0);
        int captureRadius = sec.getInt("capture-radius", 6);
        int rosterCap = sec.getInt("roster-cap", 128);
        boolean populationWorkers = sec.getBoolean("population-workers", false);   // ADR 0022 (opt-in; default = instant capacity)
        long vaultCapBase = sec.getLong("vault-cap", 6912L);          // ~2 double chests
        long vaultCapPerTier = sec.getLong("vault-cap-per-tier", vaultCapBase);
        int maxAccrueHours = sec.getInt("max-accrue-hours", 24);
        List<ItemStack> upgrade = parseItemList(sec.getStringList("upgrade"));

        Map<String, Size> sizes = new LinkedHashMap<>();
        ConfigurationSection ss = sec.getConfigurationSection("sizes");
        if (ss != null) {
            for (String key : ss.getKeys(false)) {
                ConfigurationSection z = ss.getConfigurationSection(key);
                if (z == null) continue;
                double cost = z.getDouble("cost", 1.0);
                double creatures = z.getDouble("creatures", cost);   // default: scale with cost
                double output = z.getDouble("output", 1.0);
                sizes.put(key.toLowerCase(Locale.ROOT),
                        new Size(key.toLowerCase(Locale.ROOT), z.getString("name", capitalize(key)), cost, creatures, output));
            }
        }
        if (sizes.isEmpty()) sizes.put("standard", new Size("standard", "Standard", 1.0, 1.0, 1.0));

        Map<String, Category> categories = new LinkedHashMap<>();
        readCategories(sec.getConfigurationSection("categories"), categories);
        if (catalogs != null) for (ConfigurationSection c : catalogs) readCategories(c.getConfigurationSection("categories"), categories);
        if (categories.isEmpty()) categories.put("general", new Category("general", "Farms", new ItemStack(Material.CHEST)));
        String fallbackCat = categories.keySet().iterator().next();

        Map<String, Farm> farms = new LinkedHashMap<>();
        readFarms(sec.getConfigurationSection("factories"), farms, categories, fallbackCat);
        if (catalogs != null) for (ConfigurationSection c : catalogs) readFarms(c.getConfigurationSection("factories"), farms, categories, fallbackCat);

        return new FactoryDistrictSpec(baseSlots, slotsPerTier, maxTier, captureRadius, rosterCap, populationWorkers,
                vaultCapBase, vaultCapPerTier, maxAccrueHours, upgrade, sizes, categories, farms);
    }

    private static void readCategories(ConfigurationSection cs, Map<String, Category> out) {
        if (cs == null) return;
        for (String key : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(key);
            if (c == null) continue;
            String cid = key.toLowerCase(Locale.ROOT);
            out.putIfAbsent(cid, new Category(cid, c.getString("name", capitalize(key)), iconOf(c, Material.CHEST)));
        }
    }

    private static void readFarms(ConfigurationSection fs, Map<String, Farm> out, Map<String, Category> cats, String fallback) {
        if (fs == null) return;
        for (String key : fs.getKeys(false)) {
            ConfigurationSection f = fs.getConfigurationSection(key);
            if (f == null) continue;
            String id = key.toLowerCase(Locale.ROOT);
            if (out.containsKey(id)) continue;   // built-in wins on collision
            Farm farm = parseFarm(id, f, cats, fallback);
            if (farm != null) out.put(farm.id, farm);
        }
    }

    private static Farm parseFarm(String id, ConfigurationSection f, Map<String, Category> categories, String fallbackCat) {
        String display = f.getString("name", capitalize(id));
        Material icon = Material.matchMaterial(String.valueOf(f.getString("icon", "FURNACE")).toUpperCase(Locale.ROOT));
        if (icon == null) icon = Material.FURNACE;
        String cat = String.valueOf(f.getString("category", fallbackCat)).toLowerCase(Locale.ROOT);
        if (!categories.containsKey(cat)) cat = fallbackCat;   // unknown category → fallback

        ConfigurationSection build = f.getConfigurationSection("build");
        List<ItemStack> blocks = new ArrayList<>();
        List<MmoCost> mmoBlocks = new ArrayList<>();
        if (build != null) parseBuildCost(build.getList("blocks"), blocks, mmoBlocks);
        Map<EntityType, Integer> creatures = parseCreatures(build == null ? null : build.getStringList("creatures"));

        int cycle = f.getInt("cycle-seconds", 60);
        List<Reward> output = parseRewards(f.getList("output"));
        List<ItemStack> inputs = parseItemList(f.getStringList("inputs"));
        List<ItemStack> demolishRefund = parseItemList(f.getStringList("demolish-refund"));
        Generation gen = parseGeneration(f.getConfigurationSection("generates"));

        List<String> requires = new ArrayList<>();
        for (String r : f.getStringList("requires")) if (r != null && !r.isBlank()) requires.add(r.toLowerCase(Locale.ROOT));
        int workers = f.getInt("workers", 0);
        int providesWorkers = f.getInt("provides-workers", 0);
        int workersPerLevel = f.getInt("workers-per-level", providesWorkers);   // capacity added per upgrade level
        ItemStack iconItem = iconOf(f, icon);                                    // textured head (head:) or material (icon:)
        int maxCount = f.getInt("max-count", 0);
        String raisesMaxFarm = null; int raisesMaxBy = 0;
        ConfigurationSection rm = f.getConfigurationSection("raises-max");
        if (rm != null) { raisesMaxFarm = rm.getString("farm"); if (raisesMaxFarm != null) raisesMaxFarm = raisesMaxFarm.toLowerCase(Locale.ROOT); raisesMaxBy = rm.getInt("by", 0); }
        List<ItemStack> upgradeCost = parseItemList(f.getStringList("upgrade-cost"));
        int maxLevel = f.getInt("max-level", 0);
        int outputRolls = f.getInt("output-rolls", 0);
        int depleteCycles = f.getInt("deplete-cycles", 0);
        List<ItemStack> scrap = parseItemList(f.getStringList("scrap"));
        List<Upgrade> upgrades = new ArrayList<>();
        for (Map<?, ?> raw : f.getMapList("upgrades")) {
            Object to = raw.get("to");
            if (to == null) continue;
            List<ItemStack> ub = new ArrayList<>(); List<MmoCost> um = new ArrayList<>();
            Object cost = raw.get("cost");
            if (cost instanceof List<?> l) parseBuildCost(l, ub, um);
            Map<String, Long> reqProd = new LinkedHashMap<>();
            if (raw.get("requires-produced") instanceof Map<?, ?> rp)
                for (Map.Entry<?, ?> e : rp.entrySet())
                    reqProd.put(String.valueOf(e.getKey()).toUpperCase(Locale.ROOT), e.getValue() instanceof Number n ? n.longValue() : 0L);
            List<String> reqBuild = new ArrayList<>();
            if (raw.get("requires-building") instanceof List<?> rb)
                for (Object o : rb) reqBuild.add(String.valueOf(o).toLowerCase(Locale.ROOT));
            upgrades.add(new Upgrade(String.valueOf(to).toLowerCase(Locale.ROOT),
                    raw.get("name") != null ? String.valueOf(raw.get("name")) : ("Upgrade → " + to), ub, um, reqProd, reqBuild));
        }

        String tier = f.getString("tier");
        ConfigurationSection rich = f.getConfigurationSection("richness");
        double richMin = rich != null ? rich.getDouble("min", 1.0) : 1.0;
        double richMax = rich != null ? rich.getDouble("max", richMin) : richMin;
        ConfigurationSection wp = f.getConfigurationSection("worker-proficiency");
        int wpCyc = wp != null ? wp.getInt("cycles-per-tier", 0) : 0;
        double wpBonus = wp != null ? wp.getDouble("bonus-per-tier", 0.0) : 0.0;
        int wpMax = wp != null ? wp.getInt("max-tier", 0) : 0;
        ConfigurationSection hz = f.getConfigurationSection("hazard");
        double hazChance = hz != null ? hz.getDouble("chance", 0.0) : 0.0;
        List<ItemStack> hazRepair = parseItemList(hz != null ? hz.getStringList("repair-cost") : null);

        // ── farming layer (ADR 0017): reversible soil + fertilizer + booster ──
        ConfigurationSection soil = f.getConfigurationSection("soil");
        double soilStart = soil != null ? soil.getDouble("start", 0) : 0;
        double soilPerCycle = soil != null ? soil.getDouble("per-cycle", 0) : 0;
        double soilDepletedMult = soil != null ? soil.getDouble("depleted-mult", 0.25) : 0.25;
        double soilRegen = f.getDouble("restores-soil", 0);            // legume/fallow: regenerate soil instead of consuming
        ItemStack fertItem = null; double fertRestores = 0;
        ConfigurationSection fert = f.getConfigurationSection("fertilizer");
        if (fert != null) {
            Material fm = Material.matchMaterial(String.valueOf(fert.getString("item", "")).toUpperCase(Locale.ROOT));
            if (fm != null) { fertItem = new ItemStack(fm, Math.max(1, fert.getInt("amount", 1))); fertRestores = fert.getDouble("restores", 0); }
        }
        double boostsOutput = f.getDouble("boosts-output", 1.0);

        // ── housing layer (ADR 0018): population growth + worker-capacity booster ──
        Breeding breeding = parseBreeding(f.getConfigurationSection("breeds"));
        double boostsWorkers = f.getDouble("boosts-workers", 1.0);

        // ── crafting layer (ADR 0021): vault recipe processor ──
        List<CraftRecipe> recipes = parseRecipes(f.getConfigurationSection("recipes"));
        boolean craftsVanilla = "vanilla".equalsIgnoreCase(String.valueOf(f.getString("crafts", "")));
        StackMode stackMode = "multi".equalsIgnoreCase(String.valueOf(f.getString("stack-mode", "rate")))
                ? StackMode.MULTI : StackMode.RATE;

        // A farm must do SOMETHING: produce, generate, breed, house workers, raise a cap, boost, or craft.
        if (output.isEmpty() && gen == null && breeding == null && providesWorkers == 0
                && raisesMaxFarm == null && boostsOutput <= 1.0 && boostsWorkers <= 1.0
                && recipes.isEmpty() && !craftsVanilla) return null;
        Farm farm = new Farm(id, display, icon, iconItem, workersPerLevel, cat, blocks, mmoBlocks, creatures, cycle, output, inputs, demolishRefund, gen,
                requires, workers, providesWorkers, maxCount, raisesMaxFarm, raisesMaxBy, upgradeCost, maxLevel,
                outputRolls, depleteCycles, scrap, upgrades,
                tier, richMin, richMax, wpCyc, wpBonus, wpMax, hazChance, hazRepair, f.getBoolean("buildable", true),
                soilStart, soilPerCycle, soilDepletedMult, soilRegen, fertItem, fertRestores, boostsOutput,
                breeding, boostsWorkers);
        farm.recipes = recipes;
        farm.craftsVanilla = craftsVanilla;
        farm.stackMode = stackMode;
        return farm;
    }

    /** Parse a {@code recipes:} block into selectable input→output recipes. */
    private static List<CraftRecipe> parseRecipes(ConfigurationSection sec) {
        List<CraftRecipe> out = new ArrayList<>();
        if (sec == null) return out;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(key);
            if (r == null) continue;
            Map<Material, Integer> in = parseMatMap(r.getConfigurationSection("in"));
            Map<Material, Integer> outm = parseMatMap(r.getConfigurationSection("out"));
            if (in.isEmpty() || outm.isEmpty()) continue;
            out.add(new CraftRecipe(key, r.getString("display", key), in, outm));
        }
        return out;
    }

    private static Map<Material, Integer> parseMatMap(ConfigurationSection sec) {
        Map<Material, Integer> m = new LinkedHashMap<>();
        if (sec == null) return m;
        for (String k : sec.getKeys(false)) {
            Material mat = Material.matchMaterial(k.toUpperCase(Locale.ROOT));
            if (mat != null) m.put(mat, Math.max(1, sec.getInt(k, 1)));
        }
        return m;
    }

    /** Split a {@code build.blocks} list into vanilla ItemStacks + MMOItems costs. */
    static void parseBuildCost(List<?> raw, List<ItemStack> vanillaOut, List<MmoCost> mmoOut) {
        if (raw == null) return;
        for (Object o : raw) {
            if (o instanceof String s) {                          // "MATERIAL AMOUNT"
                ItemStack it = parseItemString(s);
                if (it != null) vanillaOut.add(it);
            } else if (o instanceof Map<?, ?> m) {
                int amt = m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : amountInt(m.get("amount"));
                if (m.get("mmoitem") != null) {                   // { mmoitem: "TYPE ID", amount: N }
                    String[] ti = String.valueOf(m.get("mmoitem")).trim().split("\\s+", 2);
                    if (ti.length == 2) mmoOut.add(new MmoCost(ti[0].toUpperCase(Locale.ROOT), ti[1].toUpperCase(Locale.ROOT), amt));
                } else if (m.get("item") != null) {               // { item: MATERIAL, amount: N }
                    Material mat = Material.matchMaterial(String.valueOf(m.get("item")).toUpperCase(Locale.ROOT));
                    if (mat != null) vanillaOut.add(new ItemStack(mat, amt));
                }
            }
        }
    }

    private static int amountInt(Object a) {
        if (a instanceof Number n) return Math.max(1, n.intValue());
        try { return a == null ? 1 : Math.max(1, Integer.parseInt(a.toString().trim())); } catch (NumberFormatException e) { return 1; }
    }

    /** Parse a {@code generates:} block (machine-breeds-machine), or null if absent. */
    private static Generation parseGeneration(ConfigurationSection g) {
        if (g == null) return null;
        int every = g.getInt("every-seconds", 300);
        double chance = g.getDouble("chance", 0.25);
        int count = g.getInt("count", 1);
        int max = g.getInt("max", 3);
        List<Outcome> outcomes = new ArrayList<>();
        for (Map<?, ?> raw : g.getMapList("outcomes")) {
            Object farm = raw.get("farm");
            if (farm == null) continue;
            int w = raw.get("weight") instanceof Number n ? n.intValue() : 1;
            outcomes.add(new Outcome(String.valueOf(farm).toLowerCase(Locale.ROOT), w));
        }
        if (outcomes.isEmpty()) return null;   // nothing to generate → treat as off
        return new Generation(every, chance, count, max, outcomes, g.getBoolean("worker-scaled", false));
    }

    /** Parse a {@code breeds:} block (population growth → roster), or null if absent/invalid. */
    private static Breeding parseBreeding(ConfigurationSection b) {
        if (b == null) return null;
        EntityType type = matchEntity(b.getString("type"));
        if (type == null) return null;
        int every = b.getInt("every-seconds", 600);
        double chance = b.getDouble("chance", 1.0);
        int count = b.getInt("count", 1);
        int max = b.getInt("max", 0);
        return new Breeding(type, every, chance, count, max, b.getBoolean("worker-scaled", false));
    }

    // --- reward parsing (item / mmoitem / command, with chance + amount range) ----

    static List<Reward> parseRewards(List<?> raw) {
        List<Reward> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            Reward r = parseReward(o);
            if (r != null) out.add(r);
        }
        return out;
    }

    private static Reward parseReward(Object spec) {
        if (spec instanceof String s) {                       // back-compat: "MATERIAL AMOUNT" (or "MAT min-max")
            String[] p = s.trim().split("\\s+");
            Material m = Material.matchMaterial(p[0].toUpperCase(Locale.ROOT));
            if (m == null) return null;
            int[] r = p.length > 1 ? amountRange(p[1]) : new int[]{1, 1};
            return Reward.item(new ItemStack(m), r[0], r[1], 1.0, 1);
        }
        if (!(spec instanceof Map<?, ?> m)) return null;
        double chance = m.get("chance") instanceof Number n ? n.doubleValue() : 1.0;
        int weight = m.get("weight") instanceof Number n ? n.intValue() : 1;
        int[] amt = amountRange(m.get("amount"));
        if (m.get("command") != null) return Reward.command(String.valueOf(m.get("command")), amt[0], amt[1], chance, weight);
        if (m.get("mmoitem") != null) {
            String[] ti = String.valueOf(m.get("mmoitem")).trim().split("\\s+", 2);
            if (ti.length < 2) return null;
            return Reward.mmo(ti[0].toUpperCase(Locale.ROOT), ti[1].toUpperCase(Locale.ROOT), amt[0], amt[1], chance, weight);
        }
        if (m.get("item") != null) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("item")).toUpperCase(Locale.ROOT));
            if (mat == null) return null;
            return Reward.item(new ItemStack(mat), amt[0], amt[1], chance, weight);
        }
        return null;
    }

    /** Parse an amount that may be an int, "min-max" string, or null → defaults to 1..1. */
    private static int[] amountRange(Object a) {
        if (a instanceof Number n) { int v = Math.max(0, n.intValue()); return new int[]{v, v}; }
        if (a instanceof String s) {
            s = s.trim();
            if (s.contains("-")) {
                String[] mm = s.split("-", 2);
                try { return new int[]{ Math.max(0, Integer.parseInt(mm[0].trim())), Math.max(0, Integer.parseInt(mm[1].trim())) }; }
                catch (NumberFormatException ignored) {}
            }
            try { int v = Math.max(0, Integer.parseInt(s)); return new int[]{v, v}; } catch (NumberFormatException ignored) {}
        }
        return new int[]{1, 1};
    }

    /** Parse "ENTITY_TYPE COUNT" lines into a type→count map (order preserved). */
    static Map<EntityType, Integer> parseCreatures(List<String> entries) {
        Map<EntityType, Integer> out = new LinkedHashMap<>();
        if (entries == null) return out;
        for (String e : entries) {
            if (e == null || e.isBlank()) continue;
            String[] parts = e.trim().split("\\s+");
            EntityType type = matchEntity(parts[0]);
            if (type == null) continue;
            int n = 1;
            if (parts.length > 1) { try { n = Math.max(1, Integer.parseInt(parts[1])); } catch (NumberFormatException ignored) {} }
            out.merge(type, n, Integer::sum);
        }
        return out;
    }

    private static EntityType matchEntity(String name) {
        if (name == null) return null;
        try { return EntityType.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Throwable t) { return null; }
    }

    // --- item parsing ("MATERIAL AMOUNT") -----------------------------------

    static List<ItemStack> parseItemList(List<String> entries) {
        List<ItemStack> out = new ArrayList<>();
        if (entries == null) return out;
        for (String e : entries) {
            ItemStack it = parseItemString(e);
            if (it != null) out.add(it);
        }
        return out;
    }

    static ItemStack parseItemString(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.trim().split("\\s+");
        Material mat = Material.matchMaterial(parts[0].toUpperCase(Locale.ROOT));
        if (mat == null) return null;
        int amount = 1;
        if (parts.length > 1) {
            try { amount = Math.max(1, Integer.parseInt(parts[1])); } catch (NumberFormatException ignored) {}
        }
        return new ItemStack(mat, amount);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
