package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.api.MachineConstructAPI;
import dev.servereer.machineconstruct.collector.ChunkCollectorActions;
import dev.servereer.machineconstruct.collector.ChunkCollectorData;
import dev.servereer.machineconstruct.collector.ChunkCollectorSpec;
import dev.servereer.machineconstruct.grinder.ChestLink;
import dev.servereer.machineconstruct.grinder.econ.PriceService;
import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.servereer.machineconstruct.gui.MenuSkin.vars;

/**
 * The Chunk Collector menu — a fully code-rendered, paginated, sealed GUI (no authored
 * {@link GuiLayout}), with the same un-glitchable discipline as {@link GrinderMenus}:
 * every click touching the window is cancelled and the intended action performed
 * deterministically via {@link ChunkCollectorActions}.
 *
 * <p>Views: MAIN (the vault browser + control bar), CHESTS (OUTPUT routing),
 * CHEST_FILTER, AUTOSELL (per-item auto-sell), LOG (activity history).
 *
 * <p>Look and wording come from the machine's {@link MenuSkin} ({@code skin:} in the
 * machine YAML) — slots, titles, fillers, buttons and chat lines are all lookups whose
 * defaults are the built-in text, so a machine without a skin renders unchanged.
 */
public final class ChunkCollectorMenus implements Listener {

    private static final int SIZE = 54;            // 6 rows
    private static final int[] DEF_TABS = {0, 1, 2, 3, 4};

    private final Plugin plugin;
    private final MachineConstructAPI registry;
    private final ChunkCollectorActions actions;
    private final Map<UUID, List<Inventory>> open = new HashMap<>();
    private final Map<UUID, Long> lastRefresh = new HashMap<>();
    private static final long REFRESH_THROTTLE_MS = 1500;

    public ChunkCollectorMenus(Plugin plugin, MachineConstructAPI registry, ChunkCollectorActions actions) {
        this.plugin = plugin;
        this.registry = registry;
        this.actions = actions;
    }

    public void open(Player player, Machine machine) { openTo(player, machine, ChunkCollectorMenuHolder.View.MAIN); }

    public void openTo(Player player, Machine machine, ChunkCollectorMenuHolder.View view) {
        actions.collectorVacuum(machine);
        actions.persist(machine);
        ChunkCollectorMenuHolder holder = new ChunkCollectorMenuHolder(machine, view, 0);
        Inventory inv = Bukkit.createInventory(holder, SIZE, title(machine, holder.view()));
        holder.setInventory(inv);
        open.computeIfAbsent(machine.id(), k -> new ArrayList<>()).add(inv);
        render(inv, holder);
        player.openInventory(inv);
    }

    public void refresh(Machine machine) {
        List<Inventory> list = open.get(machine.id());
        if (list == null) return;
        list.removeIf(inv -> inv.getViewers().isEmpty());
        if (list.isEmpty()) { open.remove(machine.id()); lastRefresh.remove(machine.id()); return; }
        lastRefresh.put(machine.id(), System.currentTimeMillis());
        for (Inventory inv : list) if (inv.getHolder() instanceof ChunkCollectorMenuHolder h) render(inv, h);
    }

    public void refreshThrottled(Machine machine) {
        Long last = lastRefresh.get(machine.id());
        if (last != null && System.currentTimeMillis() - last < REFRESH_THROTTLE_MS) return;
        refresh(machine);
    }

    public void closeFor(Machine machine) {
        List<Inventory> list = open.remove(machine.id());
        if (list != null) for (Inventory inv : list)
            for (HumanEntity v : new ArrayList<>(inv.getViewers())) v.closeInventory();
    }

    public void closeAll() {
        for (List<Inventory> list : new ArrayList<>(open.values()))
            for (Inventory inv : new ArrayList<>(list))
                for (HumanEntity v : new ArrayList<>(inv.getViewers())) v.closeInventory();
        open.clear();
    }

    private MachineType typeOf(Machine m) { return registry == null ? null : registry.getMachineType(m.typeId()); }
    private PriceService pricesOf(Machine m) { MachineType t = typeOf(m); return t == null ? null : t.pricing(); }
    private MenuSkin skinOf(Machine m) { MachineType t = typeOf(m); return t == null ? MenuSkin.EMPTY : t.skin(); }

    // --- slot map (skin-overridable; defaults = the historic layout) --------

