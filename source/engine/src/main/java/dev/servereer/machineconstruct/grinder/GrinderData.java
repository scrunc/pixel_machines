package dev.servereer.machineconstruct.grinder;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-machine grinder state, attached to a {@link dev.servereer.machineconstruct.machine.Machine}
 * and persisted to the anchor's PDC (see {@link GrinderStore}). Holds the installed
 * spawners, the shared collected loot pool, pooled XP, installed capacity cores,
 * and a bounded action log shown in the menu.
 */
public final class GrinderData implements dev.servereer.machineconstruct.grinder.econ.AutoSellState {

    private static final int LOG_MAX = 50;

    private final List<InstalledSpawner> spawners = new ArrayList<>();
    private final Map<ItemStack, Long> pool = new LinkedHashMap<>();   // template (amount 1) → count
    private long storedExp;
    private final Map<org.bukkit.Material, Integer> cores = new LinkedHashMap<>();   // capacity cores installed
    private final Deque<String> log = new ArrayDeque<>();
    private final List<ChestLink> links = new ArrayList<>();   // saved input/output chests

    // auto-sell: PER-ITEM rules — each chosen item sells at its own rate. Money accumulates and is
    // paid out (and logged) once per hour to avoid transaction spam.
    private boolean autoSellEnabled;                                                     // master on/off
    private final Map<org.bukkit.Material, long[]> autoSellRules = new LinkedHashMap<>(); // mat → {maxPerSec(0=all), lastFlow}
    private double autoSellAccMoney;
    private long autoSellAccItems;
    private long autoSellLastPayout;

    public boolean autoSellEnabled() { return autoSellEnabled; }
    public void setAutoSellEnabled(boolean v) { this.autoSellEnabled = v; }
    public Map<org.bukkit.Material, long[]> autoSellRules() { return autoSellRules; }
    public boolean isAutoSellItem(org.bukkit.Material m) { return autoSellRules.containsKey(m); }
    public long autoSellRate(org.bukkit.Material m) { long[] r = autoSellRules.get(m); return r == null ? 0 : r[0]; }
    public boolean autoSellItemFull(org.bukkit.Material m) { return autoSellRate(m) <= 0; }
    public void setAutoSellItem(org.bukkit.Material m, long rate, long now) { autoSellRules.put(m, new long[]{ Math.max(0, rate), now }); }
    public void removeAutoSellItem(org.bukkit.Material m) { autoSellRules.remove(m); }
    public void clearAutoSellItems() { autoSellRules.clear(); }
    public long autoSellItemAllowance(org.bukkit.Material m, long now) {
        long[] r = autoSellRules.get(m);
        if (r == null) return 0;
        if (r[0] <= 0) return Long.MAX_VALUE;
        return Math.max(0, (long) (r[0] * Math.max(0, now - r[1]) / 1000.0));
    }
    public void advanceAutoSellFlow(org.bukkit.Material m, long now, long sold) {
        long[] r = autoSellRules.get(m);
        if (r == null) return;
        if (r[0] <= 0) { r[1] = now; return; }
        if (sold > 0) r[1] += (long) (sold * 1000.0 / r[0]);
        if (now - r[1] > 1000L) r[1] = now - 1000L;   // bound backlog after idle
    }

    public double autoSellAccMoney() { return autoSellAccMoney; }
    public long autoSellAccItems() { return autoSellAccItems; }
    public long autoSellLastPayout() { return autoSellLastPayout; }
    public void setAutoSellLastPayout(long v) { this.autoSellLastPayout = v; }
    public void addAutoSellAcc(double money, long items) { this.autoSellAccMoney += money; this.autoSellAccItems += items; }
    public void resetAutoSellAcc(long now) { this.autoSellAccMoney = 0; this.autoSellAccItems = 0; this.autoSellLastPayout = now; }

    @Override public Map<ItemStack, Long> autoSellSource() { return pool; }

    public List<InstalledSpawner> spawners() { return spawners; }
    public Map<ItemStack, Long> pool() { return pool; }
    public Map<org.bukkit.Material, Integer> cores() { return cores; }
    public List<ChestLink> links() { return links; }
    public void addLink(ChestLink l) { if (l != null) links.add(l); }

    public long storedExp() { return storedExp; }
    public void setStoredExp(long v) { this.storedExp = Math.max(0, v); }
    public void addStoredExp(long v) { this.storedExp = Math.max(0, this.storedExp + v); }

    public InstalledSpawner findSpawner(String type) {
        if (type == null) return null;
        for (InstalledSpawner s : spawners) if (s.type().equalsIgnoreCase(type)) return s;
        return null;
    }

    /**
     * Install {@code amount} spawners of {@code type}, slot-style: fill existing
     * slots of that mob up to the per-slot stack cap, then open new slots (up to
     * the tier's slot count). Returns how many were actually installed (may be
     * less than requested if the grinder is full). Never throws.
     */
    public long install(GrinderSpec spec, int tier, String type, long amount, long now) {
        if (type == null || amount <= 0 || spec == null) return 0;
        long cap = spec.stackCap(tier);
        int maxSlots = spec.slots(tier);
        long added = 0;
        // top up existing slots of this mob
        for (InstalledSpawner s : spawners) {
            if (amount <= 0) break;
            if (!s.type().equalsIgnoreCase(type)) continue;
            long room = cap - s.stackSize();
            if (room <= 0) continue;
            long take = Math.min(room, amount);
            s.setStackSize(s.stackSize() + take);
            amount -= take;
            added += take;
        }
        // open new slots while there's room
        while (amount > 0 && spawners.size() < maxSlots) {
            long take = Math.min(cap, amount);
            spawners.add(new InstalledSpawner(type, take, now));
            amount -= take;
            added += take;
        }
        return added;
    }

    /** Slots currently occupied. */
    public int slotsUsed() { return spawners.size(); }

    /** Total whole items currently in the shared pool. */
    public long pooledItems() {
        long s = 0;
        for (long v : pool.values()) s += v;
        return s;
    }

    public void addToPool(ItemStack template, long amount) {
        if (amount <= 0) return;
        pool.merge(template, amount, Long::sum);
    }

    public void addCore(org.bukkit.Material mat, int n) {
        cores.merge(mat, n, Integer::sum);
        if (cores.get(mat) <= 0) cores.remove(mat);
    }

    // --- action log ---------------------------------------------------------

    public void logEvent(String line) {
        log.addFirst(line);
        while (log.size() > LOG_MAX) log.removeLast();
    }

    /** Most-recent-first view of the action log. */
    public List<String> logLines() { return new ArrayList<>(log); }
}
