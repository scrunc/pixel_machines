package dev.servereer.machineconstruct.factorydistrict;

import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-machine state for a Virtual Factory District (ADR 0013). Mutable, one per placed
 * district; serialized by {@link FactoryDistrictStore} into the anchor's PDC.
 *
 * <ul>
 *   <li>{@link #roster} — captured creatures awaiting a build (type → count).</li>
 *   <li>{@link #slots} — fixed-size to the largest tier; each may hold a built, producing farm.</li>
 *   <li>{@link #vault} — the shared Quantum Vault (farm I/O + deposit/withdraw).</li>
 *   <li>{@link #tier} — drives slot capacity + vault cap.</li>
 * </ul>
 */
public final class FactoryDistrictData implements dev.servereer.machineconstruct.grinder.econ.AutoSellState {

    /** A district slot: empty, or a built farm with its own production clock. */
    public static final class Slot {
        public String factoryId;     // null = empty/unbuilt; else the farm running here
        public String sizeId;        // build size (small..huge); null → spec's default size
        public long lastProduce;     // epoch ms of the last accrued cycle (set at build)
        public int offspring;        // how many child machines this slot has generated (cap)
        public long lastGenerate;    // epoch ms of the last generation attempt
        public int workersAssigned;  // workers employed at this machine (ADR 0015)
        public int level;            // upgrade level (houses: ×(1+level) worker capacity)
        public long cyclesProduced;  // lifetime cycles produced (drives depletion + worker proficiency)
        public double richness = 1.0; // per-slot output-richness multiplier (rolled at build)
        public double soil;           // farming: current soil nutrients (0 = none/uninitialised); see FactoryDistrictSpec.Farm.soil*
        public boolean broken;        // hazard: production halted until repaired
        public int mergeStars;        // 0 = single unit; N = N extra same-farm units fused in (★, ADR 0020)
        public double mergeBonus;     // summed output contribution of fused units, added to this slot's multiplier
        public java.util.List<String> selectedRecipes;  // crafter (ADR 0021): chosen recipe ids (null/empty = auto)
        public final Map<String, Long> producedLog = new LinkedHashMap<>();  // lifetime output tally (key → amount)

        public boolean isBuilt() { return factoryId != null; }

        public void logProduced(String key, long amount) {
            if (key == null || amount <= 0) return;
            producedLog.merge(key, amount, Long::sum);
        }
    }

    private int tier = 1;
    private int workerPopulation = 0;   // ADR 0022: bred worker population (population-mode districts)
    private final Map<EntityType, Integer> roster = new LinkedHashMap<>();   // captured creatures
    private final List<Slot> slots = new ArrayList<>();
    private final Map<ItemStack, Long> vault = new LinkedHashMap<>();
    private final List<dev.servereer.machineconstruct.grinder.ChestLink> links = new ArrayList<>();   // saved I/O chests (reuses the grinder model)

    public int tier() { return tier; }
    public void setTier(int t) { this.tier = Math.max(1, t); }

    public int workerPopulation() { return workerPopulation; }
    public void setWorkerPopulation(int n) { this.workerPopulation = Math.max(0, n); }
    public void addWorkerPopulation(int n) { this.workerPopulation = Math.max(0, this.workerPopulation + n); }

    public List<Slot> slots() { return slots; }
    public Slot slot(int i) { return (i >= 0 && i < slots.size()) ? slots.get(i) : null; }

    /** Size the slot list to the largest tier so it never shrinks. */
    public void ensureSlots(int maxSlots) {
        while (slots.size() < maxSlots) slots.add(new Slot());
    }

    // --- creature roster ----------------------------------------------------

    public Map<EntityType, Integer> roster() { return roster; }

    public int rosterTotal() {
        int n = 0;
        for (int v : roster.values()) n += v;
        return n;
    }

    public int creatureCount(EntityType type) { return roster.getOrDefault(type, 0); }

    public void addCreatures(EntityType type, int n) {
        if (type == null || n == 0) return;
        roster.merge(type, n, Integer::sum);
        if (roster.get(type) <= 0) roster.remove(type);
    }

    /** Remove up to {@code n} of a creature type; returns how many were actually removed. */
    public int removeCreatures(EntityType type, int n) {
        if (type == null || n <= 0) return 0;
        int have = roster.getOrDefault(type, 0);
        int take = Math.min(have, n);
        if (take >= have) roster.remove(type); else roster.put(type, have - take);
        return take;
    }

    // --- quantum vault (bidirectional) --------------------------------------

    public Map<ItemStack, Long> vault() { return vault; }

    public void addToVault(ItemStack template, long amount) {
        if (template == null || amount <= 0) return;
        ItemStack key = template.clone();
        key.setAmount(1);
        vault.merge(key, amount, Long::sum);
    }

    /** Remove up to {@code amount} of an item; returns how many were actually removed. */
    public long removeFromVault(ItemStack template, long amount) {
        if (template == null || amount <= 0) return 0;
        ItemStack key = template.clone();
        key.setAmount(1);
        Long have = vault.get(key);
        if (have == null) return 0;
        long take = Math.min(have, amount);
        if (take >= have) vault.remove(key); else vault.put(key, have - take);
        return take;
    }

    public long vaultCount(ItemStack template) {
        if (template == null) return 0;
        ItemStack key = template.clone();
        key.setAmount(1);
        return vault.getOrDefault(key, 0L);
    }

    public long vaultMass() {
        long s = 0;
        for (long v : vault.values()) s += v;
        return s;
    }

    // --- saved I/O chests (OUTPUT routing / autosell hub) -------------------

    public List<dev.servereer.machineconstruct.grinder.ChestLink> links() { return links; }
    public void addLink(dev.servereer.machineconstruct.grinder.ChestLink l) { if (l != null) links.add(l); }

    // --- auto-sell (per-item rules over the vault; hourly payout) ------------
    // Mirrors GrinderData: each chosen item sells at its own rate; money accumulates and is paid out
    // (and trade-logged) once per hour to avoid transaction spam.

    private boolean autoSellEnabled;
    private final Map<org.bukkit.Material, long[]> autoSellRules = new LinkedHashMap<>();   // mat → {maxPerSec(0=all), lastFlow}
    private double autoSellAccMoney;
    private long autoSellAccItems;
    private long autoSellLastPayout;

    @Override public boolean autoSellEnabled() { return autoSellEnabled; }
    @Override public void setAutoSellEnabled(boolean v) { this.autoSellEnabled = v; }
    @Override public Map<org.bukkit.Material, long[]> autoSellRules() { return autoSellRules; }
    @Override public boolean isAutoSellItem(org.bukkit.Material m) { return autoSellRules.containsKey(m); }
    @Override public long autoSellRate(org.bukkit.Material m) { long[] r = autoSellRules.get(m); return r == null ? 0 : r[0]; }
    @Override public boolean autoSellItemFull(org.bukkit.Material m) { return autoSellRate(m) <= 0; }
    @Override public void setAutoSellItem(org.bukkit.Material m, long rate, long now) { autoSellRules.put(m, new long[]{ Math.max(0, rate), now }); }
    @Override public void removeAutoSellItem(org.bukkit.Material m) { autoSellRules.remove(m); }
    @Override public void clearAutoSellItems() { autoSellRules.clear(); }
    @Override public long autoSellItemAllowance(org.bukkit.Material m, long now) {
        long[] r = autoSellRules.get(m);
        if (r == null) return 0;
        if (r[0] <= 0) return Long.MAX_VALUE;
        return Math.max(0, (long) (r[0] * Math.max(0, now - r[1]) / 1000.0));
    }
    @Override public void advanceAutoSellFlow(org.bukkit.Material m, long now, long sold) {
        long[] r = autoSellRules.get(m);
        if (r == null) return;
        if (r[0] <= 0) { r[1] = now; return; }
        if (sold > 0) r[1] += (long) (sold * 1000.0 / r[0]);
        if (now - r[1] > 1000L) r[1] = now - 1000L;   // bound backlog after idle
    }
    @Override public double autoSellAccMoney() { return autoSellAccMoney; }
    @Override public long autoSellAccItems() { return autoSellAccItems; }
    @Override public long autoSellLastPayout() { return autoSellLastPayout; }
    @Override public void setAutoSellLastPayout(long v) { this.autoSellLastPayout = v; }
    @Override public void addAutoSellAcc(double money, long items) { this.autoSellAccMoney += money; this.autoSellAccItems += items; }
    @Override public void resetAutoSellAcc(long now) { this.autoSellAccMoney = 0; this.autoSellAccItems = 0; this.autoSellLastPayout = now; }
    @Override public Map<ItemStack, Long> autoSellSource() { return vault; }
}
