package dev.servereer.machineconstruct.machine;

import dev.servereer.machineconstruct.core.BlockContent;
import dev.servereer.machineconstruct.core.DisplayContent;
import dev.servereer.machineconstruct.core.Heads;
import dev.servereer.machineconstruct.core.ItemContent;
import dev.servereer.machineconstruct.core.MTransform;
import dev.servereer.machineconstruct.core.PacketDisplay;
import dev.servereer.machineconstruct.core.TextContent;
import dev.servereer.machineconstruct.gui.MenuSkin;
import dev.servereer.machineconstruct.model.RenderedModel;
import dev.servereer.machineconstruct.model.anim.Cue;
import dev.servereer.machineconstruct.tracking.DisplayTracker;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Plays {@link Cue}s on placed machines: a 2-tick clock advances every running cue, fires the steps
 * whose {@code at} has come, tweens moves / rotations / shakes with the tracker's 2-tick
 * interpolation, and restores + unlocks the parts when the cue ends. Parts touched by a cue are
 * LOCKED ({@link PacketDisplay#lock}) so the animation loop's per-frame recompute can't fight the
 * script. Machine-wide steps (sound / particle) play at the anchor, part steps at the part.
 */
public final class CuePlayer {

    private final Plugin plugin;
    private final DisplayTracker tracker;
    private final List<Run> runs = new ArrayList<>();
    private final Random rng = new Random();

    public CuePlayer(Plugin plugin, DisplayTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    // Every tick, not every other: reels want ~20 frames a second, and the clock only does work while
    // a cue is actually running (the first line of tick() leaves immediately when none is).
    public void start() { plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }

    /** One running cue on one machine. */
    private final class Run {
        final Machine m; final Cue cue; final Map<String, String> vars; final Player actor; final Runnable onEnd;
        Map<String, DisplayContent> objects = Map.of();   // {name} → ready-made content (e.g. the actual prize item)
        final MTransform root;                          // the machine's facing yaw about its column
        final long startTick = plugin.getServer().getCurrentTick();
        final Set<Cue.Step> fired = new HashSet<>();
        final List<Tween> tweens = new ArrayList<>();
        final List<Reel> reels = new ArrayList<>();
        final Map<PacketDisplay, MTransform> rest = new java.util.LinkedHashMap<>();   // parts we locked → where they were
        final Set<PacketDisplay> keep = new HashSet<>();
        Run(Machine m, Cue cue, Map<String, String> vars, Player actor, Runnable onEnd) {
            this.m = m; this.cue = cue; this.vars = vars; this.actor = actor; this.onEnd = onEnd;
            this.root = RenderedModel.yawAboutColumn(m.facing());
        }
        double t() { return (plugin.getServer().getCurrentTick() - startTick) / 20.0; }
    }

    /** A transform tween on one part: rest + (move / rotate / shake) evaluated from a start time. */
    private static final class Tween {
        PacketDisplay d; MTransform from; double start, over; String ease;
        float[] moveTo;            // world-axis translation at the end (null = no move)
        char axis; double rotBy;   // rotation (0 = none)
        float[] axisWorld, pivot;  // set = swing about this pivot instead of spinning on the spot
        double shakeAmp;           // > 0 = jitter
        boolean done;
    }

    /**
     * A spinning reel: frames blur past, ease out, and land on {@code stop} (with an optional tease —
     * one frame past the stop, then a flick back). The symbol shown at time t is picked from the list
     * so that the LAST frame is the stop symbol, so a reel always lands where the roll said it would.
     */
    private static final class Reel {
        PacketDisplay d; double start, over, tease; List<DisplayContent> frames; DisplayContent blur, stop;
        MTransform rest; float[] axisWorld;
        int stopIdx, spins; int shown = -2; boolean done;
    }

    /** Fire a cue on a machine. {@code vars} fill {@code {name}} in contents/colours/pitches; {@code actor} gets messages. */
    public void play(Machine m, Cue cue, Map<String, String> vars, Player actor, Runnable onEnd) { play(m, cue, vars, null, actor, onEnd); }

    /** As {@link #play(Machine, Cue, Map, Player, Runnable)}, with ready-made contents for {@code content: "{name}"} steps. */
    public void play(Machine m, Cue cue, Map<String, String> vars, Map<String, DisplayContent> objects, Player actor, Runnable onEnd) {
        if (cue == null || m.rendered() == null) { if (onEnd != null) onEnd.run(); return; }
        Run r = new Run(m, cue, vars == null ? Map.of() : vars, actor, onEnd);
        if (objects != null) r.objects = objects;
        // Take the parts NOW, not on the first tick: a button press fires its action before its own push,
        // and checks the lock right after — so the cue must already own the dial (with its true rest pose)
        // or the push lands and becomes the pose the cue later "restores" to, one notch deeper each time.
        for (Cue.Step s : cue.steps()) {
            if (s.part == null) continue;
            for (PacketDisplay d : select(m, s.part)) if (!r.rest.containsKey(d)) { r.rest.put(d, d.transform()); d.lock(true); }
        }
        runs.add(r);
    }

    /** Whether a cue of this machine is still running. */
    public boolean playing(Machine m) { for (Run r : runs) if (r.m == m) return true; return false; }

    /** Drop every cue of a machine (it was broken / re-rendered) — parts are left as they are. */
    public void forget(Machine m) { runs.removeIf(r -> { if (r.m != m) return false; for (PacketDisplay d : r.rest.keySet()) if (!d.pinned()) d.lock(false); return true; }); }

    private void tick() {
        if (runs.isEmpty()) return;
        for (Run r : new ArrayList<>(runs)) {
            double t = r.t();
            for (Cue.Step s : r.cue.steps()) {
                if (s.at > t || r.fired.contains(s)) continue;
                r.fired.add(s);
                try { fire(r, s, t); } catch (Throwable ex) { plugin.getLogger().warning("[MachineConstruct] cue " + r.cue.name() + " step failed: " + ex); }
            }
            for (Tween tw : r.tweens) if (!tw.done) advance(r, tw, t);
            for (Reel rl : r.reels) if (!rl.done) spin(rl, t);
            if (t >= r.cue.length() + 0.05 && r.fired.size() >= r.cue.steps().size()) finish(r);
        }
    }

    private void finish(Run r) {
        runs.remove(r);
        for (Map.Entry<PacketDisplay, MTransform> e : r.rest.entrySet()) {
            PacketDisplay d = e.getKey();
            boolean stillUsed = false;
            for (Run o : runs) if (o.m == r.m && o.rest.containsKey(d)) { stillUsed = true; break; }
            if (!r.keep.contains(d)) { d.forceTransform(e.getValue()); d.consumeDirty(); tracker.refresh(d); }
            if (!stillUsed && !d.pinned()) d.lock(false);
        }
        if (r.onEnd != null) try { r.onEnd.run(); } catch (Throwable ex) { plugin.getLogger().warning("[MachineConstruct] cue end failed: " + ex); }
    }

    private void fire(Run r, Cue.Step s, double now) {
        List<PacketDisplay> parts = s.part == null ? List.of() : select(r.m, s.part);
        for (PacketDisplay d : parts) {
            if (!r.rest.containsKey(d)) { r.rest.put(d, d.transform()); d.lock(true); }
            if (s.keep) r.keep.add(d);
            if (s.show != null) {
                if (!s.show) d.forceContent(blank(d));
                else d.forceContent(s.content == null ? null : resolveContent(r, d, s.content));
                d.consumeDirty(); tracker.refresh(d);
            } else if (s.content != null) { d.forceContent(resolveContent(r, d, s.content)); d.consumeDirty(); tracker.refresh(d); }
            if (s.glow != null) {
                String g = fill(s.glow, r.vars).trim();
                Integer argb = g.isEmpty() || g.equalsIgnoreCase("off") || g.equalsIgnoreCase("false") || g.equalsIgnoreCase("null") ? null : parseArgb(g);
                tracker.forEachViewer(d, v -> d.sendGlowArgb(v, argb));
            }
            if (s.moveFrom != null || s.moveTo != null || s.moveBy != null || s.rotAxis != null || s.shakeAmp > 0) {
                Tween tw = new Tween();
                tw.d = d; tw.start = now; tw.over = Math.max(s.moveOver, Math.max(s.rotOver, s.shakeOver)); tw.ease = s.ease;
                MTransform cur = d.transform();
                if (s.moveFrom != null) { float[] w = r.root.apply((float) s.moveFrom[0], (float) s.moveFrom[1], (float) s.moveFrom[2]); cur = cur.at(w[0], w[1], w[2]); d.forceTransform(cur); d.consumeDirty(); tracker.refresh(d, 0); }   // snap to the start, no slide from wherever it was
                tw.from = cur;
                if (s.moveTo != null) tw.moveTo = r.root.apply((float) s.moveTo[0], (float) s.moveTo[1], (float) s.moveTo[2]);
                else if (s.moveBy != null) { float[] dv = r.root.rotateVec((float) s.moveBy[0], (float) s.moveBy[1], (float) s.moveBy[2]); tw.moveTo = new float[]{ cur.tx + dv[0], cur.ty + dv[1], cur.tz + dv[2] }; }
                if (s.rotAxis != null) {
                    tw.axis = s.rotAxis.toLowerCase().charAt(0); tw.rotBy = s.rotBy;
                    if (s.rotPivot != null) {   // hinge: the axis is the machine's, the pivot a point in model space
                        tw.axisWorld = r.root.rotateVec(tw.axis == 'x' ? 1 : 0, tw.axis == 'y' ? 1 : 0, tw.axis == 'z' ? 1 : 0);
                        tw.pivot = r.root.apply((float) s.rotPivot[0], (float) s.rotPivot[1], (float) s.rotPivot[2]);
                    }
                }
                tw.shakeAmp = s.shakeAmp;
                if (tw.over <= 0) { tw.over = 0.001; }
                r.tweens.add(tw);
                advance(r, tw, now);
            }
            if (s.isReel()) {
                Reel rl = new Reel();
                rl.d = d; rl.start = now; rl.over = Math.max(0.2, s.reelOver); rl.tease = Math.max(0, s.reelTease);
                rl.frames = new ArrayList<>();
                for (String sym : s.reelSymbols) rl.frames.add(contentFor(d, fill(sym, r.vars)));
                String stop = fill(s.reelStop == null ? "" : s.reelStop, r.vars).trim();
                // find the stop ON THE STRIP, so the frames leading up to it are its real neighbours
                int si = indexOfSymbol(s.reelSymbols, stop, r.vars);
                rl.stopIdx = si >= 0 ? si : rl.frames.size() - 1;
                rl.stop = si >= 0 ? rl.frames.get(si) : (stop.isEmpty() ? rl.frames.get(rl.frames.size() - 1) : contentFor(d, stop));
                rl.blur = s.reelBlur == null || s.reelBlur.isBlank() ? null : contentFor(d, fill(s.reelBlur, r.vars));
                rl.spins = s.reelSpins > 0 ? s.reelSpins : Math.max(4, (int) Math.round(rl.over * 2.6));
                rl.rest = d.transform();
                char ax = s.reelAxis == null ? 'x' : s.reelAxis.toLowerCase().charAt(0);
                rl.axisWorld = r.root.rotateVec(ax == 'x' ? 1 : 0, ax == 'y' ? 1 : 0, ax == 'z' ? 1 : 0);
                r.reels.add(rl);
                spin(rl, now);
            }
            if (s.sound != null) sound(r, s, partCentre(r.m, d));
            if (s.particle != null) particle(r, s, r.m.anchor().clone().add(0.5, 0, 0.5));
        }
        if (parts.isEmpty()) {
            Location at = r.m.anchor().clone().add(0.5, 0, 0.5);
            if (s.sound != null) sound(r, s, at);
            if (s.particle != null) particle(r, s, at);
        }
        if (s.message != null && r.actor != null) r.actor.sendMessage(MenuSkin.mini(fill(s.message, r.vars)));
    }

    /**
     * A drum reel: the part physically TURNS about the machine's axis, decelerating, and its symbol is
     * swapped while the back face is toward the viewer, so a strip appears to roll past. It always lands
     * face-on (a whole number of turns) on the symbol the roll already chose. With {@code tease} the last
     * turn is a separate, much slower one — the reel all but stops on the neighbouring symbol, then rolls
     * the final notch.
     */
    private void spin(Reel rl, double now) {
        double t = now - rl.start;
        int n = rl.frames.size();
        double full = 360.0 * rl.spins;
        double angle, speed;   // degrees, degrees per second
        if (t >= rl.over) { angle = full; speed = 0; }
        else if (rl.tease > 0 && t > rl.over - rl.tease) {          // the last notch, crawling
            double u = (t - (rl.over - rl.tease)) / rl.tease;
            double e = u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2;   // ease in-out
            angle = full - 360 + 360 * e;
            speed = 360 / rl.tease;
        } else {
            double span = rl.over - Math.max(0, rl.tease);
            double u = Math.min(1, t / span);
            double target = rl.tease > 0 ? full - 360 : full;
            angle = target * (1 - Math.pow(1 - u, 3));                          // ease-out cubic
            speed = 3 * Math.pow(1 - u, 2) * target / span;
        }
        rl.d.forceTransform(rl.rest.rotatedAbout(rl.axisWorld[0], rl.axisWorld[1], rl.axisWorld[2], angle, rl.rest.tx, rl.rest.ty, rl.rest.tz));
        // one strip step per full turn — the swap happens while the back face is showing
        int k = (int) Math.floor((angle + 180) / 360);
        if (rl.blur != null && speed > 900) show(rl, rl.blur, -4);
        else {
            int idx = ((rl.stopIdx - (rl.spins - k)) % n + n) % n;
            show(rl, k >= rl.spins ? rl.stop : rl.frames.get(idx), k >= rl.spins ? -1 : idx);
        }
        rl.d.consumeDirty(); tracker.refresh(rl.d, 0);
        if (t >= rl.over) rl.done = true;
    }

    private void show(Reel rl, DisplayContent c, int tag) {
        if (rl.shown == tag) return;   // same frame as last tick (blur, a held symbol) — don't spam the packet
        rl.shown = tag;
        rl.d.forceContent(c);
    }

    private static int indexOfSymbol(List<String> symbols, String stop, Map<String, String> vars) {
        for (int i = 0; i < symbols.size(); i++) if (fill(symbols.get(i), vars).trim().equals(stop)) return i;
        return -1;
    }

    private void advance(Run r, Tween tw, double now) {
        double u = Math.min(1.0, (now - tw.start) / tw.over);
        double e = switch (tw.ease == null ? "linear" : tw.ease) {
            case "in" -> u * u;
            case "out" -> 1 - (1 - u) * (1 - u);
            case "inout" -> u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2;
            default -> u;
        };
        MTransform t = tw.from;
        if (tw.moveTo != null) t = t.at((float) (tw.from.tx + (tw.moveTo[0] - tw.from.tx) * e), (float) (tw.from.ty + (tw.moveTo[1] - tw.from.ty) * e), (float) (tw.from.tz + (tw.moveTo[2] - tw.from.tz) * e));
        if (tw.rotBy != 0) {
            t = tw.pivot != null
                    ? t.rotatedAbout(tw.axisWorld[0], tw.axisWorld[1], tw.axisWorld[2], tw.rotBy * e, tw.pivot[0], tw.pivot[1], tw.pivot[2])
                    : t.rotatedLocal(tw.axis, tw.rotBy * e);
        }
        if (tw.shakeAmp > 0 && u < 1.0) {
            float a = (float) (tw.shakeAmp * (1 - u));
            t = t.translated((rng.nextFloat() * 2 - 1) * a, (rng.nextFloat() * 2 - 1) * a * 0.5f, (rng.nextFloat() * 2 - 1) * a);
        }
        tw.d.forceTransform(t); tw.d.consumeDirty(); tracker.refresh(tw.d);
        if (u >= 1.0) tw.done = true;
    }

    // --- helpers ------------------------------------------------------------------

    /** Parts of the machine matching a selector: exact name, a|b|c, or a glob with '*'. */
    public static List<PacketDisplay> select(Machine m, String selector) {
        List<PacketDisplay> out = new ArrayList<>();
        if (selector == null) return out;
        String[] alts = selector.split("\\|");
        for (PacketDisplay d : m.displays()) {
            String n = d.partName(); if (n == null) continue;
            for (String a : alts) {
                a = a.trim();
                if (a.indexOf('*') >= 0 ? n.matches(a.replace("*", ".*")) : n.equals(a)) { out.add(d); break; }
            }
        }
        return out;
    }

    private Location partCentre(Machine m, PacketDisplay d) {
        MTransform t = d.transform();
        float[] c = d.baseContent() != null && d.baseContent().centerAnchored() ? new float[]{ t.tx, t.ty, t.tz } : t.apply(t.sx / 2, t.sy / 2, t.sz / 2);
        return m.anchor().clone().add(c[0], c[1], c[2]);
    }

    private void sound(Run r, Cue.Step s, Location at) {
        float pitch = 1f;
        try { pitch = Float.parseFloat(fill(s.pitch, r.vars).trim()); } catch (NumberFormatException ignored) { }
        try { at.getWorld().playSound(at, fill(s.sound, r.vars).trim(), s.volume, pitch); } catch (Throwable ignored) { }
    }

    private void particle(Run r, Cue.Step s, Location at) {
        try {
            Particle p = Particle.valueOf(fill(s.particle, r.vars).trim().toUpperCase());
            Location loc = at.clone();
            if (s.pOffset != null) { float[] o = r.root.rotateVec((float) s.pOffset[0], (float) s.pOffset[1], (float) s.pOffset[2]); loc = r.m.anchor().clone().add(o[0], o[1], o[2]); }
            double sx = s.pSpread == null ? 0.1 : s.pSpread[0], sy = s.pSpread == null ? 0.1 : s.pSpread[1], sz = s.pSpread == null ? 0.1 : s.pSpread[2];
            at.getWorld().spawnParticle(p, loc, Math.max(1, s.count), sx, sy, sz, s.pSpeed);
        } catch (Throwable ignored) { }
    }

    /** A step's {@code content}: a ready-made object ({@code {prize}}) if the caller supplied one, else parsed from the filled string. */
    private static DisplayContent resolveContent(Run r, PacketDisplay d, String raw) {
        String key = raw.trim();
        if (key.startsWith("{") && key.endsWith("}") && r.objects.containsKey(key.substring(1, key.length() - 1))) return r.objects.get(key.substring(1, key.length() - 1));
        return contentFor(d, fill(raw, r.vars));
    }

    /** The "nothing" content of the same display type (air block / air item / empty text). */
    public static DisplayContent blank(PacketDisplay d) {
        DisplayContent b = d.baseContent();
        if (b instanceof TextContent tc) return tc.withText("");
        if (b instanceof BlockContent) return new BlockContent(Material.AIR.createBlockData());
        return new ItemContent(new ItemStack(Material.AIR), (byte) 8);
    }

    /** Content from a cue string for a part: head texture / URL → head item, block name → block, else text. */
    public static DisplayContent contentFor(PacketDisplay d, String v) {
        String s = v == null ? "" : v.trim();
        DisplayContent b = d.baseContent();
        if (b instanceof TextContent tc) return tc.withText(s);
        if (s.startsWith("eyJ") || s.startsWith("http://") || s.startsWith("https://"))
            return new ItemContent(Heads.create(s), b instanceof ItemContent ic ? ic.displayType() : ItemContent.context("fixed"));
        Material mat = Material.matchMaterial(s.toUpperCase().replace("MINECRAFT:", ""));
        if (mat != null && mat.isBlock() && b instanceof BlockContent) return new BlockContent(mat.createBlockData());
        if (mat != null) return new ItemContent(new ItemStack(mat), b instanceof ItemContent ic ? ic.displayType() : ItemContent.context("fixed"));
        return b;
    }

    public static String fill(String s, Map<String, String> vars) {
        if (s == null) return null;
        for (Map.Entry<String, String> e : vars.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        return s;
    }

    public static int parseArgb(String hex) {
        try { String h = hex.trim().replace("#", ""); return 0xFF000000 | Integer.parseInt(h, 16); } catch (Exception e) { return 0xFF35E0D0; }
    }

    @SuppressWarnings("unused")
    private static void each(List<PacketDisplay> l, Consumer<PacketDisplay> fn) { for (PacketDisplay d : l) fn.accept(d); }
}
