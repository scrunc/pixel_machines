package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.api.MachineConstructAPI;
import dev.servereer.machineconstruct.grinder.ChestLink;
import dev.servereer.machineconstruct.grinder.GrinderActions;
import dev.servereer.machineconstruct.grinder.GrinderData;
import dev.servereer.machineconstruct.grinder.GrinderSpec;
import dev.servereer.machineconstruct.grinder.econ.PriceService;
import dev.servereer.machineconstruct.grinder.InstalledSpawner;
import dev.servereer.machineconstruct.grinder.LootTable;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.servereer.machineconstruct.gui.MenuSkin.vars;

/**
 * The Dimensional Grinder menu — a fully code-rendered, paginated, sealed GUI
 * (no authored {@link GuiLayout}). Same un-glitchable discipline as
 * {@link MachineMenus}: every click touching the window is cancelled and the
 * intended action performed deterministically via {@link GrinderActions}.
 *
 * <p>Views: MAIN (spawner grid + control bar), LOG (action history), BUY (catalog).
 *
 * <p>Look and wording come from the machine's {@link MenuSkin} ({@code skin:} in the
 * machine YAML): every slot, title, filler, button (material/name/lore) and chat line
 * below is a skin lookup whose default is the built-in text, so a machine without a
 * skin renders exactly as it always did. Handlers read the same lookups, so moving a
 * button in YAML moves its click too.
 */
public final class GrinderMenus implements Listener {

