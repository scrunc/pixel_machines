package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import dev.servereer.machineconstruct.machine.Recipe;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The machine menu manager (DESIGN.md §14) — opens, renders, and guards the
 * custom GUI. <b>Un-glitchable by construction:</b> every click and drag that
 * touches a menu is cancelled, then the intended move is performed manually and
 * deterministically against the machine's item arrays. No native inventory
 * mechanics ever run, so there is no shift-click / drag / double-click / hotbar
 * dupe surface. Items live only in the machine store (persisted on every change
 * via {@code onChange}), never in the open window — so a crash loses nothing.
 */
public final class MachineMenus implements Listener {

    private final Plugin plugin;
    private final Consumer<Machine> onChange;          // persist the store after a mutation
    private final java.util.function.BiConsumer<Machine, Player> onUpgrade;   // upgrade-button click
    private final java.util.function.BiConsumer<Machine, Player> onSell;      // sell-all button click
    private final Map<UUID, Inventory> open = new HashMap<>();   // machine id → shared window

    public MachineMenus(Plugin plugin, Consumer<Machine> onChange,
                        java.util.function.BiConsumer<Machine, Player> onUpgrade,
                        java.util.function.BiConsumer<Machine, Player> onSell) {
        this.plugin = plugin;
        this.onChange = onChange;
        this.onUpgrade = onUpgrade;
        this.onSell = onSell;
    }

    public void open(Player player, Machine machine, MachineType type, GuiLayout layout) {
        if (layout == null) return;
        Inventory inv = open.get(machine.id());
        if (inv == null) {
            MachineMenuHolder holder = new MachineMenuHolder(machine, type, layout);
            inv = Bukkit.createInventory(holder, layout.size(), layout.title());
            holder.setInventory(inv);
            open.put(machine.id(), inv);
        }
        render(inv, machine, type, layout);
        player.openInventory(inv);
    }

    /** Re-render the live window for a machine (call after processing or a store change). */
    public void refresh(Machine machine) {
        Inventory inv = open.get(machine.id());
        if (inv == null) return;
        if (inv.getViewers().isEmpty()) { open.remove(machine.id()); return; }
        if (inv.getHolder() instanceof MachineMenuHolder h) render(inv, machine, h.type(), h.layout());
    }

    public void closeAll() {
        for (Inventory inv : new ArrayList<>(open.values()))
            for (HumanEntity v : new ArrayList<>(inv.getViewers())) v.closeInventory();
        open.clear();
    }

    /** Close (and forget) the open window for a single machine — used on teardown. */
    public void closeFor(Machine machine) {
        Inventory inv = open.remove(machine.id());
        if (inv != null) for (HumanEntity v : new ArrayList<>(inv.getViewers())) v.closeInventory();
    }

    /** Reopen the window for current viewers with a new layout (used when a tier changes the GUI). */
    public void rebuild(Machine machine, MachineType type, GuiLayout layout) {
        Inventory inv = open.get(machine.id());
        if (inv == null) return;
        if (inv.getHolder() instanceof MachineMenuHolder h && h.layout() == layout) { refresh(machine); return; }
        java.util.List<HumanEntity> viewers = new ArrayList<>(inv.getViewers());
        open.remove(machine.id());
        for (HumanEntity v : viewers) if (v instanceof Player p) open(p, machine, type, layout);
    }

    // --- rendering ----------------------------------------------------------

    private void render(Inventory inv, Machine m, MachineType type, GuiLayout layout) {
        for (int slot = 0; slot < layout.size(); slot++)
            if (layout.role(slot) == SlotRole.DECOR) inv.setItem(slot, layout.decorAt(slot));

        int[] infoS = layout.infoSlots();
        if (infoS.length > 0 && type != null) {
            ItemStack info = layout.infoIcon() == null ? null : layout.infoIcon().clone();
            if (info != null) {
                ItemMeta meta = info.getItemMeta();
                if (meta != null) {
                    meta.lore(List.of(
                            label("Click to browse recipes", NamedTextColor.YELLOW),
                            label(type.recipes().size() + " recipe(s)", NamedTextColor.DARK_GRAY)));
                    info.setItemMeta(meta);
                }
            }
            for (int slot : infoS) inv.setItem(slot, info == null ? null : info.clone());
        }

        ItemStack[] in = m.inputs();
        int[] inS = layout.inputSlots();
        for (int i = 0; i < inS.length; i++) inv.setItem(inS[i], i < in.length ? in[i] : null);

        ItemStack[] out = m.outputs();
        int[] outS = layout.outputSlots();
        for (int i = 0; i < outS.length; i++) inv.setItem(outS[i], i < out.length ? out[i] : null);

        ItemStack[] fuel = m.fuel();
        int[] fuelS = layout.fuelSlots();
        for (int i = 0; i < fuelS.length; i++) inv.setItem(fuelS[i], i < fuel.length ? fuel[i] : null);

        int[] upS = layout.upgradeSlots();
        if (upS.length > 0) {
            ItemStack btn = upgradeButton(m, type);   // a click-button showing the requirement
            for (int slot : upS) inv.setItem(slot, btn);
        }

        int[] tierS = layout.tierSlots();
        if (tierS.length > 0) {
            ItemStack ti = tierIndicator(m, type);
            for (int slot : tierS) inv.setItem(slot, ti);
        }

        int[] sellS = layout.sellSlots();
        if (sellS.length > 0) {
            ItemStack sb = sellButton();
            for (int slot : sellS) inv.setItem(slot, sb);
        }

        renderProgress(inv, m, layout);
        renderBurn(inv, m, layout);
    }

