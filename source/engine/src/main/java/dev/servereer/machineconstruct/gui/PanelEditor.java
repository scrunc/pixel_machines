package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.core.TextContent;
import dev.servereer.machineconstruct.core.PacketDisplay;
import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import dev.servereer.machineconstruct.machine.PanelData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.servereer.machineconstruct.gui.MenuSkin.vars;

/**
 * Admin editor for a panel-kind machine ({@code machineconstruct.admin}): every text part of the
 * placed panel is listed; open one to edit it line by line (chat prompts, MiniMessage, {@code {theme}}
 * and {@code %placeholders%} allowed), add/delete lines, reset a part or the whole panel, and pick
 * the panel's theme colour from the type's {@code panel.themes} list or type a hex. Everything is
 * per placed panel (persisted with the machine) — the model file stays the default.
 */
public final class PanelEditor implements Listener {

    /** What the editor needs from the manager. */
    public interface Host {
        MachineType typeOf(Machine m);
        void persist(Machine m);
        /** Re-resolve + push the panel's text to viewers now. */
        void refresh(Machine m);
        /** Rebuild the machine's displays (after a block-theme change). */
        void rerender(Machine m);
    }

    private static final class Holder implements InventoryHolder {
        final Machine machine; String part; Inventory inv;
        Holder(Machine m, String part) { this.machine = m; this.part = part; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int SIZE = 54;
    private static final int S_PAGE = 2;
    private static final int S_THEME = 4, S_STATE = 6, S_RESET_ALL = 49, S_BACK = 45, S_ADD = 49, S_RESET_PART = 53, S_INFO = 4;

    private final Plugin plugin;
    private final Host host;
    private final Map<UUID, Object[]> pending = new HashMap<>();   // player → [Machine, part, lineIndex(-1 add, -2 theme)]

    public PanelEditor(Plugin plugin, Host host) {
        this.plugin = plugin;
        this.host = host;
    }

    // --- open / render ---------------------------------------------------------

    public void open(Player p, Machine m) { openView(p, m, null); }

    private void openView(Player p, Machine m, String part) {
        Holder h = new Holder(m, part);
        MenuSkin sk = skin(m);
        String title = part == null
                ? sk.title("panel_editor", "<dark_aqua>✦ Panel · Texts")
                : sk.title("panel_lines", "<dark_aqua>✦ Panel · {part}").replace("{part}", part);
        Inventory inv = Bukkit.createInventory(h, SIZE, MenuSkin.mini(title));
        h.inv = inv;
        render(h);
        p.openInventory(inv);
    }

    private MenuSkin skin(Machine m) { MachineType t = host.typeOf(m); return t == null ? MenuSkin.EMPTY : t.skin(); }

    /** Text parts of the placed panel, in model order: part name → authored template. */
    private Map<String, String> parts(Machine m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m.rendered() == null) return out;
        for (PacketDisplay d : m.rendered().leaves())
            if (d.baseContent() instanceof TextContent tc && d.partName() != null) out.put(d.partName(), tc.template());
        return out;
    }

    private List<String> linesOf(Machine m, String part, String template) {
        PanelData pd = m.panel();
        if (isPageSection(m, part)) {   // paged panel: a section's lines live on the current page
            List<String> pl = PanelData.pageLines(effectivePages(m), pd == null ? 0 : pd.page(), part);
            return pl == null ? new ArrayList<>(List.of("")) : new ArrayList<>(pl);
        }
        List<String> over = pd == null ? null : pd.lines(part);
        if (over != null) return new ArrayList<>(over);
        return new ArrayList<>(List.of((template == null ? "" : template).split("\n", -1)));
    }

