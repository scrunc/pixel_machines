package dev.servereer.machineconstruct.model;

import dev.servereer.machineconstruct.core.BlockContent;
import dev.servereer.machineconstruct.core.DisplayContent;
import dev.servereer.machineconstruct.core.Heads;
import dev.servereer.machineconstruct.core.ItemContent;
import dev.servereer.machineconstruct.core.MTransform;
import dev.servereer.machineconstruct.core.TextContent;
import dev.servereer.machineconstruct.model.anim.Bob;
import dev.servereer.machineconstruct.model.anim.ContentSwap;
import dev.servereer.machineconstruct.model.anim.Fill;
import dev.servereer.machineconstruct.model.anim.FxEmitter;
import dev.servereer.machineconstruct.model.anim.Pulse;
import dev.servereer.machineconstruct.model.anim.Shake;
import dev.servereer.machineconstruct.model.anim.Spin;
import dev.servereer.machineconstruct.model.anim.Swing;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Parses a {@code model:} YAML section into a {@link Model} transform tree — the
 * "0 hardcoded" core: machines are authored as data, not Java (DESIGN.md §9–§10).
 *
 * <p>Schema (per node, under {@code parts:}):
 * <pre>
 * &lt;name&gt;:
 *   block: &lt;material&gt;          # leaf block (item/head/text come next)
 *   offset: [x,y,z]            # default 0,0,0
 *   scale:  [x,y,z] | n        # default 1,1,1
 *   rotation: [y,x,z]          # degrees, default 0,0,0
 *   animation:
 *     &lt;trigger&gt;:               # always | idle | working | blocked | no_fuel
 *       spin:  { axis: y, speed: 60 }
 *       bob:   { axis: y, amplitude: 0.05, period: 2.0 }
 *       pulse: { from: 0.4, to: 0.6, period: 0.8 }
 *       shake: { amplitude: 0.06, hz: 14 }
 *   parts: { ... }             # nested children
 * </pre>
 */
public final class ModelLoader {

    private final Logger log;

    public ModelLoader(Logger log) {
        this.log = log;
    }

    /** Build a Model from a {@code model:} section. The root is an implicit identity group. */
    public Model load(String id, ConfigurationSection model) {
        ModelNode root = ModelNode.group(MTransform.identity());
        addChildren(root, id, model.getConfigurationSection("parts"));
        return new Model(id, root);
    }

    private ModelNode parseNode(String path, ConfigurationSection sec) {
        MTransform local = MTransform.of(
                vec(sec, "offset", 0, 0, 0),
                scale(sec),
                vec(sec, "rotation", 0, 0, 0));

        // Dynamic item binding: `item: "<input>"` / `<output>` shows the machine's live item.
        ItemBinding binding = ItemBinding.from(sec.getString("item"));
        byte bindDisplay = ItemContent.context(sec.getString("display", "fixed"));
        DisplayContent content = (binding != ItemBinding.NONE)
                ? new ItemContent(new org.bukkit.inventory.ItemStack(Material.AIR), bindDisplay)
                : parseContent(path, sec);
        ModelNode node = (content == null) ? ModelNode.group(local) : ModelNode.leaf(content, local);
        node.name(path.substring(path.lastIndexOf('.') + 1));
        if (binding != ItemBinding.NONE) node.bind(binding, bindDisplay);
        ConfigurationSection btn = sec.getConfigurationSection("button");
        if (btn != null && content != null) {
            String action = btn.getString("action", "refresh");
            int hover = parseArgb(btn.getString("hover", "#35e0d0"));
            DisplayContent lit = null;
            String litBlock = btn.getString("lit");
            if (litBlock != null) {
                if (isHeadTexture(litBlock)) lit = new ItemContent(Heads.create(litBlock.trim()), ItemContent.context("fixed"));
                else {
                    Material lm = Material.matchMaterial(litBlock.trim().toUpperCase().replace("MINECRAFT:", ""));
                    if (lm != null && lm.isBlock()) lit = new BlockContent(lm.createBlockData());
                    else log.warning("model " + path + ": button lit block '" + litBlock + "' unknown — ignored.");
                }
            }
            // the caption that sinks with the button: explicit `label:`, else the btn_X → lbl_X convention
            String label = btn.getString("label");
            if (label == null && node.name() != null && node.name().startsWith("btn_")) label = "lbl_" + node.name().substring(4);
            node.button(new dev.servereer.machineconstruct.core.ButtonSpec(action, hover, content, lit, btn.getString("lit_when"), btn.getDouble("depth", 0.03), label));
        }

        // Billboard (how the display faces the camera): fixed | vertical | horizontal | center.
        String bb = sec.getString("billboard");
        if (bb != null) node.billboard(billboard(bb));

        // Pivot: the point rotation/scale orbit. `center: true` = block centre;
        // `pivot: [x,y,z]` = explicit (0..1 unit-block space). Default = corner.
        if (sec.getBoolean("center", false)) {
            node.pivot(0.5, 0.5, 0.5);
        } else if (sec.contains("pivot")) {
            double[] p = vec(sec, "pivot", 0, 0, 0);
            node.pivot(p[0], p[1], p[2]);
        }

        ConfigurationSection anim = sec.getConfigurationSection("animation");
        if (anim != null) parseAnimations(path, node, anim, content);

        addChildren(node, path, sec.getConfigurationSection("parts"));
        return node;
    }

