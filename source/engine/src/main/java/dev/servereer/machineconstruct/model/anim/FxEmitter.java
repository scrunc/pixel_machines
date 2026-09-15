package dev.servereer.machineconstruct.model.anim;

import org.bukkit.Particle;

/**
 * A particle or sound emitter — the {@code particle}/{@code sound} channel of
 * DESIGN.md §10. Like {@link ContentSwap} it is a side-effecting channel (not a
 * transform), evaluated each tick at the node's live world position. It is
 * <b>stateless</b>: rate-gating is a pure function of the driver clock (see
 * {@link #fires}), because one {@code ModelNode} tree is shared across every
 * placed machine — per-instance timing comes from the clock's phase offset.
 */
public final class FxEmitter {

    public enum Kind { PARTICLE, SOUND }

    public final Kind kind;
    public final double[] offset;   // added to the node's world position
    public final double rate;       // seconds between emissions; <= 0 = every tick

    // PARTICLE
    public final Particle particle;
    public final int count;
    public final double speed;
    public final double[] spread;   // random box (Bukkit offsetX/Y/Z)

    // SOUND
    public final String sound;      // namespaced key, e.g. "block.anvil.land"
    public final float volume;
    public final float pitch;

    private FxEmitter(Kind kind, double[] offset, double rate,
                      Particle particle, int count, double speed, double[] spread,
                      String sound, float volume, float pitch) {
        this.kind = kind;
        this.offset = offset;
        this.rate = rate;
        this.particle = particle;
        this.count = count;
        this.speed = speed;
        this.spread = spread;
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
    }

    public static FxEmitter particle(Particle p, double[] offset, double[] spread,
                                     int count, double speed, double rate) {
        return new FxEmitter(Kind.PARTICLE, offset, rate, p, count, speed, spread, null, 0f, 0f);
    }

    public static FxEmitter sound(String key, float volume, float pitch, double[] offset, double rate) {
        return new FxEmitter(Kind.SOUND, offset, rate, null, 0, 0.0, new double[]{0, 0, 0},
                key, volume, pitch);
    }

    /** True if a rate boundary was crossed on the tick ending at {@code ctx.clock}. */
    public boolean fires(DriverContext ctx) {
        if (rate <= 0) return true;   // every tick
        long now = (long) Math.floor(ctx.clock / rate);
        long prev = (long) Math.floor((ctx.clock - ctx.dt) / rate);
        return now != prev;
    }
}
