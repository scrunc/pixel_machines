package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.api.MachineConstructAPI;
import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import dev.servereer.machineconstruct.grinder.econ.PriceService;
import dev.servereer.machineconstruct.quarry.QuarryActions;
import dev.servereer.machineconstruct.quarry.QuarryContracts;
import dev.servereer.machineconstruct.quarry.QuarryData;
import dev.servereer.machineconstruct.quarry.QuarryManager;
import dev.servereer.machineconstruct.quarry.QuarrySkill;
import dev.servereer.machineconstruct.quarry.QuarrySpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
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

/**
 * The Interdimensional Quarry menu (Q1: the MAIN control screen). Code-rendered
 * and sealed like {@link GrinderMenus}, with one twist — the pickaxe + rod slots
 * are <b>editable</b> (you insert/remove the tools that power it), handled
 * manually so there's no dupe surface. VAULT (Q3) and SKILLS (Q5) are stubbed.
 */
public final class QuarryMenus implements Listener {

    private static final int SIZE = 54;
    private static final int SLOT_PICK = 20;   // pickaxe tool slot (editable)
    private static final int SLOT_ROD = 24;    // fishing-rod tool slot (editable)
    private static final int SLOT_PICK_DURA = 29;
    private static final int SLOT_ROD_DURA = 33;
    private static final int SLOT_MODE = 31;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_DIM = 22;     // dimension selector (gated by Rift Drill)
    private static final int SLOT_CONTRACT = 13; // active contract (click = claim)
    private static final int SLOT_HEAT = 38;    // heat gauge (display)
    private static final int SLOT_LAVA = 40;    // lava tank (left=fill buckets, right=sell)
    private static final int SLOT_LAVA_TOGGLE = 42;  // lava generation on/off
    private static final int SLOT_VAULT = 48;
    private static final int SLOT_SKILLS = 50;

    // SKILLS screen: row 0 header, rows 1-4 = the four branches (col 0 = label, col 1+ = nodes), row 5 controls
    private static final int S_INFO = 4, S_BACK = 49, S_PRESTIGE = 8;
    private static final QuarrySkill.Branch[] S_BRANCHES =
            { QuarrySkill.Branch.EXCAVATION, QuarrySkill.Branch.ABYSSAL, QuarrySkill.Branch.VAULT, QuarrySkill.Branch.RIFT };

    // VAULT screen
    private static final int V_CONTENT_START = 9, V_CONTENT_END = 45, V_PER_PAGE = 36;
    private static final int V_VALUE = 8;
    private static final int V_PREV = 45, V_BACK = 46, V_COLLECT = 48, V_SORT = 49, V_SELL = 50, V_NEXT = 53;

    private final Plugin plugin;
    private final MachineConstructAPI registry;
    private final QuarryActions actions;
    private final Map<UUID, List<Inventory>> open = new HashMap<>();   // machine id → every open window (multi-viewer)
    private final Map<UUID, Long> lastRefresh = new HashMap<>();        // machine id → last repaint (sweep throttle)
    private static final long REFRESH_THROTTLE_MS = 1500;

    public QuarryMenus(Plugin plugin, MachineConstructAPI registry, QuarryActions actions) {
        this.plugin = plugin;
        this.registry = registry;
        this.actions = actions;
    }

    public void open(Player player, Machine machine) {
        if (!machine.hasQuarry()) machine.setQuarry(new QuarryData());
        actions.accrue(machine);
        actions.persist(machine);
        QuarryMenuHolder holder = new QuarryMenuHolder(machine, QuarryMenuHolder.View.MAIN);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_purple>✦ Interdimensional Quarry"));
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
        for (Inventory inv : list) if (inv.getHolder() instanceof QuarryMenuHolder h) render(inv, h);
    }

