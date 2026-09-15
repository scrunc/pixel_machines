package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.api.MachineConstructAPI;
import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import dev.servereer.machineconstruct.tradinghall.TradingHallActions;
import dev.servereer.machineconstruct.tradinghall.TradingHallData;
import dev.servereer.machineconstruct.tradinghall.TradingHallManager;
import dev.servereer.machineconstruct.tradinghall.TradingHallSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Virtual Trading Hall menu (ADR 0011). Code-rendered + sealed + paginated.
 * Three views (all on one holder): MAIN (info + capture + station-slot grid),
 * BUILD (profession picker for an empty slot), TRADER (a slot's trader — preview
 * offers, Refresh while unlocked, Trade via the native merchant, Remove).
 */
public final class TradingHallMenus implements Listener {

    private static final int SIZE = 54;
    private static final int GRID_START = 9, GRID_END = 45, PER_PAGE = 36; // rows 1–4
    private static final int SLOT_INFO = 4, SLOT_ROSTER = 0, SLOT_UPGRADE = 6, SLOT_OUTPUT = 7, SLOT_VAULT = 8;
    private static final int SLOT_SHOP = 1, SLOT_TILL = 2; // shopfront toggle + earnings till (owner MAIN)
    private static final int SLOT_PREV = 45, SLOT_NEXT = 53;
    // BUILD/TRADER bottom-row buttons
    private static final int B_BACK = 45, T_BACK = 45, T_RESET = 49, T_REMOVE = 51, T_STOCK = 53;
    // VAULT / TILL view bottom-row buttons
    private static final int V_PREV = 45, V_BACK = 49, V_NEXT = 53;
    private static final int TILL_PREV = 45, TILL_BACK = 49, TILL_SWEEP = 47, TILL_NEXT = 53;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final MachineConstructAPI registry;
    private final TradingHallActions actions;

    public TradingHallMenus(Plugin plugin, MachineConstructAPI registry, TradingHallActions actions) {
        this.plugin = plugin;
        this.registry = registry;
        this.actions = actions;
    }

    // --- open entry points ---------------------------------------------------

    public void open(Player player, Machine machine) {
        TradingHallSpec spec = specOf(machine);
        if (spec == null) { player.sendMessage(mini("<red>That isn't a Trading Hall.")); return; }
        if (!machine.hasTradingHall()) machine.setTradingHall(new TradingHallData());
        machine.tradingHall().ensureSlots(spec.slotsForTier(machine.tradingHall().tier()));
        actions.persist(machine);
        if (isOwner(player, machine)) { openMain(player, machine, 0); return; }
        // Non-owner: shopfront customer. Only enter if the owner has opened the shop.
        if (!machine.tradingHall().open()) { player.sendMessage(mini("<red>🔒 This shop is closed.")); return; }
        openCustomerMain(player, machine, 0);
    }

    /** Owner (or a legacy hall with no recorded owner) gets the full management GUI. */
    private boolean isOwner(Player p, Machine m) {
        return m.owner() == null || m.owner().equals(p.getUniqueId());
    }

