package dev.servereer.machineconstruct.model;

import org.bukkit.Location;

/**
 * A named, reusable model — the root of a transform tree (DESIGN.md §9).
 * {@link #render} produces a live {@link RenderedModel} instance at a given
 * origin (one packet display per leaf, recomputed each frame by the animation
 * clock). P3/P4 build models programmatically (Foundry uses
 * {@link ModelNode#group}/{@link ModelNode#leaf} + {@code .anim(...)}); a YAML
 * loader is a later add.
 */
public final class Model {

    private final String id;
    private final ModelNode root;

    public Model(String id, ModelNode root) {
        this.id = id;
        this.root = root;
    }

    public String id() {
        return id;
    }

    /** Create a live instance of this model at {@code origin}. */
    public RenderedModel render(Location origin) {
        return new RenderedModel(root, origin, 0);
    }

    /** Create a live instance turned {@code yawDeg} around the anchor column (x=z=0.5). */
    public RenderedModel render(Location origin, int yawDeg) {
        return new RenderedModel(root, origin, yawDeg);
    }
}
