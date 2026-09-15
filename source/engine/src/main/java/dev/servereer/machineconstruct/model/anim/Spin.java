package dev.servereer.machineconstruct.model.anim;

import dev.servereer.machineconstruct.core.MTransform;

/** Continuous rotation about an axis at {@code degPerSec} (DESIGN.md §10.4). */
public final class Spin implements AnimationFunction {

    private final char axis;        // 'x' | 'y' | 'z'
    private final double degPerSec;

    public Spin(char axis, double degPerSec) {
        this.axis = Character.toLowerCase(axis);
        this.degPerSec = degPerSec;
    }

    @Override
    public MTransform apply(DriverContext ctx) {
        double angle = ctx.clock * degPerSec;
        double[] euler = switch (axis) {
            case 'x' -> new double[]{0, angle, 0};   // MTransform euler order = [Y, X, Z]
            case 'z' -> new double[]{0, 0, angle};
            default  -> new double[]{angle, 0, 0};   // y
        };
        return MTransform.of(new double[]{0, 0, 0}, new double[]{1, 1, 1}, euler);
    }
}
