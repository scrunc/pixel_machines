package dev.servereer.machineconstruct.model.anim;

import dev.servereer.machineconstruct.core.MTransform;

/**
 * One FX emission to play this frame: an {@link FxEmitter} and the world
 * transform of the node it fired from (the manager adds the machine origin and
 * emits via Bukkit). Collected by {@code ModelNode.collectFx} during the tick.
 */
public final class FxRequest {

    public final MTransform world;
    public final FxEmitter emitter;

    public FxRequest(MTransform world, FxEmitter emitter) {
        this.world = world;
        this.emitter = emitter;
    }
}
