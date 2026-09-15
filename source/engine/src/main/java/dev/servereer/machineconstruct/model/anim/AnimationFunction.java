package dev.servereer.machineconstruct.model.anim;

import dev.servereer.machineconstruct.core.MTransform;

import java.util.ArrayList;
import java.util.List;

/**
 * A per-frame animation function (DESIGN.md §10.4): given the current
 * {@link DriverContext}, it returns an {@link MTransform} delta applied in the
 * node's local space (composed onto the node's static local transform). Presets
 * like {@link Spin}/{@link Bob}/{@link Pulse} implement this; a node may layer
 * several, which compose in order.
 */
@FunctionalInterface
public interface AnimationFunction {

    MTransform apply(DriverContext ctx);

    /** Blend a node's layered functions into one delta for this frame (per-channel). */
    static MTransform combine(List<AnimationFunction> fns, DriverContext ctx) {
        if (fns.isEmpty()) return MTransform.identity();
        List<MTransform> deltas = new ArrayList<>(fns.size());
        for (AnimationFunction fn : fns) deltas.add(fn.apply(ctx));
        return MTransform.blend(deltas);
    }
}
