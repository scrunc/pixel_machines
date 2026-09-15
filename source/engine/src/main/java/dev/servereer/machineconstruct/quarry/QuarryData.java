package dev.servereer.machineconstruct.quarry;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-machine Interdimensional Quarry state, attached to a {@link dev.servereer.machineconstruct.machine.Machine}
 * and persisted to the anchor PDC (see {@link QuarryStore}).
 *
 * <p>Fields for all phases are declared up front so persistence is stable as the
 * feature grows; Q1 only drives the tool slots + mode. The <b>quantum vault</b> is
 * a flat {@code item → count} map (no slots — capacity is total mass), and each
 * type carries a {@link Rule} (keep / auto-sell / auto-void).
 */
public final class QuarryData {

    /** What the vault does with a given item type as it arrives. */
    public enum Rule { KEEP, SELL, VOID }

    /** Which loot engine is running. */
    public enum Mode { MINING, FISHING, BOTH }

    private static final int LOG_MAX = 50;

    // tools (the "fuel" — durability is read live off these ItemStacks)
    private ItemStack pickaxe;                       // active mining tool, or null
    private ItemStack rod;                           // active fishing tool, or null
    private final List<ItemStack> pickRack = new ArrayList<>();   // spares, auto-swapped on break
    private final List<ItemStack> rodRack = new ArrayList<>();

    private Mode mode = Mode.MINING;
    private int dimension = 0;                       // active depth index (Q2)

    // quantum vault: item template (amount 1) → count, plus per-type rule
    private final Map<ItemStack, Long> vault = new LinkedHashMap<>();
    private final Map<ItemStack, Rule> rules = new LinkedHashMap<>();

    private long lava;                               // lava tank units (Q6)
    private boolean lavaOn;                           // lava generation toggle (Q6)
    private double heat;                             // overheat meter 0..max (Q6)
    private long lastLava = System.currentTimeMillis();   // lava production clock (retains remainder)
    private long lastHeat = System.currentTimeMillis();   // heat cooling clock (continuous)
    private long riftPoints;                         // skill currency (Q5)
    private long prestige;                           // ascension count (Q6)
    private final Map<String, Integer> skills = new LinkedHashMap<>();   // nodeId → level (Q5)

    // Q6b — vein depletion, rift surges, contracts
    private final Map<Integer, Double> veinHealth = new LinkedHashMap<>();   // dim index → 0..1 (default 1)
    private long lastVein = System.currentTimeMillis();
    private long surgeUntil;                          // surge active while now < this
    private long lastSurgeCheck = System.currentTimeMillis();
    private int contractType;                         // 0 = throughput, 1 = item bounty
    private long contractTarget;
    private long contractBaseMined;                   // totalMined snapshot when a throughput contract was issued
    private ItemStack contractItem;                   // bounty target (amount 1), or null
    private long contractsDone;

    private long lastAccrual = System.currentTimeMillis();   // mining clock, for offline catch-up (Q2)
    private long lastFish = System.currentTimeMillis();      // fishing clock — independent so BOTH advances both (Q4)
    private long totalMined;                         // lifetime stat
    private final Deque<String> log = new ArrayDeque<>();

    // --- tools ---------------------------------------------------------------

    public ItemStack pickaxe() { return pickaxe; }
    public void setPickaxe(ItemStack p) { this.pickaxe = p; }
    public ItemStack rod() { return rod; }
    public void setRod(ItemStack r) { this.rod = r; }
    public List<ItemStack> pickRack() { return pickRack; }
    public List<ItemStack> rodRack() { return rodRack; }

    public Mode mode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public int dimension() { return dimension; }
    public void setDimension(int d) { this.dimension = Math.max(0, d); }

    // --- quantum vault -------------------------------------------------------

    public Map<ItemStack, Long> vault() { return vault; }
    public Map<ItemStack, Rule> rules() { return rules; }

    public Rule ruleFor(ItemStack template) { return rules.getOrDefault(template, Rule.KEEP); }
    public void setRule(ItemStack template, Rule rule) { rules.put(template, rule); }

    public void addToVault(ItemStack template, long amount) {
        if (amount <= 0) return;
        vault.merge(template, amount, Long::sum);
    }

    /** Total quantum mass currently stored. */
    public long vaultMass() {
        long s = 0;
        for (long v : vault.values()) s += v;
        return s;
    }

    // --- meters / progression ------------------------------------------------

    public long lava() { return lava; }
    public void setLava(long v) { this.lava = Math.max(0, v); }
    public boolean lavaOn() { return lavaOn; }
    public void setLavaOn(boolean on) { this.lavaOn = on; }
    public long lastLava() { return lastLava; }
    public void setLastLava(long t) { this.lastLava = t; }
    public long lastHeat() { return lastHeat; }
    public void setLastHeat(long t) { this.lastHeat = t; }
    public double heat() { return heat; }
    public void setHeat(double v) { this.heat = Math.max(0, v); }
    public long riftPoints() { return riftPoints; }
    public void addRiftPoints(long n) { this.riftPoints = Math.max(0, this.riftPoints + n); }
    public void setRiftPoints(long n) { this.riftPoints = Math.max(0, n); }
    public long prestige() { return prestige; }
    public void setPrestige(long n) { this.prestige = Math.max(0, n); }
    public Map<String, Integer> skills() { return skills; }
    public int skill(String node) { return skills.getOrDefault(node, 0); }

    // --- Q6b: vein health / surge / contract --------------------------------

    public Map<Integer, Double> veinHealth() { return veinHealth; }
    public double veinHealth(int dim) { return veinHealth.getOrDefault(dim, 1.0); }
    public void setVeinHealth(int dim, double v) { veinHealth.put(dim, Math.max(0, Math.min(1.0, v))); }
    public long lastVein() { return lastVein; }
    public void setLastVein(long t) { this.lastVein = t; }

    public long surgeUntil() { return surgeUntil; }
    public void setSurgeUntil(long t) { this.surgeUntil = t; }
    public boolean surgeActive(long now) { return now < surgeUntil; }
    public long lastSurgeCheck() { return lastSurgeCheck; }
    public void setLastSurgeCheck(long t) { this.lastSurgeCheck = t; }

    public int contractType() { return contractType; }
    public void setContractType(int t) { this.contractType = t; }
    public long contractTarget() { return contractTarget; }
    public void setContractTarget(long t) { this.contractTarget = t; }
    public long contractBaseMined() { return contractBaseMined; }
    public void setContractBaseMined(long n) { this.contractBaseMined = n; }
    public ItemStack contractItem() { return contractItem; }
    public void setContractItem(ItemStack it) { this.contractItem = it; }
    public long contractsDone() { return contractsDone; }
    public void setContractsDone(long n) { this.contractsDone = Math.max(0, n); }

    public long lastAccrual() { return lastAccrual; }
    public void setLastAccrual(long t) { this.lastAccrual = t; }
    public long lastFish() { return lastFish; }
    public void setLastFish(long t) { this.lastFish = t; }
    public long totalMined() { return totalMined; }
    public void addMined(long n) { this.totalMined += Math.max(0, n); }

    public void logEvent(String line) {
        log.addFirst(line);
        while (log.size() > LOG_MAX) log.removeLast();
    }

    public List<String> logLines() { return new ArrayList<>(log); }
}
