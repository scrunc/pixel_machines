package dev.servereer.machineconstruct.model.anim;

/**
 * The inputs an {@link AnimationFunction} reads each frame (DESIGN.md §10.2).
 * P4 supplies {@code clock} (seconds, with a per-instance phase offset so
 * identical machines don't move in lockstep). {@code progress}/{@code state}/
 * {@code tier}/{@code fuel} are carried now and driven for real in P5.
 */
public final class DriverContext {

    public final double clock;       // seconds, monotonic (+ instance phase)
    public final double progress;    // 0..1 (P5)
    public final String state;       // IDLE / WORKING / ... (P5)
    public final int tier;           // >= 1 (P5)
    public final double fuel;        // 0..1 (P5)
    public final double level;       // 0..1 stored÷capacity — the fill driver (P12)
    public final double tierLevel;   // 0..1 (tier-1)÷(max-1) — the tier driver (P13)
    public final double dt;          // seconds since the previous tick (rate-gated FX)
    public final org.bukkit.inventory.ItemStack inputItem;   // live first input (for <input> binding), or null
    public final org.bukkit.inventory.ItemStack outputItem;  // live first output (for <output> binding), or null

    public DriverContext(double clock, double progress, String state, int tier, double fuel, double level,
                         double tierLevel, double dt,
                         org.bukkit.inventory.ItemStack inputItem, org.bukkit.inventory.ItemStack outputItem) {
        this.clock = clock;
        this.progress = progress;
        this.state = state;
        this.tier = tier;
        this.fuel = fuel;
        this.level = level;
        this.tierLevel = tierLevel;
        this.dt = dt;
        this.inputItem = inputItem;
        this.outputItem = outputItem;
    }

    /** P4 convenience: idle context driven only by the clock (default 0.1s tick step). */
    public static DriverContext idle(double clock) {
        return idle(clock, 0.1);
    }

    /** Idle context with an explicit tick step (for rate-gated FX emission). */
    public static DriverContext idle(double clock, double dt) {
        return new DriverContext(clock, 0.0, "IDLE", 1, 0.0, 0.0, 0.0, dt, null, null);
    }

    /** Context driven by a machine's live state + progress + level + tier + processed items. */
    public static DriverContext of(double clock, double dt, String state, double progress, double level,
                                   int tier, double tierLevel,
                                   org.bukkit.inventory.ItemStack inputItem,
                                   org.bukkit.inventory.ItemStack outputItem) {
        return new DriverContext(clock, progress, state, tier, 0.0, level, tierLevel, dt, inputItem, outputItem);
    }
}
