package dev.servereer.machineconstruct.grinder;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.HashSet;
import java.util.Set;

/**
 * A saved chest the grinder pushes loot to (OUTPUT) or pulls spawners from (INPUT).
 * Chosen by the player by clicking a chest within range (see io.radius). Persisted in
 * the grinder's PDC (see {@link GrinderStore}).
 *
 * <p>OUTPUT links carry an item {@link #filter} (empty = everything) and a flow rate:
 * {@code maxPerSec == 0} means "full transfer" (move everything that fits each sweep),
 * otherwise it's a per-second cap. Free chest space is always respected.
 */
public final class ChestLink {

    public enum Type { INPUT, OUTPUT }

    private final Type type;
    private final Location loc;            // the chest block location (cloned)
    private final Set<Material> filter;    // OUTPUT only; empty = all items
    private long maxPerSec;                // OUTPUT only; 0 = full transfer
    private long lastFlowMillis;           // rate-limit clock

    public ChestLink(Type type, Location loc, Set<Material> filter, long maxPerSec) {
        this.type = type;
        this.loc = loc.clone();
        this.filter = (filter == null) ? new HashSet<>() : new HashSet<>(filter);
        this.maxPerSec = Math.max(0, maxPerSec);
        this.lastFlowMillis = System.currentTimeMillis();
    }

    public Type type() { return type; }
    public Location loc() { return loc.clone(); }
    public Set<Material> filter() { return filter; }
    public boolean filtered() { return !filter.isEmpty(); }
    public boolean matches(Material m) { return filter.isEmpty() || filter.contains(m); }
    public void toggleFilter(Material m) { if (!filter.remove(m)) filter.add(m); }
    public void clearFilter() { filter.clear(); }

    public long maxPerSec() { return maxPerSec; }
    public boolean fullTransfer() { return maxPerSec <= 0; }
    public void setMaxPerSec(long v) { this.maxPerSec = Math.max(0, v); }

    public long lastFlowMillis() { return lastFlowMillis; }
    public void setLastFlowMillis(long v) { this.lastFlowMillis = v; }

    /** Allowed items this sweep: full transfer = unlimited, else maxPerSec × elapsed seconds (≥0). */
    public long allowance(long now) {
        if (fullTransfer()) return Long.MAX_VALUE;
        long allowed = (long) (maxPerSec * Math.max(0, now - lastFlowMillis) / 1000.0);
        return Math.max(0, allowed);
    }

    /**
     * Advance the rate clock by the time-worth of the {@code moved} items actually pushed, keeping
     * the leftover fraction so a slow rate (e.g. 1/sec, below one item per 500ms sweep) still accrues
     * across sweeps instead of being discarded. Caps the unspent backlog to ~1s so an idle link can't
     * burst-flood the chest on the next sweep. Full transfer just snaps the clock to now.
     */
    public void advanceFlow(long now, long moved) {
        if (fullTransfer()) { lastFlowMillis = now; return; }
        if (moved > 0) lastFlowMillis += (long) (moved * 1000.0 / maxPerSec);
        if (now - lastFlowMillis > 1000L) lastFlowMillis = now - 1000L;   // bound backlog after idle
    }

    /** True if this link points at the given block location (same world + coords). */
    public boolean at(Location o) {
        return o != null && o.getWorld() != null && loc.getWorld() != null
                && loc.getWorld().getUID().equals(o.getWorld().getUID())
                && loc.getBlockX() == o.getBlockX() && loc.getBlockY() == o.getBlockY() && loc.getBlockZ() == o.getBlockZ();
    }
}