    private void openMain(Player player, Machine machine, int page) {
        TradingHallSpec spec = specOf(machine);
        TradingHallData d = machine.tradingHall();
        int usable = spec.slotsForTier(d.tier());
        int pages = Math.max(1, (int) Math.ceil(usable / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        TradingHallMenuHolder holder = new TradingHallMenuHolder(machine, TradingHallMenuHolder.View.MAIN, page);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>✦ Virtual Trading Hall"));
        holder.setInventory(inv);

        String tierLabel = spec.maxTier() > 0 ? d.tier() + "<gray>/<yellow>" + spec.maxTier() : String.valueOf(d.tier());
        inv.setItem(SLOT_INFO, icon(Material.BELL, "<gold>Virtual Trading Hall", List.of(
                "<gray>Tier <yellow>" + tierLabel,
                "<gray>Slots <yellow>" + usable + "<gray> · page <yellow>" + (page + 1) + "<gray>/<yellow>" + pages,
                "<gray>Vault cap <yellow>" + spec.vaultCapForTier(d.tier()))));
        // Upgrade button
        if (spec.canUpgrade(d.tier())) {
            List<String> ul = new ArrayList<>();
            ul.add("<gray>Feed blocks to upgrade the stall:");
            for (ItemStack req : spec.upgrade())
                ul.add("<dark_gray> • <white>" + req.getAmount() + "x " + pretty(req.getType()));
            ul.add("<gray>Next: <yellow>" + spec.slotsForTier(d.tier() + 1) + "<gray> slots · vault <yellow>"
                    + spec.vaultCapForTier(d.tier() + 1));
            ul.add("<yellow>Click<gray> to upgrade (inventory + vault).");
            inv.setItem(SLOT_UPGRADE, icon(Material.ANVIL, "<gold>Upgrade stall <gray>(Tier " + (d.tier() + 1) + ")", ul));
        } else {
            inv.setItem(SLOT_UPGRADE, icon(Material.ANVIL, "<dark_gray>Upgrade stall", List.of("<dark_gray>Max tier reached.")));
        }
        inv.setItem(SLOT_ROSTER, icon(Material.VILLAGER_SPAWN_EGG, "<green>Villager roster", List.of(
                "<gray>Blank villagers ready: <white>" + d.blankVillagers(),
                "<dark_gray>Shift-right-click a wild villager within",
                "<dark_gray><white>" + spec.captureRadius() + "<dark_gray> blocks of this hall to capture it.")));
        inv.setItem(SLOT_OUTPUT, icon(d.outputToVault() ? Material.HOPPER : Material.CHEST_MINECART,
                "<gold>Trade output: " + (d.outputToVault() ? "<aqua>Vault" : "<green>Inventory"), List.of(
                "<gray>Where bought items go.",
                "<gray>Inventory mode overflows to the vault.",
                "<yellow>Click<gray> to toggle.")));
        inv.setItem(SLOT_VAULT, icon(Material.ENDER_CHEST, "<light_purple>Quantum Vault", List.of(
                "<gray>Stored mass: <white>" + d.vaultMass(),
                "<yellow>Click<gray> to open — deposit / withdraw.")));
        inv.setItem(SLOT_SHOP, icon(d.open() ? Material.GREEN_BANNER : Material.RED_BANNER,
                "<gold>Shopfront: " + (d.open() ? "<green>OPEN" : "<red>CLOSED"), List.of(
                "<gray>When open, anyone may trade here.",
                "<gray>Their payments go to your <gold>till<gray> (not the vault).",
                "<yellow>Click<gray> to toggle.")));
        inv.setItem(SLOT_TILL, icon(Material.GOLD_INGOT, "<gold>Earnings till", List.of(
                "<gray>Shop revenue waiting to collect.",
                "<gray>Stored mass: <white>" + d.tillMass(),
                "<yellow>Click<gray> to open — collect / sweep to vault.")));

        int first = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && GRID_START + i < GRID_END; i++) {
            int slotIdx = first + i;
            if (slotIdx >= usable) { inv.setItem(GRID_START + i, filler()); continue; }
            inv.setItem(GRID_START + i, slotIcon(spec, d.slot(slotIdx)));
        }
        if (page > 0) inv.setItem(SLOT_PREV, icon(Material.ARROW, "<yellow>◀ Previous", List.of()));
        if (page < pages - 1) inv.setItem(SLOT_NEXT, icon(Material.ARROW, "<yellow>Next ▶", List.of()));

        player.openInventory(inv);
    }

    /** Customer (non-owner) MAIN: a read-only catalog of trader stations — no management controls. */
    private void openCustomerMain(Player player, Machine machine, int page) {
        TradingHallSpec spec = specOf(machine);
        TradingHallData d = machine.tradingHall();
        int usable = spec.slotsForTier(d.tier());
        int pages = Math.max(1, (int) Math.ceil(usable / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        TradingHallMenuHolder holder = new TradingHallMenuHolder(machine, TradingHallMenuHolder.View.MAIN, page);
        holder.setCustomer(true);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>✦ Trading Hall <gray>(shop)"));
        holder.setInventory(inv);

        String ownerName = machine.owner() == null ? "?" : Bukkit.getOfflinePlayer(machine.owner()).getName();
        inv.setItem(SLOT_INFO, icon(Material.EMERALD, "<gold>" + (ownerName == null ? "Shop" : ownerName + "'s shop"), List.of(
                "<gray>Open station to trade. You pay from",
                "<gray>your inventory; goods go to you.",
                "<gray>Page <yellow>" + (page + 1) + "<gray>/<yellow>" + pages)));

        int first = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && GRID_START + i < GRID_END; i++) {
            int slotIdx = first + i;
            if (slotIdx >= usable) { inv.setItem(GRID_START + i, filler()); continue; }
            TradingHallData.Slot s = d.slot(slotIdx);
            // customers only see open, trader-holding stations; empties/unmanned show as inert glass
            if (s == null || !s.hasTrader()) { inv.setItem(GRID_START + i, filler()); continue; }
            inv.setItem(GRID_START + i, slotIcon(spec, s));
        }
        if (page > 0) inv.setItem(SLOT_PREV, icon(Material.ARROW, "<yellow>◀ Previous", List.of()));
        if (page < pages - 1) inv.setItem(SLOT_NEXT, icon(Material.ARROW, "<yellow>Next ▶", List.of()));
        player.openInventory(inv);
    }

    /** Customer (non-owner) TRADER: trade-only — right-click buy, shift-right bulk. No management. */
    private void openCustomerTrader(Player player, Machine machine, int hallSlot) {
        TradingHallSpec spec = specOf(machine);
        TradingHallData.Slot s = machine.tradingHall().slot(hallSlot);
        if (s == null || !s.hasTrader()) { openCustomerMain(player, machine, 0); return; }
        TradingHallData.Trader t = s.trader;
        TradingHallSpec.Profession prof = spec.profession(t.professionId);

        TradingHallMenuHolder holder = new TradingHallMenuHolder(machine, TradingHallMenuHolder.View.TRADER, 0);
        holder.setSlotIndex(hallSlot);
        holder.setCustomer(true);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>"
                + (prof != null ? prof.display : "Trader") + " <gray>(shop)"));
        holder.setInventory(inv);

        inv.setItem(SLOT_INFO, icon(Material.EMERALD, "<green>" + (prof != null ? prof.display : "Trader"), List.of(
                "<gray>Level <yellow>" + t.level,
                "<gray>You pay from your inventory.",
                "<gray>Goods are delivered to you.")));
        for (int i = 0; i < t.offers.size() && GRID_START + i < GRID_END; i++) {
            inv.setItem(GRID_START + i, customerOfferIcon(t.offers.get(i), t.effectiveMaxUses(t.offers.get(i))));
        }
        if (t.offers.isEmpty()) inv.setItem(22, icon(Material.BARRIER, "<red>No trades", List.of()));
        inv.setItem(T_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        player.openInventory(inv);
    }

    /** Owner earnings till: collect a stack / all of a type, or sweep everything into the vault. */
    private void openTill(Player player, Machine machine, int page) {
        TradingHallData d = machine.tradingHall();
        List<Map.Entry<ItemStack, Long>> entries = new ArrayList<>(d.till().entrySet());
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        TradingHallMenuHolder holder = new TradingHallMenuHolder(machine, TradingHallMenuHolder.View.TILL, page);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<gold>Earnings Till"));
        holder.setInventory(inv);

        inv.setItem(SLOT_INFO, icon(Material.GOLD_INGOT, "<gold>Earnings Till", List.of(
                "<gray>Stored mass: <white>" + d.tillMass(),
                "<gray>Stack click: <yellow>take one stack <gray>· <yellow>shift/right<gray> take all",
                "<gray>Shop payments land here — collect them.")));

        int first = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && GRID_START + i < GRID_END; i++) {
            int idx = first + i;
            if (idx >= entries.size()) break;
            Map.Entry<ItemStack, Long> e = entries.get(idx);
            ItemStack ic = e.getKey().clone();
            ic.setAmount(Math.max(1, Math.min(ic.getMaxStackSize(), e.getValue().intValue())));
            ItemMeta meta = ic.getItemMeta();
            if (meta != null) {
                meta.lore(List.of(mini("<gray>Stored: <white>" + e.getValue()),
                        mini("<yellow>Click<gray> take stack · <yellow>shift/right<gray> take all")));
                ic.setItemMeta(meta);
            }
            inv.setItem(GRID_START + i, ic);
        }
        inv.setItem(TILL_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        inv.setItem(TILL_SWEEP, icon(Material.HOPPER, "<aqua>Sweep to vault", List.of(
                "<gray>Move all earnings into the operating vault.",
                "<yellow>Click<gray> (capped at the vault limit).")));
        if (page > 0) inv.setItem(TILL_PREV, icon(Material.ARROW, "<yellow>◀ Previous", List.of()));
        if (page < pages - 1) inv.setItem(TILL_NEXT, icon(Material.ARROW, "<yellow>Next ▶", List.of()));
        player.openInventory(inv);
    }

    private void openBuild(Player player, Machine machine, int hallSlot) {
        TradingHallSpec spec = specOf(machine);
        TradingHallMenuHolder holder = new TradingHallMenuHolder(machine, TradingHallMenuHolder.View.BUILD, 0);
        holder.setSlotIndex(hallSlot);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>Build a station"));
        holder.setInventory(inv);

        inv.setItem(SLOT_INFO, icon(Material.CRAFTING_TABLE, "<gold>Choose a profession", List.of(
                "<gray>Each station consumes its blocks", "<gray>from your inventory <gray>+<gray> the vault.")));
        List<TradingHallSpec.Profession> profs = new ArrayList<>(spec.professions().values());
        for (int i = 0; i < profs.size() && GRID_START + i < GRID_END; i++) {
            inv.setItem(GRID_START + i, professionIcon(player, machine.tradingHall(), profs.get(i)));
        }
        inv.setItem(B_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        player.openInventory(inv);
    }

    private void openTrader(Player player, Machine machine, int hallSlot) {
        TradingHallSpec spec = specOf(machine);
        TradingHallData.Slot s = machine.tradingHall().slot(hallSlot);
        if (s == null || !s.hasTrader()) { openMain(player, machine, 0); return; }
        TradingHallData.Trader t = s.trader;
        TradingHallSpec.Profession prof = spec.profession(t.professionId);

        TradingHallMenuHolder holder = new TradingHallMenuHolder(machine, TradingHallMenuHolder.View.TRADER, 0);
        holder.setSlotIndex(hallSlot);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>"
                + (prof != null ? prof.display : "Trader")));
        holder.setInventory(inv);

        inv.setItem(SLOT_INFO, icon(Material.EMERALD, "<green>" + (prof != null ? prof.display : "Trader"), List.of(
                "<gray>Level <yellow>" + t.level + "<gray> · XP <white>" + t.xp,
                "<gray>Trades unlock as it levels up.",
                "<gray>Stack <white>×" + t.count)));

        // offers — each is click-to-reroll
        for (int i = 0; i < t.offers.size() && GRID_START + i < GRID_END; i++) {
            inv.setItem(GRID_START + i, offerIcon(t.offers.get(i), t.effectiveMaxUses(t.offers.get(i))));
        }
        if (t.offers.isEmpty()) inv.setItem(22, icon(Material.BARRIER, "<red>No trades", List.of()));

        inv.setItem(T_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        inv.setItem(T_RESET, icon(Material.NETHER_STAR, "<aqua>Reset stall", List.of(
                "<gray>Re-roll <white>all<gray> trades fresh.",
                "<gray>Clears the per-trade locks from trading.")));
        inv.setItem(T_REMOVE, icon(Material.BARRIER, "<red>Remove villager", List.of("<gray>Returns it to the roster.")));
        TradingHallSpec spec2 = specOf(machine);
        if (spec2 != null && spec2.canStockUpgrade()) {
            List<String> sl = new ArrayList<>();
            sl.add("<gray>This shop's stock: <yellow>×" + (1 + t.stockLevel));
            sl.add("<gray>Feed to reach <yellow>×" + (2 + t.stockLevel) + "<gray> (more uses per trade):");
            for (ItemStack req : spec2.stockUpgrade()) sl.add("<dark_gray> • <white>" + req.getAmount() + "x " + pretty(req.getType()));
            sl.add("<yellow>Click<gray> to upgrade (inventory + vault).");
            inv.setItem(T_STOCK, icon(Material.EXPERIENCE_BOTTLE, "<gold>Upgrade stock <gray>(×" + (1 + t.stockLevel) + ")", sl));
        }
        player.openInventory(inv);
    }

    private void openVault(Player player, Machine machine, int page) {
        TradingHallData d = machine.tradingHall();
        List<Map.Entry<ItemStack, Long>> entries = new ArrayList<>(d.vault().entrySet());
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        TradingHallMenuHolder holder = new TradingHallMenuHolder(machine, TradingHallMenuHolder.View.VAULT, page);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<light_purple>Quantum Vault"));
        holder.setInventory(inv);

        inv.setItem(SLOT_INFO, icon(Material.ENDER_CHEST, "<light_purple>Quantum Vault", List.of(
                "<gray>Stored mass: <white>" + d.vaultMass(),
                "<gray>Stack click: <yellow>take one stack <gray>· <yellow>shift/right<gray> take all",
                "<gray>Shift-click items in your inventory below to <green>deposit<gray>.")));

        int first = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && GRID_START + i < GRID_END; i++) {
            int idx = first + i;
            if (idx >= entries.size()) break;
            Map.Entry<ItemStack, Long> e = entries.get(idx);
            ItemStack ic = e.getKey().clone();
            ic.setAmount(Math.max(1, Math.min(ic.getMaxStackSize(), e.getValue().intValue())));
            ItemMeta meta = ic.getItemMeta();
            if (meta != null) {
                meta.lore(List.of(mini("<gray>Stored: <white>" + e.getValue()),
                        mini("<yellow>Click<gray> take stack · <yellow>shift/right<gray> take all")));
                ic.setItemMeta(meta);
            }
            inv.setItem(GRID_START + i, ic);
        }
        inv.setItem(V_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        if (page > 0) inv.setItem(V_PREV, icon(Material.ARROW, "<yellow>◀ Previous", List.of()));
        if (page < pages - 1) inv.setItem(V_NEXT, icon(Material.ARROW, "<yellow>Next ▶", List.of()));
        player.openInventory(inv);
    }

    // --- click handling (sealed) ---------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof TradingHallMenuHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Machine m = holder.machine();
        Inventory top = e.getView().getTopInventory();

        // VAULT: click any item in your own inventory to deposit it (no shift needed).
        if (holder.view() == TradingHallMenuHolder.View.VAULT
                && e.getClickedInventory() != null && e.getClickedInventory() != top) {
            ItemStack clicked = e.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                long dep = actions.deposit(m, p, clicked);
                if (dep > 0) {
                    if (dep >= clicked.getAmount()) e.getClickedInventory().setItem(e.getSlot(), null);
                    else clicked.setAmount(clicked.getAmount() - (int) dep);
                    later(() -> openVault(p, m, holder.page()));
                }
            }
            return;
        }
        // VAULT: click the vault grid while holding an item on the cursor → deposit the cursor.
        if (holder.view() == TradingHallMenuHolder.View.VAULT && e.getClickedInventory() == top) {
            ItemStack cursor = e.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                long dep = actions.deposit(m, p, cursor);
                if (dep > 0) {
                    int remain = cursor.getAmount() - (int) dep;
                    e.getView().setCursor(remain > 0 ? withAmount(cursor, remain) : null);
                    later(() -> openVault(p, m, holder.page()));
                }
                return;
            }
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;
        int slot = e.getSlot();

