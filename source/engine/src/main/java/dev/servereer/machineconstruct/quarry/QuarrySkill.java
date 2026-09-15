package dev.servereer.machineconstruct.quarry;

import org.bukkit.Material;

/**
 * The Rift Skill Tree — four branches of upgrade nodes bought with Rift Points
 * (earned by mining/fishing), some also costing money and nether stars. Node data
 * lives here; per-machine levels live in {@link QuarryData#skills()} keyed by
 * {@link #name()}. The static {@code *Mult} / {@code *Bonus} helpers aggregate a
 * data's levels into the numbers {@link QuarryManager} and the vault cap consume,
 * so balance lives in exactly one place.
 */
public enum QuarrySkill {

    // branch, display, icon, maxLevel, rpBase, rpGrowth, moneyPerLevel, starsAtMax, blurb
    RICH_VEINS(Branch.EXCAVATION, "Rich Veins", Material.RAW_GOLD, 5, 50, 1.6, 0, 0,
            "+15% mining yield / level"),
    OVERCLOCK(Branch.EXCAVATION, "Overclock", Material.REDSTONE, 5, 60, 1.6, 0, 0,
            "+10% mining speed / level"),
    DIM_FORTUNE(Branch.EXCAVATION, "Dimensional Fortune", Material.DIAMOND, 3, 120, 2.0, 2000, 0,
            "+20% ore multiplier / level"),
    RIFT_DRILL(Branch.EXCAVATION, "Rift Drill", Material.NETHERITE_PICKAXE, 3, 200, 2.5, 5000, 0,
            "Unlocks the next dimension / level"),

    CURRENT_RIDER(Branch.ABYSSAL, "Current Rider", Material.PRISMARINE_CRYSTALS, 5, 50, 1.6, 0, 0,
            "+10% fishing speed / level"),
    ABYSSAL_LUCK(Branch.ABYSSAL, "Abyssal Luck", Material.HEART_OF_THE_SEA, 5, 80, 1.8, 1500, 0,
            "+1 effective Luck of the Sea / level"),
    DUAL_DRIVE(Branch.ABYSSAL, "Dual Drive", Material.CONDUIT, 3, 150, 2.0, 3000, 0,
            "-15% tool wear in Both mode / level"),

    QUANTUM_EXPANSION(Branch.VAULT, "Quantum Expansion", Material.ENDER_CHEST, 5, 70, 1.7, 1000, 0,
            "+50% vault capacity / level"),
    MARKET_LINK(Branch.VAULT, "Market Link", Material.GOLD_INGOT, 4, 90, 1.8, 2000, 0,
            "+8% sell payout / level"),
    SINGULARITY(Branch.VAULT, "Singularity", Material.NETHER_STAR, 1, 1000, 1.0, 50000, 5,
            "×3 vault capacity (marquee)"),

    RIFT_ATTUNEMENT(Branch.RIFT, "Rift Attunement", Material.AMETHYST_SHARD, 5, 60, 1.7, 0, 0,
            "+25% Rift Point gain / level"),
    ASCENDANCE(Branch.RIFT, "Ascendance", Material.DRAGON_EGG, 1, 2000, 1.0, 100000, 5,
            "Unlocks Prestige (Q6)");

    public enum Branch {
        EXCAVATION("⛏ Excavation", "<gold>", Material.IRON_PICKAXE),
        ABYSSAL("🎣 Abyssal", "<aqua>", Material.FISHING_ROD),
        VAULT("📦 Quantum Vault", "<light_purple>", Material.ENDER_CHEST),
        RIFT("🌋 Rift", "<red>", Material.MAGMA_BLOCK);

        public final String display;
        public final String color;
        public final Material icon;
        Branch(String display, String color, Material icon) { this.display = display; this.color = color; this.icon = icon; }
    }

    public final Branch branch;
    public final String display;
    public final Material icon;
    public final int maxLevel;
    public final long rpBase;
    public final double rpGrowth;
    public final double moneyPerLevel;
    public final int starsAtMax;     // nether stars charged on the level that reaches maxLevel
    public final String blurb;

    QuarrySkill(Branch branch, String display, Material icon, int maxLevel, long rpBase, double rpGrowth,
                double moneyPerLevel, int starsAtMax, String blurb) {
        this.branch = branch; this.display = display; this.icon = icon; this.maxLevel = maxLevel;
        this.rpBase = rpBase; this.rpGrowth = rpGrowth; this.moneyPerLevel = moneyPerLevel;
        this.starsAtMax = starsAtMax; this.blurb = blurb;
    }

    // --- per-level cost (buying level `next`, 1-indexed) ---------------------

    public long rpCost(int next) { return Math.round(rpBase * Math.pow(rpGrowth, next - 1)); }
    public double moneyCost(int next) { return moneyPerLevel * next; }
    /** Nether stars are only charged on the level that reaches max. */
    public int starCost(int next) { return next >= maxLevel ? starsAtMax : 0; }

    // --- effect aggregation (read a data's levels) --------------------------

    private static int lvl(QuarryData d, QuarrySkill s) { return d == null ? 0 : d.skill(s.name()); }

    public static double miningYieldMult(QuarryData d) { return 1.0 + 0.15 * lvl(d, RICH_VEINS); }
    public static double miningSpeedMult(QuarryData d) { return 1.0 + 0.10 * lvl(d, OVERCLOCK); }
    public static double oreFortuneMult(QuarryData d) { return 1.0 + 0.20 * lvl(d, DIM_FORTUNE); }
    public static int maxDimensionIndex(QuarryData d) { return lvl(d, RIFT_DRILL); }

    public static double fishingSpeedMult(QuarryData d) { return 1.0 + 0.10 * lvl(d, CURRENT_RIDER); }
    public static int treasureLuckBonus(QuarryData d) { return lvl(d, ABYSSAL_LUCK); }
    public static double dualDriveWearMult(QuarryData d) { return Math.max(0.10, 1.0 - 0.15 * lvl(d, DUAL_DRIVE)); }

    public static double vaultCapMult(QuarryData d) {
        return (1.0 + 0.50 * lvl(d, QUANTUM_EXPANSION)) * (lvl(d, SINGULARITY) > 0 ? 3.0 : 1.0);
    }
    public static double sellPayoutMult(QuarryData d) { return 1.0 + 0.08 * lvl(d, MARKET_LINK); }
    public static double rpGainMult(QuarryData d) { return 1.0 + 0.25 * lvl(d, RIFT_ATTUNEMENT); }

    /** Permanent global yield multiplier from prestige (Ascendance), +10% per level. */
    public static double prestigeMult(QuarryData d) { return 1.0 + 0.10 * (d == null ? 0 : d.prestige()); }

    /** Effective vault capacity after VAULT-branch nodes. */
    public static long vaultCap(QuarrySpec spec, QuarryData d) {
        return Math.round(spec.vaultCap() * vaultCapMult(d));
    }
}
