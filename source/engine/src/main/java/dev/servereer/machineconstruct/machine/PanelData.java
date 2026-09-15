package dev.servereer.machineconstruct.machine;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-placed-panel state for a panel-kind machine: an admin's text overrides (part name →
 * lines, replacing the model's authored text for that part) and the panel's theme colour
 * (substituted for {@code {theme}} in every text). Empty = the model's defaults.
 */
public final class PanelData {

    private final Map<String, List<String>> texts = new LinkedHashMap<>();
    private String theme;   // theme NAME (a key of panel.themes) or null = the type's default
    private String color;   // "#rrggbb" text-colour override, or null = the theme's own colour
    private String state;   // current panel STATE name (a key of panel.states) or null = the type's default
    private final List<Map<String, List<String>>> pages = new ArrayList<>();   // paged panels: page → (part → lines); empty = the template's pages
    private int page;       // current page index (clamped on use)

    /** Per-part layout override: shift (blocks, model space), absolute text size, line justification, wrap width. Nulls = as authored. */
    public record TextLayout(Double dx, Double dy, Double scale, String align, Integer width) {
        public boolean isEmpty() { return dx == null && dy == null && scale == null && align == null && width == null; }
    }
    private final Map<String, TextLayout> layouts = new LinkedHashMap<>();
    public Map<String, TextLayout> layouts() { return layouts; }
    public TextLayout layout(String part) { return layouts.get(part); }
    public void setLayout(String part, TextLayout l) { if (l == null || l.isEmpty()) layouts.remove(part); else layouts.put(part, l); }

    public Map<String, List<String>> texts() { return texts; }
    public List<String> lines(String part) { return texts.get(part); }
    public void setLines(String part, List<String> lines) {
        if (lines == null || lines.isEmpty()) texts.remove(part); else texts.put(part, new ArrayList<>(lines));
    }
    public void reset(String part) { texts.remove(part); }
    public void resetAll() { texts.clear(); theme = null; color = null; state = null; layouts.clear(); pages.clear(); page = 0; }

    public String theme() { return theme; }
    public void setTheme(String name) { this.theme = (name == null || name.isBlank()) ? null : name.trim(); }
    public String color() { return color; }
    public void setColor(String hex) { this.color = (hex == null || hex.isBlank()) ? null : hex.trim().toLowerCase(); }

    /** Paged (info) panels: the placed panel's own pages (empty = the template's default pages). */
    public List<Map<String, List<String>>> pages() { return pages; }
    public int page() { return page; }
    public void setPage(int i) { this.page = Math.max(0, i); }
    /** Lines of {@code part} on page {@code i} of the given page list, or null if the page doesn't set it. */
    public static List<String> pageLines(List<Map<String, List<String>>> pages, int i, String part) {
        if (pages.isEmpty() || part == null) return null;
        Map<String, List<String>> pg = pages.get(Math.min(Math.max(0, i), pages.size() - 1));
        return pg == null ? null : pg.get(part);
    }
    /** Set {@code part}'s lines on page {@code i} (creating pages up to it); empty lines remove the part from the page. */
    public void setPageLines(int i, String part, List<String> lines) {
        while (pages.size() <= i) pages.add(new LinkedHashMap<>());
        Map<String, List<String>> pg = pages.get(i);
        if (lines == null || lines.isEmpty()) pg.remove(part); else pg.put(part, new ArrayList<>(lines));
    }

    public String state() { return state; }
    public void setState(String name) { this.state = (name == null || name.isBlank()) ? null : name.trim(); }

    public boolean isEmpty() { return texts.isEmpty() && theme == null && color == null && state == null && layouts.isEmpty() && pages.isEmpty() && page == 0; }

    // --- persistence (inside the machine's items blob) -------------------------

    public void save(ConfigurationSection sec) {
        sec.set("theme", theme);
        sec.set("color", color);
        sec.set("state", state);
        sec.set("page", page == 0 ? null : page);
        sec.set("pages", pages.isEmpty() ? null : pagesToYaml(pages));
        ConfigurationSection t = sec.createSection("texts");
        for (Map.Entry<String, List<String>> e : texts.entrySet()) t.set(e.getKey(), e.getValue());
        ConfigurationSection ls = sec.createSection("layouts");
        for (Map.Entry<String, TextLayout> e : layouts.entrySet()) {
            ConfigurationSection one = ls.createSection(e.getKey());
            TextLayout l = e.getValue();
            if (l.dx() != null) one.set("dx", l.dx()); if (l.dy() != null) one.set("dy", l.dy());
            if (l.scale() != null) one.set("scale", l.scale()); if (l.align() != null) one.set("align", l.align());
            if (l.width() != null) one.set("width", l.width());
        }
    }

    /** Pages as plain YAML-able lists of maps (part → lines). */
    public static List<Map<String, Object>> pagesToYaml(List<Map<String, List<String>>> pages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, List<String>> pg : pages) {
            Map<String, Object> one = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : pg.entrySet()) one.put(e.getKey(), new ArrayList<>(e.getValue()));
            out.add(one);
        }
        return out;
    }

    /** Pages back from YAML: a list of maps whose values are line lists (or a single string, split on newlines). */
    public static List<Map<String, List<String>>> pagesFromYaml(List<?> raw) {
        List<Map<String, List<String>>> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            Map<String, List<String>> pg = new LinkedHashMap<>();
            if (o instanceof Map<?, ?> m) for (Map.Entry<?, ?> e : m.entrySet()) {
                List<String> lines = new ArrayList<>();
                if (e.getValue() instanceof List<?> l) { for (Object x : l) lines.add(String.valueOf(x)); }
                else if (e.getValue() != null) lines.addAll(List.of(String.valueOf(e.getValue()).split("\n", -1)));
                pg.put(String.valueOf(e.getKey()), lines);
            }
            out.add(pg);
        }
        return out;
    }

    public static TextLayout layoutOf(ConfigurationSection one) {
        if (one == null) return null;
        return new TextLayout(one.contains("dx") ? one.getDouble("dx") : null, one.contains("dy") ? one.getDouble("dy") : null,
                one.contains("scale") ? one.getDouble("scale") : null, one.isString("align") ? one.getString("align") : null,
                one.contains("width") ? one.getInt("width") : null);
    }

    public static PanelData load(ConfigurationSection sec) {
        PanelData d = new PanelData();
        if (sec == null) return d;
        d.setTheme(sec.getString("theme"));
        d.setColor(sec.getString("color"));
        d.setState(sec.getString("state"));
        d.setPage(sec.getInt("page", 0));
        d.pages.addAll(pagesFromYaml(sec.getList("pages")));
        ConfigurationSection t = sec.getConfigurationSection("texts");
        if (t != null) for (String k : t.getKeys(false)) d.setLines(k, t.getStringList(k));
        ConfigurationSection ls = sec.getConfigurationSection("layouts");
        if (ls != null) for (String k : ls.getKeys(false)) d.setLayout(k, layoutOf(ls.getConfigurationSection(k)));
        return d;
    }
}