        switch (holder.view()) {
            case MAIN -> {
                if (holder.customer()) {                                       // shopfront customer: catalog only
                    if (slot == SLOT_PREV) { later(() -> openCustomerMain(p, m, holder.page() - 1)); }
                    else if (slot == SLOT_NEXT) { later(() -> openCustomerMain(p, m, holder.page() + 1)); }
                    else if (slot >= GRID_START && slot < GRID_END) {
                        int hallSlot = holder.page() * PER_PAGE + (slot - GRID_START);
                        TradingHallData.Slot s = m.tradingHall().slot(hallSlot);
                        if (s != null && s.hasTrader()) later(() -> openCustomerTrader(p, m, hallSlot));
                    }
                    return;
                }
                if (slot == SLOT_ROSTER) {
                    p.sendMessage(mini("<gray>Shift-right-click a wild villager near this hall to capture it into the roster."));
                } else if (slot == SLOT_UPGRADE) { actions.upgradeStall(m, p); later(() -> openMain(p, m, holder.page())); }
                else if (slot == SLOT_OUTPUT) { actions.toggleOutput(m, p); later(() -> openMain(p, m, holder.page())); }
                else if (slot == SLOT_VAULT) { later(() -> openVault(p, m, 0)); }
                else if (slot == SLOT_SHOP) { actions.toggleShopfront(m, p); later(() -> openMain(p, m, holder.page())); }
                else if (slot == SLOT_TILL) { later(() -> openTill(p, m, 0)); }
                else if (slot == SLOT_PREV) { later(() -> openMain(p, m, holder.page() - 1)); }
                else if (slot == SLOT_NEXT) { later(() -> openMain(p, m, holder.page() + 1)); }
                else if (slot >= GRID_START && slot < GRID_END) {
                    int hallSlot = holder.page() * PER_PAGE + (slot - GRID_START);
                    TradingHallData.Slot s = m.tradingHall().slot(hallSlot);
                    if (s == null) return;
                    if (!s.isBuilt()) later(() -> openBuild(p, m, hallSlot));
                    else if (!s.hasTrader()) { actions.insertVillager(m, p, hallSlot); later(() -> openMain(p, m, holder.page())); }
                    else later(() -> openTrader(p, m, hallSlot));
                }
            }
            case BUILD -> {
                if (slot == B_BACK) { later(() -> openMain(p, m, 0)); return; }
                if (slot >= GRID_START && slot < GRID_END) {
                    TradingHallSpec spec = specOf(m);
                    List<TradingHallSpec.Profession> profs = new ArrayList<>(spec.professions().values());
                    int idx = slot - GRID_START;
                    if (idx < profs.size()) {
                        boolean built = actions.buildStation(m, p, holder.slotIndex(), profs.get(idx).id);
                        int back = holder.slotIndex();
                        later(() -> { if (built) openMain(p, m, back / PER_PAGE); else openBuild(p, m, back); });
                    }
                }
            }
            case TRADER -> {
                int hallSlot = holder.slotIndex();
                if (holder.customer()) {                                       // shopfront customer: buy only
                    if (slot == T_BACK) { later(() -> openCustomerMain(p, m, hallSlot / PER_PAGE)); }
                    else if (slot >= GRID_START && slot < GRID_END && e.isRightClick()) {
                        int offerIdx = slot - GRID_START;
                        actions.executeTrade(m, p, hallSlot, offerIdx, e.isShiftClick());
                        later(() -> openCustomerTrader(p, m, hallSlot));
                    }
                    return;
                }
                if (slot == T_BACK) { later(() -> openMain(p, m, hallSlot / PER_PAGE)); }
                else if (slot == T_STOCK) { actions.upgradeStock(m, p, hallSlot); later(() -> openTrader(p, m, hallSlot)); }
                else if (slot == T_RESET) { actions.resetStall(m, p, hallSlot); later(() -> openTrader(p, m, hallSlot)); }
                else if (slot == T_REMOVE) { actions.removeVillager(m, p, hallSlot); later(() -> openMain(p, m, hallSlot / PER_PAGE)); }
                else if (slot >= GRID_START && slot < GRID_END) {
                    int offerIdx = slot - GRID_START;
                    if (e.isRightClick()) {                                  // right-click = trade (vault→inventory)
                        actions.executeTrade(m, p, hallSlot, offerIdx, e.isShiftClick());
                        later(() -> openTrader(p, m, hallSlot));
                    } else if (e.isShiftClick()) {                            // shift-left = toggle per-trade auto
                        actions.toggleAuto(m, p, hallSlot, offerIdx);
                        later(() -> openTrader(p, m, hallSlot));
                    } else {                                                  // left-click = re-roll (if not locked)
                        TradingHallData.Slot s = m.tradingHall().slot(hallSlot);
                        boolean locked = s != null && s.hasTrader() && offerIdx < s.trader.offers.size()
                                && s.trader.offers.get(offerIdx).locked;
                        if (locked) p.sendMessage(mini("<red>That trade is locked (already traded) — Reset the stall to re-roll."));
                        else if (actions.rerollOffer(m, p, hallSlot, offerIdx)) later(() -> openTrader(p, m, hallSlot));
                    }
                }
            }
            case VAULT -> {
                if (slot == V_BACK) { later(() -> openMain(p, m, 0)); }
                else if (slot == V_PREV) { later(() -> openVault(p, m, holder.page() - 1)); }
                else if (slot == V_NEXT) { later(() -> openVault(p, m, holder.page() + 1)); }
                else if (slot >= GRID_START && slot < GRID_END) {            // withdraw a stored stack
                    java.util.List<Map.Entry<ItemStack, Long>> entries = new ArrayList<>(m.tradingHall().vault().entrySet());
                    int idx = holder.page() * PER_PAGE + (slot - GRID_START);
                    if (idx < entries.size()) {
                        ItemStack template = entries.get(idx).getKey();
                        actions.withdraw(m, p, template, e.isShiftClick() || e.isRightClick());
                        later(() -> openVault(p, m, holder.page()));
                    }
                }
            }
            case TILL -> {
                if (slot == TILL_BACK) { later(() -> openMain(p, m, 0)); }
                else if (slot == TILL_SWEEP) { actions.sweepTillToVault(m, p); later(() -> openTill(p, m, holder.page())); }
                else if (slot == TILL_PREV) { later(() -> openTill(p, m, holder.page() - 1)); }
                else if (slot == TILL_NEXT) { later(() -> openTill(p, m, holder.page() + 1)); }
                else if (slot >= GRID_START && slot < GRID_END) {            // collect a stored stack
                    java.util.List<Map.Entry<ItemStack, Long>> entries = new ArrayList<>(m.tradingHall().till().entrySet());
                    int idx = holder.page() * PER_PAGE + (slot - GRID_START);
                    if (idx < entries.size()) {
                        ItemStack template = entries.get(idx).getKey();
                        actions.collectTill(m, p, template, e.isShiftClick() || e.isRightClick());
                        later(() -> openTill(p, m, holder.page()));
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof TradingHallMenuHolder holder)) return;
        e.setCancelled(true);
        if (holder.view() != TradingHallMenuHolder.View.VAULT) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int topSize = e.getView().getTopInventory().getSize();
        boolean intoVault = e.getRawSlots().stream().anyMatch(s -> s < topSize);
        if (!intoVault) return;                                   // dragging within own inventory → just cancel
        ItemStack dragged = e.getOldCursor();
        if (dragged == null || dragged.getType().isAir()) return;
        ItemStack toDeposit = dragged.clone();
        long dep = actions.deposit(holder.machine(), p, toDeposit);
        int remain = dragged.getAmount() - (int) dep;
        ItemStack rest = remain > 0 ? withAmount(dragged, remain) : null;
        later(() -> { p.setItemOnCursor(rest); openVault(p, holder.machine(), holder.page()); });
    }

    // --- icons ---------------------------------------------------------------

    private ItemStack slotIcon(TradingHallSpec spec, TradingHallData.Slot slot) {
        if (slot == null || !slot.isBuilt())
            return icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>Empty slot",
                    List.of("<yellow>Click<gray> to build a profession station."));
        TradingHallSpec.Profession prof = spec.profession(slot.stationProfessionId);
        String name = prof != null ? prof.display : slot.stationProfessionId;
        if (!slot.hasTrader())
            return icon(stationMaterial(prof), "<aqua>" + name + " Station",
                    List.of("<gray>Built. <yellow>Click<gray> to insert a villager."));
        TradingHallData.Trader t = slot.trader;
        return icon(Material.EMERALD, "<green>" + name + " <gray>(trader)", List.of(
                "<gray>Level <yellow>" + t.level + "<gray> · Trades <white>" + t.offers.size()
                        + (t.count > 1 ? " <gray>· ×" + t.count : ""),
                "<yellow>Click<gray> to manage / trade."));
    }