    /**
     * Add every child under {@code parts}. A child with {@code octagon: true} also
     * emits a 45°-rotated, centre-pivoted twin — so an octagonal disc is authored
     * once instead of as an {@code _a}/{@code _b} pair. The twin is geometry only
     * (no animations/children) so emitters don't double up.
     */
    private void addChildren(ModelNode parent, String path, ConfigurationSection parts) {
        if (parts == null) return;
        for (String key : parts.getKeys(false)) {
            ConfigurationSection child = parts.getConfigurationSection(key);
            if (child == null) continue;
            parent.add(parseNode(path + "." + key, child));
            if (child.getBoolean("octagon", false)) parent.add(octagonTwin(path + "." + key + "$oct", child));
        }
    }

    /** The +45° (about Y) centre-pivoted twin of a block node — the other half of an octagon. */
    private ModelNode octagonTwin(String path, ConfigurationSection sec) {
        double[] rot = vec(sec, "rotation", 0, 0, 0);
        rot[0] += 45;   // rotation is [Y,X,Z] — add to yaw
        double[] off = vec(sec, "offset", 0, 0, 0);
        double[] scl = scale(sec);
        // Inset the twin vertically by a hair so its top/bottom faces don't sit coplanar
        // with the primary square — that coplanarity is what makes octagon discs z-fight
        // (flicker). The 45°-protruding corners still render; only the hidden shared planes move.
        if (scl[1] > 4 * OCTAGON_Y_EPS) { off[1] += OCTAGON_Y_EPS; scl[1] -= 2 * OCTAGON_Y_EPS; }
        MTransform local = MTransform.of(off, scl, rot);
        DisplayContent content = parseContent(path, sec);
        if (content == null) return ModelNode.group(local);
        return ModelNode.leaf(content, local).pivot(0.5, 0.5, 0.5);
    }

    private static final double OCTAGON_Y_EPS = 0.0015;   // ~1/666 block — separates planes, invisible gap

    private void parseAnimations(String path, ModelNode node, ConfigurationSection anim, DisplayContent base) {
        for (String trigger : anim.getKeys(false)) {
            ConfigurationSection fns = anim.getConfigurationSection(trigger);
            if (fns == null) continue;
            for (String fn : fns.getKeys(false)) {
                ConfigurationSection p = fns.getConfigurationSection(fn);
                if (p == null) continue;
                switch (fn.toLowerCase()) {
                    case "spin" -> node.anim(trigger, new Spin(axis(p, 'y'), p.getDouble("speed", 60)));
                    case "bob" -> node.anim(trigger, new Bob(axis(p, 'y'),
                            p.getDouble("amplitude", 0.05), p.getDouble("period", 2.0)));
                    case "pulse" -> node.anim(trigger, new Pulse(
                            p.getDouble("from", 0.9), p.getDouble("to", 1.1), p.getDouble("period", 1.0)));
                    case "shake" -> node.anim(trigger, new Shake(
                            p.getDouble("amplitude", 0.05), p.getDouble("hz", 12)));
                    case "swing" -> node.anim(trigger, new Swing(axis(p, 'x'),
                            p.getDouble("amplitude", 12), p.getDouble("period", 2.0),
                            Math.toRadians(p.getDouble("phase", 0))));
                    case "fill" -> node.anim(trigger, new Fill(axis(p, 'y'),
                            p.getDouble("from", 0.0), p.getDouble("to", 1.0),
                            switch (p.getString("driver", "level").toLowerCase()) {
                                case "progress" -> Fill.Driver.PROGRESS;
                                case "tier" -> Fill.Driver.TIER;
                                default -> Fill.Driver.LEVEL;
                            }));
                    case "swap" -> {
                        ContentSwap sw = parseSwap(path, trigger, p, base);
                        if (sw != null) node.swap(trigger, sw);
                    }
                    case "particle" -> {
                        FxEmitter e = parseParticle(path, trigger, p);
                        if (e != null) node.fx(trigger, e);
                    }
                    case "sound" -> {
                        FxEmitter e = parseSound(path, trigger, p);
                        if (e != null) node.fx(trigger, e);
                    }
                    default -> log.warning("[MachineConstruct] model " + path
                            + ": unknown animation '" + fn + "' (trigger " + trigger + ").");
                }
            }
        }
    }

