package dev.servereer.machineconstruct.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A per-machine re-skin of a code-rendered menu (grinder / chunk collector): the
 * {@code skin:} section of a machine YAML. Everything is optional — a key that is
 * absent falls back to the default the Java passes in, so a machine with no
 * {@code skin:} renders exactly as before. What can be overridden:
 * <pre>
 * skin:
 *   prefix: "&lt;light_purple&gt;Grinder &lt;dark_gray&gt;» "     # chat brand (MiniMessage)
 *   titles:  { main: "…", pool: "…" }                    # window title per view
 *   fillers: { main: black_stained_glass_pane }          # border pane per view
 *   slots:                                               # move any button
 *     main: { collect: 47, spawners: [20, 21, …] }
 *   buttons:                                             # material / name / lore / model
 *     collect: { material: hopper, name: "…", lore: ["…"] }
 *   messages: { collected: "…" }                         # chat lines
 * </pre>
 * Names, lore and messages take {@code {placeholders}} (e.g. {@code {items}}) that
 * the renderer fills; the defaults use the same placeholders, so a skin can drop
 * or reorder any value. Wording lives here and functions stay in the menus.
 */
public final class MenuSkin {

    public static final MenuSkin EMPTY = new MenuSkin(null);

    private final ConfigurationSection sec;
    private final Map<String, Object> cache = new HashMap<>();

    private MenuSkin(ConfigurationSection sec) { this.sec = sec; }

    /** Wrap a {@code skin:} section (null → all defaults). */
    public static MenuSkin load(ConfigurationSection sec) { return sec == null ? EMPTY : new MenuSkin(sec); }

    public boolean isEmpty() { return sec == null; }

    // --- lookups -------------------------------------------------------------

    public String prefix(String def) { return str("prefix", def); }

    /** Any plain string key under {@code skin:} (e.g. {@code buy_command}). */
    public String get(String path, String def) { return str(path, def); }

    public String title(String view, String def) { return str("titles." + view, def); }

    public Material filler(String view, Material def) {
        String s = str("fillers." + view, null);
        if (s == null) return def;
        Material m = Material.matchMaterial(s.trim().toUpperCase());
        return m == null ? def : m;
    }

    public int slot(String view, String key, int def) {
        if (sec == null) return def;
        String path = "slots." + view + "." + key;
        Object c = cache.get(path);
        if (c instanceof Integer i) return i;
        int v = sec.isInt(path) ? sec.getInt(path) : def;
        cache.put(path, v);
        return v;
    }

    public int[] slots(String view, String key, int[] def) {
        if (sec == null) return def;
        String path = "slots." + view + "." + key;
        Object c = cache.get(path);
        if (c instanceof int[] a) return a;
        List<Integer> list = sec.getIntegerList(path);
        int[] v = def;
        if (list != null && !list.isEmpty()) {
            v = new int[list.size()];
            for (int i = 0; i < v.length; i++) v[i] = list.get(i);
        }
        cache.put(path, v);
        return v;
    }

    /**
     * Accent panes for a view: {@code accents.<view>} is a list of {@code {material, slots: [..], name?}}
     * groups painted over the filler (and under the buttons), e.g. cyan corners + blue side rails.
     */
    public void paintAccents(String view, java.util.function.BiConsumer<Integer, ItemStack> put) {
        if (sec == null) return;
        List<Map<?, ?>> groups = sec.getMapList("accents." + view);
        if (groups == null || groups.isEmpty()) return;
        for (Map<?, ?> g : groups) {
            Object ms = g.get("material");
            Material mat = ms == null ? null : Material.matchMaterial(String.valueOf(ms).trim().toUpperCase());
            if (mat == null) continue;
            Object nm = g.get("name");
            ItemStack pane = build(mat, nm == null ? " " : String.valueOf(nm), null, null);
            Object slots = g.get("slots");
            if (slots instanceof List<?> l) for (Object o : l) if (o instanceof Number n) put.accept(n.intValue(), pane);
        }
    }

    /** A chat line: the skinned text (or default) with {@code {placeholders}} filled. */
    public String msg(String key, String def, Map<String, String> vars) {
        return fill(str("messages." + key, def), vars);
    }

    /** A button/label: skinned material/name/lore (or defaults) with {@code {placeholders}} filled. */
    public ItemStack item(String key, Material defMat, String defName, List<String> defLore, Map<String, String> vars) {
        Material mat = defMat;
        String name = defName;
        List<String> lore = defLore;
        Integer model = null;
        ConfigurationSection b = sec == null ? null : sec.getConfigurationSection("buttons." + key);
        if (b != null) {
            String ms = b.getString("material");
            if (ms != null) { Material mm = Material.matchMaterial(ms.trim().toUpperCase()); if (mm != null) mat = mm; }
            if (b.isString("name")) name = b.getString("name");
            if (b.isList("lore")) lore = b.getStringList("lore");
            if (b.isInt("model")) model = b.getInt("model");
        }
        return build(mat, fill(name, vars), fillAll(lore, vars), model);
    }

    /** Same as {@link #item} but the icon is a caller-supplied stack (e.g. a mob head); only name/lore skin. */
    public ItemStack decorate(String key, ItemStack icon, String defName, List<String> defLore, Map<String, String> vars) {
        String name = defName;
        List<String> lore = defLore;
        ConfigurationSection b = sec == null ? null : sec.getConfigurationSection("buttons." + key);
        if (b != null) {
            if (b.isString("name")) name = b.getString("name");
            if (b.isList("lore")) lore = b.getStringList("lore");
        }
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(mini(fill(name, vars)));
            List<String> l = fillAll(lore, vars);
            if (l != null && !l.isEmpty()) meta.lore(miniList(l));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    // --- helpers -------------------------------------------------------------

    private String str(String path, String def) {
        if (sec == null) return def;
        Object c = cache.get(path);
        if (c instanceof String s) return s;
        String v = sec.isString(path) ? sec.getString(path) : def;
        if (v != null) cache.put(path, v);
        return v;
    }

    /** Placeholder map builder: {@code vars("items", 12, "xp", 3)}. */
    public static Map<String, String> vars(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        return m;
    }

    public static String fill(String s, Map<String, String> vars) {
        if (s == null || vars == null || vars.isEmpty() || s.indexOf('{') < 0) return s;
        for (Map.Entry<String, String> e : vars.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return s;
    }

    private static List<String> fillAll(List<String> lines, Map<String, String> vars) {
        if (lines == null) return null;
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(fill(l, vars));
        return out;
    }

    public static ItemStack build(Material mat, String name, List<String> lore, Integer model) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(mini(name));
            if (lore != null && !lore.isEmpty()) meta.lore(miniList(lore));
            if (model != null) meta.setCustomModelData(model);
            it.setItemMeta(meta);
        }
        return it;
    }

    public static Component mini(String s) {
        return MiniMessage.miniMessage().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> miniList(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String s : lines) out.add(mini(s));
        return out;
    }
}