    private ItemStack professionIcon(Player p, TradingHallData d, TradingHallSpec.Profession prof) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Station blocks <gray>(you have / need):");
        boolean can = true;
        for (ItemStack req : prof.station) {
            long have = TradingHallManager.available(p, d, req);
            boolean ok = have >= req.getAmount();
            if (!ok) can = false;
            lore.add((ok ? "<green>" : "<red>") + "  " + Math.min(have, req.getAmount()) + "/" + req.getAmount()
                    + " <gray>" + pretty(req.getType()));
        }
        lore.add(can ? "<green>Ready — click to build." : "<red>Missing materials (inventory + vault).");
        return icon(stationMaterial(prof), (can ? "<green>" : "<gray>") + prof.display, lore);
    }

    private ItemStack offerIcon(TradingHallData.LiveOffer o, int maxUses) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Buy: <white>" + amt(o.buyA) + (o.buyB != null ? " <gray>+ <white>" + amt(o.buyB) : ""));
        lore.add("<gray>Sell: <white>" + amt(o.sell));
        lore.add("<gray>Uses: <white>" + o.uses + "<gray>/<white>" + maxUses + " <dark_gray>· tier " + o.level);
        lore.add("");
        lore.add("<yellow>Right-click<gray> to trade <dark_gray>(vault → inventory)");
        lore.add("<dark_gray>shift = trade as many as possible");
        if (o.locked) lore.add("<red>Locked (traded) — Reset stall to re-roll");
        else lore.add("<yellow>Left-click<gray> to re-roll this trade");
        lore.add("<gray>Auto-trade: " + (o.autoTrade ? "<green>ON" : "<red>OFF")
                + " <dark_gray>(<yellow>shift-left<dark_gray> toggle, vault-fed)");
        ItemStack icon = o.sell.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(mini("<green>" + amt(o.sell)));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(mini(s));
            meta.lore(l);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /** Customer-facing offer: price + result only, trade-only hints (no re-roll/auto). */
    private ItemStack customerOfferIcon(TradingHallData.LiveOffer o, int maxUses) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Buy: <white>" + amt(o.buyA) + (o.buyB != null ? " <gray>+ <white>" + amt(o.buyB) : ""));
        lore.add("<gray>Sell: <white>" + amt(o.sell));
        lore.add("<gray>In stock: <white>" + Math.max(0, maxUses - o.uses) + "<gray>/<white>" + maxUses);
        lore.add("");
        lore.add("<yellow>Right-click<gray> to buy <dark_gray>(paid from your inventory)");
        lore.add("<dark_gray>shift = buy as many as you can");
        ItemStack icon = o.sell.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(mini("<green>" + amt(o.sell)));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(mini(s));
            meta.lore(l);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static Material stationMaterial(TradingHallSpec.Profession prof) {
        if (prof != null && !prof.station.isEmpty()) return prof.station.get(0).getType();
        return Material.LECTERN;
    }

    // --- helpers -------------------------------------------------------------

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }
    private static ItemStack withAmount(ItemStack s, int amount) { ItemStack c = s.clone(); c.setAmount(Math.max(1, amount)); return c; }
    private TradingHallSpec specOf(Machine m) { MachineType t = registry == null ? null : registry.getMachineType(m.typeId()); return t == null ? null : t.tradingHall(); }

    /** "1× Enchanted Book (Mending)" — appends enchants for books/tools. */
    private static String amt(ItemStack s) {
        String e = enchantText(s);
        return s.getAmount() + "× " + pretty(s.getType()) + (e.isEmpty() ? "" : " <gray>(<aqua>" + e + "<gray>)");
    }

    private static String enchantText(ItemStack s) {
        org.bukkit.inventory.meta.ItemMeta m = s.getItemMeta();
        java.util.Map<org.bukkit.enchantments.Enchantment, Integer> ench;
        if (m instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta esm && !esm.getStoredEnchants().isEmpty())
            ench = esm.getStoredEnchants();
        else if (m != null && !m.getEnchants().isEmpty()) ench = m.getEnchants();
        else return "";
        StringBuilder sb = new StringBuilder();
        for (var e : ench.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(prettyWords(e.getKey().getKey().getKey()));
            if (e.getValue() > 1) sb.append(' ').append(e.getValue());
        }
        return sb.toString();
    }

    private static String pretty(Material mat) { return prettyWords(mat.name().toLowerCase()); }
    private static String prettyWords(String snake) {
        String[] parts = snake.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : parts) if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return sb.toString().trim();
    }

    private static ItemStack icon(Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat == null ? Material.STONE : mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(mini(name));
            if (!loreLines.isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String l : loreLines) lore.add(mini(l));
                meta.lore(lore);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack filler() { return icon(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()); }
    private static Component mini(String s) { return MM.deserialize(s).decoration(TextDecoration.ITALIC, false); }
}
