package dev.servereer.machineconstruct.machine;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code plugins/MachineConstruct/panels.yml} — the engine's export of every PLACED panel-kind
 * machine (id, type, location, facing, theme, colour, text overrides), rewritten whenever a panel
 * is placed, broken, edited or loaded. Dev's Diary's Panels Studio reads it to list placed panels
 * and writes edits back; {@code /mc panels apply} then reads the file and applies each entry by
 * id (theme / color / facing / texts), persisting and re-rendering the machine. The engine is the
 * source of truth: anything the file says about a machine it doesn't know is ignored.
 * <pre>
 * panels:
 *   - id: 2f1c…            # machine uuid
 *     type: panel_stele
 *     world: world
 *     x: 12
 *     y: 64
 *     z: -30
 *     facing: 90
 *     owner: uuid
 *     theme: kraken         # '' = the type's default
 *     color: '#b07cff'      # '' = the theme's own text colour
 *     texts:                # part name → lines; absent = the template's text
 *       board: [ "…", "…" ]
 *     page: 1               # paged (info) panels only: the page showing (1-based)
 *     pages:                # paged panels: one map per page, part (section) → lines; [] = the template's pages
 *       - { sec_1: [ "…" ], sec_2: [ "…" ] }
 * </pre>
 */
public final class PanelIndex {

    private final File file;

    public PanelIndex(File dataFolder) { this.file = new File(dataFolder, "panels.yml"); }

    public File file() { return file; }

    /** Rewrite the file from the live machines (only panel-kind ones are listed). */
    public void write(Collection<Machine> machines, Function<Machine, MachineType> typeOf) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().header("Placed panels — exported by MachineConstruct, read back by `/mc panels apply`.\n"
                + "Edit theme / color / facing / texts per entry, then run the command. Ids are the engine's;\n"
                + "entries it doesn't recognise are ignored. Text lines are MiniMessage (+ {theme}, %placeholders%).");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Machine m : machines) {
            MachineType t = typeOf.apply(m);
            if (t == null || !t.isPanel()) continue;
            Map<String, Object> e = new java.util.LinkedHashMap<>();
            Location a = m.anchor();
            e.put("id", m.id().toString());
            e.put("type", m.typeId());
            e.put("world", a.getWorld() == null ? "" : a.getWorld().getName());
            e.put("x", a.getBlockX()); e.put("y", a.getBlockY()); e.put("z", a.getBlockZ());
            e.put("facing", m.facing());
            e.put("owner", m.owner() == null ? "" : m.owner().toString());
            PanelData pd = m.panel();
            e.put("theme", pd == null || pd.theme() == null ? "" : pd.theme());
            e.put("color", pd == null || pd.color() == null ? "" : pd.color());
            e.put("state", pd == null || pd.state() == null ? "" : pd.state());
            Map<String, Object> texts = new java.util.LinkedHashMap<>();
            if (pd != null) for (Map.Entry<String, List<String>> te : pd.texts().entrySet()) texts.put(te.getKey(), new ArrayList<>(te.getValue()));
            e.put("texts", texts);
            if (t.panelPaged()) {
                e.put("page", pd == null ? 1 : pd.page() + 1);
                e.put("pages", PanelData.pagesToYaml(pd == null ? List.of() : pd.pages()));
            }
            Map<String, Object> layouts = new java.util.LinkedHashMap<>();
            if (pd != null) for (Map.Entry<String, PanelData.TextLayout> le : pd.layouts().entrySet()) {
                Map<String, Object> one = new java.util.LinkedHashMap<>();
                PanelData.TextLayout l = le.getValue();
                if (l.dx() != null) one.put("dx", l.dx()); if (l.dy() != null) one.put("dy", l.dy());
                if (l.scale() != null) one.put("scale", l.scale()); if (l.align() != null) one.put("align", l.align());
                if (l.width() != null) one.put("width", l.width());
                layouts.put(le.getKey(), one);
            }
            e.put("layouts", layouts);
            list.add(e);
        }
        y.set("panels", list);
        try { y.save(file); } catch (Exception ignored) { }
    }

    /** One entry read back from the file. */
    public record Entry(String id, String theme, String color, Integer facing, Map<String, List<String>> texts, String state,
                        Map<String, PanelData.TextLayout> layouts, List<Map<String, List<String>>> pages, Integer page) { }

    /** Read the file's entries (empty list if absent/unreadable). */
    @SuppressWarnings("unchecked")
    public List<Entry> read() {
        List<Entry> out = new ArrayList<>();
        if (!file.exists()) return out;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : y.getMapList("panels")) {
            Object id = m.get("id");
            if (id == null) continue;
            Map<String, List<String>> texts = new java.util.LinkedHashMap<>();
            Object tx = m.get("texts");
            if (tx instanceof Map<?, ?> tm) for (Map.Entry<?, ?> te : tm.entrySet()) {
                if (te.getValue() instanceof List<?> l) {
                    List<String> lines = new ArrayList<>();
                    for (Object o : l) lines.add(String.valueOf(o));
                    texts.put(String.valueOf(te.getKey()), lines);
                }
            }
            Map<String, PanelData.TextLayout> layouts = new java.util.LinkedHashMap<>();
            Object lo = m.get("layouts");
            if (lo instanceof Map<?, ?> lm) for (Map.Entry<?, ?> le : lm.entrySet()) {
                if (!(le.getValue() instanceof Map<?, ?> one)) continue;
                layouts.put(String.valueOf(le.getKey()), new PanelData.TextLayout(
                        num(one.get("dx")), num(one.get("dy")), num(one.get("scale")),
                        one.get("align") == null ? null : String.valueOf(one.get("align")),
                        one.get("width") == null ? null : (int) Math.round(num(one.get("width")) == null ? 0 : num(one.get("width")))));
            }
            Integer facing = null;
            Object f = m.get("facing");
            if (f instanceof Number n) facing = n.intValue();
            else if (f != null) { try { facing = Integer.parseInt(String.valueOf(f).trim()); } catch (NumberFormatException ignored) { } }
            List<Map<String, List<String>>> pages = m.get("pages") instanceof List<?> pl ? PanelData.pagesFromYaml(pl) : new ArrayList<>();
            Double pg = num(m.get("page"));
            out.add(new Entry(String.valueOf(id), str(m.get("theme")), str(m.get("color")), facing, texts, str(m.get("state")), layouts, pages, pg == null ? null : pg.intValue()));
        }
        return out;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try { return Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }
}
