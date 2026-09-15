package dev.servereer.machineconstruct.collector;

import dev.servereer.machineconstruct.grinder.ChestLink;
import dev.servereer.machineconstruct.grinder.econ.AutoSellState;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-machine Chunk Collector state, attached to a {@link dev.servereer.machineconstruct.machine.Machine}
 * and persisted to the anchor PDC (see {@link ChunkCollectorStore}).
 *
 * <p>The <b>vault</b> is a flat {@code item template (amount 1) → count} map — the
 * vacuumed drops. It doubles as the {@link AutoSellState} source so the shared
 * grinder/factory auto-sell routine drives the collector too, and is routed to
 * OUTPUT chests via the shared {@code ChestRouter}.
 */
public final class ChunkCollectorData implements AutoSellState {

    private static final int LOG_MAX = 50;

    /**
     * Hard cap on how many <b>distinct</b> item templates the vault will track. A collector is a bulk-drop
     * vacuum — a handful of stackable materials. Without this, a player can funnel thousands of unique-NBT
     * items (written/enchanted books, nested machines) at it; each becomes a distinct key, ballooning the
     * serialized blob until it crash-kicks clients on break and floods the GUI. Past the cap, new <i>types</i>
     * are refused (left on the ground); existing types keep accumulating.
     */
    public static final int MAX_DISTINCT = 128;

    private final Map<ItemStack, Long> vault = new LinkedHashMap<>();   // template (amount 1) → count
    private final List<ChestLink> links = new ArrayList<>();            // saved OUTPUT/INPUT chests
    private final Deque<String> log = new ArrayDeque<>();

    private long lastVacuum = System.currentTimeMillis();   // vacuum clock (gates the sweep interval)
    private long collectedTotal;                            // lifetime stat

    // auto-sell: PER-ITEM rules (mirrors GrinderData) — money accrues, paid out hourly.
    private boolean autoSellEnabled;
    private final Map<Material, long[]> autoSellRules = new LinkedHashMap<>();   // mat → {maxPerSec(0=all), lastFlow}
    private double autoSellAccMoney;
    private long autoSellAccItems;
    private long autoSellLastPayout;

    // --- vault --------------------------------------------------------------

    public Map<ItemStack, Long> vault() { return vault; }

    /**
     * Runtime intake (vacuum path): honors {@link #MAX_DISTINCT}. Returns {@code false} when the vault won't
     * accept this template (a new type past the distinct cap) so the caller leaves the drop on the ground.
     */
    public boolean addToVault(ItemStack template, long amount) {
        if (amount <= 0) return false;
        if (!vault.containsKey(template) && vault.size() >= MAX_DISTINCT) return false;   // full of distinct types → refuse
        vault.merge(template, amount, Long::sum);
        return true;
    }

    /** Load path (from PDC): unconditional merge — never drop already-persisted state, cap or not. */
    public void loadIntoVault(ItemStack template, long amount) {
        if (amount <= 0) return;
        vault.merge(template, amount, Long::sum);
    }

    /** Total items currently stored. */
    public long vaultMass() {
        long s = 0;
        for (long v : vault.values()) s += v;
        return s;
    }

    public List<ChestLink> links() { return links; }
    public void addLink(ChestLink l) { if (l != null) links.add(l); }

    public long lastVacuum() { return lastVacuum; }
    public void setLastVacuum(long v) { this.lastVacuum = v; }
    public long collectedTotal() { return collectedTotal; }
    public void addCollected(long n) { this.collectedTotal += Math.max(0, n); }
    public void setCollectedTotal(long n) { this.collectedTotal = Math.max(0, n); }

    // --- AutoSellState ------------------------------------------------------

    @Override public boolean autoSellEnabled() { return autoSellEnabled; }
    @Override public void setAutoSellEnabled(boolean v) { this.autoSellEnabled = v; }
    @Override public Map<Material, long[]> autoSellRules() { return autoSellRules; }
    @Override public boolean isAutoSellItem(Material m) { return autoSellRules.containsKey(m); }
    @Override public long autoSellRate(Material m) { long[] r = autoSellRules.get(m); return r == null ? 0 : r[0]; }
    @Override public boolean autoSellItemFull(Material m) { return autoSellRate(m) <= 0; }
    @Override public void setAutoSellItem(Material m, long rate, long now) { autoSellRules.put(m, new long[]{ Math.max(0, rate), now }); }
    @Override public void removeAutoSellItem(Material m) { autoSellRules.remove(m); }
    @Override public void clearAutoSellItems() { autoSellRules.clear(); }
    @Override public long autoSellItemAllowance(Material m, long now) {
        long[] r = autoSellRules.get(m);
        if (r == null) return 0;
        if (r[0] <= 0) return Long.MAX_VALUE;
        return Math.max(0, (long) (r[0] * Math.max(0, now - r[1]) / 1000.0));
    }
    @Override public void advanceAutoSellFlow(Material m, long now, long sold) {
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

    // --- action log ---------------------------------------------------------

    public void logEvent(String line) {
        log.addFirst(line);
        while (log.size() > LOG_MAX) log.removeLast();
    }

    public List<String> logLines() { return new ArrayList<>(log); }
}