    /**
     * Parse a {@code swap} (skin selector): a list of {@code frames}, each a
     * content map ({@code head:}/{@code item:}/{@code block:}/{@code text:}),
     * plus {@code mode} (cycle|random|hold) and {@code period}/{@code interval}.
     * Frames must share the base part's display type — cross-type frames need a
     * respawn, so they're warned and dropped.
     */
    private ContentSwap parseSwap(String path, String trigger, ConfigurationSection p, DisplayContent base) {
        String where = "model " + path + " (swap, trigger " + trigger + ")";
        if (base == null) {
            log.warning("[MachineConstruct] " + where + ": a swap needs a base block/item/head/text — ignored.");
            return null;
        }
        List<Map<?, ?>> raw = p.getMapList("frames");
        if (raw.isEmpty()) {
            log.warning("[MachineConstruct] " + where + ": no 'frames' — ignored.");
            return null;
        }
        MemoryConfiguration tmp = new MemoryConfiguration();
        List<DisplayContent> frames = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> m : raw) {
            ConfigurationSection fs = tmp.createSection("frame" + (i++), m);
            DisplayContent c = parseContent(path + ".swap", fs);
            if (c == null) {
                log.warning("[MachineConstruct] " + where + ": frame " + i + " has no block/item/head/text — skipped.");
                continue;
            }
            if (!c.entityType().equals(base.entityType())) {
                log.warning("[MachineConstruct] " + where + ": frame " + i + " type " + c.entityType()
                        + " differs from base " + base.entityType()
                        + " (cross-type swap needs a respawn; not yet supported) — skipped.");
                continue;
            }
            frames.add(c);
        }
        if (frames.isEmpty()) return null;
        return new ContentSwap(
                ContentSwap.Mode.from(p.getString("mode", "cycle")),
                p.getDouble("period", 2.0),
                p.getDouble("interval", 0.5),
                frames);
    }

    /**
     * Parse a {@code particle} emitter: {@code type} (Bukkit Particle name),
     * {@code offset}/{@code spread} (blocks), {@code count}, {@code speed},
     * {@code rate} (seconds between emissions; 0 = every tick).
     */
    private FxEmitter parseParticle(String path, String trigger, ConfigurationSection p) {
        String where = "model " + path + " (particle, trigger " + trigger + ")";
        Particle particle = matchParticle(p.getString("type"));
        if (particle == null) {
            log.warning("[MachineConstruct] " + where + ": unknown particle '" + p.getString("type") + "' — ignored.");
            return null;
        }
        return FxEmitter.particle(
                particle,
                vec(p, "offset", 0, 0, 0),
                vec(p, "spread", 0, 0, 0),
                p.getInt("count", 1),
                p.getDouble("speed", 0.0),
                p.getDouble("rate", 0.0));
    }

    /**
     * Parse a {@code sound} emitter: {@code type} (namespaced key, e.g.
     * {@code block.anvil.land}), {@code volume}, {@code pitch}, {@code offset},
     * {@code rate} (seconds between plays; default 1.0 so it doesn't spam).
     */
    private FxEmitter parseSound(String path, String trigger, ConfigurationSection p) {
        String where = "model " + path + " (sound, trigger " + trigger + ")";
        String key = p.getString("type");
        if (key == null || key.isEmpty()) {
            log.warning("[MachineConstruct] " + where + ": missing sound 'type' — ignored.");
            return null;
        }
        return FxEmitter.sound(
                key.toLowerCase(),
                (float) p.getDouble("volume", 1.0),
                (float) p.getDouble("pitch", 1.0),
                vec(p, "offset", 0, 0, 0),
                p.getDouble("rate", 1.0));
    }

    private Particle matchParticle(String name) {
        if (name == null) return null;
        String key = name.toUpperCase().replace("MINECRAFT:", "").replace(':', '_');
        try {
            return Particle.valueOf(key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Determine a node's display content from its keys (first match wins):
     * {@code block:} | {@code item:} (+ {@code display:}) | {@code head:} | {@code text:}.
     * No content key → a pure transform group (returns null).
     */
    private DisplayContent parseContent(String path, ConfigurationSection sec) {
        String block = sec.getString("block");
        if (block != null && isHeadTexture(block)) {
            // a head texture where a block was expected (theme vars can swap a block for a custom head)
            return new ItemContent(Heads.create(block.trim()), ItemContent.context("fixed"));
        }
        if (block != null) {
            Material mat = Material.matchMaterial(block);
            if (mat == null || !mat.isBlock()) {
                log.warning("[MachineConstruct] model " + path + ": unknown block '" + block + "', using STONE.");
                mat = Material.STONE;
            }
            return new BlockContent(mat.createBlockData());
        }
        String item = sec.getString("item");
        if (item != null) {
            Material mat = Material.matchMaterial(item);
            if (mat == null) {
                log.warning("[MachineConstruct] model " + path + ": unknown item '" + item + "', using STONE.");
                mat = Material.STONE;
            }
            return new ItemContent(new org.bukkit.inventory.ItemStack(mat),
                    ItemContent.context(sec.getString("display")));
        }
        String head = sec.getString("head");
        if (head != null) {
            // Default 'fixed', not the worn-'head' context — the worn context
            // applies a shrink+reposition meant for a player's head slot, which
            // makes a static model part look small/misaligned. Authors who want
            // the worn look can still set `display: head`.
            return new ItemContent(Heads.create(head), ItemContent.context(sec.getString("display", "fixed")));
        }
        String text = sec.getString("text");
        if (text != null) {
            return new TextContent(text,
                    sec.getInt("line-width", 200),
                    parseColor(sec.getString("background", "#40000000")),
                    sec.getInt("opacity", -1),
                    textFlags(sec));
        }
        return null;
    }

    /** A base64 textures blob or a texture URL — what {@code head:} takes; accepted for {@code block:} too. */
    static boolean isHeadTexture(String v) {
        String s = v.trim();
        return s.startsWith("eyJ") || s.startsWith("http://") || s.startsWith("https://");
    }

    /** "#rrggbb" → opaque ARGB int (teal on a bad value). */
    private static int parseArgb(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
            if (h.length() == 8) return (int) Long.parseLong(h, 16);
        } catch (NumberFormatException ignored) { }
        return 0xFF35E0D0;
    }

    private static byte textFlags(ConfigurationSection sec) {
        int f = 0;
        if (sec.getBoolean("shadow", false)) f |= 0x01;
        if (sec.getBoolean("see-through", false)) f |= 0x02;
        // align: center (default) | left | right — how the lines of a multi-line text justify
        String align = sec.getString("align", "center").trim().toLowerCase();
        if (align.equals("left")) f |= 0x08; else if (align.equals("right")) f |= 0x10;
        return (byte) f;
    }

    private static int parseColor(String s) {
        if (s == null) return 0;
        try {
            String hex = s.startsWith("#") ? s.substring(1) : s;
            return (int) Long.parseLong(hex, 16);   // ARGB
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte billboard(String name) {
        return switch (name.toLowerCase()) {
            case "vertical" -> 1;
            case "horizontal" -> 2;
            case "center" -> 3;
            default -> 0;   // fixed
        };
    }

    // --- helpers ------------------------------------------------------------

    private static char axis(ConfigurationSection sec, char def) {
        String a = sec.getString("axis");
        return (a == null || a.isEmpty()) ? def : Character.toLowerCase(a.charAt(0));
    }

    private static double[] vec(ConfigurationSection sec, String key, double dx, double dy, double dz) {
        if (!sec.contains(key)) return new double[]{dx, dy, dz};
        List<Double> l = sec.getDoubleList(key);
        if (l.size() == 3) return new double[]{finite(l.get(0), dx), finite(l.get(1), dy), finite(l.get(2), dz)};
        return new double[]{dx, dy, dz};
    }

    private static double[] scale(ConfigurationSection sec) {
        if (!sec.contains("scale")) return new double[]{1, 1, 1};
        if (sec.isList("scale")) {
            List<Double> l = sec.getDoubleList("scale");
            if (l.size() == 3) return new double[]{finite(l.get(0), 1), finite(l.get(1), 1), finite(l.get(2), 1)};
        }
        double s = finite(sec.getDouble("scale", 1.0), 1.0);   // single number = uniform
        return new double[]{s, s, s};
    }

    /** Reject NaN/Infinity from bad YAML so transforms never emit broken packets. */
    private static double finite(double v, double fallback) {
        return Double.isFinite(v) ? v : fallback;
    }
}
