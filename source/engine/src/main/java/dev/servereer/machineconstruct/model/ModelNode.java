package dev.servereer.machineconstruct.model;

import dev.servereer.machineconstruct.core.DisplayContent;
import dev.servereer.machineconstruct.core.ItemContent;
import dev.servereer.machineconstruct.core.MTransform;
import dev.servereer.machineconstruct.core.PacketDisplay;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import dev.servereer.machineconstruct.model.anim.AnimationFunction;
import dev.servereer.machineconstruct.model.anim.ContentSwap;
import dev.servereer.machineconstruct.model.anim.DriverContext;
import dev.servereer.machineconstruct.model.anim.FxEmitter;
import dev.servereer.machineconstruct.model.anim.FxRequest;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in a model's transform tree (DESIGN.md §9, §10.7). Either a
 * <b>group</b> (transform-only) or a <b>leaf</b> (block display); a node may
 * also be both (a block that has children). Each node has a static local
 * transform plus per-frame {@link AnimationFunction}s keyed by <b>trigger</b>
 * ({@code always}, {@code idle}, {@code working}, {@code blocked}, …). The
 * active trigger is chosen from the machine state in the {@link DriverContext},
 * so {@code idle:} and {@code working:} animations resolve automatically.
 *
 * <p>Parenting is server-side: {@link #instantiate}/{@link #compose} walk the
 * tree in identical order, composing world transforms down the chain.
 */
public final class ModelNode {

    private final MTransform local;
    private final DisplayContent content;     // null → pure group
    private byte billboard = 0;               // 0 fixed | 1 vertical | 2 horizontal | 3 center
    private final List<ModelNode> children = new ArrayList<>();
    private final Map<String, List<AnimationFunction>> anims = new LinkedHashMap<>();
    private final Map<String, ContentSwap> swaps = new LinkedHashMap<>();      // trigger → content selector
    private final Map<String, List<FxEmitter>> fx = new LinkedHashMap<>();     // trigger → particle/sound emitters
    private ItemBinding binding = ItemBinding.NONE;   // show the machine's live input/output item
    private byte bindingDisplay = 8;                  // item display context for a bound item (fixed)
    private double px = 0, py = 0, pz = 0;    // pivot (unit-block space): rotation/scale origin
    private String name;                      // the part's key in the YAML (last path segment); null for twins/unnamed
    private dev.servereer.machineconstruct.core.ButtonSpec button;   // button: — a clickable, hover-glowing part

    private static DisplayContent air;        // cached invisible item (lazy: server must be up)

    private ModelNode(MTransform local, DisplayContent content) {
        this.local = local;
        this.content = content;
    }

    public ModelNode name(String name) { this.name = name; return this; }
    public ModelNode button(dev.servereer.machineconstruct.core.ButtonSpec b) { this.button = b; return this; }
    public String name() { return name; }

    /** Set the pivot — the point (in 0..1 unit-block space) rotation/scale orbit. */
    public ModelNode pivot(double px, double py, double pz) {
        this.px = px; this.py = py; this.pz = pz;
        return this;
    }

    public static ModelNode group(MTransform local) {
        return new ModelNode(local, null);
    }

    public static ModelNode leaf(DisplayContent content, MTransform local) {
        return new ModelNode(local, content);
    }

    /** Billboard mode for this leaf: 0 fixed, 1 vertical, 2 horizontal, 3 center. */
    public ModelNode billboard(byte billboard) {
        this.billboard = billboard;
        return this;
    }

    public ModelNode add(ModelNode child) {
        children.add(child);
        return this;
    }

    /** Add an animation function under a trigger ({@code always}/{@code idle}/{@code working}/…). */
    public ModelNode anim(String trigger, AnimationFunction fn) {
        anims.computeIfAbsent(trigger.toLowerCase(), k -> new ArrayList<>()).add(fn);
        return this;
    }

    /** Convenience: an always-on animation. */
    public ModelNode anim(AnimationFunction fn) {
        return anim("always", fn);
    }

    /** Add a content swap (skin selector) under a trigger. */
    public ModelNode swap(String trigger, ContentSwap swap) {
        swaps.put(trigger.toLowerCase(), swap);
        return this;
    }

    /** Add a particle/sound emitter under a trigger. */
    public ModelNode fx(String trigger, FxEmitter emitter) {
        fx.computeIfAbsent(trigger.toLowerCase(), k -> new ArrayList<>()).add(emitter);
        return this;
    }

    /** Bind this leaf's displayed item to the machine's live input/output (0-hardcoded content). */
    public ModelNode bind(ItemBinding binding, byte displayContext) {
        this.binding = binding;
        this.bindingDisplay = displayContext;
        return this;
    }

    public boolean animated() {
        if (!anims.isEmpty() || !swaps.isEmpty() || !fx.isEmpty() || binding != ItemBinding.NONE) return true;
        for (ModelNode c : children) if (c.animated()) return true;
        return false;
    }

    public boolean hasFx() {
        if (!fx.isEmpty()) return true;
        for (ModelNode c : children) if (c.hasFx()) return true;
        return false;
    }

    /**
     * The content this leaf should show this frame, or {@code null} to keep its
     * base content. A live item binding wins; else a state-specific swap wins
     * over an {@code always} swap.
     */
    private DisplayContent contentAt(DriverContext ctx) {
        if (binding == ItemBinding.INPUT) return boundItem(ctx.inputItem);
        if (binding == ItemBinding.OUTPUT) return boundItem(ctx.outputItem);
        if (swaps.isEmpty()) return null;
        ContentSwap s = swaps.get(ctx.state.toLowerCase());
        if (s == null) s = swaps.get("always");
        return (s == null) ? null : s.pick(ctx);
    }

    /** A bound item's content this frame — the live item, or an invisible AIR item when empty. */
    private DisplayContent boundItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            if (air == null) air = new ItemContent(new ItemStack(Material.AIR), (byte) 8);
            return air;
        }
        return new ItemContent(item, bindingDisplay);
    }

    private MTransform localAt(DriverContext ctx) {
        boolean center = content != null && content.centerAnchored();
        // Fast path: static block at the corner with no animation/pivot.
        if (anims.isEmpty() && px == 0 && py == 0 && pz == 0 && !center) return local;

        List<AnimationFunction> active = new ArrayList<>();
        List<AnimationFunction> always = anims.get("always");
        if (always != null) active.addAll(always);
        List<AnimationFunction> byState = anims.get(ctx.state.toLowerCase());
        if (byState != null) active.addAll(byState);
        MTransform anim = AnimationFunction.combine(active, ctx);

        if (center) {
            // Item/text: centre in the offset→offset+scale box (so its offset/scale
            // match a block's), then apply the content's intrinsic fill so it also
            // occupies that box at the same size — block↔head swap is a no-op.
            MTransform t = local.compose(anim);
            float[] f = content.intrinsicFill();
            return new MTransform(
                    t.tx + t.sx / 2f, t.ty + t.sy / 2f, t.tz + t.sz / 2f,
                    t.sx * f[0], t.sy * f[1], t.sz * f[2],
                    t.qx, t.qy, t.qz, t.qw);
        }
        // Block: corner-anchored; rotation/scale around the configured pivot.
        return local.applyAnimAndPivot(anim, px, py, pz);
    }

    /** Build: create a display per leaf, positioned by its world transform at {@code ctx}. */
    public void instantiate(MTransform parentWorld, DriverContext ctx, Location origin, List<PacketDisplay> out) {
        MTransform world = parentWorld.composeNoScale(localAt(ctx));   // parent scale stays local
        if (content != null) out.add(new PacketDisplay(origin, content, world).billboard(billboard).partName(name).button(button));
        for (ModelNode child : children) child.instantiate(world, ctx, origin, out);
    }

    /**
     * Animate: collect each leaf's world transform <b>and</b> its active content
     * at {@code ctx}, in build order. The content entry is {@code null} when no
     * swap is active (the leaf keeps its base content).
     */
    public void compose(MTransform parentWorld, DriverContext ctx,
                        List<MTransform> outWorld, List<DisplayContent> outContent) {
        MTransform world = parentWorld.composeNoScale(localAt(ctx));   // parent scale stays local
        if (content != null) {
            outWorld.add(world);
            outContent.add(contentAt(ctx));
        }
        for (ModelNode child : children) child.compose(world, ctx, outWorld, outContent);
    }

    /**
     * Walk the tree collecting FX emissions that fire this frame, in build order.
     * Unlike {@link #compose}, FX live on <b>any</b> node (groups too), and emit
     * at the node's world position (the manager adds the machine origin).
     */
    public void collectFx(MTransform parentWorld, DriverContext ctx, List<FxRequest> out) {
        MTransform world = parentWorld.composeNoScale(localAt(ctx));
        if (!fx.isEmpty()) {
            List<FxEmitter> active = fx.get("always");
            List<FxEmitter> byState = fx.get(ctx.state.toLowerCase());
            if (active != null) for (FxEmitter e : active) if (e.fires(ctx)) out.add(new FxRequest(world, e));
            if (byState != null) for (FxEmitter e : byState) if (e.fires(ctx)) out.add(new FxRequest(world, e));
        }
        for (ModelNode child : children) child.collectFx(world, ctx, out);
    }
}