    private ItemStack tierIndicator(Machine m, MachineType type) {
        dev.servereer.machineconstruct.machine.Tiers t = (type == null) ? null : type.tiers();
        int tier = m.tier();
        int max = (t == null) ? 1 : t.max();
        ItemStack item = new ItemStack(tier >= max
                ? org.bukkit.Material.NETHER_STAR : org.bukkit.Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(label("Tier " + roman(tier) + " / " + roman(max), NamedTextColor.AQUA));
            java.util.List<Component> lore = new ArrayList<>();
            if (t == null || tier >= max) {
                lore.add(label("Max tier", NamedTextColor.GRAY));
            } else {
                lore.add(label("Next: ×" + trim(t.speed(tier + 1)) + " speed · ×" + t.batch(tier + 1) + " output",
                        NamedTextColor.GRAY));
                lore.add(label("Drop the upgrade item below to advance", NamedTextColor.DARK_GRAY));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** The upgrade button — shows what's required (consumed from your inventory on click). */
    private ItemStack upgradeButton(Machine m, MachineType type) {
        dev.servereer.machineconstruct.machine.Tiers t = (type == null) ? null : type.tiers();
        if (t == null) return null;
        if (m.tier() >= t.max()) {
            ItemStack maxed = new ItemStack(org.bukkit.Material.NETHER_STAR);
            ItemMeta mm = maxed.getItemMeta();
            if (mm != null) { mm.displayName(label("✦ Fully upgraded (Tier " + roman(m.tier()) + ")", NamedTextColor.GRAY)); maxed.setItemMeta(mm); }
            return maxed;
        }
        ItemStack req = t.upgradeItem();
        int need = (req == null) ? 1 : Math.max(1, req.getAmount());
        org.bukkit.Material mat = (req == null) ? org.bukkit.Material.AMETHYST_SHARD : req.getType();
        ItemStack btn = new ItemStack(mat, need);   // icon = the required item, stack = the amount
        ItemMeta mm = btn.getItemMeta();
        if (mm != null) {
            mm.displayName(label("⏫ Upgrade to Tier " + roman(m.tier() + 1), NamedTextColor.AQUA));
            java.util.List<Component> lore = new ArrayList<>();
            lore.add(label("Requires: " + need + "× " + pretty(mat), NamedTextColor.WHITE));
            lore.add(label("taken from your inventory", NamedTextColor.DARK_GRAY));
            lore.add(label("▶ Click to upgrade", NamedTextColor.GREEN));
            mm.lore(lore);
            btn.setItemMeta(mm);
        }
        return btn;
    }

    /** The sell-all button — sells everything in the output slots for money. */
    private ItemStack sellButton() {
        ItemStack btn = new ItemStack(org.bukkit.Material.EMERALD);
        ItemMeta mm = btn.getItemMeta();
        if (mm != null) {
            mm.displayName(label("$ Sell all", NamedTextColor.GREEN));
            mm.lore(List.of(
                    label("Sell everything in the output", NamedTextColor.GRAY),
                    label("▶ Click to sell", NamedTextColor.DARK_GRAY)));
            btn.setItemMeta(mm);
        }
        return btn;
    }

    private static String pretty(org.bukkit.Material mat) {
        String[] parts = mat.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return sb.toString().trim();
    }

    private static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> Integer.toString(n); };
    }

    private static String trim(double d) {
        return (d == Math.floor(d)) ? Integer.toString((int) d) : String.format("%.1f", d);
    }

    private void renderBurn(Inventory inv, Machine m, GuiLayout layout) {
        int[] bs = layout.burnSlots();
        if (bs.length == 0) return;
        GuiLayout.ProgressIcon lit = layout.progressIcon("burn");
        GuiLayout.ProgressIcon empty = layout.progressIcon("burn_empty");
        int percent = (int) Math.round(m.fuel01() * 100);
        int filled = (int) Math.round(m.fuel01() * bs.length);
        for (int i = 0; i < bs.length; i++) {
            GuiLayout.ProgressIcon icon = (i < filled) ? lit : empty;
            inv.setItem(bs[i], icon == null ? null : icon.build(percent));
        }
    }

    private void renderProgress(Inventory inv, Machine m, GuiLayout layout) {
        int[] ps = layout.progressSlots();
        if (ps.length == 0) return;
        String state = m.state().name().toLowerCase();
        int percent = (int) Math.round(m.progress01() * 100);
        GuiLayout.ProgressIcon idle = layout.progressIcon("idle");
        if (state.equals("working")) {
            GuiLayout.ProgressIcon working = layout.progressIcon("working");
            int filled = (int) Math.round(m.progress01() * ps.length);
            for (int i = 0; i < ps.length; i++) {
                GuiLayout.ProgressIcon icon = (i < filled) ? working : idle;
                inv.setItem(ps[i], icon == null ? null : icon.build(percent));
            }
        } else {
            GuiLayout.ProgressIcon icon = layout.progressIcon(state);
            if (icon == null) icon = idle;
            for (int slot : ps) inv.setItem(slot, icon == null ? null : icon.build(percent));
        }
    }

    // --- events (all defensive) --------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        org.bukkit.inventory.InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof RecipeBrowserHolder rb) { onBrowserClick(e, rb); return; }
        if (!(holder instanceof MachineMenuHolder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) { e.setCancelled(true); return; }
        Machine m = h.machine();
        GuiLayout layout = h.layout();
        int raw = e.getRawSlot();

        // Click in the player's OWN inventory: leave normal item handling alone —
        // only intercept the actions that reach into the menu.
        if (raw < 0 || raw >= layout.size()) {
            if (e.getClick() == ClickType.DOUBLE_CLICK) {   // collect-to-cursor could siphon the menu
                e.setCancelled(true);
                return;
            }
            if (e.isShiftClick()) {                         // shift moves into the menu → route to inputs
                e.setCancelled(true);
                ItemStack moving = e.getCurrentItem();
                if (moving != null && !moving.getType().isAir()) {
                    int before = moving.getAmount();
                    int leftover = depositToInputs(m, moving);
                    if (leftover != before) {
                        e.setCurrentItem(leftover <= 0 ? null : copyAmt(moving, leftover));
                        changed(m, p);
                    }
                }
            }
            return;   // any other own-inventory click is normal vanilla behaviour
        }

        // Click in the MENU inventory → fully manual.
        e.setCancelled(true);
        SlotRole role = layout.role(raw);
        if (role == SlotRole.INFO) {                  // the recipe-book button
            MachineType type = h.type();
            if (type != null) openLater(() -> openBrowser(p, m, type, 0));
            return;
        }
        if (role == SlotRole.UPGRADE) {               // the upgrade button → consume from player inventory
            openLater(() -> onUpgrade.accept(m, p));
            return;
        }
        if (role == SlotRole.SELL) {                  // the sell-all button → sell output items for money
            onSell.accept(m, p);
            return;
        }
        try {
            boolean dirty = switch (role) {
                case INPUT -> handleSlot(e, p, m.inputs(), layout.inputIndexOf(raw));
                case FUEL -> handleSlot(e, p, m.fuel(), layout.fuelIndexOf(raw));
                case OUTPUT -> handleOutput(e, p, m, layout.outputIndexOf(raw));
                default -> false;   // DECOR / PROGRESS / BURN / TIER are inert
            };
            if (dirty) changed(m, p);
        } catch (Throwable t) {
            // A click must never throw to the player or leave a half-applied move.
            // The arrays are the source of truth — resync the window + cursor from them.
            refresh(m);
            p.updateInventory();
        }
    }

    // --- recipe browser (sub-menu) -----------------------------------------

    /** Open the recipe browser for a machine type at the given page. */
    public void openBrowser(Player player, Machine machine, MachineType type, int page) {
        BrowserLayout b = type.browser();
        if (b == null) return;
        int pages = Math.max(1, (int) Math.ceil(type.recipes().size() / (double) b.perPage()));
        page = Math.max(0, Math.min(page, pages - 1));
        RecipeBrowserHolder holder = new RecipeBrowserHolder(machine, type, page);
        Inventory inv = Bukkit.createInventory(holder, b.size(), b.title(type.id(), page, pages));
        holder.setInventory(inv);
        renderBrowser(inv, type, b, page, pages);
        player.openInventory(inv);
    }

    private void renderBrowser(Inventory inv, MachineType type, BrowserLayout b, int page, int pages) {
        for (int i = 0; i < b.size(); i++) inv.setItem(i, b.filler().clone());

        List<Recipe> recipes = type.recipes();
        for (int i = 0; i < b.perPage(); i++) {
            int idx = page * b.perPage() + i;
            if (idx >= recipes.size()) break;
            Recipe r = recipes.get(idx);
            int row = i * 9;
            List<ItemStack> ins = r.inputs();
            int[] inCols = b.inputCols();
            for (int j = 0; j < ins.size() && j < inCols.length; j++) inv.setItem(row + inCols[j], ins.get(j).clone());
            inv.setItem(row + b.arrowCol(), arrowItem(r, type, b));
            List<ItemStack> outs = r.outputs();
            int[] outCols = b.outputCols();
            for (int j = 0; j < outs.size() && j < outCols.length; j++) inv.setItem(row + outCols[j], outs.get(j).clone());
        }

        inv.setItem(b.backSlot(), b.backIcon().clone());
        inv.setItem(b.pageSlot(), b.pageButton(page, pages));
        if (page > 0) inv.setItem(b.prevSlot(), b.prevIcon().clone());
        if (page < pages - 1) inv.setItem(b.nextSlot(), b.nextIcon().clone());
    }

    private ItemStack arrowItem(Recipe r, MachineType type, BrowserLayout b) {
        ItemStack a = b.arrowIcon().clone();
        ItemMeta meta = a.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            double s = r.timeTicks() / 20.0;
            lore.add(label("Time: " + (s == Math.floor(s) ? (int) s + "s" : String.format("%.1fs", s)), NamedTextColor.GRAY));
            if (type.requiresFuel()) lore.add(label("Requires fuel", NamedTextColor.GOLD));
            meta.lore(lore);
            a.setItemMeta(meta);
        }
        return a;
    }

