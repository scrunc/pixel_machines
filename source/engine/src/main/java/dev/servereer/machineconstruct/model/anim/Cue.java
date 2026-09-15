package dev.servereer.machineconstruct.model.anim;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A CUE — a one-shot, time-based script over named model parts, fired by an event (a button, a
 * pull, a page turn) and played once for everyone in range. Unlike {@code animation:} (continuous,
 * per state), a cue has a timeline: each step has an {@code at} (seconds from the start) and does
 * one thing to one or more parts, optionally tweened {@code over} seconds. When the cue ends the
 * parts return to their rest transforms unless the step said {@code keep: true}.
 * <pre>
 * cues:
 *   pull:
 *     - { at: 0.0, part: dial,     rotate: { axis: z, by: 90, over: 0.35, ease: out } }
 *     - { at: 0.0, part: dial,     sound: { type: block.lever.click, pitch: 0.8 } }
 *     - { at: 0.1, part: "pool_*", shake: { amplitude: 0.03, over: 0.6 } }
 *     - { at: 0.4, part: capsule,  show: true, content: "{capsule}" }
 *     - { at: 0.4, part: capsule,  move: { from: [0, 1.1, -0.2], to: [0, 0.32, -0.42], over: 0.9, ease: in } }
 *     - { at: 1.3, part: flap,     glow: "{color}" }
 *     - { at: 0.3, part: reel_3,   reel: { symbols: [ "eyJ…", "eyJ…" ], stop: "{sym3}", over: 2.8, blur: "eyJ…", tease: 0.5, spins: 9 } }
 *     - { at: 0.0, part: lever,     rotate: { axis: x, by: 70, over: 0.3, pivot: [-0.5, 1.4, 0.0] } }   # hinges on its mount, not a spin in place
 *     - { at: 1.4, particle: { type: end_rod, count: 20, offset: [0, 0.35, -0.42], speed: 0.05 } }
 * </pre>
 * Part selectors: a name, {@code a|b|c}, or a glob with {@code *}. Positions are model space relative
 * to the anchor (the root frame, before the machine's facing yaw). Values may hold {@code {vars}}
 * supplied when the cue is fired. {@code rotate}/{@code spin} turn a part about its own centre, so
 * use them on centre-anchored parts (heads / items / text).
 */
public final class Cue {

    /** One timeline step. Everything optional except {@code at}. */
    public static final class Step {
        public double at;                 // seconds from cue start
        public String part;               // selector (null = machine-level: sound/particle only)
        public Boolean show;              // true = restore content (or `content`), false = blank the part
        public String content;            // a head texture / block name / text to show (with {vars})
        public boolean keep;              // leave the part where the step put it when the cue ends
        // tweens (seconds)
        public double[] moveFrom, moveTo, moveBy; public double moveOver; public String ease = "linear";
        public String rotAxis; public double rotBy, rotOver;         // rotate: degrees about an axis
        public double[] rotPivot;   // rotate about a point in model space (a lever's mount) instead of the part's centre
        public double shakeAmp, shakeOver;                            // shake: jitter
        public String glow;               // "#rrggbb" outline colour, "" / "off" = none
        public String sound; public float volume = 1f; public String pitch = "1";   // pitch may be a {var}
        public String particle; public int count; public double[] pOffset, pSpread; public double pSpeed;
        public String message;            // skinned message to the actor (MiniMessage, {vars})
        // reel: a slot reel — the player generates the frames (blur while fast, symbols easing out, landing on `stop`)
        public List<String> reelSymbols; public String reelStop, reelBlur, reelAxis; public double reelOver, reelTease, reelDecel, reelJitter; public int reelSpins;
        public String reelLand; public double reelLandPitch = 1;   // the clack when this reel stops (follows the jitter)

        public boolean isReel() { return reelSymbols != null && !reelSymbols.isEmpty(); }

        public double end() {   // when this step's effect is finished
            return at + Math.max(reelOver, Math.max(moveOver, Math.max(rotOver, shakeOver)));
        }
    }

    private final String name;
    private final List<Step> steps;
    private final double length;

    public Cue(String name, List<Step> steps) {
        this.name = name;
        this.steps = Collections.unmodifiableList(steps);
        double l = 0; for (Step s : steps) l = Math.max(l, s.end());
        this.length = l;
    }

    public String name() { return name; }
    public List<Step> steps() { return steps; }
    /** Seconds until the last effect finishes. */
    public double length() { return length; }

    // --- parse ------------------------------------------------------------------

    /** {@code cues:} → name → Cue. Each cue is a list of step maps. */
    public static Map<String, Cue> parseAll(ConfigurationSection sec) {
        Map<String, Cue> out = new LinkedHashMap<>();
        if (sec == null) return out;
        for (String k : sec.getKeys(false)) {
            List<Step> steps = new ArrayList<>();
            for (Map<?, ?> m : sec.getMapList(k)) steps.add(parseStep(m));
            steps.sort((a, b) -> Double.compare(a.at, b.at));
            out.put(k, new Cue(k, steps));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Step parseStep(Map<?, ?> m) {
        Step s = new Step();
        s.at = num(m.get("at"), 0);
        s.part = str(m.get("part"));
        if (m.get("show") instanceof Boolean b) s.show = b;
        s.content = str(m.get("content"));
        s.keep = Boolean.TRUE.equals(m.get("keep"));
        if (m.get("move") instanceof Map<?, ?> mv) {
            s.moveFrom = vec(mv.get("from")); s.moveTo = vec(mv.get("to")); s.moveBy = vec(mv.get("by"));
            s.moveOver = num(mv.get("over"), 0); s.ease = str(mv.get("ease")) == null ? "linear" : str(mv.get("ease"));
        }
        Map<?, ?> rot = m.get("rotate") instanceof Map<?, ?> r ? r : m.get("spin") instanceof Map<?, ?> r2 ? r2 : null;
        if (rot != null) {
            s.rotAxis = str(rot.get("axis")) == null ? "y" : str(rot.get("axis"));
            s.rotBy = rot.containsKey("turns") ? num(rot.get("turns"), 1) * 360.0 : num(rot.get("by"), 90);
            s.rotOver = num(rot.get("over"), 0.5);
            s.rotPivot = vec(rot.get("pivot"));
            if (rot.get("ease") != null) s.ease = str(rot.get("ease"));
        }
        if (m.get("shake") instanceof Map<?, ?> sh) { s.shakeAmp = num(sh.get("amplitude"), 0.03); s.shakeOver = num(sh.get("over"), 0.5); }
        if (m.get("reel") instanceof Map<?, ?> re) {
            List<String> syms = new ArrayList<>();
            if (re.get("symbols") instanceof List<?> l) for (Object o : l) syms.add(String.valueOf(o));
            s.reelSymbols = syms;
            s.reelStop = str(re.get("stop"));
            s.reelBlur = str(re.get("blur"));
            s.reelOver = num(re.get("over"), 2.0);
            s.reelTease = num(re.get("tease"), 0);
            s.reelSpins = (int) num(re.get("spins"), 0);
            s.reelDecel = num(re.get("decel"), 0);
            s.reelJitter = num(re.get("jitter"), 0);      // ± this fraction of the spin length, rolled per pull
            s.reelLand = str(re.get("land"));
            s.reelLandPitch = num(re.get("land_pitch"), 1);
            s.reelAxis = str(re.get("axis")) == null ? "x" : str(re.get("axis"));
        }
        if (m.containsKey("glow")) s.glow = m.get("glow") == null ? "" : String.valueOf(m.get("glow"));
        if (m.containsKey("lit")) s.glow = m.get("lit") == null ? "" : String.valueOf(m.get("lit"));
        if (m.get("sound") instanceof Map<?, ?> so) {
            s.sound = str(so.get("type")); s.volume = (float) num(so.get("volume"), 1);
            s.pitch = so.get("pitch") == null ? "1" : String.valueOf(so.get("pitch"));
        } else if (m.get("sound") instanceof String ss) s.sound = ss;
        if (m.get("particle") instanceof Map<?, ?> pa) {
            s.particle = str(pa.get("type")); s.count = (int) num(pa.get("count"), 8);
            s.pOffset = vec(pa.get("offset")); s.pSpread = vec(pa.get("spread")); s.pSpeed = num(pa.get("speed"), 0.02);
        }
        s.message = str(m.get("message"));
        return s;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return def;
        try { return Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; }
    }
    private static double[] vec(Object o) {
        if (!(o instanceof List<?> l) || l.size() < 3) return null;
        return new double[]{ num(l.get(0), 0), num(l.get(1), 0), num(l.get(2), 0) };
    }
}
