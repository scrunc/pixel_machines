package dev.servereer.machineconstruct.model.anim;

import dev.servereer.machineconstruct.core.MTransform;

/**
 * High-frequency jitter translation — the "vibrating material" look while a
 * machine works (DESIGN.md §10.4). Deterministic from the clock (multi-frequency
 * sines per axis) so it reads as a buzz, not random teleporting.
 */
public final class Shake implements AnimationFunction {

    private final double amplitude;
    private final double hz;

    public Shake(double amplitude, double hz) {
        this.amplitude = amplitude;
        this.hz = hz <= 0 ? 10 : hz;
    }

    @Override
    public MTransform apply(DriverContext ctx) {
        double t = ctx.clock * hz;
        double dx = amplitude * Math.sin(2 * Math.PI * t * 1.00);
        double dy = amplitude * Math.sin(2 * Math.PI * t * 1.31 + 1.7);
        double dz = amplitude * Math.sin(2 * Math.PI * t * 0.83 + 3.1);
        return MTransform.of(new double[]{dx, dy, dz}, new double[]{1, 1, 1}, new double[]{0, 0, 0});
    }
}
