package dev.servereer.machineconstruct.model.anim;

import dev.servereer.machineconstruct.core.MTransform;

/**
 * Oscillating rotation about an axis — a pendulum/rocking motion (DESIGN.md
 * §10.4), as opposed to {@link Spin}'s continuous spin. The angle is
 * {@code amplitude·sin(2π·clock/period + phase)}; pivots about the node's
 * configured pivot, so a walking beam rocks about its fulcrum and its children
 * (horse-head, counterweight) follow via parenting.
 */
public final class Swing implements AnimationFunction {

    private static final double TWO_PI = Math.PI * 2;

    private final char axis;            // 'x' | 'y' | 'z'
    private final double amplitudeDeg;
    private final double period;        // seconds per full back-and-forth
    private final double phase;         // radians, to desync paired arms

    public Swing(char axis, double amplitudeDeg, double period, double phase) {
        this.axis = Character.toLowerCase(axis);
        this.amplitudeDeg = amplitudeDeg;
        this.period = period <= 0 ? 1.0 : period;
        this.phase = phase;
    }

    @Override
    public MTransform apply(DriverContext ctx) {
        double angle = amplitudeDeg * Math.sin(TWO_PI * ctx.clock / period + phase);
        double[] euler = switch (axis) {
            case 'x' -> new double[]{0, angle, 0};   // MTransform euler order = [Y, X, Z]
            case 'z' -> new double[]{0, 0, angle};
            default  -> new double[]{angle, 0, 0};   // y
        };
        return MTransform.of(new double[]{0, 0, 0}, new double[]{1, 1, 1}, euler);
    }
}
