package dev.servereer.machineconstruct.model.anim;

import dev.servereer.machineconstruct.core.MTransform;

/** Sinusoidal translation along an axis — hover/float life (DESIGN.md §10.4). */
public final class Bob implements AnimationFunction {

    private final char axis;
    private final double amplitude;
    private final double periodSec;

    public Bob(char axis, double amplitude, double periodSec) {
        this.axis = Character.toLowerCase(axis);
        this.amplitude = amplitude;
        this.periodSec = periodSec <= 0 ? 1.0 : periodSec;
    }

    @Override
    public MTransform apply(DriverContext ctx) {
        double v = amplitude * Math.sin(2 * Math.PI * ctx.clock / periodSec);
        double dx = axis == 'x' ? v : 0;
        double dy = axis == 'y' ? v : 0;
        double dz = axis == 'z' ? v : 0;
        return MTransform.of(new double[]{dx, dy, dz}, new double[]{1, 1, 1}, new double[]{0, 0, 0});
    }
}
