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
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Parses a machine's {@code gui:} section into a {@link GuiLayout} (DESIGN.md
 * §14). A {@code layout:} is a list of up-to-9-char rows; each char is a slot:
 * {@code I}=input, {@code O}=output, {@code P}=progress, anything else = decor
 * (its icon from {@code icons:}, or an empty locked slot). {@code progress:} maps
 * {@code idle}/{@code working}/{@code blocked} to status icons.
 */
public final class GuiLoader {

    private static final List<String> DEFAULT_LAYOUT = List.of(
            "#########",
            "#IIIPOOO#",
            "#########");

    private final Logger log;

    public GuiLoader(Logger log) {
        this.log = log;
    }

    /** Build a layout from a {@code gui:} section, or a sensible default if {@code gui} is null. */
    public GuiLayout load(String id, ConfigurationSection gui) {
        Component title = mm(gui != null ? gui.getString("title", "Machine") : "Machine");

        List<String> rows = (gui != null && !gui.getStringList("layout").isEmpty())
                ? gui.getStringList("layout") : DEFAULT_LAYOUT;
        if (rows.size() > 6) rows = rows.subList(0, 6);
        int size = rows.size() * 9;

        Map<String, ItemStack> icons = parseIcons(gui == null ? null : gui.getConfigurationSection("icons"));
        Map<String, GuiLayout.ProgressIcon> progress =
                parseProgress(id, gui == null ? null : gui.getConfigurationSection("progress"));

        SlotRole[] roles = new SlotRole[size];
        ItemStack[] decor = new ItemStack[size];
        List<Integer> in = new ArrayList<>(), out = new ArrayList<>(), fuel = new ArrayList<>(),
                prog = new ArrayList<>(), burn = new ArrayList<>(), info = new ArrayList<>(),
                upg = new ArrayList<>(), tierS = new ArrayList<>(), sell = new ArrayList<>();

        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            for (int c = 0; c < 9; c++) {
                int slot = r * 9 + c;
                char ch = c < row.length() ? row.charAt(c) : ' ';
                switch (ch) {
                    case 'I' -> { roles[slot] = SlotRole.INPUT; in.add(slot); }
                    case 'O' -> { roles[slot] = SlotRole.OUTPUT; out.add(slot); }
                    case 'F' -> { roles[slot] = SlotRole.FUEL; fuel.add(slot); }
                    case 'P' -> { roles[slot] = SlotRole.PROGRESS; prog.add(slot); }
                    case 'B' -> { roles[slot] = SlotRole.BURN; burn.add(slot); }
                    case '?' -> { roles[slot] = SlotRole.INFO; info.add(slot); }
                    case 'U' -> { roles[slot] = SlotRole.UPGRADE; upg.add(slot); }
                    case 'T' -> { roles[slot] = SlotRole.TIER; tierS.add(slot); }
                    case '$' -> { roles[slot] = SlotRole.SELL; sell.add(slot); }
                    default -> { roles[slot] = SlotRole.DECOR; decor[slot] = icons.get(String.valueOf(ch)); }
                }
            }
        }
        // (No 'I' slots is fine — generators like the oil jack / elixir collector take no input.)
        ItemStack infoIcon = parseInfoIcon(gui == null ? null : gui.getConfigurationSection("info"));