    /** Paged (info) panels: parts named sec_* are per-page sections; everything else is static. */
    private boolean isPageSection(Machine m, String part) {
        MachineType t = host.typeOf(m);
        return t != null && t.panelPaged() && part != null && part.startsWith("sec_");
    }
    private boolean isPaged(Machine m) { MachineType t = host.typeOf(m); return t != null && t.panelPaged(); }
    private List<Map<String, List<String>>> effectivePages(Machine m) {
        MachineType t = host.typeOf(m);
        PanelData pd = m.panel();
        if (pd != null && !pd.pages().isEmpty()) return pd.pages();
        return t == null ? List.of() : t.panelPages();
    }
    /** Make the placed panel own its pages (copy the template's) before editing them. */
    private PanelData ownPages(Machine m) {
        PanelData pd = panel(m);
        if (pd.pages().isEmpty()) for (Map<String, List<String>> pg : effectivePages(m)) pd.pages().add(new LinkedHashMap<>(pg));
        if (pd.pages().isEmpty()) pd.pages().add(new LinkedHashMap<>());
        return pd;
    }
    private void putLines(Machine m, String part, List<String> lines) {
        if (isPageSection(m, part)) { PanelData pd = ownPages(m); pd.setPageLines(Math.min(pd.page(), pd.pages().size() - 1), part, lines); }
        else panel(m).setLines(part, lines);
    }
    private int curPage(Machine m) {
        int n = effectivePages(m).size();
        return n == 0 ? 0 : Math.min(Math.max(0, m.panel() == null ? 0 : m.panel().page()), n - 1);
    }

    private void render(Holder h) {
        Inventory inv = h.inv;
        inv.clear();
        Machine m = h.machine;
        MenuSkin sk = skin(m);
        ItemStack edge = sk.item("filler", sk.filler("panel_editor", Material.BLACK_STAINED_GLASS_PANE), " ", List.of(), null);
        for (int i = 0; i < 9; i++) inv.setItem(i, edge);
        for (int i = 45; i < SIZE; i++) inv.setItem(i, edge);
        PanelData pd = m.panel();
        if (h.part == null) {
            MachineType t = host.typeOf(m);
            String themeName = effectiveTheme(m);
            String hex = pd != null && pd.color() != null ? pd.color() : (t == null ? "#35e0d0" : t.panelThemeColor(themeName));
            List<String> names = t == null ? List.of() : new ArrayList<>(t.panelThemeModels().keySet());
            List<String> tlore = new ArrayList<>();
            tlore.add("<gray>Swaps the panel's blocks + <white>{theme}</white> colour.");
            for (String n : names) tlore.add((n.equals(themeName) ? "<aqua>▸ " : "<dark_gray>  ") + n);
            tlore.add("<yellow>◀ Left: next theme   <gold>▶ Right: type a text-colour hex" + (pd != null && pd.color() != null ? " <dark_gray>(override on)" : ""));
            inv.setItem(S_THEME, sk.item("panel_theme", Material.CYAN_DYE, "<aqua>Theme <dark_gray>» <white>{name} <#{hex}>■",
                    tlore, vars("name", themeName.isEmpty() ? "default" : themeName, "hex", hex.replace("#", ""))));
            if (isPaged(m)) {
                int n = effectivePages(m).size(), cur = curPage(m);
                List<String> plore = new ArrayList<>();
                plore.add("<gray>Sections (<white>sec_*</white>) are edited on the page showing.");
                plore.add("<gray>Pages: <white>" + n + (pd != null && !pd.pages().isEmpty() ? " <yellow>(edited)" : " <dark_gray>(template)"));
                plore.add("<yellow>◀ Left: previous page   <gold>▶ Right: next page");
                plore.add("<green>⇧ Shift-left: add a page after this one   <red>⇧ Shift-right: delete this page");
                inv.setItem(S_PAGE, sk.item("panel_page", Material.BOOK, "<aqua>Page <dark_gray>» <white>{page} / {pages}", plore,
                        vars("page", String.valueOf(n == 0 ? 0 : cur + 1), "pages", String.valueOf(n))));
            }
            if (t != null && !t.panelStates().isEmpty()) {
                String cur = pd != null && pd.state() != null && t.panelStates().containsKey(pd.state()) ? pd.state() : t.panelDefaultState();
                List<String> slore = new ArrayList<>();
                slore.add("<gray>What the panel's buttons switch — {period} / {label} / {state}.");
                for (String n : t.panelStates().keySet()) slore.add((n.equals(cur) ? "<aqua>▸ " : "<dark_gray>  ") + n);
                slore.add("<yellow>◀ Left: next state");
                inv.setItem(S_STATE, sk.item("panel_state", Material.LEVER, "<aqua>State <dark_gray>» <white>{state}", slore, vars("state", cur)));
            }
            int slot = 9;
            for (Map.Entry<String, String> e : parts(m).entrySet()) {
                List<String> lines = linesOf(m, e.getKey(), e.getValue());
                boolean edited = pd != null && (pd.lines(e.getKey()) != null || (isPageSection(m, e.getKey()) && !pd.pages().isEmpty()));
                List<String> lore = new ArrayList<>();
                lore.add("<gray>Lines: <white>" + lines.size() + (edited ? " <yellow>(edited)" : " <dark_gray>(default)"));
                for (int i = 0; i < Math.min(4, lines.size()); i++) lore.add("<dark_gray>" + escape(trim(lines.get(i), 40)));
                if (lines.size() > 4) lore.add("<dark_gray>…");
                lore.add("<dark_gray>▶ Click to edit lines");
                inv.setItem(slot++, sk.item("panel_part", Material.OAK_SIGN, "<aqua>{part}", lore, vars("part", e.getKey())));
                if (slot >= 45) break;
            }
            inv.setItem(S_RESET_ALL, sk.item("panel_reset_all", Material.BARRIER, "<red>Reset panel",
                    List.of("<gray>Drop every override + theme", "<dark_gray>⇧ Shift-click to confirm"), null));
        } else {
            String template = parts(m).get(h.part);
            List<String> lines = linesOf(m, h.part, template);
            inv.setItem(S_INFO, sk.item("panel_lines_info", Material.WRITABLE_BOOK, "<aqua><bold>{part}",
                    List.of("<gray>Click a line to retype it in chat.",
                            "<gray>Shift-click a line to delete it.",
                            "<dark_gray>MiniMessage · {theme} · %placeholders%"), vars("part", h.part)));
            for (int i = 0; i < lines.size() && 9 + i < 45; i++) {
                inv.setItem(9 + i, sk.item("panel_line", Material.PAPER, "<white>{n}<dark_gray>. <reset>{line}",
                        List.of("<dark_gray>" + escape(trim(lines.get(i), 60)), "<yellow>◀ click: edit   <red>⇧ shift: delete"),
                        vars("n", i + 1, "line", trim(lines.get(i), 40))));
            }
            inv.setItem(S_BACK, sk.item("back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>to the part list"), null));
            inv.setItem(S_ADD, sk.item("panel_add_line", Material.LIME_DYE, "<green>+ Add line", List.of("<dark_gray>▶ type it in chat"), null));
            inv.setItem(S_RESET_PART, sk.item("panel_reset_part", Material.REDSTONE, "<red>Reset this text",
                    List.of("<gray>Back to the model's default", "<dark_gray>⇧ Shift-click to confirm"), null));
        }
    }

