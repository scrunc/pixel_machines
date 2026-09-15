package dev.servereer.machineconstruct.model.anim;

import dev.servereer.machineconstruct.core.MTransform;

/** Uniform scale oscillation between {@code from} and {@code to} (DESIGN.md §10.4). */
public final class Pulse implements AnimationFunction {

    private final double from;
    private final double to;
    private final double periodSec;

    public Pulse(double from, double to, double periodSec) {
        this.from = from;
        this.to = to;
        this.periodSec = periodSec <= 0 ? 1.0 : periodSec;
    }

    @Override
    public MTransform apply(DriverContext ctx) {
        double t = 0.5 + 0.5 * Math.sin(2 * Math.PI * ctx.clock / periodSec);
        double s = from + (to - from) * t;
        return MTransform.of(new double[]{0, 0, 0}, new double[]{s, s, s}, new double[]{0, 0, 0});
    }
}
