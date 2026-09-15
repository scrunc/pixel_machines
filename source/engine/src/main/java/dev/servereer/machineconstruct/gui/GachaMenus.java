package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.core.Heads;
import dev.servereer.machineconstruct.gacha.GachaManager;
import dev.servereer.machineconstruct.gacha.GachaSeries;
import dev.servereer.machineconstruct.gacha.GachaSpec;
import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The capsule machine's sealed GUI (ADR 0045): MAIN (pull ×1 / ×N, your pity, collection, odds),
 * COLLECTION (the series' pieces — owned ones show the real item, the rest their capsule),
 * ODDS (rarity shares + pity), RESULT (a ×N pull's haul) and ADMIN (feed the held item as a new
 * piece, cycle rarities, toggle collectible, remove). Look + wording from the machine's
 * {@link MenuSkin} ({@code skin:}), defaults built in.
 */
public final class GachaMenus implements Listener {

    public static final class Holder implements InventoryHolder {
        public enum View { MAIN, COLLECTION, ODDS, RESULT, ADMIN }
        final Machine machine; View view; int page; List<GachaManager.Result> results; Inventory inv;
        Holder(Machine m, View v) { machine = m; view = v; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int SIZE = 54;
    private final Plugin plugin;
    private final GachaManager gacha;
    private final Function<Machine, MachineType> typeOf;

    public GachaMenus(Plugin plugin, GachaManager gacha, Function<Machine, MachineType> typeOf) {
        this.plugin = plugin; this.gacha = gacha; this.typeOf = typeOf;
    }

    /** Admins land on the loading view (players never get a GUI — the dial is the interface). */
    public void open(Player p, Machine m) { show(p, m, p.hasPermission("machineconstruct.admin") ? Holder.View.ADMIN : Holder.View.MAIN, 0, null); }
    public void showResults(Player p, Machine m, List<GachaManager.Result> results) { show(p, m, Holder.View.RESULT, 0, results); }

    private void show(Player p, Machine m, Holder.View view, int page, List<GachaManager.Result> results) {
        MachineType t = typeOf.apply(m);
        if (t == null || t.gacha() == null) return;
        Holder h = new Holder(m, view); h.page = page; h.results = results;
        MenuSkin sk = t.skin();
        String title = switch (view) {
            case MAIN -> sk.title("gacha_main", "<dark_aqua>✦ {title}");
            case COLLECTION -> sk.title("gacha_collection", "<dark_aqua>✦ {title} · Collection");
            case ODDS -> sk.title("gacha_odds", "<dark_aqua>✦ {title} · Odds");
            case RESULT -> sk.title("gacha_result", "<dark_aqua>✦ {title} · Your haul");
            case ADMIN -> sk.title("gacha_admin", "<dark_aqua>✦ {title} · Admin");
        };
        h.inv = Bukkit.createInventory(h, SIZE, MenuSkin.mini(MenuSkin.fill(title, MenuSkin.vars("title", t.gacha().title()))));
        render(h, p, t);
        p.openInventory(h.inv);
    }

    private void render(Holder h, Player p, MachineType t) {
        Inventory inv = h.inv; inv.clear();
        MenuSkin sk = t.skin();
        GachaSpec spec = t.gacha();
        GachaSeries s = gacha.series(spec);
        GachaSeries.Record rec = s.record(p.getUniqueId());
        String viewKey = "gacha_" + h.view.name().toLowerCase();
        ItemStack edge = sk.item("filler", sk.filler(viewKey, Material.BLACK_STAINED_GLASS_PANE), " ", List.of(), null);
        for (int i = 0; i < 9; i++) inv.setItem(i, edge);
        for (int i = 45; i < SIZE; i++) inv.setItem(i, edge);
        sk.paintAccents(viewKey, inv::setItem);
        int owned = 0; for (GachaSeries.Entry e : s.loot()) if (rec.owned.contains(e.id)) owned++;
        Map<String, String> v = MenuSkin.vars("title", spec.title(), "pulls", rec.pulls, "owned", owned, "total", s.loot().size(),
                "pity", spec.pityEvery() > 0 ? String.valueOf(Math.max(0, spec.pityEvery() - rec.sinceRare)) : "-", "pity_rarity", spec.pityRarity() == null ? "" : spec.rarity(spec.pityRarity()).label(),
                "price", gacha.priceText(spec, 1), "price_multi", gacha.priceText(spec, spec.multi()), "multi", spec.multi());
        switch (h.view) {
            case MAIN -> {
                inv.setItem(sk.slot(viewKey, "info", 4), sk.item("gacha_info", Material.BOOK, "<aqua>{title}",
                        List.of("<gray>Your pulls: <white>{pulls}", "<gray>Collected: <white>{owned}<dark_gray>/<white>{total}",
                                spec.pityEvery() > 0 ? "<gray>Guaranteed <white>{pity_rarity}</white> in <white>{pity}</white> pull(s)" : "<dark_gray>No pity rule"), v));
                inv.setItem(sk.slot(viewKey, "pull", 20), sk.decorate("gacha_pull", capsuleIcon(spec, spec.rarityOrder().isEmpty() ? null : spec.rarity(spec.rarityOrder().get(0))),
                        "<green>Pull ×1 <dark_gray>» <gold>{price}", List.of("<gray>Turn the dial once.", "<dark_gray>▶ Click"), v));
                if (spec.multi() > 1) inv.setItem(sk.slot(viewKey, "pull_multi", 22), sk.decorate("gacha_pull_multi", capsuleIcon(spec, spec.rarity(spec.rarityOrder().get(spec.rarityOrder().size() - 1))),
                        "<green>Pull ×{multi} <dark_gray>» <gold>{price_multi}", List.of("<gray>{multi} capsules, one reveal.", "<dark_gray>▶ Click"), v));
                inv.setItem(sk.slot(viewKey, "collection", 24), sk.item("gacha_collection", Material.CHEST, "<aqua>Collection <dark_gray>» <white>{owned}<dark_gray>/<white>{total}",
                        List.of("<gray>Every piece in this series — yours lit up.", "<dark_gray>▶ Click"), v));
                inv.setItem(sk.slot(viewKey, "odds", 31), sk.item("gacha_odds", Material.COMPARATOR, "<aqua>Odds", List.of("<gray>Rarity shares and the pity rule.", "<dark_gray>▶ Click"), v));
                if (p.hasPermission("machineconstruct.admin"))
                    inv.setItem(sk.slot(viewKey, "admin", 49), sk.item("gacha_admin", Material.COMMAND_BLOCK, "<red>Admin <dark_gray>» <white>load the machine", List.of("<gray>Feed held items as pieces, set rarities.", "<dark_gray>▶ Click"), v));
            }
            case COLLECTION, ADMIN -> {
                boolean admin = h.view == Holder.View.ADMIN;
                List<GachaSeries.Entry> all = new ArrayList<>(s.loot());
                all.sort((a, b) -> Integer.compare(spec.rank(b.rarity), spec.rank(a.rarity)));
                int per = 36, pages = Math.max(1, (all.size() + per - 1) / per);
                h.page = Math.min(h.page, pages - 1);
                for (int i = 0; i < per; i++) {
                    int idx = h.page * per + i; if (idx >= all.size()) break;
                    GachaSeries.Entry e = all.get(idx);
                    GachaSpec.Rarity r = spec.rarity(e.rarity);
                    boolean has = rec.owned.contains(e.id);
                    int n = rec.counts.getOrDefault(e.id, 0);
                    ItemStack icon;
                    List<String> lore = new ArrayList<>();
                    lore.add("<" + r.color() + ">" + r.label() + (e.once ? " <dark_gray>· collectible" : ""));
                    if (admin) {
                        icon = e.shown();
                        if (e.isCommand()) lore.add("<gray>Runs: <white>" + String.join(" <dark_gray>· <white>", e.commands));
                        lore.add("<gray>Weight in rarity: <white>" + GachaManager.fmt(e.weight));
                        lore.add("<gray>Capsule: <white>" + (e.capsule == null || e.capsule.isBlank() ? "rarity's" : "custom"));
                        lore.add("<yellow>◀ Left: next rarity   <gold>▶ Right: collectible on/off");
                        lore.add("<red>⇧ Shift-left: remove this piece");
                    } else if (has) { icon = e.shown(); lore.add("<gray>Pulled <white>" + n + "×"); }
                    else { icon = Heads.create(e.capsule == null || e.capsule.isBlank() ? r.capsule() : e.capsule); lore.add("<dark_gray>Not pulled yet"); }
                    inv.setItem(9 + i, sk.decorate("gacha_piece", icon, (has || admin ? "<white>" : "<gray>") + e.name, lore, v));
                }
                if (h.page > 0) inv.setItem(sk.slot(viewKey, "prev", 48), sk.item("prev", Material.ARROW, "<yellow>◀ Previous page", List.of(), v));
                if (h.page < pages - 1) inv.setItem(sk.slot(viewKey, "next", 50), sk.item("next", Material.ARROW, "<yellow>Next page ▶", List.of(), v));
                inv.setItem(sk.slot(viewKey, "back", 45), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of(), v));
                if (admin) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    boolean holding = hand != null && !hand.getType().isAir();
                    inv.setItem(sk.slot(viewKey, "add", 49), sk.item("gacha_add", holding ? Material.LIME_DYE : Material.GRAY_DYE,
                            holding ? "<green>＋ Add the item in your hand" : "<gray>Hold an item to add it",
                            List.of("<gray>Added as <white>" + (spec.rarityOrder().isEmpty() ? "?" : spec.rarity(spec.rarityOrder().get(0)).label()) + "</white> — click it after to change.", "<gray>The exact stack (NBT, amount) is kept."), v));
                    inv.setItem(sk.slot(viewKey, "reload", 53), sk.item("gacha_reload", Material.REPEATER, "<aqua>Reload series files", List.of("<gray>After edits in Dev's Diary / the yml."), v));
                    if (!t.panelThemeModels().isEmpty()) {
                        String cur = h.machine.panel() != null && h.machine.panel().theme() != null ? h.machine.panel().theme() : t.panelTheme();
                        List<String> tl = new ArrayList<>();
                        tl.add("<gray>Swaps the machine's blocks + <white>{theme}</white> colour.");
                        for (String n : t.panelThemeModels().keySet()) tl.add((n.equals(cur) ? "<aqua>▸ " : "<dark_gray>  ") + n);
                        tl.add("<yellow>◀ Click: next theme");
                        inv.setItem(sk.slot(viewKey, "theme", 47), sk.item("gacha_theme", Material.CYAN_DYE, "<aqua>Theme <dark_gray>» <white>" + cur, tl, v));
                    }
                    inv.setItem(sk.slot(viewKey, "collection", 51), sk.item("gacha_collection", Material.CHEST, "<aqua>Collection / odds preview", List.of("<gray>What a player would see."), v));
                    ItemStack coin = gacha.coinOf(s);
                    inv.setItem(sk.slot(viewKey, "coin", 46), sk.decorate("gacha_coin", coin, "<gold>Coin <dark_gray>» <white>" + GachaSeries.displayName(coin) + (s.coin() == null ? " <dark_gray>(default)" : ""),
                            List.of("<gray>What a pull costs: <white>{price}", "<yellow>◀ Left: set the item in your hand as this series' coin",
                                    "<gold>▶ Right: give yourself 16 coins", "<red>⇧ Shift-left: back to the default token", "<dark_gray>/mc coin give <player> <n> " + s.key()), v));
                }
            }
            case ODDS -> {
                int slot = 10;
                for (String rn : spec.rarityOrder()) {
                    GachaSpec.Rarity r = spec.rarity(rn);
                    int pieces = s.byRarity(rn).size();
                    inv.setItem(slot, sk.decorate("gacha_rarity", capsuleIcon(spec, r), "<" + r.color() + ">" + r.label(),
                            List.of("<gray>Chance: <white>" + String.format(java.util.Locale.ROOT, "%.1f", spec.percent(rn)) + "%", "<gray>Pieces: <white>" + pieces), v));
                    slot += 2; if (slot % 9 == 8) slot += 3;
                }
                inv.setItem(sk.slot(viewKey, "pity", 31), sk.item("gacha_pity", Material.CLOCK, "<aqua>Pity",
                        spec.pityEvery() > 0 ? List.of("<gray>A <white>{pity_rarity}</white> or better is guaranteed", "<gray>every <white>" + spec.pityEvery() + "</white> pulls.", "<gray>Yours: next in <white>{pity}")
                                : List.of("<dark_gray>No pity rule on this machine."), v));
                inv.setItem(sk.slot(viewKey, "back", 45), sk.item("back", Material.BARRIER, "<red>◀ Back", List.of(), v));
            }
            case RESULT -> {
                int i = 0;
                for (GachaManager.Result r : h.results == null ? List.<GachaManager.Result>of() : h.results) {
                    if (9 + i >= 45) break;
                    ItemStack icon = r.entry().shown();
                    inv.setItem(9 + i++, sk.decorate("gacha_result", icon, "<" + r.rarity().color() + ">" + r.entry().name,
                            List.of("<gray>" + r.rarity().label() + (r.duplicate() ? " <dark_gray>· duplicate" : "")), v));
                }
                inv.setItem(sk.slot(viewKey, "back", 49), sk.item("gacha_again", Material.NETHER_STAR, "<green>Pull again", List.of(), v));
            }
        }
    }

    private static ItemStack capsuleIcon(GachaSpec spec, GachaSpec.Rarity r) {
        return r == null || r.capsule().isBlank() ? new ItemStack(Material.HEART_OF_THE_SEA) : Heads.create(r.capsule());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE) return;
        Machine m = h.machine;
        MachineType t = typeOf.apply(m);
        if (t == null || t.gacha() == null) return;
        MenuSkin sk = t.skin();
        GachaSpec spec = t.gacha();
        String viewKey = "gacha_" + h.view.name().toLowerCase();
        switch (h.view) {
            case MAIN -> {
                if (raw == sk.slot(viewKey, "pull", 20)) { p.closeInventory(); gacha.pull(p, m, t, 1); }
                else if (spec.multi() > 1 && raw == sk.slot(viewKey, "pull_multi", 22)) { p.closeInventory(); gacha.pull(p, m, t, spec.multi()); }
                else if (raw == sk.slot(viewKey, "collection", 24)) show(p, m, Holder.View.COLLECTION, 0, null);
                else if (raw == sk.slot(viewKey, "odds", 31)) show(p, m, Holder.View.ODDS, 0, null);
                else if (raw == sk.slot(viewKey, "admin", 49) && p.hasPermission("machineconstruct.admin")) show(p, m, Holder.View.ADMIN, 0, null);
            }
            case COLLECTION, ODDS -> {
                if (raw == sk.slot(viewKey, "back", 45)) show(p, m, Holder.View.MAIN, 0, null);
                else if (raw == sk.slot(viewKey, "prev", 48) && h.page > 0) show(p, m, h.view, h.page - 1, null);
                else if (raw == sk.slot(viewKey, "next", 50)) show(p, m, h.view, h.page + 1, null);
            }
            case RESULT -> { if (raw == sk.slot(viewKey, "back", 49)) p.closeInventory(); }
            case ADMIN -> {
                if (!p.hasPermission("machineconstruct.admin")) return;
                GachaSeries s = gacha.series(spec);
                if (raw == sk.slot(viewKey, "back", 45)) { show(p, m, Holder.View.MAIN, 0, null); return; }
                if (raw == sk.slot(viewKey, "prev", 48) && h.page > 0) { show(p, m, h.view, h.page - 1, null); return; }
                if (raw == sk.slot(viewKey, "next", 50)) { show(p, m, h.view, h.page + 1, null); return; }
                if (raw == sk.slot(viewKey, "reload", 53)) { gacha.reload(); p.sendMessage(MenuSkin.mini("<aqua>Capsules <dark_gray>» <gray>Series files reloaded.")); show(p, m, h.view, h.page, null); return; }
                if (raw == sk.slot(viewKey, "theme", 47) && !t.panelThemeModels().isEmpty()) { p.closeInventory(); String n = gacha.cycleTheme(m, t); if (n != null) p.sendMessage(MenuSkin.mini("<aqua>Capsules <dark_gray>» <gray>Theme <white>" + n + "</white>.")); return; }
                if (raw == sk.slot(viewKey, "collection", 51)) { show(p, m, Holder.View.MAIN, 0, null); return; }
                if (raw == sk.slot(viewKey, "coin", 46)) {
                    if (e.isShiftClick()) { s.setCoin(null); p.sendMessage(MenuSkin.mini("<aqua>Capsules <dark_gray>» <gray>Coin reset to the default token.")); }
                    else if (e.getClick() == ClickType.RIGHT) { gacha.giveCoins(p, 16, s); }
                    else {
                        ItemStack hand = p.getInventory().getItemInMainHand();
                        if (hand == null || hand.getType().isAir()) { p.sendMessage(MenuSkin.mini("<aqua>Capsules <dark_gray>» <red>Hold the item that should be the coin.")); return; }
                        s.setCoin(hand); p.sendMessage(MenuSkin.mini("<aqua>Capsules <dark_gray>» <gray>Coin is now <white>" + GachaSeries.displayName(hand) + "</white>."));
                    }
                    show(p, m, h.view, h.page, null); return;
                }
                if (raw == sk.slot(viewKey, "add", 49)) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand == null || hand.getType().isAir()) return;
                    String first = spec.rarityOrder().isEmpty() ? "common" : spec.rarityOrder().get(0);
                    GachaSeries.Entry en = s.add(hand, first, null);
                    p.sendMessage(MenuSkin.mini("<aqua>Capsules <dark_gray>» <gray>Added <white>" + en.name + "</white> as <white>" + first + "</white>. Click it to change rarity."));
                    show(p, m, h.view, h.page, null); return;
                }
                int idx = raw - 9;
                if (idx < 0 || idx >= 36) return;
                List<GachaSeries.Entry> all = new ArrayList<>(s.loot());
                all.sort((a, b) -> Integer.compare(spec.rank(b.rarity), spec.rank(a.rarity)));
                int at = h.page * 36 + idx;
                if (at >= all.size()) return;
                GachaSeries.Entry en = all.get(at);
                if (e.isShiftClick() && e.getClick() == ClickType.SHIFT_LEFT) { s.remove(en.id); }
                else if (e.getClick() == ClickType.RIGHT) { en.once = !en.once; s.save(); }
                else { List<String> order = spec.rarityOrder(); en.rarity = order.get((Math.max(0, order.indexOf(en.rarity)) + 1) % order.size()); s.save(); }
                show(p, m, h.view, h.page, null);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @SuppressWarnings("unused")
    private static ItemStack named(ItemStack it, String name) { ItemMeta im = it.getItemMeta(); if (im != null) { im.displayName(MenuSkin.mini(name)); it.setItemMeta(im); } return it; }
}