        return new GuiLayout(title, size, roles, decor,
                toArray(in), toArray(out), toArray(fuel), toArray(prog), toArray(burn),
                toArray(info), toArray(upg), toArray(tierS), toArray(sell), infoIcon, progress);
    }

    /** Build a recipe-browser layout from a {@code browser:} section, or defaults if null. */
    public BrowserLayout loadBrowser(String id, ConfigurationSection b) {
        String title = (b != null) ? b.getString("title", "<dark_gray>Recipes — <gold><machine>")
                : "<dark_gray>Recipes — <gold><machine>";
        int rows = clamp((b != null) ? b.getInt("rows", 6) : 6, 1, 6);
        int perPage = Math.max(1, (b != null) ? b.getInt("per-page", 5) : 5);

        ConfigurationSection row = (b == null) ? null : b.getConfigurationSection("row");
        int[] inputCols = ints(row, "inputs", new int[]{0, 1, 2, 3});
        int[] outputCols = ints(row, "outputs", new int[]{5, 6, 7, 8});
        int arrowCol = (row != null) ? row.getInt("arrow", 4) : 4;

        ItemStack arrow = iconOr(b, "arrow", Material.SPECTRAL_ARROW, "<white>➜");
        ItemStack filler = iconOr(b, "filler", Material.BLACK_STAINED_GLASS_PANE, " ");

        ConfigurationSection btns = (b == null) ? null : b.getConfigurationSection("buttons");
        int backSlot = btnSlot(btns, "back", 45);
        int prevSlot = btnSlot(btns, "prev", 48);
        int pageSlot = btnSlot(btns, "page", 49);
        int nextSlot = btnSlot(btns, "next", 50);
        ItemStack back = btnIcon(btns, "back", Material.BARRIER, "<red>← Back");
        ItemStack prev = btnIcon(btns, "prev", Material.ARROW, "<yellow>← Previous");
        ItemStack next = btnIcon(btns, "next", Material.ARROW, "<yellow>Next →");
        ConfigurationSection page = (btns == null) ? null : btns.getConfigurationSection("page");
        Material pageMat = (page != null) ? matOr(page.getString("item"), Material.PAPER) : Material.PAPER;
        String pageName = (page != null) ? page.getString("name", "<gray>Page <page>/<pages>") : "<gray>Page <page>/<pages>";

        return new BrowserLayout(title, rows, perPage, inputCols, arrowCol, outputCols,
                arrow, filler, backSlot, back, prevSlot, prev, nextSlot, next, pageSlot, pageMat, pageName);
    }

    private int btnSlot(ConfigurationSection btns, String key, int def) {
        ConfigurationSection s = (btns == null) ? null : btns.getConfigurationSection(key);
        return (s != null) ? s.getInt("slot", def) : def;
    }

    private ItemStack btnIcon(ConfigurationSection btns, String key, Material def, String defName) {
        ConfigurationSection s = (btns == null) ? null : btns.getConfigurationSection(key);
        if (s != null) return buildIcon(s);
        ItemStack i = new ItemStack(def);
        ItemMeta m = i.getItemMeta();
        if (m != null) { m.displayName(mm(defName)); i.setItemMeta(m); }
        return i;
    }

    private ItemStack iconOr(ConfigurationSection parent, String key, Material def, String defName) {
        ConfigurationSection s = (parent == null) ? null : parent.getConfigurationSection(key);
        if (s != null) return buildIcon(s);
        ItemStack i = new ItemStack(def);
        ItemMeta m = i.getItemMeta();
        if (m != null) { m.displayName(mm(defName)); i.setItemMeta(m); }
        return i;
    }

    private static Material matOr(String name, Material def) {
        Material m = (name == null) ? null : Material.matchMaterial(name);
        return (m == null || !m.isItem()) ? def : m;
    }

    private static int[] ints(ConfigurationSection sec, String key, int[] def) {
        if (sec == null || !sec.contains(key)) return def;
        List<Integer> l = sec.getIntegerList(key);
        if (l.isEmpty()) return def;
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private ItemStack parseInfoIcon(ConfigurationSection info) {
        if (info != null) return buildIcon(info);
        ItemStack book = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) { meta.displayName(mm("<gold>Recipes")); book.setItemMeta(meta); }
        return book;
    }

    private Map<String, ItemStack> parseIcons(ConfigurationSection icons) {
        Map<String, ItemStack> map = new HashMap<>();
        if (icons == null) {
            map.put("#", filler(Material.GRAY_STAINED_GLASS_PANE));   // default frame
            return map;
        }
        for (String key : icons.getKeys(false)) {
            ConfigurationSection sec = icons.getConfigurationSection(key);
            ItemStack item = (sec != null) ? buildIcon(sec) : filler(Material.matchMaterial(icons.getString(key, "GRAY_STAINED_GLASS_PANE")));
            if (item != null) map.put(key, item);
        }
        map.putIfAbsent("#", filler(Material.GRAY_STAINED_GLASS_PANE));
        return map;
    }

    private Map<String, GuiLayout.ProgressIcon> parseProgress(String id, ConfigurationSection prog) {
        Map<String, GuiLayout.ProgressIcon> map = new HashMap<>();
        // Defaults so a machine without a progress: block still shows status.
        map.put("idle", new GuiLayout.ProgressIcon(Material.GRAY_STAINED_GLASS_PANE, "<gray>Idle", null, null));
        map.put("working", new GuiLayout.ProgressIcon(Material.LIME_STAINED_GLASS_PANE, "<green>Working… <white><percent>%", null, null));
        map.put("blocked", new GuiLayout.ProgressIcon(Material.RED_STAINED_GLASS_PANE, "<red>Output full", null, null));
        map.put("no_fuel", new GuiLayout.ProgressIcon(Material.RED_STAINED_GLASS_PANE, "<red>Out of fuel", null, null));
        map.put("burn", new GuiLayout.ProgressIcon(Material.ORANGE_STAINED_GLASS_PANE, "<gold>Fuel <white><percent>%", null, null));
        map.put("burn_empty", new GuiLayout.ProgressIcon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>No fuel", null, null));
        if (prog == null) return map;
        for (String state : prog.getKeys(false)) {
            ConfigurationSection sec = prog.getConfigurationSection(state);
            if (sec == null) continue;
            Material mat = Material.matchMaterial(sec.getString("item", "GRAY_STAINED_GLASS_PANE"));
            if (mat == null) { log.warning("[MachineConstruct] gui " + id + ": unknown progress item for '" + state + "'."); continue; }
            Integer model = sec.contains("model") ? sec.getInt("model") : null;
            map.put(state.toLowerCase(), new GuiLayout.ProgressIcon(mat, sec.getString("name"), sec.getStringList("lore"), model));
        }
        return map;
    }

    private ItemStack buildIcon(ConfigurationSection sec) {
        Material mat = Material.matchMaterial(sec.getString("item", "GRAY_STAINED_GLASS_PANE"));
        if (mat == null || !mat.isItem()) return filler(Material.GRAY_STAINED_GLASS_PANE);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(mm(sec.getString("name", " ")));
            List<String> lore = sec.getStringList("lore");
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String l : lore) lines.add(mm(l));
                meta.lore(lines);
            }
            if (sec.contains("model")) meta.setCustomModelData(sec.getInt("model"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack filler(Material mat) {
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));   // blank, no "minecraft:..." tooltip noise
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Component mm(String s) {
        return MiniMessage.miniMessage().deserialize(s == null ? " " : s)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }
}