    /** The theme NAME in effect for a placed panel. */
    private String effectiveTheme(Machine m) {
        PanelData pd = m.panel();
        MachineType t = host.typeOf(m);
        if (pd != null && pd.theme() != null && t != null && t.panelThemeModels().containsKey(pd.theme())) return pd.theme();
        return t == null ? "" : t.panelTheme();
    }

    private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
    /** Show a raw line (with its tags) as literal text in lore. */
    private static String escape(String s) { return s.replace("<", "\\<"); }

    // --- clicks ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE) return;
        Machine m = h.machine;
        if (h.part == null) {
            if (raw == S_THEME) {
                if (e.getClick() == ClickType.RIGHT) { prompt(p, m, null, -2, "<aqua>Type a text-colour hex like <white>#35e0d0</white> <gray>(<white>reset</white> = the theme's own, <white>cancel</white> to abort)"); return; }
                cycleTheme(m); host.persist(m); host.rerender(m); render(h); return;
            }
            if (raw == S_PAGE && isPaged(m)) {
                int n = effectivePages(m).size(), cur = curPage(m);
                if (e.isShiftClick() && e.getClick() == ClickType.SHIFT_LEFT) { PanelData pd = ownPages(m); pd.pages().add(Math.min(cur + 1, pd.pages().size()), new LinkedHashMap<>()); pd.setPage(cur + 1); }
                else if (e.isShiftClick() && e.getClick() == ClickType.SHIFT_RIGHT) { PanelData pd = ownPages(m); if (pd.pages().size() > 1) { pd.pages().remove(cur); pd.setPage(Math.max(0, cur - 1)); } }
                else if (e.getClick() == ClickType.RIGHT) { if (n > 0) panel(m).setPage(Math.min(n - 1, cur + 1)); }
                else panel(m).setPage(Math.max(0, cur - 1));
                host.persist(m); host.refresh(m); render(h); return;
            }
            if (raw == S_STATE) {
                MachineType t = host.typeOf(m);
                if (t == null || t.panelStates().isEmpty()) return;
                List<String> names = new ArrayList<>(t.panelStates().keySet());
                String cur = m.panel() != null && m.panel().state() != null && names.contains(m.panel().state()) ? m.panel().state() : t.panelDefaultState();
                panel(m).setState(names.get((names.indexOf(cur) + 1) % names.size()));
                host.persist(m); host.refresh(m); render(h); return;
            }
            if (raw == S_RESET_ALL) {
                if (!e.isShiftClick()) return;
                if (m.panel() != null) m.panel().resetAll();
                host.persist(m); host.refresh(m); render(h); return;
            }
            List<String> names = new ArrayList<>(parts(m).keySet());
            int idx = raw - 9;
            if (idx >= 0 && idx < names.size()) { h.part = names.get(idx); p.closeInventory(); openView(p, m, h.part); }
            return;
        }
        // LINES view
        if (raw == S_BACK) { p.closeInventory(); openView(p, m, null); return; }
        if (raw == S_ADD) { prompt(p, m, h.part, -1, "<aqua>Type the new line <gray>(MiniMessage; <white>cancel</white> to abort)"); return; }
        if (raw == S_RESET_PART) {
            if (!e.isShiftClick()) return;
            if (m.panel() != null) m.panel().reset(h.part);
            host.persist(m); host.refresh(m); render(h); return;
        }
        int idx = raw - 9;
        List<String> lines = linesOf(m, h.part, parts(m).get(h.part));
        if (idx < 0 || idx >= lines.size()) return;
        if (e.isShiftClick()) {
            lines.remove(idx);
            putLines(m, h.part, lines.isEmpty() ? List.of("") : lines);
            host.persist(m); host.refresh(m); render(h);
            return;
        }
        prompt(p, m, h.part, idx, "<aqua>Retype line <white>" + (idx + 1) + "</white> <gray>(current: <white>" + escape(lines.get(idx)) + "</white>; <white>cancel</white> to abort)");
    }

    private PanelData panel(Machine m) {
        if (m.panel() == null) m.setPanel(new PanelData());
        return m.panel();
    }

    private void cycleTheme(Machine m) {
        MachineType t = host.typeOf(m);
        List<String> names = t == null ? List.of() : new ArrayList<>(t.panelThemeModels().keySet());
        if (names.isEmpty()) return;
        int i = names.indexOf(effectiveTheme(m));
        panel(m).setTheme(names.get((i + 1) % names.size()));
    }

    private void prompt(Player p, Machine m, String part, int lineIndex, String msg) {
        pending.put(p.getUniqueId(), new Object[]{ m, part, lineIndex });
        p.closeInventory();
        p.sendMessage(MenuSkin.mini(msg));
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Object[] pr = pending.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Machine m = (Machine) pr[0];
        String part = (String) pr[1];
        int idx = (Integer) pr[2];
        String txt = e.getMessage();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (txt.trim().equalsIgnoreCase("cancel")) { openView(p, m, part); return; }
            if (idx == -2) {
                if (txt.trim().equalsIgnoreCase("reset")) { panel(m).setColor(null); }
                else {
                    String hex = txt.trim().startsWith("#") ? txt.trim() : "#" + txt.trim();
                    if (!hex.matches("#[0-9a-fA-F]{6}")) { p.sendMessage(MenuSkin.mini("<red>That isn't a hex colour (#rrggbb).")); openView(p, m, null); return; }
                    panel(m).setColor(hex.toLowerCase());
                }
            } else {
                List<String> lines = linesOf(m, part, parts(m).get(part));
                if (idx == -1) lines.add(txt); else if (idx < lines.size()) lines.set(idx, txt);
                putLines(m, part, lines);
            }
            host.persist(m); host.refresh(m);
            openView(p, m, part);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @SuppressWarnings("unused")
    private static Component c(String s) { return MenuSkin.mini(s); }
}