    private static int contentStart(MenuSkin sk) { return sk.slot("content", "start", 9); }
    private static int contentEnd(MenuSkin sk) { return sk.slot("content", "end", 36); }     // exclusive
    private static int perPage(MenuSkin sk) { return Math.max(1, contentEnd(sk) - contentStart(sk)); }
    // MAIN — top row
    private static int[] tTabs(MenuSkin sk) { return sk.slots("main", "tabs", DEF_TABS); }
    private static int tAutoSell(MenuSkin sk) { return sk.slot("main", "autosell", 6); }
    private static int tChests(MenuSkin sk) { return sk.slot("main", "chests", 7); }
    private static int tValue(MenuSkin sk) { return sk.slot("main", "value", 8); }
    // MAIN — bottom row
    private static int bPrev(MenuSkin sk) { return sk.slot("main", "prev", 45); }
    private static int bUpgrade(MenuSkin sk) { return sk.slot("main", "upgrade", 46); }
    private static int bCollect(MenuSkin sk) { return sk.slot("main", "collect", 47); }
    private static int bSort(MenuSkin sk) { return sk.slot("main", "sort", 49); }
    private static int bSell(MenuSkin sk) { return sk.slot("main", "sell", 50); }
    private static int bLog(MenuSkin sk) { return sk.slot("main", "log", 52); }
    private static int bNext(MenuSkin sk) { return sk.slot("main", "next", 53); }
    private static int mEmpty(MenuSkin sk) { return sk.slot("main", "empty", 22); }
    // LOG
    private static int lInfo(MenuSkin sk) { return sk.slot("log", "info", 4); }
    private static int lPrev(MenuSkin sk) { return sk.slot("log", "prev", 45); }
    private static int lNext(MenuSkin sk) { return sk.slot("log", "next", 53); }
    private static int lBack(MenuSkin sk) { return sk.slot("log", "back", 52); }
    private static int lEmpty(MenuSkin sk) { return sk.slot("log", "empty", 22); }
    // CHESTS / CHEST_FILTER
    private static int cInfo(MenuSkin sk) { return sk.slot("chests", "info", 4); }
    private static int cAddOut(MenuSkin sk) { return sk.slot("chests", "add_out", 48); }
    private static int cAddIn(MenuSkin sk) { return sk.slot("chests", "add_in", 46); }
    private static int cBack(MenuSkin sk) { return sk.slot("chests", "back", 50); }
    private static int cEmpty(MenuSkin sk) { return sk.slot("chests", "empty", 22); }
    private static int fInfo(MenuSkin sk) { return sk.slot("chest_filter", "info", 4); }
    private static int fClear(MenuSkin sk) { return sk.slot("chest_filter", "clear", 48); }
    private static int fBack(MenuSkin sk) { return sk.slot("chest_filter", "back", 50); }
    private static int fEmpty(MenuSkin sk) { return sk.slot("chest_filter", "empty", 22); }
    // AUTOSELL
    private static int asInfo(MenuSkin sk) { return sk.slot("autosell", "info", 4); }
    private static int asToggle(MenuSkin sk) { return sk.slot("autosell", "toggle", 45); }
    private static int asAll(MenuSkin sk) { return sk.slot("autosell", "all", 47); }
    private static int asClear(MenuSkin sk) { return sk.slot("autosell", "clear", 49); }
    private static int asBack(MenuSkin sk) { return sk.slot("autosell", "back", 53); }
    private static int asEmpty(MenuSkin sk) { return sk.slot("autosell", "empty", 22); }

    private static final String[] TAB_KEYS = {"all", "ores", "gems", "blocks", "misc"};
    private static final String[] TAB_NAMES = {"All", "Ores", "Gems", "Blocks", "Misc"};
    private static final Material[] TAB_ICONS = {Material.CHEST, Material.RAW_IRON, Material.DIAMOND, Material.COBBLESTONE, Material.ENDER_EYE};

    // --- rendering ----------------------------------------------------------

    private void render(Inventory inv, ChunkCollectorMenuHolder h) {
        inv.clear();
        switch (h.view()) {
            case MAIN -> renderMain(inv, h);
            case LOG -> renderLog(inv, h);
            case CHESTS -> renderChests(inv, h);
            case CHEST_FILTER -> renderChestFilter(inv, h);
            case AUTOSELL -> renderAutoSell(inv, h);
        }
    }