    /** Place/take handling for an editable slot (input or fuel) — vanilla-like, but manual. */
    private boolean handleSlot(InventoryClickEvent e, Player p, ItemStack[] inputs, int idx) {
        if (idx < 0 || idx >= inputs.length) return false;
        ItemStack slot = inputs[idx];
        ItemStack cursor = p.getItemOnCursor();
        boolean slotEmpty = isEmpty(slot);
        boolean cursorEmpty = isEmpty(cursor);

        if (e.isShiftClick()) {                     // move slot → player inventory
            if (slotEmpty) return false;
            Map<Integer, ItemStack> left = p.getInventory().addItem(slot.clone());
            inputs[idx] = left.isEmpty() ? null : left.values().iterator().next();
            return true;
        }

        switch (e.getClick()) {
            case LEFT -> {
                if (cursorEmpty && !slotEmpty) { p.setItemOnCursor(slot); inputs[idx] = null; return true; }
                if (!cursorEmpty && slotEmpty) { inputs[idx] = cursor; p.setItemOnCursor(null); return true; }
                if (!cursorEmpty) {                 // both present
                    if (slot.isSimilar(cursor)) {
                        int move = Math.min(slot.getMaxStackSize() - slot.getAmount(), cursor.getAmount());
                        if (move > 0) {
                            slot.setAmount(slot.getAmount() + move);
                            p.setItemOnCursor(shrink(cursor, move));
                            return true;
                        }
                    }
                    inputs[idx] = cursor; p.setItemOnCursor(slot); return true;   // swap
                }
                return false;
            }
            case RIGHT -> {
                if (cursorEmpty && !slotEmpty) {     // take half
                    int half = (slot.getAmount() + 1) / 2;
                    p.setItemOnCursor(copyAmt(slot, half));
                    slot.setAmount(slot.getAmount() - half);
                    if (slot.getAmount() <= 0) inputs[idx] = null;
                    return true;
                }
                if (!cursorEmpty) {                  // place one
                    if (slotEmpty) { inputs[idx] = copyAmt(cursor, 1); p.setItemOnCursor(shrink(cursor, 1)); return true; }
                    if (slot.isSimilar(cursor) && slot.getAmount() < slot.getMaxStackSize()) {
                        slot.setAmount(slot.getAmount() + 1);
                        p.setItemOnCursor(shrink(cursor, 1));
                        return true;
                    }
                }
                return false;
            }
            default -> { return false; }            // number keys, etc. — ignored
        }
    }

