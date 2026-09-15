package dev.servereer.machineconstruct.machine;

import dev.servereer.machineconstruct.core.MTransform;
import dev.servereer.machineconstruct.model.anim.FxEmitter;
import dev.servereer.machineconstruct.model.anim.FxRequest;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Plays an {@link FxRequest} via the Bukkit world at {@code origin + nodeWorld +
 * offset}. Particles/sounds are emitted to all players in range (ambient FX),
 * unlike the viewer-scoped packet displays — a deliberate simplification
 * (DESIGN.md §10 P5c); packet-scoping is a future refinement. Main-thread only.
 */
final class FxPlayer {

    private FxPlayer() {}

    static void play(Location origin, FxRequest req) {
        World w = origin.getWorld();
        if (w == null) return;
        FxEmitter e = req.emitter;
        MTransform t = req.world;
        Location at = origin.clone().add(
                t.tx + e.offset[0], t.ty + e.offset[1], t.tz + e.offset[2]);
        try {
            switch (e.kind) {
                case PARTICLE -> w.spawnParticle(
                        e.particle, at, e.count, e.spread[0], e.spread[1], e.spread[2], e.speed);
                case SOUND -> w.playSound(at, e.sound, e.volume, e.pitch);
            }
        } catch (Throwable ignored) {
            // Bad particle data / sound key must never break the animation tick.
        }
    }
}