    private void fillAll(Inventory inv, MenuSkin sk, String view, Material def) {
        ItemStack edge = sk.item("filler", sk.filler(view, def), " ", List.of(), null);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, edge);
        sk.paintAccents(view, (slot, pane) -> { if (slot >= 0 && slot < SIZE) inv.setItem(slot, pane); });
    }

    private void fillFrame(Inventory inv, MenuSkin sk, String view, Material def) {
        ItemStack edge = sk.item("filler", sk.filler(view, def), " ", List.of(), null);
        int cs = contentStart(sk), ce = contentEnd(sk);
        for (int i = 0; i < cs; i++) inv.setItem(i, edge);
        for (int i = ce; i < SIZE; i++) inv.setItem(i, edge);
        sk.paintAccents(view, (slot, pane) -> { if (slot >= 0 && slot < SIZE && (slot < cs || slot >= ce)) inv.setItem(slot, pane); });
    }

    private void renderMain(Inventory inv, ChunkCollectorMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        ChunkCollectorData d = collector(m);
        ChunkCollectorSpec spec = type == null ? null : type.chunkCollector();
        if (d == null || spec == null) return;
        PriceService prices = type.pricing();
        MenuSkin sk = type.skin();

        fillAll(inv, sk, "main", Material.BLACK_STAINED_GLASS_PANE);

        int[] tabs = tTabs(sk);
        for (int i = 0; i < TAB_NAMES.length && i < tabs.length; i++) {
            Map<String, String> v = vars("tab", TAB_NAMES[i]);
            inv.setItem(tabs[i], h.tab() == i
                    ? sk.item("tab_" + TAB_KEYS[i] + "_on", TAB_ICONS[i], "<green>▸ {tab}", List.of("<dark_gray>filter"), v)
                    : sk.item("tab_" + TAB_KEYS[i], TAB_ICONS[i], "<gray>{tab}", List.of("<dark_gray>filter"), v));
        }

        boolean asOn = d.autoSellEnabled();
        int outs = 0, ins = 0;
        for (ChestLink l : d.links()) { if (l.type() == ChestLink.Type.OUTPUT) outs++; else ins++; }
        Map<String, String> v = vars("rules", d.autoSellRules().size(), "outs", outs, "ins", ins);
        List<String> asLore = List.of("<gray>Auto-sells chosen items for money (per item).",
                "<gray>Items set: <white>{rules}",
                "<dark_gray>Paid out hourly.",
                "<dark_gray>▶ Click to configure");
        inv.setItem(tAutoSell(sk), asOn
                ? sk.item("autosell_on", Material.GOLD_BLOCK, "<gold>Auto-Sell: <green>ON", asLore, v)
                : sk.item("autosell_off", Material.GOLD_NUGGET, "<gold>Auto-Sell: <red>OFF", asLore, v));
        inv.setItem(tChests(sk), sk.item("chests", Material.HOPPER, "<aqua>I/O Chests",
                List.of("<gray>Route the vault <green>out</green> to chests, or pull items <aqua>in</aqua>.",
                        "<gray>Linked: <green>{outs} out</green> · <aqua>{ins} in",
                        "<dark_gray>▶ Click to manage"), v));
        long cap = spec.capacity(m.tier());
        inv.setItem(tValue(sk), sk.item("value", Material.GOLD_INGOT, "<gold>Vault value",
                List.of("<yellow>${value}",
                        "<gray>Items: <white>{items}{cap}",
                        "<gray>Lifetime collected: <white>{lifetime}"),
                vars("value", trim(vaultValue(d, prices)), "items", d.vaultMass(),
                        "cap", cap > 0 ? "</white>/<white>" + cap : "", "capacity", cap, "lifetime", d.collectedTotal())));

        int cs = contentStart(sk), per = perPage(sk);
        List<Map.Entry<ItemStack, Long>> list = sortedFiltered(d, h.tab(), h.sort(), prices);
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) per));
        h.setPage(Math.min(h.page(), pages - 1));
        int start = h.page() * per;
        for (int i = 0; i < per; i++) {
            int idx = start + i;
            if (idx >= list.size()) break;
            inv.setItem(cs + i, vaultCard(sk, list.get(idx), prices));
        }
        if (list.isEmpty())
            inv.setItem(mEmpty(sk), sk.item("vault_empty", Material.GRAY_STAINED_GLASS_PANE, "<gray>The vault is empty",
                    List.of("<dark_gray>Drop items in range — it vacuums them up"), null));

        Map<String, String> pv = vars("page", h.page() + 1, "pages", pages, "types", list.size(), "sort", h.sort());
        String pageInfo = "<gray>Page <white>{page}</white>/<white>{pages}</white> · <white>{types}</white> types";
        if (h.page() > 0) inv.setItem(bPrev(sk), sk.item("prev", Material.ARROW, "<yellow>◀ Prev", List.of(pageInfo), pv));
        if (h.page() < pages - 1) inv.setItem(bNext(sk), sk.item("next", Material.ARROW, "<yellow>Next ▶", List.of(pageInfo), pv));
        inv.setItem(bUpgrade(sk), upgradeButton(sk, m, type, spec));
        inv.setItem(bCollect(sk), sk.item("collect", Material.HOPPER, "<green>Collect all", List.of("<gray>Everything that fits → your inventory", "<dark_gray>▶ click"), pv));
        inv.setItem(bSort(sk), sk.item("sort", Material.COMPARATOR, "<white>Sort: <aqua>{sort}", List.of("<dark_gray>▶ cycle qty / value / name"), pv));
        inv.setItem(bSell(sk), h.sellArmed()
                ? sk.item("sell_confirm", Material.REDSTONE_BLOCK, "<red>⚠ Click again to confirm",
                        List.of("<red>Sells the ENTIRE vault", "<dark_gray>(disarms if you click elsewhere)"), pv)
                : sk.item("sell", Material.SUNFLOWER, "<gold>$ Sell all", List.of("<gray>Sell the whole vault", "<dark_gray>▶ click"), pv));
        inv.setItem(bLog(sk), sk.item("log", Material.WRITABLE_BOOK, "<yellow>Activity log", List.of("<gray>Recent collector events", "<dark_gray>▶ click"), null));
    }

    private ItemStack vaultCard(MenuSkin sk, Map.Entry<ItemStack, Long> e, PriceService prices) {
        ItemStack tmpl = e.getKey();
        long count = e.getValue();
        ItemStack icon = tmpl.clone();
        icon.setAmount((int) Math.max(1, Math.min(count, tmpl.getMaxStackSize())));
        double each = prices == null ? 0 : prices.price(tmpl);
        List<String> lore = new ArrayList<>();
        if (each > 0) lore.add("<gray>Value: <yellow>${each}</yellow> ea · <yellow>${total}");
        lore.add("<dark_gray>click=stack · right=one · shift=all");
        return sk.decorate("vault_card", icon, "<white>{item} <gray>×<yellow>{count}", lore,
                vars("item", prettyItem(tmpl), "count", count, "each", trim(each), "total", trim(each * count)));
    }

    private void renderLog(Inventory inv, ChunkCollectorMenuHolder h) {
        ChunkCollectorData d = collector(h.machine());
        MenuSkin sk = skinOf(h.machine());
        fillFrame(inv, sk, "log", Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(lInfo(sk), sk.item("log_title", Material.WRITABLE_BOOK, "<yellow><bold>Activity Log", List.of(), null));

        int cs = contentStart(sk), per = perPage(sk);
        List<String> lines = d == null ? List.of() : d.logLines();
        int pages = Math.max(1, (int) Math.ceil(lines.size() / (double) per));
        h.setPage(Math.min(h.page(), pages - 1));
        int start = h.page() * per;
        for (int i = 0; i < per; i++) {
            int idx = start + i;
            if (idx >= lines.size()) break;
            inv.setItem(cs + i, sk.item("log_line", Material.PAPER, "<white>{line}", List.of(), vars("line", lines.get(idx))));
        }
        if (lines.isEmpty()) inv.setItem(lEmpty(sk), sk.item("log_empty", Material.PAPER, "<gray>No activity yet", List.of(), null));
        if (h.page() > 0) inv.setItem(lPrev(sk), sk.item("log_prev", Material.ARROW, "<yellow>◀ Previous page", List.of(), null));
        if (h.page() < pages - 1) inv.setItem(lNext(sk), sk.item("log_next", Material.ARROW, "<yellow>Next page ▶", List.of(), null));
        inv.setItem(lBack(sk), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>Return to the collector"), null));
    }

    // --- CHESTS (OUTPUT routing) --------------------------------------------

    private void renderChests(Inventory inv, ChunkCollectorMenuHolder h) {
        ChunkCollectorData d = collector(h.machine());
        MenuSkin sk = skinOf(h.machine());
        fillFrame(inv, sk, "chests", Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(cInfo(sk), sk.item("chests_title", Material.HOPPER, "<aqua><bold>I/O Chests",
                List.of("<gray>OUTPUT chests receive the routed vault.",
                        "<gray>INPUT chests feed items into the vault.",
                        "<dark_gray>Reach grows as the collector is upgraded."), null));
        int cs = contentStart(sk), per = perPage(sk);
        if (d != null) {
            List<ChestLink> links = d.links();
            for (int i = 0; i < links.size() && i < per; i++) inv.setItem(cs + i, linkCard(sk, links.get(i), i));
            if (links.isEmpty()) inv.setItem(cEmpty(sk), sk.item("chests_empty", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>No linked chests yet",
                    List.of("<dark_gray>Use the buttons below"), null));
        }
        inv.setItem(cAddOut(sk), sk.item("add_out", Material.CHEST, "<green>+ Add OUTPUT chest",
                List.of("<gray>Routes the vault into a chest.", "<dark_gray>▶ Click, then click a chest in range"), null));
        inv.setItem(cAddIn(sk), sk.item("add_in", Material.TRAPPED_CHEST, "<aqua>+ Add INPUT chest",
                List.of("<gray>Pulls items from it into the vault.", "<dark_gray>▶ Click, then click a chest in range"), null));
        inv.setItem(cBack(sk), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>Return to the collector"), null));
    }

    private ItemStack linkCard(MenuSkin sk, ChestLink l, int index) {
        boolean out = l.type() == ChestLink.Type.OUTPUT;
        org.bukkit.Location loc = l.loc();
        Map<String, String> v = vars("n", index + 1,
                "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ(),
                "filter", l.filtered() ? l.filter().size() + " item(s)" : "everything",
                "rate", l.fullTransfer() ? "full (all that fits)" : l.maxPerSec() + "/sec");
        List<String> lore = List.of(
                "<gray>At <white>{x}, {y}, {z}",
                "<gray>Filter: <white>{filter}",
                "<gray>Rate: <white>{rate}",
                "<yellow>◀ Left: set rate   <gold>⇧ Shift: filter",
                "<red>▶ Right: remove");
        return out ? sk.item("link_out", Material.CHEST, "<green>OUTPUT <dark_gray>#{n}", lore, v)
                   : sk.item("link_in", Material.TRAPPED_CHEST, "<aqua>INPUT <dark_gray>#{n}", lore, v);
    }

    private void renderChestFilter(Inventory inv, ChunkCollectorMenuHolder h) {
        Machine m = h.machine();
        ChunkCollectorData d = collector(m);
        MenuSkin sk = skinOf(m);
        fillFrame(inv, sk, "chest_filter", Material.BLACK_STAINED_GLASS_PANE);
        int idx = h.linkIndex();
        ChestLink link = (d != null && idx >= 0 && idx < d.links().size()) ? d.links().get(idx) : null;
        if (link == null) { h.setView(ChunkCollectorMenuHolder.View.CHESTS); renderChests(inv, h); return; }
        boolean inFilter = link.type() == ChestLink.Type.INPUT;
        inv.setItem(fInfo(sk), inFilter
                ? sk.item("filter_title_in", Material.HOPPER, "<aqua><bold>Input Filter",
                        List.of("<gray>Click items to pull ONLY those.", "<gray>Empty filter = pull everything."), null)
                : sk.item("filter_title_out", Material.HOPPER, "<aqua><bold>Output Filter",
                        List.of("<gray>Click items to route ONLY those.", "<gray>Empty filter = route everything."), null));
        int cs = contentStart(sk), per = perPage(sk);
        List<Material> cands = candidateMaterials(d);
        for (int i = 0; i < cands.size() && i < per; i++) {
            Material mat = cands.get(i);
            boolean on = link.filter().contains(mat);
            inv.setItem(cs + i, toggleIcon(sk, on ? "filter_item_on" : "filter_item_off", mat, on,
                    on ? "routing this" : "not routed", null));
        }
        if (cands.isEmpty()) inv.setItem(fEmpty(sk), sk.item("filter_empty", Material.BARRIER, "<gray>Vault is empty", List.of("<dark_gray>collect some items first"), null));
        inv.setItem(fClear(sk), sk.item("filter_clear", Material.HOPPER, "<yellow>Route everything", List.of("<gray>Clear the filter", "<dark_gray>▶ Click"), null));
        inv.setItem(fBack(sk), sk.item("filter_back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>to Output Chests"), null));
    }

    // --- AUTO-SELL ----------------------------------------------------------

    private void renderAutoSell(Inventory inv, ChunkCollectorMenuHolder h) {
        Machine m = h.machine();
        ChunkCollectorData d = collector(m);
        MenuSkin sk = skinOf(m);
        fillFrame(inv, sk, "autosell", Material.BLACK_STAINED_GLASS_PANE);
        if (d == null) return;
        inv.setItem(asInfo(sk), sk.item("autosell_title", Material.GOLD_BLOCK, "<gold><bold>Auto-Sell <dark_gray>(per item)",
                List.of("<gray>Master: {master}",
                        "<gray>Auto-selling: <white>{rules} item type(s)",
                        "<gray>Pending payout: <yellow>${money} <dark_gray>({pending} items, paid hourly)",
                        "<yellow>◀ Left: toggle item   <gold>▶ Right: set its rate"),
                vars("master", d.autoSellEnabled() ? "<green>ON" : "<red>OFF", "rules", d.autoSellRules().size(),
                        "money", trim(d.autoSellAccMoney()), "pending", d.autoSellAccItems())));
        int cs = contentStart(sk), per = perPage(sk);
        List<Material> cands = candidateMaterials(d);
        for (int i = 0; i < cands.size() && i < per; i++) {
            Material mat = cands.get(i);
            boolean on = d.isAutoSellItem(mat);
            String rate = d.autoSellItemFull(mat) ? "all" : d.autoSellRate(mat) + "/sec";
            inv.setItem(cs + i, toggleIcon(sk, on ? "autosell_item_on" : "autosell_item_off", mat, on,
                    on ? "auto-selling @ {rate}" : "not auto-selling", vars("rate", rate)));
        }
        if (cands.isEmpty()) inv.setItem(asEmpty(sk), sk.item("autosell_empty", Material.BARRIER, "<gray>Vault is empty", List.of("<dark_gray>collect some items first"), null));
        inv.setItem(asToggle(sk), d.autoSellEnabled()
                ? sk.item("autosell_toggle_on", Material.LIME_DYE, "<green>Auto-Sell ON", List.of("<dark_gray>▶ master switch"), null)
                : sk.item("autosell_toggle_off", Material.GRAY_DYE, "<red>Auto-Sell OFF", List.of("<dark_gray>▶ master switch"), null));
        inv.setItem(asAll(sk), sk.item("autosell_all", Material.HOPPER, "<aqua>Auto-sell EVERY item", List.of("<gray>Enable all vault items at rate 'all'", "<dark_gray>▶ click"), null));
        inv.setItem(asClear(sk), sk.item("autosell_clear", Material.REDSTONE, "<red>Stop selling all", List.of("<gray>Clear every per-item rule", "<dark_gray>▶ click"), null));
        inv.setItem(asBack(sk), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>Return to the collector"), null));
    }

    private ItemStack toggleIcon(MenuSkin sk, String key, Material mat, boolean on, String state, Map<String, String> extra) {
        ItemStack it = new ItemStack(mat.isItem() ? mat : Material.PAPER);
        Map<String, String> v = vars("item", pretty(mat));
        if (extra != null) v.putAll(extra);
        it = sk.decorate(key, it, on ? "<green>✔ {item}" : "<gray>{item}",
                List.of((on ? "<green>" : "<dark_gray>") + state, "<dark_gray>▶ click to toggle"), v);
        if (on) {
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                it.setItemMeta(meta);
            }
        }
        return it;
    }

    // --- events -------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof ChunkCollectorMenuHolder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) { e.setCancelled(true); return; }
        int raw = e.getRawSlot();
        if (e.getClick() == ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }
        if (raw < 0 || raw >= SIZE) {
            if (e.getClick() == ClickType.DOUBLE_CLICK || e.isShiftClick()) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        Machine m = h.machine();
        try {
            switch (h.view()) {
                case MAIN -> onMainClick(p, h, m, raw, e.getClick());
                case LOG -> onLogClick(p, h, m, raw);
                case CHESTS -> onChestsClick(p, h, m, raw, e.getClick());
                case CHEST_FILTER -> onChestFilterClick(p, h, m, raw);
                case AUTOSELL -> onAutoSellClick(p, h, m, raw, e.getClick());
            }
        } catch (Throwable t) {
            refresh(m);
            p.updateInventory();
        }
    }

    private void onMainClick(Player p, ChunkCollectorMenuHolder h, Machine m, int raw, ClickType click) {
        ChunkCollectorData d = collector(m);
        if (d == null) return;
        MenuSkin sk = skinOf(m);
        if (raw != bSell(sk)) h.setSellArmed(false);

        int[] tabs = tTabs(sk);
        for (int i = 0; i < tabs.length && i < TAB_NAMES.length; i++)
            if (raw == tabs[i]) { h.setTab(i); refresh(m); return; }
        if (raw == tAutoSell(sk)) { h.setView(ChunkCollectorMenuHolder.View.AUTOSELL); h.setPage(0); refresh(m); return; }
        if (raw == tChests(sk)) { h.setView(ChunkCollectorMenuHolder.View.CHESTS); h.setPage(0); refresh(m); return; }
        if (raw == bPrev(sk)) { h.setPage(h.page() - 1); refresh(m); return; }
        if (raw == bNext(sk)) { h.setPage(h.page() + 1); refresh(m); return; }
        if (raw == bSort(sk)) { h.setSort(nextSort(h.sort())); refresh(m); return; }
        if (raw == bLog(sk)) { h.setView(ChunkCollectorMenuHolder.View.LOG); h.setPage(0); refresh(m); return; }
        if (raw == bUpgrade(sk)) { actions.upgrade(m, p); refresh(m); return; }
        if (raw == bCollect(sk)) {
            long n = actions.collectVault(m, p);
            actions.persist(m); refresh(m); p.updateInventory();
            p.sendMessage(brand(sk, n > 0 ? sk.msg("collected", "<green>Collected <white>{items}</white> items.", vars("items", n))
                                          : sk.msg("nothing_fit", "<gray>Nothing fit (inventory full?) / vault empty.", null)));
            return;
        }
        if (raw == bSell(sk)) {
            if (!h.sellArmed()) {
                h.setSellArmed(true); refresh(m);
                p.sendMessage(brand(sk, sk.msg("sell_confirm", "<red>Click Sell again to confirm — this sells the whole vault.", null)));
            } else {
                double money = actions.sellVault(m, p);
                actions.persist(m); h.setSellArmed(false); refresh(m);
                if (money < 0) p.sendMessage(brand(sk, sk.msg("no_economy", "<red>No economy available to sell.", null)));
                else p.sendMessage(brand(sk, money > 0 ? sk.msg("sold", "<gold>Sold the vault for <white>{money}</white>.", vars("money", trim(money)))
                                                       : sk.msg("nothing_sellable", "<gray>Nothing sellable.", null)));
            }
            return;
        }
        int cs = contentStart(sk), ce = contentEnd(sk);
        if (raw >= cs && raw < ce) {
            List<Map.Entry<ItemStack, Long>> list = sortedFiltered(d, h.tab(), h.sort(), pricesOf(m));
            int idx = h.page() * perPage(sk) + (raw - cs);
            if (idx >= list.size()) return;
            ItemStack tmpl = list.get(idx).getKey();
            long amt = click.isShiftClick() ? Long.MAX_VALUE : (click == ClickType.RIGHT ? 1 : tmpl.getMaxStackSize());
            actions.withdrawVaultItem(m, p, tmpl, amt);
            actions.persist(m); refresh(m); p.updateInventory();
        }
    }

    private void onLogClick(Player p, ChunkCollectorMenuHolder h, Machine m, int raw) {
        MenuSkin sk = skinOf(m);
        if (raw == lPrev(sk)) { h.setPage(h.page() - 1); refresh(m); return; }
        if (raw == lNext(sk)) { h.setPage(h.page() + 1); refresh(m); return; }
        if (raw == lBack(sk)) { h.setView(ChunkCollectorMenuHolder.View.MAIN); h.setPage(0); refresh(m); }
    }

    private void onChestsClick(Player p, ChunkCollectorMenuHolder h, Machine m, int raw, ClickType click) {
        MenuSkin sk = skinOf(m);
        if (raw == cBack(sk)) { h.setView(ChunkCollectorMenuHolder.View.MAIN); refresh(m); return; }
        if (raw == cAddOut(sk)) { actions.beginChestSelection(m, p, ChestLink.Type.OUTPUT); return; }
        if (raw == cAddIn(sk)) { actions.beginChestSelection(m, p, ChestLink.Type.INPUT); return; }
        int cs = contentStart(sk), ce = contentEnd(sk);
        if (raw >= cs && raw < ce) {
            ChunkCollectorData d = collector(m);
            if (d == null) return;
            int idx = raw - cs;
            if (idx >= d.links().size()) return;
            if (click == ClickType.RIGHT) { actions.removeChestLink(m, idx); refresh(m); p.sendMessage(brand(sk, sk.msg("link_removed", "<yellow>Removed link.", null))); return; }
            // Rate + filter apply to OUTPUT (push) and INPUT (pull) alike.
            if (click.isShiftClick()) { h.setLinkIndex(idx); h.setView(ChunkCollectorMenuHolder.View.CHEST_FILTER); refresh(m); }
            else actions.promptLinkRate(m, p, idx);   // closes the menu + chat-prompts; reopens to CHESTS
        }
    }

    private void onChestFilterClick(Player p, ChunkCollectorMenuHolder h, Machine m, int raw) {
        MenuSkin sk = skinOf(m);
        if (raw == fBack(sk)) { h.setView(ChunkCollectorMenuHolder.View.CHESTS); refresh(m); return; }
        if (raw == fClear(sk)) { actions.clearLinkFilter(m, h.linkIndex()); refresh(m); return; }
        int cs = contentStart(sk), ce = contentEnd(sk);
        if (raw >= cs && raw < ce) {
            ChunkCollectorData d = collector(m);
            if (d == null) return;
            List<Material> cands = candidateMaterials(d);
            int idx = raw - cs;
            if (idx < cands.size()) { actions.toggleLinkFilter(m, h.linkIndex(), cands.get(idx)); refresh(m); }
        }
    }

    private void onAutoSellClick(Player p, ChunkCollectorMenuHolder h, Machine m, int raw, ClickType click) {
        MenuSkin sk = skinOf(m);
        if (raw == asBack(sk)) { h.setView(ChunkCollectorMenuHolder.View.MAIN); refresh(m); return; }
        if (raw == asToggle(sk)) {
            actions.toggleAutoSell(m); refresh(m);
            p.sendMessage(brand(sk, collector(m).autoSellEnabled()
                    ? sk.msg("autosell_on", "<green>Auto-Sell enabled.", null)
                    : sk.msg("autosell_off", "<yellow>Auto-Sell disabled.", null)));
            return;
        }
        if (raw == asAll(sk)) {
            ChunkCollectorData dd = collector(m);
            if (dd != null) for (Material mat : candidateMaterials(dd)) if (!dd.isAutoSellItem(mat)) actions.toggleAutoSellItem(m, mat);
            refresh(m); return;
        }
        if (raw == asClear(sk)) { actions.clearAutoSellItems(m); refresh(m); return; }
        int cs = contentStart(sk), ce = contentEnd(sk);
        if (raw >= cs && raw < ce) {
            ChunkCollectorData d = collector(m);
            if (d == null) return;
            List<Material> cands = candidateMaterials(d);
            int idx = raw - cs;
            if (idx >= cands.size()) return;
            Material mat = cands.get(idx);
            if (click == ClickType.RIGHT) {
                if (!d.isAutoSellItem(mat)) actions.toggleAutoSellItem(m, mat);
                actions.promptAutoSellItemRate(m, p, mat);
            } else {
                actions.toggleAutoSellItem(m, mat); refresh(m);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof ChunkCollectorMenuHolder)) return;
        for (int raw : e.getRawSlots()) if (raw < SIZE) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof ChunkCollectorMenuHolder h)) return;
        List<Inventory> list = open.get(h.machine().id());
        if (list != null) {
            list.remove(e.getInventory());
            if (list.isEmpty()) { open.remove(h.machine().id()); lastRefresh.remove(h.machine().id()); }
        }
    }

    // --- helpers ------------------------------------------------------------

    private ChunkCollectorData collector(Machine m) { return m.chunkCollector(); }

    private ItemStack upgradeButton(MenuSkin sk, Machine m, MachineType type, ChunkCollectorSpec spec) {
        dev.servereer.machineconstruct.machine.Tiers t = type == null ? null : type.tiers();
        int tier = m.tier();
        int max = t == null ? 1 : t.max();
        if (t == null || tier >= max) {
            return sk.item("upgrade_max", Material.NETHER_STAR, "<aqua>Tier {tier} <dark_gray>(max)", List.of("<dark_gray>Fully upgraded"), vars("tier", tier));
        }
        ItemStack req = t.upgradeItem();
        int need = req == null ? 1 : Math.max(1, req.getAmount());
        Material mat = req == null ? Material.NETHER_STAR : req.getType();
        Map<String, String> v = vars("tier", tier, "next", tier + 1, "need", need, "item", pretty(mat),
                "range", ring(spec.radiusChunks(tier)), "range_next", ring(spec.radiusChunks(tier + 1)),
                "cap", spec.capacity(tier), "cap_next", spec.capacity(tier + 1));
        return sk.item("upgrade", mat, "<light_purple>Upgrade → Tier {next}",
                List.of("<gray>Range: <white>{range}</white> → <white>{range_next}",
                        "<gray>Vault cap: <white>{cap}</white> → <white>{cap_next}",
                        "<gray>Requires <white>{need}× {item}</white> in your inventory",
                        "<dark_gray>▶ Click to upgrade to Tier {next}"), v);
    }

    private static String ring(int radius) {
        int side = radius * 2 + 1;
        return side + "×" + side + " chunks";
    }

    private List<Material> candidateMaterials(ChunkCollectorData d) {
        LinkedHashSet<Material> set = new LinkedHashSet<>();
        for (ItemStack t : d.vault().keySet()) set.add(t.getType());
        return new ArrayList<>(set);
    }

    private double vaultValue(ChunkCollectorData d, PriceService prices) {
        if (prices == null) return 0;
        double v = 0;
        for (Map.Entry<ItemStack, Long> e : d.vault().entrySet()) v += prices.price(e.getKey()) * e.getValue();
        return v;
    }

    private List<Map.Entry<ItemStack, Long>> sortedFiltered(ChunkCollectorData d, int tab, ChunkCollectorMenuHolder.Sort sort, PriceService prices) {
        List<Map.Entry<ItemStack, Long>> list = new ArrayList<>();
        for (Map.Entry<ItemStack, Long> e : d.vault().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            if (tab != 0 && category(e.getKey().getType()) != tab) continue;
            list.add(e);
        }
        Comparator<Map.Entry<ItemStack, Long>> cmp = switch (sort) {
            case VALUE -> Comparator.comparingDouble((Map.Entry<ItemStack, Long> e) ->
                    (prices == null ? 0 : prices.price(e.getKey())) * e.getValue()).reversed();
            case NAME -> Comparator.comparing(e -> e.getKey().getType().name());
            default -> Comparator.comparingLong((Map.Entry<ItemStack, Long> e) -> e.getValue()).reversed();
        };
        list.sort(cmp);
        return list;
    }

    private int category(Material mat) {
        String n = mat.name();
        if (mat == Material.DIAMOND || mat == Material.EMERALD || n.startsWith("AMETHYST") || mat == Material.NETHER_STAR
                || mat == Material.LAPIS_LAZULI || mat == Material.QUARTZ) return 2;
        if (n.startsWith("RAW_") || n.endsWith("_INGOT") || n.endsWith("_NUGGET") || mat == Material.COAL
                || mat == Material.REDSTONE || mat == Material.GLOWSTONE_DUST || mat == Material.ANCIENT_DEBRIS || mat == Material.NETHERITE_SCRAP) return 1;
        if (mat.isBlock()) return 3;
        return 4;
    }

    private static ChunkCollectorMenuHolder.Sort nextSort(ChunkCollectorMenuHolder.Sort s) {
        return switch (s) {
            case QTY -> ChunkCollectorMenuHolder.Sort.VALUE;
            case VALUE -> ChunkCollectorMenuHolder.Sort.NAME;
            case NAME -> ChunkCollectorMenuHolder.Sort.QTY;
        };
    }

    private Component title(Machine m, ChunkCollectorMenuHolder.View view) {
        MenuSkin sk = skinOf(m);
        String s = switch (view) {
            case MAIN -> sk.title("main", "<dark_aqua>✦ Chunk Collector");
            case LOG -> sk.title("log", "<dark_aqua>✦ Collector · Log");
            case CHESTS -> sk.title("chests", "<dark_aqua>✦ Collector · Output Chests");
            case CHEST_FILTER -> sk.title("chest_filter", "<dark_aqua>✦ Collector · Chest Filter");
            case AUTOSELL -> sk.title("autosell", "<dark_aqua>✦ Collector · Auto-Sell");
        };
        return MenuSkin.mini(s);
    }

    private static String pretty(Material mat) {
        String[] parts = mat.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return sb.toString().trim();
    }

    private static String prettyItem(ItemStack item) {
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName())
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        return pretty(item.getType());
    }

    /** Chat brand: the skin's prefix (MiniMessage) + the message. */
    private static Component brand(MenuSkin sk, String s) {
        return MenuSkin.mini(sk.prefix("<aqua>Collector <dark_gray>» ") + s);
    }

    private static String trim(double d) {
        return (d == Math.floor(d)) ? Long.toString((long) d) : String.format("%.2f", d);
    }
}