    /** Sweep-driven repaint: skipped if anything repainted within the throttle window, so the
     *  0.5s processing tick never stomps a player who is actively clicking. */
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
            for (Inventory inv : list)
                for (HumanEntity v : new ArrayList<>(inv.getViewers())) v.closeInventory();
        open.clear();
    }

    private MachineType typeOf(Machine m) { return registry == null ? null : registry.getMachineType(m.typeId()); }

    // --- render --------------------------------------------------------------

    private void render(Inventory inv, QuarryMenuHolder h) {
        inv.clear();
        switch (h.view()) {
            case VAULT -> renderVault(inv, h);
            case SKILLS -> renderSkills(inv, h);
            default -> renderMain(inv, h);
        }
    }

    private void renderMain(Inventory inv, QuarryMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        QuarryData d = m.quarry();
        QuarrySpec spec = type == null ? null : type.quarry();
        if (d == null || spec == null) return;

        ItemStack edge = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, edge);

        inv.setItem(SLOT_INFO, infoItem(m, d, spec));

        // editable tool slots (the actual tools live here; durability read live)
        inv.setItem(SLOT_PICK, d.pickaxe());
        inv.setItem(SLOT_ROD, d.rod());
        inv.setItem(SLOT_PICK_DURA, duraGauge("Pickaxe", d.pickaxe(), Material.IRON_PICKAXE));
        inv.setItem(SLOT_ROD_DURA, duraGauge("Fishing rod", d.rod(), Material.FISHING_ROD));

        inv.setItem(SLOT_MODE, modeButton(d));
        inv.setItem(SLOT_DIM, dimensionButton(d, spec));
        inv.setItem(SLOT_CONTRACT, contractButton(d, spec));
        inv.setItem(SLOT_HEAT, heatGauge(d, spec));
        inv.setItem(SLOT_LAVA, lavaButton(d, spec));
        inv.setItem(SLOT_LAVA_TOGGLE, button(d.lavaOn() ? Material.MAGMA_BLOCK : Material.NETHERRACK,
                d.lavaOn() ? "<gold>Lava generation: <green>ON" : "<gray>Lava generation: <red>OFF",
                List.of("<gray>Generates lava into the tank,", "<gray>building <red>heat<gray> as it runs.", "<dark_gray>▶ toggle")));
        inv.setItem(SLOT_VAULT, button(Material.ENDER_CHEST, "<aqua>Quantum Vault",
                List.of("<gray>Stored: <white>" + d.vaultMass() + "</white>/<white>" + QuarrySkill.vaultCap(spec, d),
                        "<dark_gray>▶ open")));
        inv.setItem(SLOT_SKILLS, button(Material.NETHER_STAR, "<light_purple>Rift Skills",
                List.of("<gray>Rift Points: <white>" + d.riftPoints(), "<dark_gray>▶ open the tree")));
    }

    private ItemStack dimensionButton(QuarryData d, QuarrySpec spec) {
        int unlocked = Math.min(spec.dimensionCount() - 1, QuarrySkill.maxDimensionIndex(d));
        int vein = (int) Math.round(d.veinHealth(d.dimension()) * 100);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Mining: <white>" + spec.dimensionName(d.dimension()));
        lore.add("<gray>Vein: " + (vein > 60 ? "<green>" : vein > 35 ? "<yellow>" : "<red>") + vein + "%</white> <dark_gray>(regenerates over time)");
        lore.add("<gray>Unlocked: <white>" + (unlocked + 1) + "</white>/<white>" + spec.dimensionCount());
        lore.add(unlocked > 0 ? "<dark_gray>▶ click to switch depth" : "<dark_gray>raise the ceiling with Rift Drill");
        return label(Material.SPYGLASS, "<dark_aqua>Dimension", lore);
    }

    private ItemStack contractButton(QuarryData d, QuarrySpec spec) {
        QuarryContracts.ensure(d, spec);
        long prog = QuarryContracts.progress(d), target = d.contractTarget();
        boolean ready = QuarryContracts.ready(d);
        List<String> lore = new ArrayList<>();
        if (d.contractType() == 1 && d.contractItem() != null)
            lore.add("<gray>Bank <white>" + target + "</white> × " + prettyItem(d.contractItem()));
        else
            lore.add("<gray>Extract <white>" + target + "</white> items total");
        lore.add("<gray>Progress: <white>" + Math.min(prog, target) + "</white>/<white>" + target);
        lore.add("<gray>Reward: <yellow>+" + QuarryContracts.rpReward(d, spec) + " RP<gray> · <gold>$" + fmt(QuarryContracts.moneyReward(d, spec)));
        lore.add("<dark_gray>Completed: " + d.contractsDone());
        lore.add(ready ? "<green>▶ click to claim!" : "<dark_gray>in progress");
        lore.add("<dark_gray>shift-click to reroll (no reward)");
        return label(ready ? Material.WRITABLE_BOOK : Material.BOOK,
                ready ? "<green>Contract — READY" : "<yellow>Contract", lore);
    }

    private ItemStack heatGauge(QuarryData d, QuarrySpec spec) {
        int pct = (int) Math.round(Math.min(1.0, d.heat() / spec.heatMax()) * 100);
        Material mat = pct >= 100 ? Material.RED_STAINED_GLASS_PANE
                : pct > 60 ? Material.ORANGE_STAINED_GLASS_PANE
                : pct > 25 ? Material.YELLOW_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + (int) Math.round(d.heat()) + " / " + (int) spec.heatMax() + " <dark_gray>(" + pct + "%)");
        lore.add(pct >= 100 ? "<red>Overheated — generation paused until it cools."
                : "<dark_gray>Cools " + fmt(spec.coolPerSec()) + "/s while idle.");
        return label(mat, "<red>Heat", lore);
    }

    private ItemStack lavaButton(QuarryData d, QuarrySpec spec) {
        int pct = (int) Math.round(Math.min(1.0, (double) d.lava() / spec.lavaCap()) * 100);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Stored: <white>" + d.lava() + "</white>/<white>" + spec.lavaCap() + " <dark_gray>(" + pct + "%)");
        lore.add("<gray>= <white>" + (d.lava() / spec.unitsPerBucket()) + "</white> buckets ("
                + spec.unitsPerBucket() + " units each)");
        lore.add("<yellow>◀ Left: fill lava buckets (needs empty buckets)");
        lore.add("<gold>▶ Right: sell tank @ $" + fmt(spec.lavaSellPrice()) + "/unit");
        return label(Material.LAVA_BUCKET, "<gold>Lava Tank", lore);
    }

    // --- VAULT screen (Q3) --------------------------------------------------

    private void renderVault(Inventory inv, QuarryMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        QuarryData d = m.quarry();
        QuarrySpec spec = type == null ? null : type.quarry();
        PriceService prices = type == null ? null : type.pricing();
        if (d == null || spec == null) return;

        ItemStack edge = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, edge);

        String[] names = {"All", "Ores", "Gems", "Blocks", "Sea", "Misc"};
        Material[] icons = {Material.ENDER_CHEST, Material.RAW_IRON, Material.DIAMOND, Material.COBBLESTONE, Material.COD, Material.ENDER_EYE};
        for (int i = 0; i < names.length; i++)
            inv.setItem(i, label(icons[i], (h.tab() == i ? "<green>▸ " : "<gray>") + names[i], List.of("<dark_gray>filter")));

        inv.setItem(V_VALUE, label(Material.GOLD_INGOT, "<gold>Vault value",
                List.of("<yellow>$" + fmt(vaultValue(d, prices)),
                        "<gray>Mass: <white>" + d.vaultMass() + "</white>/<white>" + QuarrySkill.vaultCap(spec, d))));

        List<Map.Entry<ItemStack, Long>> list = sortedFiltered(d, h.tab(), h.sort(), prices);
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) V_PER_PAGE));
        h.setPage(Math.min(h.page(), pages - 1));
        int start = h.page() * V_PER_PAGE;
        for (int i = 0; i < V_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= list.size()) break;
            inv.setItem(V_CONTENT_START + i, card(list.get(idx), prices, d));
        }
        if (list.isEmpty())
            inv.setItem(22, label(Material.GRAY_STAINED_GLASS_PANE, "<gray>Nothing here yet", List.of("<dark_gray>mine something")));

        String pageInfo = "<gray>Page <white>" + (h.page() + 1) + "</white>/<white>" + pages + "</white> · <white>" + list.size() + "</white> types";
        if (h.page() > 0) inv.setItem(V_PREV, label(Material.ARROW, "<yellow>◀ Prev", List.of(pageInfo)));
        if (h.page() < pages - 1) inv.setItem(V_NEXT, label(Material.ARROW, "<yellow>Next ▶", List.of(pageInfo)));
        inv.setItem(V_BACK, label(Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>to the quarry", pageInfo)));
        inv.setItem(V_COLLECT, label(Material.HOPPER, "<green>Collect all", List.of("<gray>Everything that fits → your inventory")));
        inv.setItem(V_SORT, label(Material.COMPARATOR, "<white>Sort: <aqua>" + h.sort(), List.of("<dark_gray>▶ cycle qty / value / name")));
        inv.setItem(V_SELL, h.sellArmed()
                ? label(Material.REDSTONE_BLOCK, "<red>⚠ Click again to confirm",
                        List.of("<red>Sells the ENTIRE vault", "<dark_gray>(disarms if you click elsewhere)"))
                : label(Material.EMERALD, "<green>$ Sell all", List.of("<gray>Sell the whole vault", "<dark_gray>▶ click")));
    }

    private ItemStack card(Map.Entry<ItemStack, Long> e, PriceService prices, QuarryData d) {
        ItemStack tmpl = e.getKey();
        long count = e.getValue();
        ItemStack icon = tmpl.clone();
        icon.setAmount((int) Math.max(1, Math.min(count, tmpl.getMaxStackSize())));
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(mini("<white>" + prettyItem(tmpl) + " <gray>×<yellow>" + count));
            List<String> lore = new ArrayList<>();
            double each = prices == null ? 0 : prices.price(tmpl);
            if (each > 0) lore.add("<gray>Value: <yellow>$" + fmt(each) + "</yellow> ea · <yellow>$" + fmt(each * count));
            lore.add("<gray>Rule: " + ruleLabel(d.ruleFor(tmpl)));
            lore.add("<dark_gray>click=stack · right=one · shift=all");
            lore.add("<dark_gray>shift-right=cycle rule");
            meta.lore(miniList(lore));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void onVaultClick(Player p, QuarryMenuHolder h, Machine m, int raw, ClickType click) {
        QuarryData d = m.quarry();
        if (raw != V_SELL) h.setSellArmed(false);   // any other action disarms the sell confirm

        if (raw >= 0 && raw <= 5) { h.setTab(raw); refresh(m); return; }
        if (raw == V_PREV) { h.setPage(h.page() - 1); refresh(m); return; }
        if (raw == V_NEXT) { h.setPage(h.page() + 1); refresh(m); return; }
        if (raw == V_BACK) { h.setView(QuarryMenuHolder.View.MAIN); refresh(m); return; }
        if (raw == V_SORT) { h.setSort(nextSort(h.sort())); refresh(m); return; }
        if (raw == V_COLLECT) {
            long n = actions.collectAll(m, p);
            refresh(m);
            p.sendMessage(brand(n > 0 ? "<green>Collected <white>" + n + "</white> items." : "<gray>Nothing fit (inventory full?) / vault empty."));
            return;
        }
        if (raw == V_SELL) {
            if (!h.sellArmed()) {
                h.setSellArmed(true); refresh(m);
                p.sendMessage(brand("<red>Click Sell again to confirm — this sells the whole vault."));
            } else {
                double money = actions.sellAll(m, p);
                h.setSellArmed(false); refresh(m);
                if (money < 0) p.sendMessage(brand("<red>No economy available to sell."));
                else p.sendMessage(brand(money > 0 ? "<gold>Sold the vault for <white>" + fmt(money) + "</white>." : "<gray>Nothing sellable."));
            }
            return;
        }
        if (raw >= V_CONTENT_START && raw < V_CONTENT_END) {
            MachineType type = typeOf(m);
            PriceService prices = type == null ? null : type.pricing();
            List<Map.Entry<ItemStack, Long>> list = sortedFiltered(d, h.tab(), h.sort(), prices);
            int idx = h.page() * V_PER_PAGE + (raw - V_CONTENT_START);
            if (idx >= list.size()) return;
            ItemStack tmpl = list.get(idx).getKey();
            if (click == ClickType.SHIFT_RIGHT) {
                d.setRule(tmpl, nextRule(d.ruleFor(tmpl)));
                actions.persist(m);
                refresh(m);
            } else {
                long amt = click == ClickType.SHIFT_LEFT ? Long.MAX_VALUE
                        : (click == ClickType.RIGHT ? 1 : tmpl.getMaxStackSize());
                actions.withdraw(m, p, tmpl, amt);
                refresh(m);
            }
        }
    }

    private double vaultValue(QuarryData d, PriceService prices) {
        if (prices == null) return 0;
        double v = 0;
        for (Map.Entry<ItemStack, Long> e : d.vault().entrySet()) v += prices.price(e.getKey()) * e.getValue();
        return v;
    }

    private List<Map.Entry<ItemStack, Long>> sortedFiltered(QuarryData d, int tab, QuarryMenuHolder.Sort sort, PriceService prices) {
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

    /** 1=ore/metal, 2=gem, 3=block, 4=sea, 5=misc (tab 0 = all). Sea is checked first — vault items
     *  only ever come from the mining/fishing tables, so membership here is unambiguous. */
    private int category(Material m) {
        if (isSea(m)) return 4;
        String n = m.name();
        if (m == Material.DIAMOND || m == Material.EMERALD || n.startsWith("AMETHYST") || m == Material.NETHER_STAR
                || m == Material.LAPIS_LAZULI || m == Material.QUARTZ) return 2;
        if (n.startsWith("RAW_") || n.endsWith("_INGOT") || n.endsWith("_NUGGET") || m == Material.COAL
                || m == Material.REDSTONE || m == Material.GLOWSTONE_DUST || m == Material.ANCIENT_DEBRIS || m == Material.NETHERITE_SCRAP) return 1;
        if (m.isBlock()) return 3;
        return 5;
    }

    private boolean isSea(Material m) {
        return switch (m) {
            case COD, SALMON, PUFFERFISH, TROPICAL_FISH, COOKED_COD, COOKED_SALMON,
                 INK_SAC, GLOW_INK_SAC, PRISMARINE_SHARD, PRISMARINE_CRYSTALS, SEAGRASS, KELP,
                 LILY_PAD, NAUTILUS_SHELL, HEART_OF_THE_SEA, ENCHANTED_BOOK, NAME_TAG,
                 FISHING_ROD, BOW, SADDLE, BOWL, LEATHER_BOOTS, STICK, STRING, BONE, ROTTEN_FLESH -> true;
            default -> false;
        };
    }

    private static QuarryMenuHolder.Sort nextSort(QuarryMenuHolder.Sort s) {
        return switch (s) { case QTY -> QuarryMenuHolder.Sort.VALUE; case VALUE -> QuarryMenuHolder.Sort.NAME; case NAME -> QuarryMenuHolder.Sort.QTY; };
    }

    private static QuarryData.Rule nextRule(QuarryData.Rule r) {
        return switch (r) { case KEEP -> QuarryData.Rule.SELL; case SELL -> QuarryData.Rule.VOID; case VOID -> QuarryData.Rule.KEEP; };
    }

    private static String ruleLabel(QuarryData.Rule r) {
        return switch (r) { case KEEP -> "<green>Keep"; case SELL -> "<gold>Auto-sell"; case VOID -> "<red>Auto-void"; };
    }

    private static String prettyItem(ItemStack item) {
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName())
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        String[] parts = item.getType().name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) if (!s.isEmpty()) sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        String base = sb.toString().trim();
        // distinguish rolled enchanted books by their stored enchant
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta esm && esm.hasStoredEnchants()) {
            var entry = esm.getStoredEnchants().entrySet().iterator().next();
            String ench = entry.getKey().getKey().getKey().replace('_', ' ');
            ench = Character.toUpperCase(ench.charAt(0)) + ench.substring(1);
            base += " <gray>(" + ench + " " + roman(entry.getValue()) + "<gray>)";
        }
        return base;
    }

    private static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n); };
    }

    private static String fmt(double d) {
        return (d == Math.floor(d)) ? Long.toString((long) d) : String.format("%.2f", d);
    }

    private static List<Component> miniList(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String s : lines) out.add(mini(s));
        return out;
    }

    // --- SKILLS screen (Q5) -------------------------------------------------

    private void renderSkills(Inventory inv, QuarryMenuHolder h) {
        Machine m = h.machine();
        QuarryData d = m.quarry();
        if (d == null) return;

        ItemStack edge = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, edge);

        inv.setItem(S_INFO, label(Material.NETHER_STAR, "<light_purple><bold>Rift Skill Tree",
                List.of("<gray>Rift Points: <yellow>" + d.riftPoints(),
                        "<gray>Prestige: <white>" + d.prestige() + " <dark_gray>(+" + (int) (d.prestige() * 10) + "% yield)",
                        "<gray>Spend Rift Points (earned by mining/fishing);",
                        "<gray>deeper nodes also cost <white>money<gray> + <white>nether stars<gray>.",
                        "<dark_gray>Click a node to buy its next level.")));

        // Prestige button — only once Ascendance is owned
        if (d.skill(QuarrySkill.ASCENDANCE.name()) >= 1) {
            inv.setItem(S_PRESTIGE, h.prestigeArmed()
                    ? label(Material.DRAGON_BREATH, "<red>⚠ Click again to ASCEND",
                            List.of("<red>Resets your skill tree + Rift Points", "<gray>Gain +10% permanent yield", "<dark_gray>(disarms if you click elsewhere)"))
                    : label(Material.DRAGON_EGG, "<dark_purple>✦ Ascend (Prestige)",
                            List.of("<gray>Reset skills + Rift Points for", "<gray>+10% permanent global yield.", "<dark_gray>▶ click")));
        }

        for (int b = 0; b < S_BRANCHES.length; b++) {
            QuarrySkill.Branch branch = S_BRANCHES[b];
            int base = (b + 1) * 9;
            inv.setItem(base, label(branch.icon, branch.color + "<bold>" + branch.display, List.of("<dark_gray>branch")));
            List<QuarrySkill> nodes = nodesOf(branch);
            for (int i = 0; i < nodes.size(); i++) inv.setItem(base + 1 + i, nodeItem(d, nodes.get(i)));
        }

        inv.setItem(S_BACK, label(Material.BARRIER, "<red>◀ Back", List.of("<dark_gray>to the quarry")));
    }

    private static List<QuarrySkill> nodesOf(QuarrySkill.Branch branch) {
        List<QuarrySkill> out = new ArrayList<>();
        for (QuarrySkill s : QuarrySkill.values()) if (s.branch == branch) out.add(s);
        return out;
    }

    private QuarrySkill skillAtSlot(int raw) {
        int row = raw / 9, col = raw % 9;
        if (row < 1 || row > S_BRANCHES.length || col < 1) return null;
        List<QuarrySkill> nodes = nodesOf(S_BRANCHES[row - 1]);
        int idx = col - 1;
        return idx < nodes.size() ? nodes.get(idx) : null;
    }

    private ItemStack nodeItem(QuarryData d, QuarrySkill s) {
        int cur = d.skill(s.name());
        boolean maxed = cur >= s.maxLevel;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + s.blurb);
        lore.add("<gray>Level: <white>" + cur + "</white>/<white>" + s.maxLevel);
        if (maxed) {
            lore.add("<green>✔ Maxed");
        } else {
            int next = cur + 1;
            lore.add("");
            lore.add("<gray>Next level costs:");
            long rp = s.rpCost(next);
            lore.add((d.riftPoints() >= rp ? "<yellow>" : "<red>") + "• " + rp + " Rift Points");
            if (s.moneyCost(next) > 0) lore.add("<gold>• $" + fmt(s.moneyCost(next)));
            if (s.starCost(next) > 0) lore.add("<aqua>• " + s.starCost(next) + " Nether Star" + (s.starCost(next) > 1 ? "s" : ""));
            lore.add("<dark_gray>▶ click to buy");
        }
        return label(maxed ? Material.LIME_STAINED_GLASS_PANE : s.icon,
                (maxed ? "<green>✔ " : s.branch.color) + s.display, lore);
    }

    private void onSkillsClick(Player p, QuarryMenuHolder h, Machine m, int raw) {
        if (raw != S_PRESTIGE) h.setPrestigeArmed(false);   // any other click disarms the prestige confirm
        if (raw == S_BACK) { h.setView(QuarryMenuHolder.View.MAIN); refresh(m); return; }
        if (raw == S_PRESTIGE) {
            if (m.quarry().skill(QuarrySkill.ASCENDANCE.name()) < 1) return;
            if (!h.prestigeArmed()) {
                h.setPrestigeArmed(true); refresh(m);
                p.sendMessage(brand("<red>Click Ascend again to confirm — this resets your skills + Rift Points."));
            } else {
                h.setPrestigeArmed(false);
                actions.prestige(m, p);
                refresh(m);
            }
            return;
        }
        QuarrySkill s = skillAtSlot(raw);
        if (s == null) return;
        QuarryActions.BuyResult r = actions.buySkill(m, p, s);
        switch (r) {
            case OK -> p.sendMessage(brand("<green>Upgraded <white>" + s.display + "</white> to level <white>" + m.quarry().skill(s.name()) + "</white>."));
            case MAXED -> p.sendMessage(brand("<gray>" + s.display + " is already maxed."));
            case NEED_RIFT_POINTS -> p.sendMessage(brand("<red>Not enough Rift Points."));
            case NEED_MONEY -> p.sendMessage(brand("<red>Not enough money."));
            case NEED_STARS -> p.sendMessage(brand("<red>You need nether stars in your inventory for this."));
            case NO_ECONOMY -> p.sendMessage(brand("<red>No economy available for the money cost."));
            default -> p.sendMessage(brand("<red>Couldn't buy that."));
        }
        refresh(m);
    }

    private ItemStack infoItem(Machine m, QuarryData d, QuarrySpec spec) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Mode: <white>" + d.mode());
        lore.add("<gray>Dimension: <white>" + spec.dimensionName(d.dimension()));
        lore.add("<gray>Vault: <white>" + d.vaultMass() + "</white>/<white>" + QuarrySkill.vaultCap(spec, d));
        lore.add("<gray>Rift Points: <white>" + d.riftPoints());
        if (d.prestige() > 0) lore.add("<gray>Prestige: <light_purple>" + d.prestige() + " <dark_gray>(+" + (int) (d.prestige() * 10) + "% yield)");
        long now = System.currentTimeMillis();
        if (d.surgeActive(now)) lore.add("<gold>⚡ RIFT SURGE — ×" + spec.surgeMult() + " for " + ((d.surgeUntil() - now) / 1000 + 1) + "s");
        lore.add("<gray>Mined (lifetime): <white>" + d.totalMined());
        boolean ready = (d.mode() != QuarryData.Mode.FISHING && QuarryManager.durabilityLeft(d.pickaxe()) > 0)
                || (d.mode() != QuarryData.Mode.MINING && QuarryManager.durabilityLeft(d.rod()) > 0);
        lore.add(ready ? "<green>● running" : "<red>● insert a tool to run");
        lore.add("<dark_gray>Powered by a pickaxe / rod's durability.");
        return label(Material.HEART_OF_THE_SEA, "<dark_purple><bold>Interdimensional Quarry", lore);
    }

    private ItemStack duraGauge(String what, ItemStack tool, Material emptyIcon) {
        if (tool == null) {
            return label(Material.GRAY_STAINED_GLASS_PANE, "<gray>No " + what.toLowerCase(),
                    List.of("<dark_gray>Insert one in the slot above"));
        }
        int left = QuarryManager.durabilityLeft(tool), max = QuarryManager.durabilityMax(tool);
        int pct = (int) Math.round(QuarryManager.durability01(tool) * 100);
        Material mat = pct > 50 ? Material.LIME_STAINED_GLASS_PANE
                : pct > 20 ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        return label(mat, "<white>" + what + " durability",
                List.of("<gray>" + left + " / " + max + " <dark_gray>(" + pct + "%)"));
    }

    private ItemStack modeButton(QuarryData d) {
        String now = switch (d.mode()) {
            case MINING -> "<gold>⛏ Mining"; case FISHING -> "<aqua>🎣 Fishing"; case BOTH -> "<light_purple>⛏🎣 Both";
        };
        return button(Material.COMPARATOR, "<white>Mode: " + now,
                List.of("<gray>Mining uses the pickaxe; Fishing the rod.",
                        "<gray>Both = <light_purple>Dual Drive<gray> — runs each on its own clock,",
                        "<gray>burning both tools at once.",
                        "<dark_gray>▶ Click to switch"));
    }

    // --- events --------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof QuarryMenuHolder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) { e.setCancelled(true); return; }
        int raw = e.getRawSlot();
        Machine m = h.machine();
        QuarryData d = m.quarry();
        if (d == null) { e.setCancelled(true); return; }

        // A double-click on a button that just switched the view would land its 2nd hit on a
        // different action occupying the same slot (e.g. the Vault button at 48 → "Collect all" at 48).
        // No double-click is ever meaningful in this menu, so swallow it.
        if (e.getClick() == ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }

        if (h.view() == QuarryMenuHolder.View.VAULT) {
            if (raw < 0 || raw >= SIZE) {
                if (e.getClick() == ClickType.DOUBLE_CLICK || e.isShiftClick()) e.setCancelled(true);
                return;
            }
            e.setCancelled(true);
            try { onVaultClick(p, h, m, raw, e.getClick()); } catch (Throwable t) { refresh(m); p.updateInventory(); }
            return;
        }

        if (h.view() == QuarryMenuHolder.View.SKILLS) {
            if (raw < 0 || raw >= SIZE) {
                if (e.getClick() == ClickType.DOUBLE_CLICK || e.isShiftClick()) e.setCancelled(true);
                return;
            }
            e.setCancelled(true);
            try { onSkillsClick(p, h, m, raw); } catch (Throwable t) { refresh(m); p.updateInventory(); }
            return;
        }

        // MAIN — the two tool slots are the ONLY editable slots — handle them manually.
        if (raw == SLOT_PICK || raw == SLOT_ROD) {
            e.setCancelled(true);
            boolean pick = raw == SLOT_PICK;
            handleToolSlot(p, m, d, pick);
            return;
        }

        // Own-inventory clicks: only block siphon-into-menu actions.
        if (raw < 0 || raw >= SIZE) {
            if (e.getClick() == ClickType.DOUBLE_CLICK || e.isShiftClick()) e.setCancelled(true);
            return;
        }

        e.setCancelled(true);
        try {
            if (raw == SLOT_MODE) { actions.toggleMode(m, p); refresh(m); }
            else if (raw == SLOT_DIM) { actions.cycleDimension(m, p); refresh(m); }
            else if (raw == SLOT_CONTRACT) {
                if (e.isShiftClick()) actions.rerollContract(m, p); else actions.claimContract(m, p);
                refresh(m);
            }
            else if (raw == SLOT_LAVA_TOGGLE) { actions.toggleLava(m, p); refresh(m); }
            else if (raw == SLOT_LAVA) {
                if (e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT) {
                    double money = actions.sellLava(m, p);
                    refresh(m);
                    if (money < 0) p.sendMessage(brand("<red>No economy available to sell lava."));
                    else p.sendMessage(brand(money > 0 ? "<gold>Sold the lava tank for <white>" + fmt(money) + "</white>." : "<gray>Tank is empty."));
                } else {
                    int n = actions.collectLava(m, p);
                    refresh(m);
                    p.sendMessage(brand(n > 0 ? "<gold>Filled <white>" + n + "</white> lava bucket(s)."
                            : "<gray>Need empty buckets + enough lava in the tank."));
                }
            }
            else if (raw == SLOT_VAULT) { h.setView(QuarryMenuHolder.View.VAULT); h.setPage(0); refresh(m); }
            else if (raw == SLOT_SKILLS) { h.setView(QuarryMenuHolder.View.SKILLS); refresh(m); }
        } catch (Throwable t) {
            refresh(m);
            p.updateInventory();
        }
    }

    /** Insert/remove a tool in the pickaxe or rod slot (only the right kind goes in). */
    private void handleToolSlot(Player p, Machine m, QuarryData d, boolean pick) {
        ItemStack slot = pick ? d.pickaxe() : d.rod();
        ItemStack cursor = p.getItemOnCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();

        if (cursorEmpty && slot != null) {                 // take the tool out
            p.setItemOnCursor(slot);
            if (pick) d.setPickaxe(null); else d.setRod(null);
        } else if (!cursorEmpty) {                          // try to put a tool in
            boolean ok = pick ? QuarryManager.isPickaxe(cursor) : QuarryManager.isRod(cursor);
            if (!ok) { p.sendMessage(brand(pick ? "<red>That isn't a pickaxe." : "<red>That isn't a fishing rod.")); return; }
            ItemStack one = cursor.clone(); one.setAmount(1);
            ItemStack back = (cursor.getAmount() > 1) ? shrink(cursor, 1) : null;   // keep only one tool
            if (pick) d.setPickaxe(one); else d.setRod(one);
            p.setItemOnCursor(back);
            if (slot != null) p.getInventory().addItem(slot);   // swap old one back to inventory
        } else {
            return;
        }
        actions.persist(m);
        refresh(m);
        p.updateInventory();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof QuarryMenuHolder)) return;
        for (int raw : e.getRawSlots()) if (raw < SIZE && raw != SLOT_PICK && raw != SLOT_ROD) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof QuarryMenuHolder h)) return;
        List<Inventory> list = open.get(h.machine().id());
        if (list != null) {
            list.remove(e.getInventory());
            if (list.isEmpty()) { open.remove(h.machine().id()); lastRefresh.remove(h.machine().id()); }
        }
    }

    // --- helpers -------------------------------------------------------------

    private ItemStack pane(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) { meta.displayName(mini(" ")); it.setItemMeta(meta); }
        return it;
    }

    private ItemStack button(Material mat, String name, List<String> lore) { return label(mat, name, lore); }

    private ItemStack label(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(mini(name));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String s : lore) lines.add(mini(s));
                meta.lore(lines);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component mini(String s) {
        return MiniMessage.miniMessage().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private Component brand(String s) {
        return mini("<dark_purple>Quarry <dark_gray>» " + s);
    }

    private static ItemStack shrink(ItemStack s, int by) {
        ItemStack c = s.clone();
        c.setAmount(Math.max(0, s.getAmount() - by));
        return c.getAmount() <= 0 ? null : c;
    }
}
