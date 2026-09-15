package dev.servereer.machineconstruct.model;

import dev.servereer.machineconstruct.core.DisplayContent;
import dev.servereer.machineconstruct.core.MTransform;
import dev.servereer.machineconstruct.core.PacketDisplay;
import dev.servereer.machineconstruct.model.anim.DriverContext;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * A placed, live instance of a {@link Model} at one origin. Built once (one
 * {@link PacketDisplay} per leaf); {@link #recompute} re-derives each leaf's
 * world transform from the current {@link DriverContext} each frame. The shared
 * animation clock calls {@code recompute} then re-sends the changed leaves to
 * viewers — the immediate-mode loop of DESIGN.md §6/§10.
 */
public final class RenderedModel {

    private final ModelNode root;
    private final List<PacketDisplay> leaves = new ArrayList<>();
    private final boolean animated;
    private final boolean hasFx;
    private final Location origin;
    private final MTransform rootWorld;   // identity, or the placement yaw about the column (facing: player)

    public RenderedModel(ModelNode root, Location origin) { this(root, origin, 0); }

    public RenderedModel(ModelNode root, Location origin, int yawDeg) {
        this.root = root;
        this.origin = origin.clone();
        this.rootWorld = yawAboutColumn(yawDeg);
        root.instantiate(rootWorld, DriverContext.idle(0.0), origin.clone(), leaves);
        this.animated = root.animated();
        this.hasFx = root.hasFx();
    }

    public List<PacketDisplay> leaves() {
        return leaves;
    }

    public boolean animated() {
        return animated;
    }

    public boolean hasFx() {
        return hasFx;
    }

    public Location origin() {
        return origin.clone();
    }

    /** A rotation of {@code yawDeg} about the vertical axis through the anchor column (0.5, y, 0.5). */
    public static MTransform yawAboutColumn(int yawDeg) {
        if (yawDeg % 360 == 0) return MTransform.identity();
        double[] one = {1, 1, 1};
        MTransform toCentre = MTransform.of(new double[]{0.5, 0, 0.5}, one, new double[]{0, 0, 0});
        MTransform turn = MTransform.of(new double[]{0, 0, 0}, one, new double[]{yawDeg, 0, 0});
        MTransform back = MTransform.of(new double[]{-0.5, 0, -0.5}, one, new double[]{0, 0, 0});
        return toCentre.composeNoScale(turn).composeNoScale(back);
    }

    /** Collect the FX emissions firing this frame (positions relative to origin). */
    public void collectFx(DriverContext ctx, List<dev.servereer.machineconstruct.model.anim.FxRequest> out) {
        root.collectFx(rootWorld, ctx, out);
    }

    /** Re-derive every leaf's world transform and active content for this frame. */
    public void recompute(DriverContext ctx) {
        List<MTransform> worlds = new ArrayList<>(leaves.size());
        List<DisplayContent> contents = new ArrayList<>(leaves.size());
        root.compose(rootWorld, ctx, worlds, contents);
        int n = Math.min(leaves.size(), worlds.size());
        for (int i = 0; i < n; i++) {
            leaves.get(i).setTransform(worlds.get(i));
            leaves.get(i).setContent(contents.get(i));   // null reverts to base content
        }
    }
}
