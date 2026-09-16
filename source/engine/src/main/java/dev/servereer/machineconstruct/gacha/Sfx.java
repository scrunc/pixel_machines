package dev.servereer.machineconstruct.gacha;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The sound a machine makes, pulled out of the code and into the file.
 *
 * <p>Every noise a coin machine makes — the coin going in, the dial, the capsule landing, the claw closing
 * on nothing, the fanfare on a Mythic — is an EVENT with a name. A machine's {@code sfx:} block rebinds any
 * of them; anything it leaves alone keeps the engine's default, so a machine with no {@code sfx:} block
 * sounds exactly as it always did.
 *
 * <pre>
 * sfx:
 *   insert: { sound: block.amethyst_block.chime, volume: 0.7, pitch: [0.9, 1.1] }
 *   land:
 *     all: true                     # play every layer (default: pick ONE, by weight)
 *     layers:
 *       - { sound: block.copper_bulb.turn_on, volume: 0.8 }
 *       - { sound: entity.item.pickup, pitch: 0.7, delay: 3 }
 *   miss:
 *     layers:                       # no `all`, so one of these at random — the machine never
 *       - { sound: block.stone_button.click_off, weight: 2 }   # repeats itself twice running
 *       - { sound: block.barrel.close, pitch: 0.8 }
 *   reveal: { track: fanfare, distance: 24 }   # a real audio file, via PixelAudio
 *   jackpot: off                    # silence this one entirely
 * </pre>
 *
 * <p><b>pitch</b> is a number, a {@code [min, max]} range rolled per play, or a {@code {var}} from the cue's
 * variables ({@code {pitch}} is the rarity's). <b>delay</b> is in ticks, <b>chance</b> (0–1) makes a layer
 * occasional, and <b>track</b> names a track from the music library, played through PixelAudio at
 * {@code distance} blocks — that is the hook for custom audio, since vanilla keys are all a resource pack
 * away from being anything else.
 */
public final class Sfx {

    /** One noise: a vanilla key or a library track, with how loud, how high, how late, how likely. */
    public record Layer(String sound, String track, float volume, String pitch, int delay,
                        double chance, double weight, float distance) { }

    /** What one event plays: layers, and whether they all fire or one is picked. */
    public record Event(List<Layer> layers, boolean all) {
        public boolean silent() { return layers.isEmpty(); }
    }

    private static final Random RNG = new Random();

    private final Map<String, Event> events;

    private Sfx(Map<String, Event> events) { this.events = events; }

    /** Parse a {@code sfx:} block. Null section is fine — every event then falls back to its default. */
    public static Sfx parse(ConfigurationSection sec) {
        Map<String, Event> out = new LinkedHashMap<>();
        if (sec != null) for (String key : sec.getKeys(false)) {
            String name = key.toLowerCase();
            Object raw = sec.get(key);
            // `event: off` (or false, or ~) silences it — an empty layer list, which is NOT the same as absent
            if (raw == null || "off".equalsIgnoreCase(String.valueOf(raw)) || Boolean.FALSE.equals(raw)) {
                out.put(name, new Event(List.of(), false));
                continue;
            }
            ConfigurationSection es = sec.getConfigurationSection(key);
            if (es == null) {   // a bare string: `insert: block.lever.click`
                out.put(name, new Event(List.of(layer(null, String.valueOf(raw))), false));
                continue;
            }
            List<Layer> layers = new ArrayList<>();
            List<Map<?, ?>> list = es.getMapList("layers");
            if (!list.isEmpty()) for (Map<?, ?> m : list) layers.add(layer(m, null));
            else layers.add(layer(null, null, es));
            layers.removeIf(l -> l.sound() == null && l.track() == null);
            out.put(name, new Event(layers, es.getBoolean("all", false)));
        }
        return new Sfx(out);
    }

    private static Layer layer(Map<?, ?> m, String bareSound) {
        if (bareSound != null) return new Layer(bareSound, null, 1f, "1", 0, 1, 1, 16);
        return new Layer(str(m.get("sound")), str(m.get("track")), (float) num(m.get("volume"), 1),
                pitchOf(m.get("pitch")), (int) num(m.get("delay"), 0), num(m.get("chance"), 1),
                num(m.get("weight"), 1), (float) num(m.get("distance"), 16));
    }

    private static Layer layer(Map<?, ?> ignored, String alsoIgnored, ConfigurationSection s) {
        return new Layer(s.getString("sound"), s.getString("track"), (float) s.getDouble("volume", 1),
                pitchOf(s.get("pitch")), s.getInt("delay", 0), s.getDouble("chance", 1),
                s.getDouble("weight", 1), (float) s.getDouble("distance", 16));
    }

    /** A number, a {@code [min, max]} range kept as "min~max", or a {var} passed through untouched. */
    private static String pitchOf(Object o) {
        if (o == null) return "1";
        if (o instanceof List<?> l && l.size() >= 2) return num(l.get(0), 1) + "~" + num(l.get(1), 1);
        return String.valueOf(o);
    }

    private static String str(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o); }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return dflt; }
    }

    /** What this machine plays for {@code event}, or {@code dflt} when the file says nothing about it. */
    public Event event(String event, Event dflt) {
        Event e = events.get(event.toLowerCase());
        return e == null ? dflt : e;
    }

    /** True if the file mentions this event at all (even to silence it). */
    public boolean overrides(String event) { return events.containsKey(event.toLowerCase()); }

    /** Roll one layer out of an event, or all of them. */
    public static List<Layer> pick(Event e) {
        if (e == null || e.silent()) return List.of();
        List<Layer> live = new ArrayList<>();
        for (Layer l : e.layers()) if (l.chance() >= 1 || RNG.nextDouble() < l.chance()) live.add(l);
        if (e.all() || live.size() <= 1) return live;
        double total = 0;
        for (Layer l : live) total += Math.max(0, l.weight());
        double x = RNG.nextDouble() * total;
        for (Layer l : live) { x -= Math.max(0, l.weight()); if (x <= 0) return List.of(l); }
        return List.of(live.get(live.size() - 1));
    }

    /** Resolve a layer's pitch: a plain number, a "min~max" range rolled now, or a {var} filled first. */
    public static float pitch(Layer l, Map<String, String> vars) {
        String p = l.pitch() == null ? "1" : l.pitch();
        if (vars != null) for (Map.Entry<String, String> v : vars.entrySet()) p = p.replace("{" + v.getKey() + "}", v.getValue());
        int tilde = p.indexOf('~');
        try {
            if (tilde > 0) {
                double a = Double.parseDouble(p.substring(0, tilde).trim());
                double b = Double.parseDouble(p.substring(tilde + 1).trim());
                return (float) (a + RNG.nextDouble() * (b - a));
            }
            return Float.parseFloat(p.trim());
        } catch (Exception e) { return 1f; }
    }

    /** A sound key with {vars} filled in — {@code sound: "{sound}"} plays whatever the rarity carries. */
    public static String key(Layer l, Map<String, String> vars) {
        String s = l.sound();
        if (s == null) return null;
        if (vars != null) for (Map.Entry<String, String> v : vars.entrySet()) s = s.replace("{" + v.getKey() + "}", v.getValue());
        return s.isBlank() ? null : s.trim();
    }

    /** Build an event in code — how every engine default is written. */
    public static Event one(String sound, double volume, String pitch) {
        return new Event(List.of(new Layer(sound, null, (float) volume, pitch, 0, 1, 1, 16)), false);
    }

    /** Play {@code at} a location, honouring delays. Safe to call from the main thread only. */
    public static void play(org.bukkit.plugin.Plugin plugin, Location at, Event e, Map<String, String> vars,
                            java.util.function.BiConsumer<String, Layer> trackPlayer) {
        if (at == null || at.getWorld() == null) return;
        for (Layer l : pick(e)) {
            Runnable go = () -> {
                if (l.track() != null && trackPlayer != null) { trackPlayer.accept(l.track(), l); return; }
                String k = key(l, vars);
                if (k == null) return;
                try { at.getWorld().playSound(at, k, l.volume(), pitch(l, vars)); } catch (Throwable ignored) { }
            };
            if (l.delay() <= 0) go.run();
            else plugin.getServer().getScheduler().runTaskLater(plugin, go, l.delay());
        }
    }
}