    private static final int SIZE = 54;          // 6 rows
    private static final int[] DEF_SLOTS = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33};   // 10 spawner slots (rows 3-4)
    private static final int[] DEF_TABS = {0, 1, 2, 3, 4};

    private final Plugin plugin;
    private final MachineConstructAPI registry;
    private final GrinderActions actions;
    private final Map<UUID, List<Inventory>> open = new HashMap<>();   // machine id → every open window (multi-viewer)
    private final Map<UUID, Long> lastRefresh = new HashMap<>();        // machine id → last repaint (sweep throttle)
    private static final long REFRESH_THROTTLE_MS = 1500;

    public GrinderMenus(Plugin plugin, MachineConstructAPI registry, GrinderActions actions) {
        this.plugin = plugin;
        this.registry = registry;
        this.actions = actions;
    }

    public void open(Player player, Machine machine) { openTo(player, machine, GrinderMenuHolder.View.MAIN); }

    public void openTo(Player player, Machine machine, GrinderMenuHolder.View view) {
        actions.accrue(machine);
        actions.persist(machine);
        GrinderMenuHolder holder = new GrinderMenuHolder(machine, view, 0);
        Inventory inv = Bukkit.createInventory(holder, SIZE, title(machine, holder.view()));
        holder.setInventory(inv);
        open.computeIfAbsent(machine.id(), k -> new ArrayList<>()).add(inv);
        render(inv, holder);
        player.openInventory(inv);
    }

    /** Immediate repaint of every open window for a machine (used after a click). */
    public void refresh(Machine machine) {
        List<Inventory> list = open.get(machine.id());
        if (list == null) return;
        list.removeIf(inv -> inv.getViewers().isEmpty());
        if (list.isEmpty()) { open.remove(machine.id()); lastRefresh.remove(machine.id()); return; }
        lastRefresh.put(machine.id(), System.currentTimeMillis());   // any repaint defers the next sweep repaint
        for (Inventory inv : list) if (inv.getHolder() instanceof GrinderMenuHolder h) render(inv, h);
    }

    /** Sweep-driven repaint: skipped within the throttle window so the processing tick
     *  never stomps a player who is actively clicking. */
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
            for (Inventory inv : new ArrayList<>(list))   // snapshot: closeInventory() fires onClose → list.remove() (CME otherwise)
                for (HumanEntity v : new ArrayList<>(inv.getViewers())) v.closeInventory();
        open.clear();
    }

    private MachineType typeOf(Machine m) {
        return registry == null ? null : registry.getMachineType(m.typeId());
    }

    private MenuSkin skinOf(Machine m) {
        MachineType t = typeOf(m);
        return t == null ? MenuSkin.EMPTY : t.skin();
    }

    // --- slot map (skin-overridable; defaults = the historic layout) --------

    private static int contentStart(MenuSkin sk) { return sk.slot("content", "start", 9); }
    private static int contentEnd(MenuSkin sk) { return sk.slot("content", "end", 36); }     // exclusive
    private static int perPage(MenuSkin sk) { return Math.max(1, contentEnd(sk) - contentStart(sk)); }

    // MAIN
    private static int sPrev(MenuSkin sk) { return sk.slot("main", "prev", 45); }
    private static int sUpgrade(MenuSkin sk) { return sk.slot("main", "upgrade", 46); }
    private static int sCollect(MenuSkin sk) { return sk.slot("main", "collect", 47); }
    private static int sPool(MenuSkin sk) { return sk.slot("main", "pool", 48); }
    private static int sXp(MenuSkin sk) { return sk.slot("main", "xp", 49); }
    private static int sSell(MenuSkin sk) { return sk.slot("main", "sell", 50); }
    private static int sBuy(MenuSkin sk) { return sk.slot("main", "buy", 51); }
    private static int sLog(MenuSkin sk) { return sk.slot("main", "log", 52); }
    private static int sNext(MenuSkin sk) { return sk.slot("main", "next", 53); }
    private static int sInfo(MenuSkin sk) { return sk.slot("main", "info", 4); }
    private static int sAutoSell(MenuSkin sk) { return sk.slot("main", "autosell", 2); }
    private static int sChests(MenuSkin sk) { return sk.slot("main", "chests", 6); }
    private static int sInsert(MenuSkin sk) { return sk.slot("main", "insert", 8); }
    private static int[] spawnerSlots(MenuSkin sk) { return sk.slots("main", "spawners", DEF_SLOTS); }
    // sub views (LOG / BUY / DETAIL) share prev/next/back/info
    private static int subPrev(MenuSkin sk) { return sk.slot("sub", "prev", 45); }
    private static int subNext(MenuSkin sk) { return sk.slot("sub", "next", 53); }
    private static int subBack(MenuSkin sk) { return sk.slot("sub", "back", 52); }
    private static int subInfo(MenuSkin sk) { return sk.slot("sub", "info", 4); }
    private static int subEmpty(MenuSkin sk) { return sk.slot("sub", "empty", 22); }
    private static int dCollect(MenuSkin sk) { return sk.slot("detail", "collect", 20); }
    private static int dRemoveOne(MenuSkin sk) { return sk.slot("detail", "remove_one", 22); }
    private static int dRemoveAll(MenuSkin sk) { return sk.slot("detail", "remove_all", 24); }
    // AUTOSELL
    private static int asToggle(MenuSkin sk) { return sk.slot("autosell", "toggle", 45); }
    private static int asAll(MenuSkin sk) { return sk.slot("autosell", "all", 47); }
    private static int asClear(MenuSkin sk) { return sk.slot("autosell", "clear", 49); }
    private static int asBack(MenuSkin sk) { return sk.slot("autosell", "back", 53); }
    private static int asInfo(MenuSkin sk) { return sk.slot("autosell", "info", 4); }
    // CHESTS / CHEST_FILTER
    private static int cAddOut(MenuSkin sk) { return sk.slot("chests", "add_out", 47); }
    private static int cAddIn(MenuSkin sk) { return sk.slot("chests", "add_in", 49); }
    private static int cBack(MenuSkin sk) { return sk.slot("chests", "back", 51); }
    private static int cInfo(MenuSkin sk) { return sk.slot("chests", "info", 4); }
    private static int fClear(MenuSkin sk) { return sk.slot("chest_filter", "clear", 47); }
    private static int fBack(MenuSkin sk) { return sk.slot("chest_filter", "back", 51); }
    private static int fInfo(MenuSkin sk) { return sk.slot("chest_filter", "info", 4); }
    // POOL
    private static int[] pTabs(MenuSkin sk) { return sk.slots("pool", "tabs", DEF_TABS); }
    private static int pValue(MenuSkin sk) { return sk.slot("pool", "value", 8); }
    private static int pPrev(MenuSkin sk) { return sk.slot("pool", "prev", 45); }
    private static int pBack(MenuSkin sk) { return sk.slot("pool", "back", 46); }
    private static int pCollect(MenuSkin sk) { return sk.slot("pool", "collect", 48); }
    private static int pSort(MenuSkin sk) { return sk.slot("pool", "sort", 49); }
    private static int pSell(MenuSkin sk) { return sk.slot("pool", "sell", 50); }
    private static int pNext(MenuSkin sk) { return sk.slot("pool", "next", 53); }

    // --- rendering ----------------------------------------------------------

    private void render(Inventory inv, GrinderMenuHolder h) {
        inv.clear();
        switch (h.view()) {
            case MAIN -> renderMain(inv, h);
            case LOG -> renderLog(inv, h);
            case BUY -> renderBuy(inv, h);
            case DETAIL -> renderDetail(inv, h);
            case POOL -> renderPool(inv, h);
            case CHESTS -> renderChests(inv, h);
            case CHEST_FILTER -> renderChestFilter(inv, h);
            case AUTOSELL -> renderAutoSell(inv, h);
        }
    }

    /** Fill the whole window with the view's filler pane. */
    private void fillAll(Inventory inv, MenuSkin sk, String view, Material def) {
        ItemStack edge = sk.item("filler", sk.filler(view, def), " ", List.of(), null);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, edge);
        sk.paintAccents(view, (slot, pane) -> { if (slot >= 0 && slot < SIZE) inv.setItem(slot, pane); });
    }

    /** Fill only the frame (outside the content rows) with the view's filler pane. */
    private void fillFrame(Inventory inv, MenuSkin sk, String view, Material def) {
        ItemStack edge = sk.item("filler", sk.filler(view, def), " ", List.of(), null);
        int cs = contentStart(sk), ce = contentEnd(sk);
        for (int i = 0; i < cs; i++) inv.setItem(i, edge);
        for (int i = ce; i < SIZE; i++) inv.setItem(i, edge);
        sk.paintAccents(view, (slot, pane) -> { if (slot >= 0 && slot < SIZE && (slot < cs || slot >= ce)) inv.setItem(slot, pane); });
    }

    private void renderMain(Inventory inv, GrinderMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        GrinderData d = m.grinder();
        GrinderSpec spec = type == null ? null : type.grinder();
        if (d == null || spec == null) return;
        MenuSkin sk = type.skin();

        fillAll(inv, sk, "main", Material.BLACK_STAINED_GLASS_PANE);

        inv.setItem(sInfo(sk), infoItem(sk, m, d, spec));
        int outs = 0, ins = 0;
        for (ChestLink l : d.links())
            if (l.type() == ChestLink.Type.OUTPUT) outs++; else ins++;
        boolean asOn = d.autoSellEnabled();
        Map<String, String> v = vars("rules", d.autoSellRules().size(), "outs", outs, "ins", ins);
        inv.setItem(sAutoSell(sk), asOn
                ? sk.item("autosell_on", Material.GOLD_BLOCK, "<gold>Auto-Sell: <green>ON",
                        List.of("<gray>Auto-sells chosen loot for money (per item).",
                                "<gray>Items set: <white>{rules}",
                                "<dark_gray>Paid out hourly.",
                                "<dark_gray>▶ Click to configure"), v)
                : sk.item("autosell_off", Material.GOLD_NUGGET, "<gold>Auto-Sell: <red>OFF",
                        List.of("<gray>Auto-sells chosen loot for money (per item).",
                                "<gray>Items set: <white>{rules}",
                                "<dark_gray>Paid out hourly.",
                                "<dark_gray>▶ Click to configure"), v));
        inv.setItem(sChests(sk), sk.item("chests", Material.HOPPER, "<aqua>I/O / Saved Chests",
                List.of("<gray>Link chests in range to auto-route loot <green>out</green>,",
                        "<gray>or pull items <aqua>in</aqua> to the sellable pool.",
                        "<gray>Linked: <green>{outs} out</green> · <aqua>{ins} in",
                        "<dark_gray>▶ Click to manage"), v));
        inv.setItem(sInsert(sk), sk.item("insert", Material.SPAWNER, "<green>Insert spawner",
                List.of("<gray>Hold a spawner on your cursor &amp; click a slot,",
                        "<gray>or click here to insert one",
                        "<dark_gray>▶ Click"), null));
        inv.setItem(sUpgrade(sk), upgradeButton(sk, m, type));

        // fixed spawner slots on row 3 — tier upgrades only raise capacity, never slot count (no locking)
        List<InstalledSpawner> list = d.spawners();
        int[] slots = spawnerSlots(sk);
        for (int i = 0; i < slots.length; i++) {
            if (i < list.size()) inv.setItem(slots[i], spawnerIcon(sk, list.get(i), type, d, spec, m.tier()));
            else inv.setItem(slots[i], emptySlot(sk));
        }

        // control bar
        Map<String, String> pv = vars("items", d.pooledItems(), "xp", d.storedExp());
        inv.setItem(sCollect(sk), sk.item("collect", Material.HOPPER, "<green>Collect all",
                List.of("<gray>Pool all buffered loot + XP", "<dark_gray>▶ Click to collect"), pv));
        inv.setItem(sPool(sk), sk.item("pool", Material.CHEST, "<aqua>Open pool",
                List.of("<gray>In pool: <white>{items} items",
                        "<gray>Browse, filter & pick items one by one",
                        "<dark_gray>▶ Click to open"), pv));
        inv.setItem(sXp(sk), sk.item("xp", Material.EXPERIENCE_BOTTLE, "<green>Claim XP",
                List.of("<gray>Stored: <white>{xp} XP",
                        "<gray>Repairs Mending gear as you claim.",
                        "<yellow>◀ Left: claim all   <gold>▶ Right: a little"), pv));
        inv.setItem(sSell(sk), sk.item("sell", Material.SUNFLOWER, "<gold>Sell all",
                List.of("<gray>Sell the collected pool", "<dark_gray>▶ Click to sell"), pv));
        inv.setItem(sBuy(sk), sk.item("buy", Material.SPAWNER, "<light_purple>Buy spawner",
                List.of("<gray>Open the spawner catalog", "<dark_gray>▶ Click to browse"), null));
        inv.setItem(sLog(sk), sk.item("log", Material.WRITABLE_BOOK, "<yellow>Activity log",
                List.of("<gray>Recent grinder events", "<dark_gray>▶ Click to view"), null));
    }

    private void renderLog(Inventory inv, GrinderMenuHolder h) {
        Machine m = h.machine();
        GrinderData d = m.grinder();
        MenuSkin sk = skinOf(m);
        fillFrame(inv, sk, "log", Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(subInfo(sk), sk.item("log_title", Material.WRITABLE_BOOK, "<yellow><bold>Activity Log", List.of(), null));

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
        if (lines.isEmpty()) inv.setItem(subEmpty(sk), sk.item("log_empty", Material.PAPER, "<gray>No activity yet", List.of(), null));
        if (h.page() > 0) inv.setItem(subPrev(sk), sk.item("prev", Material.ARROW, "<yellow>◀ Previous page", List.of(), null));
        if (h.page() < pages - 1) inv.setItem(subNext(sk), sk.item("next", Material.ARROW, "<yellow>Next page ▶", List.of(), null));
        inv.setItem(subBack(sk), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>Return to the grinder"), null));
    }

    private void renderBuy(Inventory inv, GrinderMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        GrinderSpec spec = type == null ? null : type.grinder();
        MenuSkin sk = skinOf(m);
        fillFrame(inv, sk, "buy", Material.PURPLE_STAINED_GLASS_PANE);
        inv.setItem(subInfo(sk), sk.item("buy_title", Material.SPAWNER, "<light_purple><bold>Spawner Catalog", List.of(), null));
        if (spec == null) return;

        int cs = contentStart(sk), per = perPage(sk);
        List<GrinderSpec.CatalogEntry> entries = new ArrayList<>(spec.catalog().values());
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) per));
        h.setPage(Math.min(h.page(), pages - 1));
        int start = h.page() * per;
        for (int i = 0; i < per; i++) {
            int idx = start + i;
            if (idx >= entries.size()) break;
            inv.setItem(cs + i, catalogIcon(sk, entries.get(idx), type));
        }
        if (h.page() > 0) inv.setItem(subPrev(sk), sk.item("prev", Material.ARROW, "<yellow>◀ Previous page", List.of(), null));
        if (h.page() < pages - 1) inv.setItem(subNext(sk), sk.item("next", Material.ARROW, "<yellow>Next page ▶", List.of(), null));
        inv.setItem(subBack(sk), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>Return to the grinder"), null));
    }

    private void renderDetail(Inventory inv, GrinderMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        GrinderData d = m.grinder();
        GrinderSpec spec = type == null ? null : type.grinder();
        MenuSkin sk = skinOf(m);
        fillAll(inv, sk, "detail", Material.BLACK_STAINED_GLASS_PANE);
        if (d == null || spec == null) return;
        int idx = h.detailSlot();
        InstalledSpawner s = (idx >= 0 && idx < d.spawners().size()) ? d.spawners().get(idx) : null;
        if (s == null) { h.setView(GrinderMenuHolder.View.MAIN); renderMain(inv, h); return; }

        inv.setItem(subInfo(sk), spawnerIcon(sk, s, type, d, spec, m.tier()));
        Map<String, String> v = vars("stack", s.stackSize(), "mob", LootTable.pretty(s.type()));
        inv.setItem(dCollect(sk), sk.item("detail_collect", Material.HOPPER, "<green>Collect this spawner",
                List.of("<gray>Pool this spawner's buffer + XP", "<dark_gray>▶ uses the global Collect"), v));
        inv.setItem(dRemoveOne(sk), sk.item("detail_remove_one", Material.REDSTONE, "<yellow>Remove 1",
                List.of("<gray>Return 1 spawner to your inventory", "<dark_gray>▶ Click"), v));
        inv.setItem(dRemoveAll(sk), sk.item("detail_remove_all", Material.TNT, "<red>Remove all (×{stack})",
                List.of("<gray>Return the whole stack as spawner items", "<dark_gray>▶ Click"), v));
        inv.setItem(subBack(sk), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>Return to the grinder"), null));
    }

    // --- CHESTS (saved input/output chests) ---------------------------------

    private void renderChests(Inventory inv, GrinderMenuHolder h) {
        Machine m = h.machine();
        GrinderData d = m.grinder();
        MenuSkin sk = skinOf(m);
        fillFrame(inv, sk, "chests", Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(cInfo(sk), sk.item("chests_title", Material.HOPPER, "<aqua><bold>Saved Chests",
                List.of("<gray>OUTPUT chests receive routed loot.",
                        "<gray>INPUT chests feed items into the pool.",
                        "<dark_gray>Reach grows as the grinder is upgraded."), null));
        int cs = contentStart(sk), per = perPage(sk);
        if (d != null) {
            List<ChestLink> links = d.links();
            for (int i = 0; i < links.size() && i < per; i++) inv.setItem(cs + i, linkCard(sk, links.get(i), i));
            if (links.isEmpty()) inv.setItem(subEmpty(sk), sk.item("chests_empty", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>No linked chests yet",
                    List.of("<dark_gray>Use the buttons below"), null));
        }
        inv.setItem(cAddOut(sk), sk.item("add_out", Material.CHEST, "<green>+ Add OUTPUT chest",
                List.of("<gray>Routes loot into a chest.", "<dark_gray>▶ Click, then click a chest in range"), null));
        inv.setItem(cAddIn(sk), sk.item("add_in", Material.TRAPPED_CHEST, "<aqua>+ Add INPUT chest",
                List.of("<gray>Pulls items from it into the pool.", "<dark_gray>▶ Click, then click a chest in range"), null));
        inv.setItem(cBack(sk), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>Return to the grinder"), null));
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

    private void renderChestFilter(Inventory inv, GrinderMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        GrinderData d = m.grinder();
        MenuSkin sk = skinOf(m);
        fillFrame(inv, sk, "chest_filter", Material.BLACK_STAINED_GLASS_PANE);
        int idx = h.linkIndex();
        ChestLink link = (d != null && idx >= 0 && idx < d.links().size()) ? d.links().get(idx) : null;
        if (link == null) { h.setView(GrinderMenuHolder.View.CHESTS); renderChests(inv, h); return; }
        boolean inFilter = link.type() == ChestLink.Type.INPUT;
        inv.setItem(fInfo(sk), inFilter
                ? sk.item("filter_title_in", Material.HOPPER, "<aqua><bold>Input Filter",
                        List.of("<gray>Click items to pull ONLY those.", "<gray>Empty filter = pull everything."), null)
                : sk.item("filter_title_out", Material.HOPPER, "<aqua><bold>Output Filter",
                        List.of("<gray>Click items to route ONLY those.", "<gray>Empty filter = route everything."), null));
        int cs = contentStart(sk), per = perPage(sk);
        List<Material> cands = candidateMaterials(d, type);
        for (int i = 0; i < cands.size() && i < per; i++) {
            Material mat = cands.get(i);
            boolean on = link.filter().contains(mat);
            ItemStack it = new ItemStack(mat.isItem() ? mat : Material.PAPER);
            Map<String, String> v = vars("item", pretty(mat));
            it = on ? sk.decorate("filter_item_on", it, "<green>✔ {item}", List.of("<green>routing this", "<dark_gray>▶ click to toggle"), v)
                    : sk.decorate("filter_item_off", it, "<gray>{item}", List.of("<dark_gray>not routed", "<dark_gray>▶ click to toggle"), v);
            if (on) glint(it);
            inv.setItem(cs + i, it);
        }
        if (cands.isEmpty()) inv.setItem(subEmpty(sk), sk.item("filter_empty", Material.BARRIER, "<gray>No known loot yet", List.of("<dark_gray>install spawners first"), null));
        inv.setItem(fClear(sk), sk.item("filter_clear", Material.HOPPER, "<yellow>Route everything", List.of("<gray>Clear the filter", "<dark_gray>▶ Click"), null));
        inv.setItem(fBack(sk), sk.item("filter_back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>to Saved Chests"), null));
    }

    private static void glint(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(meta);
    }

    private List<Material> candidateMaterials(GrinderData d, MachineType type) {
        java.util.LinkedHashSet<Material> set = new java.util.LinkedHashSet<>();
        LootTable loot = type == null ? null : type.loot();
        if (loot != null) for (InstalledSpawner s : d.spawners()) {
            LootTable.MobLoot ml = loot.get(s.type());
            if (ml != null) for (LootTable.LootDrop drop : ml.drops()) { ItemStack t = drop.template(); if (t != null) set.add(t.getType()); }
        }
        for (ItemStack t : d.pool().keySet()) set.add(t.getType());
        return new ArrayList<>(set);
    }

    // --- AUTO-SELL ----------------------------------------------------------

    private void renderAutoSell(Inventory inv, GrinderMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        GrinderData d = m.grinder();
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
        List<Material> cands = candidateMaterials(d, type);
        for (int i = 0; i < cands.size() && i < per; i++) {
            Material mat = cands.get(i);
            boolean on = d.isAutoSellItem(mat);
            ItemStack it = new ItemStack(mat.isItem() ? mat : Material.PAPER);
            Map<String, String> v = vars("item", pretty(mat), "rate", d.autoSellItemFull(mat) ? "all" : d.autoSellRate(mat) + "/sec");
            it = on ? sk.decorate("autosell_item_on", it, "<green>✔ {item}",
                            List.of("<green>auto-selling @ <white>{rate}", "<yellow>◀ Left: stop   <gold>▶ Right: set rate"), v)
                    : sk.decorate("autosell_item_off", it, "<gray>{item}",
                            List.of("<dark_gray>not auto-selling", "<dark_gray>▶ Left-click to enable"), v);
            if (on) glint(it);
            inv.setItem(cs + i, it);
        }
        if (cands.isEmpty()) inv.setItem(subEmpty(sk), sk.item("autosell_empty", Material.BARRIER, "<gray>No known loot yet", List.of("<dark_gray>install spawners first"), null));
        inv.setItem(asToggle(sk), d.autoSellEnabled()
                ? sk.item("autosell_toggle_on", Material.LIME_DYE, "<green>Auto-Sell ON", List.of("<dark_gray>▶ master switch"), null)
                : sk.item("autosell_toggle_off", Material.GRAY_DYE, "<red>Auto-Sell OFF", List.of("<dark_gray>▶ master switch"), null));
        inv.setItem(asAll(sk), sk.item("autosell_all", Material.HOPPER, "<aqua>Auto-sell EVERY item", List.of("<gray>Enable all loot at rate 'all'", "<dark_gray>▶ click"), null));
        inv.setItem(asClear(sk), sk.item("autosell_clear", Material.REDSTONE, "<red>Stop selling all", List.of("<gray>Clear every per-item rule", "<dark_gray>▶ click"), null));
        inv.setItem(asBack(sk), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>Return to the grinder"), null));
    }

    private void onAutoSellClick(Player p, GrinderMenuHolder h, Machine m, int raw, ClickType click) {
        MenuSkin sk = skinOf(m);
        if (raw == asBack(sk)) { h.setView(GrinderMenuHolder.View.MAIN); refresh(m); return; }
        if (raw == asToggle(sk)) {
            actions.toggleAutoSell(m); refresh(m);
            p.sendMessage(brand(sk, m.grinder().autoSellEnabled()
                    ? sk.msg("autosell_on", "<green>Auto-Sell enabled.", null)
                    : sk.msg("autosell_off", "<yellow>Auto-Sell disabled.", null)));
            return;
        }
        if (raw == asAll(sk)) {   // enable every candidate
            GrinderData dd = m.grinder();
            if (dd != null) for (Material mat : candidateMaterials(dd, typeOf(m))) if (!dd.isAutoSellItem(mat)) actions.toggleAutoSellItem(m, mat);
            refresh(m); return;
        }
        if (raw == asClear(sk)) { actions.clearAutoSellItems(m); refresh(m); return; }
        int cs = contentStart(sk), ce = contentEnd(sk);
        if (raw >= cs && raw < ce) {
            GrinderData d = m.grinder();
            if (d == null) return;
            List<Material> cands = candidateMaterials(d, typeOf(m));
            int idx = raw - cs;
            if (idx >= cands.size()) return;
            Material mat = cands.get(idx);
            if (click == ClickType.RIGHT) {
                if (!d.isAutoSellItem(mat)) actions.toggleAutoSellItem(m, mat);   // enable first
                actions.promptAutoSellItemRate(m, p, mat);                        // then prompt its rate
            } else {
                actions.toggleAutoSellItem(m, mat); refresh(m);
            }
        }
    }

    /** The upgrade button for the control bar (consumes the tier item on click). */
    private ItemStack upgradeButton(MenuSkin sk, Machine m, MachineType type) {
        dev.servereer.machineconstruct.machine.Tiers t = type == null ? null : type.tiers();
        int tier = m.tier();
        int max = t == null ? 1 : t.max();
        if (t == null || tier >= max) {
            return sk.item("upgrade_max", Material.NETHER_STAR, "<aqua>Tier {tier} <dark_gray>(max)",
                    List.of("<dark_gray>Fully upgraded"), vars("tier", tier));
        }
        ItemStack req = t.upgradeItem();
        int need = req == null ? 1 : Math.max(1, req.getAmount());
        Material mat = req == null ? Material.NETHER_STAR : req.getType();
        GrinderSpec spec = type.grinder();
        Map<String, String> v = vars("tier", tier, "next", tier + 1, "need", need, "item", pretty(mat),
                "stack", spec == null ? "" : spec.stackCap(tier), "stack_next", spec == null ? "" : spec.stackCap(tier + 1),
                "buffer", spec == null ? "" : (long) spec.itemCap(tier, Map.of()), "buffer_next", spec == null ? "" : (long) spec.itemCap(tier + 1, Map.of()));
        List<String> lore = new ArrayList<>();
        if (spec != null) lore.add("<gray>Capacity: stack <white>×{stack}</white> → <white>×{stack_next}</white> · buffer <white>{buffer}</white> → <white>{buffer_next}");
        lore.add("<gray>Requires <white>{need}× {item}</white> in your inventory");
        lore.add("<dark_gray>▶ Click to upgrade to Tier {next}");
        return sk.item("upgrade", mat, "<light_purple>Upgrade → Tier {next}", lore, v);
    }

    private static String pretty(Material mat) {
        String[] parts = mat.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return sb.toString().trim();
    }

    private ItemStack infoItem(MenuSkin sk, Machine m, GrinderData d, GrinderSpec spec) {
        long buffered = 0;
        for (InstalledSpawner s : d.spawners()) buffered += s.collectableItems();
        Map<String, String> v = vars("tier", m.tier(), "used", d.slotsUsed(), "slots", spec.slots(m.tier()),
                "stack", spec.stackCap(m.tier()), "items", d.pooledItems(), "xp", d.storedExp(), "buffered", buffered);
        return sk.item("info", Material.NETHER_STAR, "<light_purple><bold>Dimensional Grinder",
                List.of("<gray>Tier <white>{tier}</white> · slots <white>{used}</white>/<white>{slots}</white> · stack cap <white>×{stack}",
                        "<gray>Pool: <white>{items}</white> items · <white>{xp}</white> XP",
                        "<gray>Buffered (uncollected): <white>{buffered}</white> items",
                        "<dark_gray>Generates over time — works offline."), v);
    }

    private ItemStack spawnerIcon(MenuSkin sk, InstalledSpawner s, MachineType type, GrinderData d, GrinderSpec spec, int tier) {
        LootTable loot = type.loot();
        LootTable.MobLoot ml = loot == null ? null : loot.get(s.type());
        ItemStack icon = ml != null ? ml.icon() : new ItemStack(Material.SPAWNER);
        double cap = spec.itemCap(tier, d.cores());
        double pct = cap <= 0 ? 0 : Math.min(100, 100.0 * s.bufferedItems() / cap);
        Map<String, String> v = vars("mob", LootTable.pretty(s.type()), "stack", s.stackSize(), "stack_cap", spec.stackCap(tier),
                "buffered", s.collectableItems(), "cap", (long) cap, "pct", (long) pct, "xp", (long) s.accruedExp());
        return sk.decorate("spawner", icon, "<aqua>{mob}",
                List.of("<gray>Stack: <white>×{stack}</white> / <white>{stack_cap}",
                        "<gray>Buffered: <white>{buffered}</white> / <white>{cap}</white> <dark_gray>({pct}%)",
                        "<gray>XP: <white>{xp}",
                        "<dark_gray>▶ Click for details"), v);
    }

    /** An empty, unlocked slot. */
    private ItemStack emptySlot(MenuSkin sk) {
        return sk.item("empty_slot", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>Empty slot",
                List.of("<dark_gray>Buy or insert a spawner"), null);
    }

    private ItemStack catalogIcon(MenuSkin sk, GrinderSpec.CatalogEntry e, MachineType type) {
        LootTable loot = type.loot();
        LootTable.MobLoot ml = loot == null ? null : loot.get(e.type);
        ItemStack icon = ml != null ? ml.icon() : new ItemStack(Material.SPAWNER);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Price: <gold>{price}");
        if (e.permission != null) lore.add("<dark_gray>Requires: {perm}");
        lore.add("<dark_gray>▶ Click to buy");
        return sk.decorate("catalog", icon, "<light_purple>{mob} Spawner", lore,
                vars("mob", LootTable.pretty(e.type), "price", trim(e.price), "perm", e.permission == null ? "" : e.permission));
    }

    // --- events -------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GrinderMenuHolder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) { e.setCancelled(true); return; }
        int raw = e.getRawSlot();

        // Swallow double-clicks: a fast double on a view-switch button (e.g. "Open pool" at 48)
        // would land its 2nd hit on the new view's action at the same slot ("Collect all" at 48).
        if (e.getClick() == ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }

        // Clicks in the player's own inventory: only block siphon actions into the menu.
        if (raw < 0 || raw >= SIZE) {
            if (e.getClick() == ClickType.DOUBLE_CLICK || e.isShiftClick()) e.setCancelled(true);
            return;
        }

        e.setCancelled(true);
        Machine m = h.machine();
        try {
            switch (h.view()) {
                case MAIN -> onMainClick(p, h, m, raw, e.getClick());
                case LOG, BUY -> onSubClick(p, h, m, raw);
                case DETAIL -> onDetailClick(p, h, m, raw);
                case POOL -> onPoolClick(p, h, m, raw, e.getClick());
                case CHESTS -> onChestsClick(p, h, m, raw, e.getClick());
                case CHEST_FILTER -> onChestFilterClick(p, h, m, raw);
                case AUTOSELL -> onAutoSellClick(p, h, m, raw, e.getClick());
            }
        } catch (Throwable t) {
            refresh(m);
            p.updateInventory();
        }
    }

    private void onMainClick(Player p, GrinderMenuHolder h, Machine m, int raw, ClickType click) {
        MenuSkin sk = skinOf(m);
        if (raw == sPrev(sk)) { h.setPage(h.page() - 1); refresh(m); }
        else if (raw == sNext(sk)) { h.setPage(h.page() + 1); refresh(m); }
        else if (raw == sCollect(sk)) {
            long[] got = actions.collectAll(m);
            actions.persist(m);
            refresh(m);
            p.sendMessage(brand(sk, sk.msg("collected", "<green>Collected <white>{items}</white> items and <white>{xp}</white> XP into the pool.",
                    vars("items", got[0], "xp", got[1]))));
        }
        else if (raw == sPool(sk)) { h.setView(GrinderMenuHolder.View.POOL); h.setPage(0); refresh(m); }
        else if (raw == sXp(sk)) {
            long want = click.isRightClick() ? 100 : Long.MAX_VALUE;   // right = a little (Mending top-up), left = all
            long xp = actions.claimXp(m, p, want);
            actions.persist(m);
            refresh(m);
            p.sendMessage(brand(sk, xp > 0 ? sk.msg("claimed_xp", "<green>Claimed <white>{xp}</white> XP.", vars("xp", xp))
                                           : sk.msg("no_xp", "<gray>No XP stored.", null)));
        }
        else if (raw == sSell(sk)) {
            double money = actions.sellAll(m, p);
            actions.persist(m);
            refresh(m);
            if (money < 0) p.sendMessage(brand(sk, sk.msg("no_economy", "<red>No economy available to sell.", null)));
            else p.sendMessage(brand(sk, money > 0 ? sk.msg("sold", "<gold>Sold the pool for <white>{money}</white>.", vars("money", trim(money)))
                                                   : sk.msg("nothing_to_sell", "<gray>Nothing to sell.", null)));
        }
        else if (raw == sBuy(sk)) openBuy(p, h, m, sk);
        else if (raw == sChests(sk)) { h.setView(GrinderMenuHolder.View.CHESTS); h.setPage(0); refresh(m); }
        else if (raw == sAutoSell(sk)) { h.setView(GrinderMenuHolder.View.AUTOSELL); h.setPage(0); refresh(m); }
        else if (raw == sLog(sk)) { h.setView(GrinderMenuHolder.View.LOG); h.setPage(0); refresh(m); }
        else if (raw == sInsert(sk)) { actions.insertHeld(m, p); actions.persist(m); refresh(m); p.updateInventory(); }
        else if (raw == sUpgrade(sk)) { actions.upgrade(m, p); refresh(m); }
        else {
            int idx = slotIndex(sk, raw);
            if (idx >= 0) {
                ItemStack cur = p.getItemOnCursor();
                if (cur != null && cur.getType() == Material.SPAWNER) {   // drag-and-drop: held spawner → insert
                    actions.insertHeld(m, p); actions.persist(m); refresh(m); p.updateInventory();
                    return;
                }
                int used = m.grinder().slotsUsed();
                if (idx < used) {                       // filled slot → details
                    h.setView(GrinderMenuHolder.View.DETAIL);
                    h.setDetailSlot(idx);
                    refresh(m);
                } else {                                // empty slot → the spawner shop
                    openBuy(p, h, m, sk);
                }
            }
        }
    }

    /** The Buy button: {@code skin.buy_command} (default {@code spawnershop} → PixelProfiler's logged
     *  shop); blank = the grinder's own BUY catalog view (priced from {@code grinder.catalog}). */
    private void openBuy(Player p, GrinderMenuHolder h, Machine m, MenuSkin sk) {
        String cmd = sk.get("buy_command", "spawnershop");
        if (cmd == null || cmd.isBlank()) { h.setView(GrinderMenuHolder.View.BUY); h.setPage(0); refresh(m); return; }
        p.closeInventory();
        p.performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
    }

    /** Map a clicked GUI slot to a spawner index (0-based), or -1 if it isn't a spawner slot. */
    private static int slotIndex(MenuSkin sk, int raw) {
        int[] slots = spawnerSlots(sk);
        for (int i = 0; i < slots.length; i++) if (slots[i] == raw) return i;
        return -1;
    }

    private void onDetailClick(Player p, GrinderMenuHolder h, Machine m, int raw) {
        MenuSkin sk = skinOf(m);
        if (raw == subBack(sk)) { h.setView(GrinderMenuHolder.View.MAIN); refresh(m); return; }
        GrinderData d = m.grinder();
        int idx = h.detailSlot();
        InstalledSpawner s = (d != null && idx >= 0 && idx < d.spawners().size()) ? d.spawners().get(idx) : null;
        if (s == null) { h.setView(GrinderMenuHolder.View.MAIN); refresh(m); return; }
        if (raw == dCollect(sk)) {   // collect (global)
            long[] got = actions.collectAll(m);
            actions.persist(m);
            refresh(m);
            p.sendMessage(brand(sk, sk.msg("collected_detail", "<green>Collected <white>{items}</white> items and <white>{xp}</white> XP.",
                    vars("items", got[0], "xp", got[1]))));
        } else if (raw == dRemoveOne(sk)) {
            actions.destackSlot(m, p, idx, 1);
            backOrRefresh(h, m);
        } else if (raw == dRemoveAll(sk)) {
            actions.destackSlot(m, p, idx, s.stackSize());
            h.setView(GrinderMenuHolder.View.MAIN);
            refresh(m);
        }
    }

    private void backOrRefresh(GrinderMenuHolder h, Machine m) {
        GrinderData d = m.grinder();
        int idx = h.detailSlot();
        if (d == null || idx < 0 || idx >= d.spawners().size()) h.setView(GrinderMenuHolder.View.MAIN);
        refresh(m);
    }

    private void onSubClick(Player p, GrinderMenuHolder h, Machine m, int raw) {
        MenuSkin sk = skinOf(m);
        if (raw == subPrev(sk)) { h.setPage(h.page() - 1); refresh(m); return; }
        if (raw == subNext(sk)) { h.setPage(h.page() + 1); refresh(m); return; }
        if (raw == subBack(sk)) { h.setView(GrinderMenuHolder.View.MAIN); h.setPage(0); refresh(m); return; }
        int cs = contentStart(sk), ce = contentEnd(sk);
        if (h.view() == GrinderMenuHolder.View.BUY && raw >= cs && raw < ce) {
            MachineType type = typeOf(m);
            GrinderSpec spec = type == null ? null : type.grinder();
            if (spec == null) return;
            List<GrinderSpec.CatalogEntry> entries = new ArrayList<>(spec.catalog().values());
            int idx = h.page() * perPage(sk) + (raw - cs);
            if (idx < entries.size()) {
                actions.buySpawner(m, p, entries.get(idx).type);
                actions.persist(m);
                refresh(m);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GrinderMenuHolder)) return;
        for (int raw : e.getRawSlots()) if (raw < SIZE) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof GrinderMenuHolder h)) return;
        List<Inventory> list = open.get(h.machine().id());
        if (list != null) {
            list.remove(e.getInventory());
            if (list.isEmpty()) { open.remove(h.machine().id()); lastRefresh.remove(h.machine().id()); }
        }
    }

    // --- item helpers -------------------------------------------------------

    private Component title(Machine m, GrinderMenuHolder.View view) {
        MenuSkin sk = skinOf(m);
        String s = switch (view) {
            case MAIN -> sk.title("main", "<dark_purple>✦ Dimensional Grinder");
            case LOG -> sk.title("log", "<dark_purple>✦ Grinder · Log");
            case BUY -> sk.title("buy", "<dark_purple>✦ Grinder · Catalog");
            case DETAIL -> sk.title("detail", "<dark_purple>✦ Grinder · Spawner");
            case POOL -> sk.title("pool", "<dark_purple>✦ Grinder · Pool");
            case CHESTS -> sk.title("chests", "<dark_purple>✦ Grinder · Saved Chests");
            case CHEST_FILTER -> sk.title("chest_filter", "<dark_purple>✦ Grinder · Chest Filter");
            case AUTOSELL -> sk.title("autosell", "<dark_purple>✦ Grinder · Auto-Sell");
        };
        return MenuSkin.mini(s);
    }

    // --- POOL browser (mirrors the quarry's Quantum Vault) ------------------

    private static final String[] TAB_KEYS = {"all", "ores", "gems", "blocks", "misc"};
    private static final String[] TAB_NAMES = {"All", "Ores", "Gems", "Blocks", "Misc"};
    private static final Material[] TAB_ICONS = {Material.CHEST, Material.RAW_IRON, Material.DIAMOND, Material.COBBLESTONE, Material.ENDER_EYE};

    private void renderPool(Inventory inv, GrinderMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        GrinderData d = m.grinder();
        if (d == null) return;
        PriceService prices = type == null ? null : type.pricing();
        MenuSkin sk = skinOf(m);

        fillAll(inv, sk, "pool", Material.BLACK_STAINED_GLASS_PANE);

        int[] tabs = pTabs(sk);
        for (int i = 0; i < TAB_NAMES.length && i < tabs.length; i++) {
            Map<String, String> v = vars("tab", TAB_NAMES[i]);
            inv.setItem(tabs[i], h.tab() == i
                    ? sk.item("pool_tab_" + TAB_KEYS[i] + "_on", TAB_ICONS[i], "<green>▸ {tab}", List.of("<dark_gray>filter"), v)
                    : sk.item("pool_tab_" + TAB_KEYS[i], TAB_ICONS[i], "<gray>{tab}", List.of("<dark_gray>filter"), v));
        }

        inv.setItem(pValue(sk), sk.item("pool_value", Material.GOLD_INGOT, "<gold>Pool value",
                List.of("<yellow>${value}", "<gray>Items: <white>{items}"),
                vars("value", trim(poolValue(d, prices)), "items", d.pooledItems())));

        int cs = contentStart(sk), per = perPage(sk);
        List<Map.Entry<ItemStack, Long>> list = sortedFiltered(d, h.tab(), h.sort(), prices);
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) per));
        h.setPage(Math.min(h.page(), pages - 1));
        int start = h.page() * per;
        for (int i = 0; i < per; i++) {
            int idx = start + i;
            if (idx >= list.size()) break;
            inv.setItem(cs + i, poolCard(sk, list.get(idx), prices));
        }
        if (list.isEmpty())
            inv.setItem(subEmpty(sk), sk.item("pool_empty", Material.GRAY_STAINED_GLASS_PANE, "<gray>Nothing here yet", List.of("<dark_gray>collect some loot"), null));

        Map<String, String> pv = vars("page", h.page() + 1, "pages", pages, "types", list.size(), "sort", h.sort());
        String pageInfo = "<gray>Page <white>{page}</white>/<white>{pages}</white> · <white>{types}</white> types";
        if (h.page() > 0) inv.setItem(pPrev(sk), sk.item("pool_prev", Material.ARROW, "<yellow>◀ Prev", List.of(pageInfo), pv));
        if (h.page() < pages - 1) inv.setItem(pNext(sk), sk.item("pool_next", Material.ARROW, "<yellow>Next ▶", List.of(pageInfo), pv));
        inv.setItem(pBack(sk), sk.item("pool_back", Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>to the grinder", pageInfo), pv));
        inv.setItem(pCollect(sk), sk.item("pool_collect", Material.HOPPER, "<green>Collect all", List.of("<gray>Everything that fits → your inventory"), pv));
        inv.setItem(pSort(sk), sk.item("pool_sort", Material.COMPARATOR, "<white>Sort: <aqua>{sort}", List.of("<dark_gray>▶ cycle qty / value / name"), pv));
        inv.setItem(pSell(sk), h.sellArmed()
                ? sk.item("pool_sell_confirm", Material.REDSTONE_BLOCK, "<red>⚠ Click again to confirm",
                        List.of("<red>Sells the ENTIRE pool", "<dark_gray>(disarms if you click elsewhere)"), pv)
                : sk.item("pool_sell", Material.SUNFLOWER, "<gold>$ Sell all", List.of("<gray>Sell the whole pool", "<dark_gray>▶ click"), pv));
    }

    private ItemStack poolCard(MenuSkin sk, Map.Entry<ItemStack, Long> e, PriceService prices) {
        ItemStack tmpl = e.getKey();
        long count = e.getValue();
        ItemStack icon = tmpl.clone();
        icon.setAmount((int) Math.max(1, Math.min(count, tmpl.getMaxStackSize())));
        double each = prices == null ? 0 : prices.price(tmpl);
        List<String> lore = new ArrayList<>();
        if (each > 0) lore.add("<gray>Value: <yellow>${each}</yellow> ea · <yellow>${total}");
        lore.add("<dark_gray>click=stack · right=one · shift=all");
        return sk.decorate("pool_card", icon, "<white>{item} <gray>×<yellow>{count}", lore,
                vars("item", prettyItem(tmpl), "count", count, "each", trim(each), "total", trim(each * count)));
    }

    private void onPoolClick(Player p, GrinderMenuHolder h, Machine m, int raw, ClickType click) {
        GrinderData d = m.grinder();
        if (d == null) return;
        MenuSkin sk = skinOf(m);
        if (raw != pSell(sk)) h.setSellArmed(false);

        int[] tabs = pTabs(sk);
        for (int i = 0; i < tabs.length && i < TAB_NAMES.length; i++)
            if (raw == tabs[i]) { h.setTab(i); refresh(m); return; }
        if (raw == pPrev(sk)) { h.setPage(h.page() - 1); refresh(m); return; }
        if (raw == pNext(sk)) { h.setPage(h.page() + 1); refresh(m); return; }
        if (raw == pBack(sk)) { h.setView(GrinderMenuHolder.View.MAIN); refresh(m); return; }
        if (raw == pSort(sk)) { h.setSort(nextSort(h.sort())); refresh(m); return; }
        if (raw == pCollect(sk)) {
            long n = actions.withdrawPool(m, p);
            actions.persist(m); refresh(m);
            p.sendMessage(brand(sk, n > 0 ? sk.msg("pool_collected", "<green>Collected <white>{items}</white> items.", vars("items", n))
                                          : sk.msg("pool_nothing", "<gray>Nothing fit (inventory full?) / pool empty.", null)));
            return;
        }
        if (raw == pSell(sk)) {
            if (!h.sellArmed()) {
                h.setSellArmed(true); refresh(m);
                p.sendMessage(brand(sk, sk.msg("sell_confirm", "<red>Click Sell again to confirm — this sells the whole pool.", null)));
            } else {
                double money = actions.sellAll(m, p);
                actions.persist(m); h.setSellArmed(false); refresh(m);
                if (money < 0) p.sendMessage(brand(sk, sk.msg("no_economy", "<red>No economy available to sell.", null)));
                else p.sendMessage(brand(sk, money > 0 ? sk.msg("sold", "<gold>Sold the pool for <white>{money}</white>.", vars("money", trim(money)))
                                                       : sk.msg("nothing_sellable", "<gray>Nothing sellable.", null)));
            }
            return;
        }
        int cs = contentStart(sk), ce = contentEnd(sk);
        if (raw >= cs && raw < ce) {
            MachineType type = typeOf(m);
            PriceService prices = type == null ? null : type.pricing();
            List<Map.Entry<ItemStack, Long>> list = sortedFiltered(d, h.tab(), h.sort(), prices);
            int idx = h.page() * perPage(sk) + (raw - cs);
            if (idx >= list.size()) return;
            ItemStack tmpl = list.get(idx).getKey();
            long amt = click.isShiftClick() ? Long.MAX_VALUE : (click == ClickType.RIGHT ? 1 : tmpl.getMaxStackSize());
            actions.withdrawPoolItem(m, p, tmpl, amt);
            refresh(m);
        }
    }

    private void onChestsClick(Player p, GrinderMenuHolder h, Machine m, int raw, ClickType click) {
        MenuSkin sk = skinOf(m);
        if (raw == cBack(sk)) { h.setView(GrinderMenuHolder.View.MAIN); refresh(m); return; }
        if (raw == cAddOut(sk)) { actions.beginChestSelection(m, p, ChestLink.Type.OUTPUT); return; }
        if (raw == cAddIn(sk)) { actions.beginChestSelection(m, p, ChestLink.Type.INPUT); return; }
        int cs = contentStart(sk), ce = contentEnd(sk);
        if (raw >= cs && raw < ce) {
            GrinderData d = m.grinder();
            if (d == null) return;
            int idx = raw - cs;
            if (idx >= d.links().size()) return;
            if (click == ClickType.RIGHT) { actions.removeChestLink(m, idx); refresh(m); p.sendMessage(brand(sk, sk.msg("link_removed", "<yellow>Removed link.", null))); return; }
            // Rate + filter apply to OUTPUT (push) and INPUT (pull) alike.
            if (click.isShiftClick()) { h.setLinkIndex(idx); h.setView(GrinderMenuHolder.View.CHEST_FILTER); refresh(m); }
            else actions.promptLinkRate(m, p, idx);   // closes the menu + chat-prompts; reopens to CHESTS
        }
    }

    private void onChestFilterClick(Player p, GrinderMenuHolder h, Machine m, int raw) {
        MenuSkin sk = skinOf(m);
        if (raw == fBack(sk)) { h.setView(GrinderMenuHolder.View.CHESTS); refresh(m); return; }
        if (raw == fClear(sk)) { actions.clearLinkFilter(m, h.linkIndex()); refresh(m); return; }   // "route everything"
        int cs = contentStart(sk), ce = contentEnd(sk);
        if (raw >= cs && raw < ce) {
            GrinderData d = m.grinder();
            if (d == null) return;
            List<Material> cands = candidateMaterials(d, typeOf(m));
            int idx = raw - cs;
            if (idx < cands.size()) { actions.toggleLinkFilter(m, h.linkIndex(), cands.get(idx)); refresh(m); }
        }
    }

    private double poolValue(GrinderData d, PriceService prices) {
        if (prices == null) return 0;
        double v = 0;
        for (Map.Entry<ItemStack, Long> e : d.pool().entrySet()) v += prices.price(e.getKey()) * e.getValue();
        return v;
    }

    private List<Map.Entry<ItemStack, Long>> sortedFiltered(GrinderData d, int tab, GrinderMenuHolder.Sort sort, PriceService prices) {
        List<Map.Entry<ItemStack, Long>> list = new ArrayList<>();
        for (Map.Entry<ItemStack, Long> e : d.pool().entrySet()) {
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

    /** 1=ore/metal, 2=gem, 3=block, 4=misc (tab 0 = all). */
    private int category(Material mat) {
        String n = mat.name();
        if (mat == Material.DIAMOND || mat == Material.EMERALD || n.startsWith("AMETHYST") || mat == Material.NETHER_STAR
                || mat == Material.LAPIS_LAZULI || mat == Material.QUARTZ) return 2;
        if (n.startsWith("RAW_") || n.endsWith("_INGOT") || n.endsWith("_NUGGET") || mat == Material.COAL
                || mat == Material.REDSTONE || mat == Material.GLOWSTONE_DUST || mat == Material.ANCIENT_DEBRIS || mat == Material.NETHERITE_SCRAP) return 1;
        if (mat.isBlock()) return 3;
        return 4;
    }

    private static GrinderMenuHolder.Sort nextSort(GrinderMenuHolder.Sort s) {
        return switch (s) {
            case QTY -> GrinderMenuHolder.Sort.VALUE;
            case VALUE -> GrinderMenuHolder.Sort.NAME;
            case NAME -> GrinderMenuHolder.Sort.QTY;
        };
    }

    private static String prettyItem(ItemStack item) {
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName())
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        return pretty(item.getType());
    }

    /** Chat brand: the skin's prefix (MiniMessage) + the message. */
    private static Component brand(MenuSkin sk, String s) {
        return MenuSkin.mini(sk.prefix("<light_purple>Grinder <dark_gray>» ") + s);
    }

    private static String trim(double d) {
        return (d == Math.floor(d)) ? Long.toString((long) d) : String.format("%.2f", d);
    }
}
