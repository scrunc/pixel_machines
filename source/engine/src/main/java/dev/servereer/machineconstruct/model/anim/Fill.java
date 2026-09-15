package dev.servereer.machineconstruct.model.anim;

import dev.servereer.machineconstruct.core.MTransform;

/**
 * Driver-mapped scaling — grows a part along an axis from {@code from}→{@code to}
 * by a 0..1 driver value ({@code level} = stored÷capacity, or {@code progress}),
 * not by the clock (DESIGN.md §10.4). The output is a scale <b>factor</b> that
 * multiplies the part's base scale, so with a corner pivot a block rises from its
 * base — the Clash-of-Clans elixir gauge.
 */
public final class Fill implements AnimationFunction {

    /** Which 0..1 driver scales the part. */
    public enum Driver { LEVEL, PROGRESS, TIER }

    private final char axis;            // 'x' | 'y' | 'z'
    private final double from, to;
    private final Driver driver;

    public Fill(char axis, double from, double to, Driver driver) {
        this.axis = Character.toLowerCase(axis);
        this.from = from;
        this.to = to;
        this.driver = driver;
    }

    @Override
    public MTransform apply(DriverContext ctx) {
        double v = switch (driver) {
            case PROGRESS -> ctx.progress;
            case TIER -> ctx.tierLevel;
            default -> ctx.level;
        };
        if (v < 0) v = 0; else if (v > 1) v = 1;
        double f = from + (to - from) * v;
        double[] scale = switch (axis) {
            case 'x' -> new double[]{f, 1, 1};
            case 'z' -> new double[]{1, 1, f};
            default  -> new double[]{1, f, 1};   // y
        };
        return MTransform.of(new double[]{0, 0, 0}, scale, new double[]{0, 0, 0});
    }
}