    private boolean handleOutput(InventoryClickEvent e, Player p, Machine m, int idx) {
        if (idx < 0 || idx >= m.outputs().length) return false;
        ItemStack[] outputs = m.outputs();
        ItemStack slot = outputs[idx];
        if (isEmpty(slot)) return false;            // take-only; nothing to take

        if (e.isShiftClick()) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(slot.clone());
            outputs[idx] = left.isEmpty() ? null : left.values().iterator().next();
            return true;
        }
        ItemStack cursor = p.getItemOnCursor();
        if (isEmpty(cursor)) { p.setItemOnCursor(slot); outputs[idx] = null; return true; }
        if (cursor.isSimilar(slot)) {               // top up the cursor stack
            int move = Math.min(cursor.getMaxStackSize() - cursor.getAmount(), slot.getAmount());
            if (move > 0) {
                cursor.setAmount(cursor.getAmount() + move);
                p.setItemOnCursor(cursor);
                slot.setAmount(slot.getAmount() - move);
                if (slot.getAmount() <= 0) outputs[idx] = null;
                return true;
            }
        }
        return false;                               // can't place a foreign item into output
    }

    /** Merge an item into the input array (existing similar stacks, then empty slots). Returns leftover. */
    private int depositToInputs(Machine m, ItemStack moving) {
        ItemStack[] inputs = m.inputs();
        int remaining = moving.getAmount();
        for (int i = 0; i < inputs.length && remaining > 0; i++) {
            ItemStack s = inputs[i];
            if (s != null && s.isSimilar(moving) && s.getAmount() < s.getMaxStackSize()) {
                int move = Math.min(s.getMaxStackSize() - s.getAmount(), remaining);
                s.setAmount(s.getAmount() + move);
                remaining -= move;
            }
        }
        for (int i = 0; i < inputs.length && remaining > 0; i++) {
            if (isEmpty(inputs[i])) {
                int place = Math.min(moving.getMaxStackSize(), remaining);
                inputs[i] = copyAmt(moving, place);
                remaining -= place;
            }
        }
        return remaining;
    }

    /** Recipe browser is view-only: nav buttons act, everything else is inert. */
    private void onBrowserClick(InventoryClickEvent e, RecipeBrowserHolder rb) {
        BrowserLayout b = rb.type().browser();
        int raw = e.getRawSlot();
        if (raw >= 0 && raw < (b == null ? 0 : b.size())) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            if (raw == b.backSlot()) openLater(() -> open(p, rb.machine(), rb.type(), rb.type().gui()));
            else if (raw == b.prevSlot()) openLater(() -> openBrowser(p, rb.machine(), rb.type(), rb.page() - 1));
            else if (raw == b.nextSlot()) openLater(() -> openBrowser(p, rb.machine(), rb.type(), rb.page() + 1));
            return;
        }
        if (e.getClick() == ClickType.DOUBLE_CLICK || e.isShiftClick()) e.setCancelled(true);   // no siphon/blackhole
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        org.bukkit.inventory.InventoryHolder holder = e.getView().getTopInventory().getHolder();
        int topSize = (holder instanceof MachineMenuHolder mh) ? mh.layout().size()
                : (holder instanceof RecipeBrowserHolder rb && rb.type().browser() != null) ? rb.type().browser().size() : -1;
        if (topSize < 0) return;
        for (int raw : e.getRawSlots())             // any drag touching the top inventory is refused
            if (raw < topSize) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof MachineMenuHolder h)) return;
        if (e.getInventory().getViewers().size() <= 1) open.remove(h.machine().id());   // last viewer leaving
    }

    private void changed(Machine m, Player p) {
        onChange.accept(m);     // persist store → PDC (crash-safe)
        refresh(m);             // re-render the shared window for all viewers
        p.updateInventory();    // push authoritative cursor/inventory state to the client
    }

    // --- item helpers -------------------------------------------------------

    private void openLater(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    private static Component label(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static boolean isEmpty(ItemStack s) { return s == null || s.getType().isAir() || s.getAmount() <= 0; }

    private static ItemStack copyAmt(ItemStack s, int amount) {
        ItemStack c = s.clone();
        c.setAmount(amount);
        return c;
    }

    private static ItemStack shrink(ItemStack s, int by) {
        int left = s.getAmount() - by;
        if (left <= 0) return null;
        ItemStack c = s.clone();
        c.setAmount(left);
        return c;
    }
}
