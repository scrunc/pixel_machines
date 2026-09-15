package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.api.MachineConstructAPI;
import dev.servereer.machineconstruct.factorydistrict.FactoryDistrictActions;
import dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData;
import dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager;
import dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec;
import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
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
 * The Virtual Factory District menu (ADR 0013). Code-rendered + sealed + paginated.
 * Views: MAIN (capture + district-slot grid), BUILD (farm picker for an empty slot),
 * FACTORY (a built farm's production detail + demolish), VAULT (deposit / withdraw).
 */
public final class FactoryDistrictMenus implements Listener {

    private static final int SIZE = 54;
    private static final int GRID_START = 9, GRID_END = 45, PER_PAGE = 36;
    private static final int SLOT_INFO = 4, SLOT_CAPTURE = 0, SLOT_UPGRADE = 6, SLOT_VAULT = 8, SLOT_CHESTS = 2, SLOT_AUTOSELL = 3;
    private static final int SLOT_PREV = 45, SLOT_NEXT = 53;
    private static final int CH_ADD_OUT = 47, CH_ADD_IN = 51, CH_BACK = 49;   // CHESTS view buttons
    private static final int CF_ROUTE_ALL = 47, CF_BACK = 49;    // CHEST_FILTER view buttons
    private static final int AS_BACK = 45, AS_TOGGLE = 47, AS_SELL_ALL = 49, AS_CLEAR = 51;   // AUTOSELL view buttons
    private static final int B_BACK = 45, F_BACK = 45, F_DEMOLISH = 53;
    private static final int F_WORKER_MINUS = 47, F_WORKER_PLUS = 48, F_UPGRADE = 50, F_REPAIR = 49, F_MERGE = 46, F_RECIPES = 51;
    private static final int V_PREV = 45, V_BACK = 49, V_NEXT = 53;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final MachineConstructAPI registry;
    private final FactoryDistrictActions actions;

    public FactoryDistrictMenus(Plugin plugin, MachineConstructAPI registry, FactoryDistrictActions actions) {
        this.plugin = plugin;
        this.registry = registry;
        this.actions = actions;
    }

    public void open(Player player, Machine machine) {
        FactoryDistrictSpec spec = specOf(machine);
        if (spec == null) { player.sendMessage(mini("<red>That isn't a Factory District.")); return; }
        if (!machine.hasFactory()) machine.setFactory(new FactoryDistrictData());
        machine.factory().ensureSlots(spec.slotsForTier(machine.factory().tier()));
        actions.persistFactory(machine);
        openMain(player, machine, 0);
    }

    private void openMain(Player player, Machine machine, int page) {
        FactoryDistrictSpec spec = specOf(machine);
        FactoryDistrictData d = machine.factory();
        // Population mode: pull any roster villagers into free houses before rendering (ADR 0022).
        if (FactoryDistrictManager.houseVillagers(spec, d) != 0) actions.persistFactory(machine);
        int usable = spec.slotsForTier(d.tier());
        int pages = Math.max(1, (int) Math.ceil(usable / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.MAIN, page);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>⚙ Virtual Factory District"));
        holder.setInventory(inv);

        String tierLabel = spec.maxTier() > 0 ? d.tier() + "<gray>/<yellow>" + spec.maxTier() : String.valueOf(d.tier());
        int wCap = FactoryDistrictManager.workerCapacity(spec, d);
        int employable = FactoryDistrictManager.employableWorkers(spec, d);
        double oBoost = FactoryDistrictManager.outputBoost(spec, d);
        List<String> infoLore = new ArrayList<>(List.of(
                "<gray>Tier <yellow>" + tierLabel,
                "<gray>Slots <yellow>" + usable + "<gray> · page <yellow>" + (page + 1) + "<gray>/<yellow>" + pages,
                "<gray>Vault cap <yellow>" + spec.vaultCapForTier(d.tier()),
                "<gray>Workers <yellow>" + FactoryDistrictManager.workersFree(spec, d) + "<gray>/<yellow>" + employable + "<gray> free"
                        + (wCap == 0 ? " <dark_gray>(build a house)" : "")));
        if (spec.populationWorkers()) {
            int waiting = d.creatureCount(org.bukkit.entity.EntityType.VILLAGER);
            infoLore.add("<gray>Housed villagers <yellow>" + d.workerPopulation() + "<gray>/<yellow>" + wCap
                    + (wCap == 0 ? " <dark_gray>(build a house)"
                       : d.workerPopulation() >= wCap ? " <dark_gray>(homes full)"
                       : waiting > 0 ? " <dark_gray>(" + waiting + " moving in…)"
                       : " <dark_gray>(breed/capture villagers)"));
        }
        infoLore.add("<gray>Output boost: " + (oBoost > 1.0
                ? "<light_purple>×" + String.format("%.2f", oBoost) + " <gray>(staffed boosters)"
                : "<dark_gray>×1.00 (none active)"));
        infoLore.add("<gray>Production runs offline (catches up on load).");
        infoLore.add(0, "<dark_gray>───────────");
        infoLore.add(0, nextStep(spec, d, usable));   // contextual "what do I do?" prompt
        inv.setItem(SLOT_INFO, icon(Material.BLAST_FURNACE, "<gold>Factory District", infoLore));

        List<String> capLore = new ArrayList<>();
        capLore.add("<gray>Captured creatures (for builds):");
        if (d.roster().isEmpty()) capLore.add("<dark_gray> (none)");
        else for (Map.Entry<EntityType, Integer> e : d.roster().entrySet())
            capLore.add("<dark_gray> • <white>" + e.getValue() + "x " + FactoryDistrictManager.pretty(e.getKey().name()));
        capLore.add("<yellow>Click<gray> to vacuum mobs within <white>" + spec.captureRadius() + "<gray> blocks.");
        inv.setItem(SLOT_CAPTURE, icon(Material.LEAD, "<green>Capture creatures", capLore));

        if (spec.canUpgrade(d.tier())) {
            List<String> ul = new ArrayList<>();
            ul.add("<gray>Feed blocks to upgrade the district:");
            for (ItemStack req : spec.upgrade())
                ul.add("<dark_gray> • <white>" + req.getAmount() + "x " + FactoryDistrictManager.pretty(req.getType().name()));
            ul.add("<gray>Next: <yellow>" + spec.slotsForTier(d.tier() + 1) + "<gray> slots · vault <yellow>"
                    + spec.vaultCapForTier(d.tier() + 1));
            ul.add("<yellow>Click<gray> to upgrade (inventory + vault).");
            inv.setItem(SLOT_UPGRADE, icon(Material.ANVIL, "<gold>Upgrade district <gray>(Tier " + (d.tier() + 1) + ")", ul));
        } else {
            inv.setItem(SLOT_UPGRADE, icon(Material.ANVIL, "<dark_gray>Upgrade district", List.of("<dark_gray>Max tier reached.")));
        }
        inv.setItem(SLOT_VAULT, icon(Material.ENDER_CHEST, "<light_purple>Quantum Vault", List.of(
                "<gray>Stored mass: <white>" + d.vaultMass(),
                "<gray>Where production lands.",
                "<yellow>Click<gray> to open — deposit / withdraw.")));
        int outChests = 0, inChests = 0;
        for (var l : d.links()) {
            if (l.type() == dev.servereer.machineconstruct.grinder.ChestLink.Type.OUTPUT) outChests++;
            else inChests++;
        }
        inv.setItem(SLOT_CHESTS, icon(Material.HOPPER, "<aqua>I/O Chests", List.of(
                "<gray>INPUT chests feed items into the vault;",
                "<gray>OUTPUT chests drain it (filter + flow rate).",
                "<gray>Linked: <green>" + outChests + " out<gray>, <aqua>" + inChests + " in",
                "<yellow>Click<gray> to manage.")));
        inv.setItem(SLOT_AUTOSELL, icon(Material.GOLD_INGOT, "<gold>Auto-Sell", List.of(
                "<gray>Sell vault items for money (per-item",
                "<gray>rates, paid hourly). Master: " + (d.autoSellEnabled() ? "<green>ON" : "<red>OFF"),
                "<gray>Selling <white>" + d.autoSellRules().size() + "<gray> item type(s).",
                "<yellow>Click<gray> to manage.")));

        int first = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && GRID_START + i < GRID_END; i++) {
            int slotIdx = first + i;
            if (slotIdx >= usable) { inv.setItem(GRID_START + i, filler()); continue; }
            inv.setItem(GRID_START + i, slotIcon(spec, d, d.slot(slotIdx)));
        }
        if (page > 0) inv.setItem(SLOT_PREV, icon(Material.ARROW, "<yellow>◀ Previous", List.of()));
        if (page < pages - 1) inv.setItem(SLOT_NEXT, icon(Material.ARROW, "<yellow>Next ▶", List.of()));
        player.openInventory(inv);
    }

    private void openCategories(Player player, Machine machine, int slotIdx) {
        FactoryDistrictSpec spec = specOf(machine);
        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.CATEGORIES, 0);
        holder.setSlotIndex(slotIdx);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>Choose a category"));
        holder.setInventory(inv);

        inv.setItem(SLOT_INFO, icon(Material.CHEST, "<gold>Farm categories", List.of(
                "<gray>Pick a category, then a farm to build.")));
        List<FactoryDistrictSpec.Category> cats = visibleCategories(spec);
        for (int i = 0; i < cats.size() && GRID_START + i < GRID_END; i++) {
            FactoryDistrictSpec.Category c = cats.get(i);
            int count = spec.buildableFarmsIn(c.id).size();
            inv.setItem(GRID_START + i, icon(c.iconItem, "<green>" + c.display,
                    List.of("<gray>" + count + " farm(s)", "<yellow>Click<gray> to browse.")));
        }
        inv.setItem(B_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        player.openInventory(inv);
    }

    private void openBuild(Player player, Machine machine, int slotIdx, String categoryId) {
        FactoryDistrictSpec spec = specOf(machine);
        FactoryDistrictSpec.Category cat = spec.category(categoryId);
        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.BUILD, 0);
        holder.setSlotIndex(slotIdx);
        holder.setCategoryId(categoryId);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>" + (cat != null ? cat.display : "Farms")));
        holder.setInventory(inv);

        inv.setItem(SLOT_INFO, icon(Material.CRAFTING_TABLE, "<gold>Choose a farm", List.of(
                "<gray>Pays blocks (inv + vault) + creatures (roster).")));
        FactoryDistrictSpec.Size size = spec.size(null);   // single implicit size
        List<FactoryDistrictSpec.Farm> farms = spec.buildableFarmsIn(categoryId);
        for (int i = 0; i < farms.size() && GRID_START + i < GRID_END; i++) {
            inv.setItem(GRID_START + i, farmIcon(player, machine.factory(), spec, farms.get(i), size));
        }
        inv.setItem(B_BACK, icon(Material.ARROW, "<yellow>◀ Categories", List.of()));
        player.openInventory(inv);
    }

    private void openFactory(Player player, Machine machine, int slotIdx) {
        FactoryDistrictSpec spec = specOf(machine);
        FactoryDistrictData.Slot s = machine.factory().slot(slotIdx);
        if (s == null || !s.isBuilt()) { openMain(player, machine, 0); return; }
        FactoryDistrictSpec.Farm farm = spec.farm(s.factoryId);
        if (farm == null) { openMain(player, machine, 0); return; }
        FactoryDistrictSpec.Size size = spec.size(s.sizeId);

        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.FACTORY, 0);
        holder.setSlotIndex(slotIdx);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>" + size.display + " " + farm.display));
        holder.setInventory(inv);

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Size: <gold>" + size.display);
        if (farm.craftsVanilla) {
            lore.add("<dark_aqua>⚒ Crafting bench");
            lore.add("<dark_gray>  craft any vanilla recipe from vault materials");
        } else if (farm.isCrafter()) {
            lore.add("<dark_aqua>⚒ Recipe processor <gray>· every <yellow>" + farm.cycleSeconds + "s"
                    + (farm.stackMode == FactoryDistrictSpec.StackMode.MULTI ? " <dark_gray>(★ = +recipes)" : " <dark_gray>(★ = faster)"));
            List<FactoryDistrictSpec.CraftRecipe> active = FactoryDistrictManager.activeRecipes(farm, s);
            for (FactoryDistrictSpec.CraftRecipe r : farm.recipes)
                lore.add((active.contains(r) ? "<green> ▶ " : "<dark_gray>   ") + "<gray>" + r.display + " " + recipeShort(r));
        } else {
            lore.add("<gray>Produces every <yellow>" + farm.cycleSeconds + "s<gray>:");
            List<FactoryDistrictSpec.Reward> outs = FactoryDistrictManager.outputFor(farm, size);
            for (FactoryDistrictSpec.Reward o : outs)
                lore.add("<dark_gray> → " + rewardLine(o));
            if (!outs.isEmpty()) {
                double eff = FactoryDistrictManager.contribution(farm, s)
                        * FactoryDistrictManager.outputBoost(spec, machine.factory());
                lore.add("<gray>Effective output: <gold>×" + String.format("%.2f", eff)
                        + "<dark_gray> (" + (s.mergeStars > 0 ? "★" + (s.mergeStars + 1) + " fused, " : "")
                        + "richness×proficiency×district" + (farm.hasSoil() ? ", ×soil/cycle" : "") + ")");
            }
        }
        if (!farm.inputs.isEmpty()) {
            lore.add("<gray>Consumes per cycle (from vault):");
            for (ItemStack in : FactoryDistrictManager.inputsFor(farm, size))
                lore.add("<dark_gray> ← <white>" + in.getAmount() + "x " + FactoryDistrictManager.pretty(in.getType().name()));
        }
        if (farm.providesWorkers > 0)
            lore.add("<gray>House: <aqua>+" + ((farm.providesWorkers + s.level * farm.workersPerLevel) * (s.mergeStars + 1))
                    + "<gray> worker capacity (lvl " + s.level + (s.mergeStars > 0 ? ", ★" + (s.mergeStars + 1) : "") + ")");
        if (!farm.requires.isEmpty()) {
            lore.add("<gray>Requires running:");
            for (String r : farm.requires) {
                var rf = spec.farm(r);
                boolean met = reqMet(machine.factory(), spec, r);
                lore.add((met ? "<green> ✔ " : "<red> ✖ ") + (rf != null ? rf.display : r));
            }
        }
        lore.add("<gray>Output goes to the <light_purple>vault<gray>; runs offline.");
        if (farm.tier != null) lore.add("<gray>Tier: <gold>" + farm.tier);
        if (farm.hasRichness() || s.richness != 1.0) lore.add("<gray>Richness: <gold>×" + String.format("%.2f", s.richness));
        if (farm.hasProficiency()) {
            int t = FactoryDistrictManager.workerTier(farm, s);
            lore.add("<gray>Worker tier: <gold>" + tierName(t) + " <gray>(" + t + "/" + farm.wpMaxTier
                    + ", +" + (int) Math.round(t * farm.wpBonusPerTier * 100) + "% output)");
        }
        if (farm.depletes())
            lore.add("<gray>Depletion: <yellow>" + s.cyclesProduced + "<gray>/<yellow>" + farm.depleteCycles + "<gray> cycles");
        if (farm.hasSoil()) {
            if (farm.regensSoil())
                lore.add("<gray>Soil: <green>regenerating <gray>(" + (int) s.soil + (farm.soilStart > 0 ? "/" + (int) farm.soilStart : "") + ") — rotate back when full");
            else {
                int pct = farm.soilStart > 0 ? (int) Math.round(100.0 * Math.max(0, s.soil) / farm.soilStart) : 0;
                String col = s.soil <= 0 ? "<red>" : pct < 25 ? "<gold>" : "<green>";
                lore.add("<gray>Soil: " + col + (int) Math.max(0, s.soil) + "<gray>/" + (int) farm.soilStart + " (" + pct + "%)"
                        + (s.soil <= 0 ? " <red>— exhausted, ×" + String.format("%.2f", farm.soilDepletedMult) : ""));
                if (farm.hasFertilizer())
                    lore.add("<dark_gray>  auto-fertilises with " + FactoryDistrictManager.pretty(farm.fertilizerItem.getType().name()) + " from the vault");
            }
        }
        if (farm.boostsOutput())
            lore.add("<light_purple>⬆ Increases ALL farms' production: <light_purple>×"
                    + String.format("%.2f", Math.pow(farm.boostsOutput, s.mergeStars + 1))
                    + (s.mergeStars > 0 ? " <gray>(★" + (s.mergeStars + 1) + ", while staffed)" : " <gray>(while staffed)"));
        if (farm.boostsWorkers())
            lore.add("<aqua>⬆ Increases worker capacity: <aqua>×"
                    + String.format("%.2f", Math.pow(farm.boostsWorkers, s.mergeStars + 1))
                    + (s.mergeStars > 0 ? " <gray>(★" + (s.mergeStars + 1) + ")" : ""));
        if (farm.breeds()) {
            lore.add("<gray>Breeds <green>" + FactoryDistrictManager.pretty(farm.breeding.type.name())
                    + "<gray> into the roster every <yellow>" + (farm.breeding.everySeconds / 60) + "m"
                    + (farm.breeding.workerScaled ? " <dark_gray>(faster with workers)" : ""));
            lore.add("<dark_gray>  bred so far: " + s.offspring + (farm.breeding.max > 0 ? "/" + farm.breeding.max : ""));
            if (spec.populationWorkers() && farm.breeding.type == org.bukkit.entity.EntityType.VILLAGER)
                lore.add("<dark_gray>  villagers fill your houses → become workers");
        }
        if (farm.gen != null) {
            var g = farm.gen;
            String every = g.everySeconds >= 60 ? (g.everySeconds / 60) + "m" : g.everySeconds + "s";
            lore.add("<dark_aqua>⚒ Generates new machines <gray>every <yellow>" + every
                    + " <gray>(<yellow>" + (int) Math.round(g.chance * 100) + "%<gray> chance"
                    + (g.count > 1 ? ", <white>×" + g.count : "")
                    + (g.workerScaled ? ", <aqua>faster with workers" : "") + "):");
            for (FactoryDistrictSpec.Outcome o : g.outcomes) {
                FactoryDistrictSpec.Farm of = spec.farm(o.farmId);
                lore.add("<dark_gray> → <white>" + (of != null ? of.display : o.farmId));
            }
            lore.add("<dark_gray>  generated " + s.offspring + (g.max > 0 ? "/" + g.max : "") + " so far (they fill empty slots)");
        }
        // operating status
        boolean exhausted = FactoryDistrictManager.exhausted(farm, s);
        boolean op = FactoryDistrictManager.operational(spec, machine.factory(), s, farm);
        if (s.broken) lore.add("<red>● Broken <gray>(cave-in — repair to resume)");
        else if (exhausted) lore.add("<red>● Exhausted <gray>(depleted — demolish for scrap)");
        else lore.add(op ? "<green>● Operational" : "<red>● Idle" + idleReason(machine.factory(), spec, s, farm));
        String star = s.mergeStars > 0 ? " <gold>★" + (s.mergeStars + 1) : "";
        inv.setItem(SLOT_INFO, icon(farm.iconItem, ((s.broken || exhausted) ? "<red>" : "<green>") + size.display + " " + farm.display + star, lore));

        if (s.broken) {
            List<String> rl = new ArrayList<>();
            if (farm.hazardRepair.isEmpty()) rl.add("<gray>Free.");
            else { rl.add("<gray>Cost (inventory + vault):"); for (ItemStack c : farm.hazardRepair) rl.add("<dark_gray> • <white>" + c.getAmount() + "x " + FactoryDistrictManager.pretty(c.getType().name())); }
            inv.setItem(F_REPAIR, icon(Material.IRON_PICKAXE, "<gold>⛏ Repair cave-in", rl));
        }

        // convert-upgrades: each option is a clickable icon in the grid
        for (int i = 0; i < farm.upgrades.size() && GRID_START + i < GRID_END; i++) {
            inv.setItem(GRID_START + i, upgradeIcon(player, machine.factory(), spec, s, farm.upgrades.get(i)));
        }

        // worker assignment (machines that need labour)
        // lifetime production log
        List<String> log = new ArrayList<>();
        log.add("<gray>Total cycles: <yellow>" + s.cyclesProduced);
        if (s.producedLog.isEmpty()) log.add("<dark_gray> (nothing produced yet)");
        else s.producedLog.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8)
                .forEach(e -> log.add("<dark_gray> • <white>" + e.getValue() + "x <gray>" + prettyKey(e.getKey())));
        inv.setItem(2, icon(Material.WRITABLE_BOOK, "<gold>Production log", log));

        if (farm.workers > 0) {
            inv.setItem(SLOT_INFO + 1, icon(Material.PLAYER_HEAD, "<gold>Workers: <white>" + s.workersAssigned + "<gray>/<yellow>" + farm.workers, List.of(
                    "<gray>Pool free: <white>" + FactoryDistrictManager.workersFree(spec, machine.factory())
                            + "<gray>/<white>" + FactoryDistrictManager.employableWorkers(spec, machine.factory()),
                    "<gray>Assign workers to run this machine.")));
            inv.setItem(F_WORKER_MINUS, icon(Material.RED_CONCRETE, "<red>– Lay off a worker", List.of()));
            inv.setItem(F_WORKER_PLUS, icon(Material.LIME_CONCRETE, "<green>+ Employ a worker", List.of(
                    "<gray>Needs a free worker in the pool.")));
        }
        if (farm.upgradeable()) {
            String lvlLabel = farm.maxLevel > 0 ? s.level + "<gray>/<yellow>" + farm.maxLevel : String.valueOf(s.level);
            if (farm.canUpgrade(s.level)) {
                List<String> ul = new ArrayList<>();
                ul.add("<gray>Level <yellow>" + lvlLabel + "<gray> → <yellow>" + (s.level + 1));
                if (farm.providesWorkers > 0) ul.add("<gray>+<aqua>" + farm.workersPerLevel + "<gray> worker capacity");
                ul.add("<gray>Cost (inventory + vault):");
                for (ItemStack c : farm.upgradeCost) ul.add("<dark_gray> • <white>" + c.getAmount() + "x " + FactoryDistrictManager.pretty(c.getType().name()));
                inv.setItem(F_UPGRADE, icon(Material.ANVIL, "<gold>Upgrade <gray>(lvl " + lvlLabel + ")", ul));
            } else {
                inv.setItem(F_UPGRADE, icon(Material.BARRIER, "<dark_gray>Max level <gray>(" + lvlLabel + ")",
                        List.of("<dark_gray>Fully upgraded.")));
            }
        }

        int mergePartner = findMergePartner(machine.factory(), slotIdx, s.factoryId);
        if (mergePartner >= 0 && !s.broken) {
            var ps = machine.factory().slot(mergePartner);
            inv.setItem(F_MERGE, icon(Material.NETHER_STAR, "<gold>★ Merge", List.of(
                    "<gray>Fuse another <white>" + farm.display + "<gray> into this one.",
                    "<gray>Their yields add up; the other slot frees.",
                    "<gray>Result: <gold>★" + ((s.mergeStars + 1) + (ps.mergeStars + 1)),
                    "<yellow>Click<gray> to merge.")));
        } else if (s.mergeStars > 0) {
            inv.setItem(F_MERGE, icon(Material.NETHER_STAR, "<gold>★" + (s.mergeStars + 1) + " <gray>(fused)", List.of(
                    "<gray>Build another <white>" + farm.display + "<gray> to fuse more.")));
        }
        if (!farm.recipes.isEmpty()) {
            int sel = (s.selectedRecipes == null) ? 0 : s.selectedRecipes.size();
            inv.setItem(F_RECIPES, icon(Material.CRAFTING_TABLE, "<gold>Select recipe" + (sel > 0 ? " <gray>(" + sel + ")" : ""), List.of(
                    "<gray>Choose what this machine crafts.",
                    farm.stackMode == FactoryDistrictSpec.StackMode.MULTI
                            ? "<gray>★ = up to <white>" + (s.mergeStars + 1) + "<gray> recipes at once."
                            : "<gray>Pick one (★ = faster).",
                    "<yellow>Click<gray> to choose.")));
        } else if (farm.craftsVanilla) {
            inv.setItem(F_RECIPES, icon(Material.CRAFTING_TABLE, "<gold>Open crafting bench", List.of(
                    "<gray>Craft any vanilla recipe straight",
                    "<gray>from materials in the vault.",
                    "<yellow>Click<gray> to open.")));
        }
        inv.setItem(F_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        inv.setItem(F_DEMOLISH, icon(Material.TNT, "<red>Demolish", List.of(
                "<gray>Frees the slot + its workers." + (farm.demolishRefund.isEmpty() ? " <red>No refund." : " <green>Refunds some materials."))));
        player.openInventory(inv);
    }

    private static boolean reqMet(FactoryDistrictData d, FactoryDistrictSpec spec, String reqId) {
        for (FactoryDistrictData.Slot s : d.slots()) {
            if (!s.isBuilt() || !reqId.equalsIgnoreCase(s.factoryId)) continue;
            var rf = spec.farm(s.factoryId);
            if (rf != null && FactoryDistrictManager.staffed(rf, s)) return true;
        }
        return false;
    }

    private static String idleReason(FactoryDistrictData d, FactoryDistrictSpec spec, FactoryDistrictData.Slot s, FactoryDistrictSpec.Farm farm) {
        if (!FactoryDistrictManager.staffed(farm, s)) return " <gray>(needs workers)";
        if (!FactoryDistrictManager.requiresMet(spec, d, farm)) return " <gray>(missing prerequisite)";
        return "";
    }

    private void openRecipes(Player player, Machine machine, int slotIdx) {
        FactoryDistrictSpec spec = specOf(machine);
        FactoryDistrictData.Slot s = machine.factory().slot(slotIdx);
        FactoryDistrictSpec.Farm farm = (s != null && s.isBuilt()) ? spec.farm(s.factoryId) : null;
        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.RECIPES, 0);
        holder.setSlotIndex(slotIdx);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>Select recipe"));
        holder.setInventory(inv);
        if (farm != null && !farm.recipes.isEmpty()) {
            List<FactoryDistrictSpec.CraftRecipe> active = FactoryDistrictManager.activeRecipes(farm, s);
            inv.setItem(SLOT_INFO, icon(Material.CRAFTING_TABLE, "<gold>Recipes", List.of(
                    farm.stackMode == FactoryDistrictSpec.StackMode.MULTI
                            ? "<gray>Pick up to <white>" + FactoryDistrictManager.unitCount(s) + "<gray> — it runs them all."
                            : "<gray>Pick one — ★ runs it faster.",
                    "<yellow>Click<gray> a recipe to toggle it.")));
            for (int i = 0; i < farm.recipes.size() && GRID_START + i < GRID_END; i++) {
                FactoryDistrictSpec.CraftRecipe r = farm.recipes.get(i);
                boolean on = active.contains(r);
                inv.setItem(GRID_START + i, icon(on ? Material.LIME_DYE : Material.GRAY_DYE, "<gold>" + r.display, List.of(
                        "<gray>" + recipeShort(r),
                        on ? "<green>▶ Selected" : "<dark_gray>Click to select")));
            }
        }
        inv.setItem(F_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        player.openInventory(inv);
    }

    /** Vault entries, most-stored first (ties keep insertion order) — the display + click order. */
    private static List<Map.Entry<ItemStack, Long>> vaultEntriesSorted(FactoryDistrictData d) {
        List<Map.Entry<ItemStack, Long>> entries = new ArrayList<>(d.vault().entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue() == null ? 0 : b.getValue(), a.getValue() == null ? 0 : a.getValue()));
        return entries;
    }

    private void openVault(Player player, Machine machine, int page) {
        FactoryDistrictData d = machine.factory();
        List<Map.Entry<ItemStack, Long>> entries = vaultEntriesSorted(d);
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.VAULT, page);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<light_purple>Quantum Vault"));
        holder.setInventory(inv);

        inv.setItem(SLOT_INFO, icon(Material.ENDER_CHEST, "<light_purple>Quantum Vault", List.of(
                "<gray>Stored mass: <white>" + d.vaultMass(),
                "<gray>Stack click: <yellow>take one stack <gray>· <yellow>shift/right<gray> take all",
                "<gray>Click items in your inventory below to <green>deposit<gray>.")));

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

    /** Vault-sourced vanilla crafting browser (ADR 0021): every recipe the vault can afford right now. */
    private void openVanilla(Player player, Machine machine, int slotIdx, int page) {
        List<FactoryDistrictManager.CraftOption> opts = FactoryDistrictManager.craftableFromVault(machine.factory());
        int pages = Math.max(1, (int) Math.ceil(opts.size() / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.VANILLA, page);
        holder.setSlotIndex(slotIdx);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>Crafting (from vault)"));
        holder.setInventory(inv);

        inv.setItem(SLOT_INFO, icon(Material.CRAFTING_TABLE, "<gold>Vault crafting", List.of(
                "<gray>Everything your vault can craft right now.",
                "<gray>Deposit raw materials, then craft here —",
                "<gray>ingredients leave the vault, products return.",
                "<yellow>Click<gray> craft 1 · <yellow>shift<gray> craft as many as you can")));
        if (opts.isEmpty())
            inv.setItem(GRID_START + 13, icon(Material.BARRIER, "<red>Nothing craftable yet", List.of(
                    "<gray>Deposit raw materials into the vault",
                    "<gray>(planks, ingots, …), then reopen this bench.")));

        int first = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && GRID_START + i < GRID_END; i++) {
            int idx = first + i;
            if (idx >= opts.size()) break;
            FactoryDistrictManager.CraftOption opt = opts.get(idx);
            ItemStack ic = opt.result.clone();
            ic.setAmount(Math.max(1, Math.min(ic.getMaxStackSize(), opt.result.getAmount())));
            ItemMeta meta = ic.getItemMeta();
            if (meta != null) {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(mini("<gray>Makes <white>" + opt.result.getAmount() + "x <gray>per craft"));
                lore.add(mini("<gray>Needs:"));
                for (Map.Entry<Material, Integer> en : opt.need.entrySet())
                    lore.add(mini("<dark_gray> • <white>" + en.getValue() + "x <gray>" + FactoryDistrictManager.pretty(en.getKey().name())));
                lore.add(mini("<gray>Vault affords: <yellow>" + opt.maxCrafts));
                lore.add(mini("<yellow>Click<gray> craft 1 · <yellow>shift<gray> craft " + opt.maxCrafts));
                meta.lore(lore);
                ic.setItemMeta(meta);
            }
            inv.setItem(GRID_START + i, ic);
        }
        inv.setItem(V_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        if (page > 0) inv.setItem(V_PREV, icon(Material.ARROW, "<yellow>◀ Previous", List.of()));
        if (page < pages - 1) inv.setItem(V_NEXT, icon(Material.ARROW, "<yellow>Next ▶", List.of()));
        player.openInventory(inv);
    }

    // --- output chests (vault → chest routing) -------------------------------

    /** Public re-entry used by the manager after a chest-link/rate chat flow completes. */
    public void openChestsView(Player player, Machine machine) { openChests(player, machine, 0); }

    private void openChests(Player player, Machine machine, int page) {
        FactoryDistrictData d = machine.factory();
        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.CHESTS, page);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>Output Chests"));
        holder.setInventory(inv);
        inv.setItem(SLOT_INFO, icon(Material.HOPPER, "<aqua>I/O Chests", List.of(
                "<gray><aqua>INPUT<gray> chests feed items into the vault.",
                "<gray><green>OUTPUT<gray> chests drain it out.",
                "<gray>Each has its own filter + flow rate.",
                "<dark_gray>Reach grows as the district is upgraded.")));
        var links = d == null ? java.util.List.<dev.servereer.machineconstruct.grinder.ChestLink>of() : d.links();
        int shown = 0;
        for (int i = 0; i < links.size() && GRID_START + i < GRID_END; i++) { inv.setItem(GRID_START + i, linkCard(links.get(i), i)); shown++; }
        if (shown == 0) inv.setItem(22, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>No chests linked yet",
                List.of("<dark_gray>Use the buttons below.")));
        inv.setItem(CH_ADD_OUT, icon(Material.CHEST, "<green>+ Add OUTPUT chest", List.of(
                "<gray>Pushes vault items into a chest.",
                "<dark_gray>▶ Click, then right-click a chest in range.")));
        inv.setItem(CH_ADD_IN, icon(Material.TRAPPED_CHEST, "<aqua>+ Add INPUT chest", List.of(
                "<gray>Pulls items from a chest into the vault",
                "<gray>(feeds processors & crafters).",
                "<dark_gray>▶ Click, then right-click a chest in range.")));
        inv.setItem(CH_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        player.openInventory(inv);
    }

    private static ItemStack linkCard(dev.servereer.machineconstruct.grinder.ChestLink l, int index) {
        boolean out = l.type() == dev.servereer.machineconstruct.grinder.ChestLink.Type.OUTPUT;
        org.bukkit.Location loc = l.loc();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>At <white>" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
        lore.add("<gray>" + (out ? "Pushes vault → chest." : "Pulls chest → vault."));
        lore.add("<gray>Filter: <white>" + (l.filtered() ? l.filter().size() + " item(s)" : "everything"));
        lore.add("<gray>Rate: <white>" + (l.fullTransfer() ? "full (all that fits)" : l.maxPerSec() + "/sec"));
        lore.add("<yellow>◀ Left: set rate   <gold>⇧ Shift: filter");
        lore.add("<red>▶ Right: remove");
        return icon(out ? Material.CHEST : Material.TRAPPED_CHEST,
                (out ? "<green>OUTPUT" : "<aqua>INPUT") + " <dark_gray>#" + (index + 1), lore);
    }

    private void openChestFilter(Player player, Machine machine, int linkIndex) {
        FactoryDistrictData d = machine.factory();
        var links = d == null ? java.util.List.<dev.servereer.machineconstruct.grinder.ChestLink>of() : d.links();
        if (linkIndex < 0 || linkIndex >= links.size()) { openChests(player, machine, 0); return; }
        var link = links.get(linkIndex);
        boolean in = link.type() == dev.servereer.machineconstruct.grinder.ChestLink.Type.INPUT;
        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.CHEST_FILTER, 0);
        holder.setLinkIndex(linkIndex);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>" + (in ? "Input" : "Output") + " Filter"));
        holder.setInventory(inv);
        inv.setItem(SLOT_INFO, icon(Material.HOPPER, "<aqua>" + (in ? "Input" : "Output") + " Filter", List.of(
                "<gray>Click items to " + (in ? "pull" : "push") + " <white>only<gray> those.",
                "<gray>Empty filter = " + (in ? "pull" : "push") + " everything.",
                in ? "<dark_gray>Candidates = items in the linked chest." : "<dark_gray>Candidates = items in the vault.")));
        List<Material> cands = filterCandidates(d, link);
        for (int i = 0; i < cands.size() && i < PER_PAGE; i++) {
            Material mat = cands.get(i);
            boolean on = link.filter().contains(mat);
            ItemStack it = new ItemStack(mat.isItem() ? mat : Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.displayName(mini((on ? "<green>✔ " : "<gray>") + FactoryDistrictManager.pretty(mat.name())));
                meta.lore(List.of(mini(on ? (in ? "<green>pulling this" : "<green>pushing this") : "<dark_gray>not " + (in ? "pulled" : "pushed")), mini("<dark_gray>▶ click to toggle")));
                if (on) { meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true); meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS); }
                it.setItemMeta(meta);
            }
            inv.setItem(GRID_START + i, it);
        }
        if (cands.isEmpty()) inv.setItem(22, icon(Material.BARRIER,
                in ? "<gray>Linked chest is empty" : "<gray>Vault is empty",
                List.of(in ? "<dark_gray>put items in the chest first" : "<dark_gray>produce or deposit items first")));
        inv.setItem(CF_ROUTE_ALL, icon(Material.HOPPER, "<yellow>" + (in ? "Pull" : "Push") + " everything", List.of("<gray>Clear the filter.", "<dark_gray>▶ Click")));
        inv.setItem(CF_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        player.openInventory(inv);
    }

    /** The filter candidate set for a link: the linked chest's contents (INPUT) or the vault's (OUTPUT). */
    private static List<Material> filterCandidates(FactoryDistrictData d, dev.servereer.machineconstruct.grinder.ChestLink link) {
        java.util.LinkedHashSet<Material> set = new java.util.LinkedHashSet<>();
        if (link != null && link.type() == dev.servereer.machineconstruct.grinder.ChestLink.Type.INPUT) {
            org.bukkit.Location loc = link.loc();
            if (loc.getWorld() != null && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)
                    && loc.getBlock().getState() instanceof org.bukkit.block.Container c) {
                for (ItemStack it : c.getInventory().getContents())
                    if (it != null && it.getType() != Material.AIR) set.add(it.getType());
            }
        } else if (d != null) {
            for (ItemStack k : d.vault().keySet())
                if (k != null && k.getType() != Material.AIR) set.add(k.getType());
        }
        return new ArrayList<>(set);
    }

    // --- auto-sell (vault → economy) -----------------------------------------

    /** Public re-entry used by the manager after an auto-sell rate chat flow completes. */
    public void openAutoSellView(Player player, Machine machine) { openAutoSell(player, machine); }

    private void openAutoSell(Player player, Machine machine) {
        FactoryDistrictData d = machine.factory();
        FactoryDistrictMenuHolder holder = new FactoryDistrictMenuHolder(machine, FactoryDistrictMenuHolder.View.AUTOSELL, 0);
        Inventory inv = Bukkit.createInventory(holder, SIZE, mini("<dark_aqua>Auto-Sell"));
        holder.setInventory(inv);
        boolean on = d != null && d.autoSellEnabled();
        inv.setItem(SLOT_INFO, icon(Material.GOLD_BLOCK, "<gold>Auto-Sell <dark_gray>(per item)", List.of(
                "<gray>Master: " + (on ? "<green>ON" : "<red>OFF"),
                "<gray>Selling: <white>" + (d == null ? 0 : d.autoSellRules().size()) + "<gray> item type(s)",
                "<gray>Pending payout: <yellow>$" + (d == null ? "0" : String.format("%.2f", d.autoSellAccMoney()))
                        + " <dark_gray>(" + (d == null ? 0 : d.autoSellAccItems()) + " items, paid hourly)",
                "<dark_gray>Prices via the server shop (EconomyShopGUI).",
                "<yellow>◀ Left: toggle item   <gold>▶ Right: set its rate")));
        List<Material> cands = filterCandidates(d, null);   // current vault item types
        for (int i = 0; i < cands.size() && i < PER_PAGE; i++) {
            Material mat = cands.get(i);
            boolean sel = d != null && d.isAutoSellItem(mat);
            ItemStack it = new ItemStack(mat.isItem() ? mat : Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.displayName(mini((sel ? "<green>✔ " : "<gray>") + FactoryDistrictManager.pretty(mat.name())));
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                if (sel) {
                    lore.add(mini("<green>auto-selling @ <white>" + (d.autoSellItemFull(mat) ? "all" : d.autoSellRate(mat) + "/sec")));
                    lore.add(mini("<yellow>◀ Left: stop   <gold>▶ Right: set rate"));
                } else {
                    lore.add(mini("<dark_gray>not auto-selling"));
                    lore.add(mini("<dark_gray>▶ Left-click to enable"));
                }
                meta.lore(lore);
                if (sel) { meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true); meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS); }
                it.setItemMeta(meta);
            }
            inv.setItem(GRID_START + i, it);
        }
        if (cands.isEmpty()) inv.setItem(22, icon(Material.BARRIER, "<gray>Vault is empty", List.of("<dark_gray>produce or deposit items first")));
        inv.setItem(AS_TOGGLE, icon(on ? Material.LIME_DYE : Material.GRAY_DYE, on ? "<green>Auto-Sell ON" : "<red>Auto-Sell OFF", List.of("<dark_gray>▶ master switch")));
        inv.setItem(AS_SELL_ALL, icon(Material.HOPPER, "<aqua>Auto-sell EVERY item", List.of("<gray>Enable every vault item at rate 'all'.", "<dark_gray>▶ click")));
        inv.setItem(AS_CLEAR, icon(Material.REDSTONE, "<red>Stop selling all", List.of("<gray>Clear every per-item rule.", "<dark_gray>▶ click")));
        inv.setItem(AS_BACK, icon(Material.ARROW, "<yellow>◀ Back", List.of()));
        player.openInventory(inv);
    }

    // --- click handling (sealed) ---------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof FactoryDistrictMenuHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Machine m = holder.machine();
        Inventory top = e.getView().getTopInventory();

        // VAULT: click an item in your own inventory to deposit it.
        if (holder.view() == FactoryDistrictMenuHolder.View.VAULT
                && e.getClickedInventory() != null && e.getClickedInventory() != top) {
            ItemStack clicked = e.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                long dep = actions.depositVault(m, p, clicked);
                if (dep > 0) {
                    if (dep >= clicked.getAmount()) e.getClickedInventory().setItem(e.getSlot(), null);
                    else clicked.setAmount(clicked.getAmount() - (int) dep);
                    later(() -> openVault(p, m, holder.page()));
                }
            }
            return;
        }
        if (holder.view() == FactoryDistrictMenuHolder.View.VAULT && e.getClickedInventory() == top) {
            ItemStack cursor = e.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                long dep = actions.depositVault(m, p, cursor);
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
                if (slot == SLOT_CAPTURE) {
                    int n = actions.captureCreatures(m, p);
                    p.sendMessage(mini(n > 0 ? "<green>Captured <white>" + n + "<green> creature(s)."
                            : "<yellow>No creatures within range."));
                    later(() -> openMain(p, m, holder.page()));
                } else if (slot == SLOT_UPGRADE) { actions.upgradeDistrict(m, p); later(() -> openMain(p, m, holder.page())); }
                else if (slot == SLOT_VAULT) { later(() -> openVault(p, m, 0)); }
                else if (slot == SLOT_CHESTS) { later(() -> openChests(p, m, 0)); }
                else if (slot == SLOT_AUTOSELL) { later(() -> openAutoSell(p, m)); }
                else if (slot == SLOT_PREV) { later(() -> openMain(p, m, holder.page() - 1)); }
                else if (slot == SLOT_NEXT) { later(() -> openMain(p, m, holder.page() + 1)); }
                else if (slot >= GRID_START && slot < GRID_END) {
                    int idx = holder.page() * PER_PAGE + (slot - GRID_START);
                    FactoryDistrictData.Slot s = m.factory().slot(idx);
                    if (s == null) return;
                    if (!s.isBuilt()) later(() -> openCategories(p, m, idx));
                    else later(() -> openFactory(p, m, idx));
                }
            }
            case CATEGORIES -> {
                int back = holder.slotIndex();
                if (slot == B_BACK) { later(() -> openMain(p, m, back / PER_PAGE)); return; }
                if (slot >= GRID_START && slot < GRID_END) {
                    FactoryDistrictSpec spec = specOf(m);
                    List<FactoryDistrictSpec.Category> cats = visibleCategories(spec);
                    int idx = slot - GRID_START;
                    if (idx < cats.size()) {
                        String catId = cats.get(idx).id;
                        later(() -> openBuild(p, m, back, catId));
                    }
                }
            }
            case BUILD -> {
                int back = holder.slotIndex();
                if (slot == B_BACK) { later(() -> openCategories(p, m, back)); return; }
                if (slot >= GRID_START && slot < GRID_END) {
                    FactoryDistrictSpec spec = specOf(m);
                    List<FactoryDistrictSpec.Farm> farms = spec.buildableFarmsIn(holder.categoryId());
                    int idx = slot - GRID_START;
                    if (idx < farms.size()) {
                        String farmId = farms.get(idx).id;
                        String catId = holder.categoryId();
                        boolean built = actions.buildFarm(m, p, back, farmId, null);   // single implicit size
                        later(() -> { if (built) openMain(p, m, back / PER_PAGE); else openBuild(p, m, back, catId); });
                    }
                }
            }
            case FACTORY -> {
                int idx = holder.slotIndex();
                if (slot == F_BACK) { later(() -> openMain(p, m, idx / PER_PAGE)); }
                else if (slot == F_DEMOLISH) { actions.demolishFarm(m, p, idx); later(() -> openMain(p, m, idx / PER_PAGE)); }
                else if (slot == F_WORKER_PLUS) { actions.assignWorker(m, p, idx, +1); later(() -> openFactory(p, m, idx)); }
                else if (slot == F_WORKER_MINUS) { actions.assignWorker(m, p, idx, -1); later(() -> openFactory(p, m, idx)); }
                else if (slot == F_UPGRADE) { actions.upgradeHouse(m, p, idx); later(() -> openFactory(p, m, idx)); }
                else if (slot == F_REPAIR) { actions.repairFarm(m, p, idx); later(() -> openFactory(p, m, idx)); }
                else if (slot == F_MERGE) {
                    var self = m.factory().slot(idx);
                    if (self != null && self.isBuilt()) {
                        int partner = findMergePartner(m.factory(), idx, self.factoryId);
                        if (partner >= 0) actions.mergeFarm(m, p, idx, partner);
                    }
                    later(() -> openFactory(p, m, idx));
                }
                else if (slot == F_RECIPES) {
                    FactoryDistrictSpec spec = specOf(m);
                    FactoryDistrictData.Slot s = m.factory().slot(idx);
                    FactoryDistrictSpec.Farm farm = (s != null && s.isBuilt()) ? spec.farm(s.factoryId) : null;
                    if (farm != null && farm.recipes.isEmpty() && farm.craftsVanilla) {
                        later(() -> openVanilla(p, m, idx, 0));
                    } else {
                        later(() -> openRecipes(p, m, idx));
                    }
                }
                else if (slot >= GRID_START && slot < GRID_END) {           // a convert-upgrade option
                    actions.convertFarm(m, p, idx, slot - GRID_START);
                    later(() -> openFactory(p, m, idx));
                }
            }
            case RECIPES -> {
                int idx = holder.slotIndex();
                if (slot == F_BACK) { later(() -> openFactory(p, m, idx)); return; }
                if (slot >= GRID_START && slot < GRID_END) {
                    FactoryDistrictSpec spec = specOf(m);
                    FactoryDistrictData.Slot s = m.factory().slot(idx);
                    FactoryDistrictSpec.Farm farm = (s != null && s.isBuilt()) ? spec.farm(s.factoryId) : null;
                    if (farm != null && !farm.recipes.isEmpty()) {
                        int rIdx = slot - GRID_START;
                        if (rIdx < farm.recipes.size()) {
                            actions.selectRecipe(m, p, idx, farm.recipes.get(rIdx).id);
                            later(() -> openRecipes(p, m, idx));
                        }
                    }
                }
            }
            case VANILLA -> {
                int idx = holder.slotIndex();
                if (slot == V_BACK) { later(() -> openFactory(p, m, idx)); return; }
                if (slot == V_PREV) { later(() -> openVanilla(p, m, idx, holder.page() - 1)); return; }
                if (slot == V_NEXT) { later(() -> openVanilla(p, m, idx, holder.page() + 1)); return; }
                if (slot >= GRID_START && slot < GRID_END) {
                    List<FactoryDistrictManager.CraftOption> opts = FactoryDistrictManager.craftableFromVault(m.factory());
                    int oi = holder.page() * PER_PAGE + (slot - GRID_START);
                    if (oi < opts.size()) {
                        org.bukkit.Material res = opts.get(oi).result.getType();
                        boolean all = e.isShiftClick();
                        actions.craftVanilla(m, p, idx, res, all);
                        later(() -> openVanilla(p, m, idx, holder.page()));
                    }
                }
            }
            case VAULT -> {
                if (slot == V_BACK) { later(() -> openMain(p, m, 0)); }
                else if (slot == V_PREV) { later(() -> openVault(p, m, holder.page() - 1)); }
                else if (slot == V_NEXT) { later(() -> openVault(p, m, holder.page() + 1)); }
                else if (slot >= GRID_START && slot < GRID_END) {
                    List<Map.Entry<ItemStack, Long>> entries = vaultEntriesSorted(m.factory());
                    int idx = holder.page() * PER_PAGE + (slot - GRID_START);
                    if (idx < entries.size()) {
                        ItemStack template = entries.get(idx).getKey();
                        actions.withdrawVault(m, p, template, e.isShiftClick() || e.isRightClick());
                        later(() -> openVault(p, m, holder.page()));
                    }
                }
            }
            case CHESTS -> {
                if (slot == CH_BACK) { later(() -> openMain(p, m, 0)); }
                else if (slot == CH_ADD_OUT) {
                    actions.beginChestSelection(m, p, dev.servereer.machineconstruct.grinder.ChestLink.Type.OUTPUT); // closes menu, awaits chest click
                } else if (slot == CH_ADD_IN) {
                    actions.beginChestSelection(m, p, dev.servereer.machineconstruct.grinder.ChestLink.Type.INPUT);
                } else if (slot >= GRID_START && slot < GRID_END) {
                    var links = m.factory().links();
                    int idx = slot - GRID_START;
                    if (idx >= links.size()) return;
                    if (e.isRightClick()) { actions.removeChestLink(m, idx); later(() -> openChests(p, m, holder.page())); }
                    else if (e.isShiftClick()) later(() -> openChestFilter(p, m, idx));
                    else actions.promptLinkRate(m, p, idx);   // closes menu + chat-prompts; reopens to CHESTS
                }
            }
            case CHEST_FILTER -> {
                int idx = holder.linkIndex();
                if (slot == CF_BACK) { later(() -> openChests(p, m, 0)); }
                else if (slot == CF_ROUTE_ALL) { actions.clearLinkFilter(m, idx); later(() -> openChestFilter(p, m, idx)); }
                else if (slot >= GRID_START && slot < GRID_END) {
                    var links = m.factory().links();
                    dev.servereer.machineconstruct.grinder.ChestLink link = (idx >= 0 && idx < links.size()) ? links.get(idx) : null;
                    List<Material> cands = filterCandidates(m.factory(), link);
                    int ci = slot - GRID_START;
                    if (ci < cands.size()) { actions.toggleLinkFilter(m, idx, cands.get(ci)); later(() -> openChestFilter(p, m, idx)); }
                }
            }
            case AUTOSELL -> {
                if (slot == AS_BACK) { later(() -> openMain(p, m, 0)); }
                else if (slot == AS_TOGGLE) { actions.toggleAutoSell(m); later(() -> openAutoSell(p, m)); }
                else if (slot == AS_SELL_ALL) {
                    var d = m.factory();
                    for (Material mat : filterCandidates(d, null)) if (!d.isAutoSellItem(mat)) actions.toggleAutoSellItem(m, mat);
                    later(() -> openAutoSell(p, m));
                } else if (slot == AS_CLEAR) { actions.clearAutoSellItems(m); later(() -> openAutoSell(p, m)); }
                else if (slot >= GRID_START && slot < GRID_END) {
                    var d = m.factory();
                    List<Material> cands = filterCandidates(d, null);
                    int ci = slot - GRID_START;
                    if (ci < cands.size()) {
                        Material mat = cands.get(ci);
                        if (e.isRightClick()) {
                            if (!d.isAutoSellItem(mat)) actions.toggleAutoSellItem(m, mat);   // enable first
                            actions.promptAutoSellItemRate(m, p, mat);                        // then prompt rate (closes menu)
                        } else { actions.toggleAutoSellItem(m, mat); later(() -> openAutoSell(p, m)); }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof FactoryDistrictMenuHolder holder)) return;
        e.setCancelled(true);
        if (holder.view() != FactoryDistrictMenuHolder.View.VAULT) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int topSize = e.getView().getTopInventory().getSize();
        boolean intoVault = e.getRawSlots().stream().anyMatch(s -> s < topSize);
        if (!intoVault) return;
        ItemStack dragged = e.getOldCursor();
        if (dragged == null || dragged.getType().isAir()) return;
        long dep = actions.depositVault(holder.machine(), p, dragged.clone());
        int remain = dragged.getAmount() - (int) dep;
        ItemStack rest = remain > 0 ? withAmount(dragged, remain) : null;
        later(() -> { p.setItemOnCursor(rest); openVault(p, holder.machine(), holder.page()); });
    }

    // --- icons ---------------------------------------------------------------

    private ItemStack slotIcon(FactoryDistrictSpec spec, FactoryDistrictData d, FactoryDistrictData.Slot slot) {
        if (slot == null || !slot.isBuilt())
            return icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>Empty slot",
                    List.of("<yellow>Click<gray> to build a farm."));
        FactoryDistrictSpec.Farm farm = spec.farm(slot.factoryId);
        if (farm == null) return icon(Material.BARRIER, "<red>Unknown farm", List.of(slot.factoryId));
        FactoryDistrictSpec.Size size = spec.size(slot.sizeId);
        List<String> lore = new ArrayList<>();
        boolean exhausted = FactoryDistrictManager.exhausted(farm, slot);
        boolean op = FactoryDistrictManager.operational(spec, d, slot, farm);
        if (slot.broken) lore.add("<red>● Broken <gray>(cave-in — repair)");
        else if (exhausted) lore.add("<red>● Exhausted <gray>(demolish for scrap)");
        else lore.add(op ? "<green>● Operational" : "<red>● Idle" + idleReason(d, spec, slot, farm));
        if (farm.hasRichness() || slot.richness != 1.0) lore.add("<gray>Richness <gold>×" + String.format("%.2f", slot.richness));
        if (farm.depletes()) lore.add("<gray>Depletion <yellow>" + slot.cyclesProduced + "<gray>/<yellow>" + farm.depleteCycles);
        if (!farm.upgrades.isEmpty()) lore.add("<gold>⬆ Upgradeable <gray>(open to convert)");
        if (farm.providesWorkers > 0) lore.add("<aqua>House<gray>: +" + (farm.providesWorkers + slot.level * farm.workersPerLevel) + " workers");
        if (farm.workers > 0) lore.add("<gray>Workers <white>" + slot.workersAssigned + "<gray>/<yellow>" + farm.workers);
        if (!farm.output.isEmpty()) {
            lore.add("<gray>Every <yellow>" + farm.cycleSeconds + "s<gray>:");
            for (FactoryDistrictSpec.Reward o : FactoryDistrictManager.outputFor(farm, size))
                lore.add("<dark_gray> → " + rewardLine(o));
        }
        if (!farm.inputs.isEmpty()) lore.add("<aqua>Vault-fed<gray> (consumes inputs)");
        lore.add("<yellow>Click<gray> to manage.");
        return icon(farm.iconItem, (op ? "<green>" : "<gray>") + size.display + " " + farm.display, lore);
    }

    /** BUILD picker: a farm with its full build cost (have/need) + output. Click → build. */
    private ItemStack farmIcon(Player p, FactoryDistrictData d, FactoryDistrictSpec spec, FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size) {
        List<String> lore = new ArrayList<>();
        if (farm.providesWorkers > 0) lore.add(spec.populationWorkers()
                ? "<aqua>House<gray> — homes <white>" + farm.providesWorkers + "<gray> villager(s) <dark_gray>(they become workers)"
                : "<aqua>House<gray> — provides <white>" + farm.providesWorkers + "<gray> workers");
        if (farm.raisesMaxFarm != null) {
            var tf = spec.farm(farm.raisesMaxFarm);
            lore.add("<aqua>Outpost<gray> — +<white>" + farm.raisesMaxBy + "<gray> max " + (tf != null ? tf.display : farm.raisesMaxFarm));
        }
        if (farm.boostsOutput())
            lore.add("<light_purple>⬆ Increases ALL farms' production ×" + String.format("%.2f", farm.boostsOutput) + " <gray>(while staffed)");
        if (farm.boostsWorkers())
            lore.add("<aqua>⬆ Increases worker capacity ×" + String.format("%.2f", farm.boostsWorkers));
        if (farm.gen != null) {
            String every = farm.gen.everySeconds >= 60 ? (farm.gen.everySeconds / 60) + "m" : farm.gen.everySeconds + "s";
            lore.add("<dark_aqua>⚒ Generates new machines<gray> every <yellow>" + every
                    + " <gray>(<yellow>" + (int) Math.round(farm.gen.chance * 100) + "%<gray>"
                    + (farm.gen.workerScaled ? ", <aqua>faster with workers" : "") + ")");
            for (FactoryDistrictSpec.Outcome o : farm.gen.outcomes) {
                FactoryDistrictSpec.Farm of = spec.farm(o.farmId);
                lore.add("<dark_gray> → <white>" + (of != null ? of.display : o.farmId));
            }
        }
        if (farm.breeds())
            lore.add("<green>⚘ Breeds <white>" + FactoryDistrictManager.pretty(farm.breeding.type.name()) + "<gray> into the roster");
        if (farm.isCrafter()) {
            lore.add("<dark_aqua>⚒ " + (farm.craftsVanilla ? "Vanilla crafting bench" : "Recipe processor (vault)"));
            for (FactoryDistrictSpec.CraftRecipe r : farm.recipes)
                lore.add("<dark_gray>   " + r.display + " " + recipeShort(r));
        }
        if (!farm.output.isEmpty()) {
            lore.add("<gray>Produces every <yellow>" + farm.cycleSeconds + "s<gray>:");
            for (FactoryDistrictSpec.Reward o : FactoryDistrictManager.outputFor(farm, size))
                lore.add("<dark_gray> → " + rewardLine(o));
        }
        if (farm.workers > 0) lore.add("<gray>Needs <yellow>" + farm.workers + "<gray> worker(s) to run");
        if (!farm.requires.isEmpty()) {
            StringBuilder rq = new StringBuilder();
            for (String r : farm.requires) { var rf = spec.farm(r); if (rq.length() > 0) rq.append(", "); rq.append(rf != null ? rf.display : r); }
            lore.add("<gray>Requires: <white>" + rq);
        }
        int cap = FactoryDistrictManager.effectiveMaxCount(spec, d, farm);
        if (cap != Integer.MAX_VALUE)
            lore.add("<gray>Built: <white>" + FactoryDistrictManager.countOf(d, farm.id) + "<gray>/<yellow>" + cap);
        if (!farm.inputs.isEmpty()) {
            lore.add("<gray>Per-cycle fuel/inputs (from vault):");
            for (ItemStack in : FactoryDistrictManager.inputsFor(farm, size))
                lore.add("<dark_gray> ← <white>" + in.getAmount() + "x " + FactoryDistrictManager.pretty(in.getType().name()));
        }
        lore.add("<gray>Build cost <gray>(have / need):");
        boolean can = true;
        for (ItemStack req : FactoryDistrictManager.blocksFor(farm, size)) {
            long have = FactoryDistrictManager.available(p, d, req);
            boolean ok = have >= req.getAmount();
            if (!ok) can = false;
            lore.add((ok ? "<green>" : "<red>") + "  " + Math.min(have, req.getAmount()) + "/" + req.getAmount()
                    + " <gray>" + FactoryDistrictManager.pretty(req.getType().name()));
        }
        for (FactoryDistrictSpec.MmoCost c : FactoryDistrictManager.mmoBlocksFor(farm, size)) {
            long have = FactoryDistrictManager.mmoAvailable(p, d, c.type, c.id);
            boolean ok = have >= c.amount;
            if (!ok) can = false;
            lore.add((ok ? "<green>" : "<red>") + "  " + Math.min(have, c.amount) + "/" + c.amount
                    + " <aqua>" + FactoryDistrictManager.pretty(c.id) + " <dark_gray>(MMO)");
        }
        for (Map.Entry<EntityType, Integer> c : FactoryDistrictManager.creaturesFor(farm, size).entrySet()) {
            int have = d.creatureCount(c.getKey());
            boolean ok = have >= c.getValue();
            if (!ok) can = false;
            lore.add((ok ? "<green>" : "<red>") + "  " + Math.min(have, c.getValue()) + "/" + c.getValue()
                    + " <gray>" + FactoryDistrictManager.pretty(c.getKey().name()) + " <dark_gray>(creature)");
        }
        lore.add(can ? "<green>Ready — click to build." : "<red>Missing materials/creatures.");
        return icon(farm.iconItem, (can ? "<green>" : "<gray>") + farm.display, lore);
    }

    // --- helpers -------------------------------------------------------------

    private static String tierName(int t) {
        return switch (t) { case 0 -> "Apprentice"; case 1 -> "Journeyman"; case 2 -> "Expert"; case 3 -> "Master"; default -> "Master+" + (t - 3); };
    }

    /** A convert-upgrade option icon: target farm + cost (have/need) + unlock gates. */
    private ItemStack upgradeIcon(Player p, FactoryDistrictData d, FactoryDistrictSpec spec, FactoryDistrictData.Slot slot, FactoryDistrictSpec.Upgrade up) {
        FactoryDistrictSpec.Farm target = spec.farm(up.to);
        boolean unlocked = FactoryDistrictManager.upgradeUnlocked(spec, d, slot, up);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Convert into <white>" + (target != null ? target.display : up.to));
        if (!up.requiresProduced.isEmpty() || !up.requiresBuilding.isEmpty()) {
            lore.add("<gray>Unlock:");
            for (Map.Entry<String, Long> e : up.requiresProduced.entrySet()) {
                long have = slot.producedLog.getOrDefault(e.getKey(), 0L);
                boolean ok = have >= e.getValue();
                lore.add((ok ? "<green> ✔ " : "<red> ✖ ") + "produced " + Math.min(have, e.getValue()) + "/" + e.getValue() + " " + FactoryDistrictManager.pretty(e.getKey()));
            }
            for (String b : up.requiresBuilding) {
                boolean ok = FactoryDistrictManager.countOf(d, b) > 0;
                var bf = spec.farm(b);
                lore.add((ok ? "<green> ✔ " : "<red> ✖ ") + "building: " + (bf != null ? bf.display : b));
            }
        }
        lore.add("<gray>Cost (have / need):");
        boolean can = true;
        for (ItemStack req : up.blocks) {
            long have = FactoryDistrictManager.available(p, d, req);
            boolean ok = have >= req.getAmount(); if (!ok) can = false;
            lore.add((ok ? "<green>" : "<red>") + "  " + Math.min(have, req.getAmount()) + "/" + req.getAmount()
                    + " <gray>" + FactoryDistrictManager.pretty(req.getType().name()));
        }
        for (FactoryDistrictSpec.MmoCost c : up.mmoBlocks) {
            long have = FactoryDistrictManager.mmoAvailable(p, d, c.type, c.id);
            boolean ok = have >= c.amount; if (!ok) can = false;
            lore.add((ok ? "<green>" : "<red>") + "  " + Math.min(have, c.amount) + "/" + c.amount + " <aqua>" + FactoryDistrictManager.pretty(c.id) + " <dark_gray>(MMO)");
        }
        lore.add(!unlocked ? "<red>🔒 Locked" : can ? "<green>Click to upgrade." : "<red>Missing materials.");
        return icon(target != null ? target.iconItem : new ItemStack(Material.ANVIL), (unlocked ? "<gold>" : "<dark_gray>") + "⬆ " + up.name, lore);
    }

    /** Pretty-print a production-log key (material / "TYPE/ID" MMO / "⚡ command"). */
    private static String prettyKey(String key) {
        if (key.startsWith("⚡")) return key;
        if (key.contains("/")) { String[] p = key.split("/", 2); return FactoryDistrictManager.pretty(p[1]) + " <dark_gray>(MMO)"; }
        return FactoryDistrictManager.pretty(key);
    }

    /** A reward line for lore: "1-2x Diamond (25%)", "MMO Sword Cutlass", or "command". */
    private static String rewardLine(FactoryDistrictSpec.Reward r) {
        String amt = r.min == r.max ? String.valueOf(r.min) : r.min + "-" + r.max;
        String chance = r.chance < 1.0 ? " <dark_gray>(" + Math.round(r.chance * 100) + "%)" : "";
        return switch (r.kind) {
            case ITEM -> "<white>" + amt + "x " + FactoryDistrictManager.pretty(r.item.getType().name()) + chance;
            case MMOITEM -> "<aqua>" + amt + "x " + FactoryDistrictManager.pretty(r.mmoType) + " " + FactoryDistrictManager.pretty(r.mmoId) + " <dark_gray>(MMO)" + chance;
            case COMMAND -> "<light_purple>reward command" + chance;
        };
    }

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }
    private static ItemStack withAmount(ItemStack s, int amount) { ItemStack c = s.clone(); c.setAmount(Math.max(1, amount)); return c; }
    private FactoryDistrictSpec specOf(Machine m) { MachineType t = registry == null ? null : registry.getMachineType(m.typeId()); return t == null ? null : t.factory(); }

    /** Categories that have at least one buildable farm (hides convert-only categories like Mines). */
    private static List<FactoryDistrictSpec.Category> visibleCategories(FactoryDistrictSpec spec) {
        List<FactoryDistrictSpec.Category> out = new ArrayList<>();
        for (var c : spec.categories().values()) if (!spec.buildableFarmsIn(c.id).isEmpty()) out.add(c);
        return out;
    }

    /** Build a menu icon from a prototype ItemStack (material or textured head) + name/lore. */
    /** The single most useful "what do I do next?" prompt for the district's current state. */
    private static String nextStep(FactoryDistrictSpec spec, FactoryDistrictData d, int usable) {
        boolean anyBuilt = false, understaffed = false, broken = false, exhausted = false;
        for (int i = 0; i < usable; i++) {
            FactoryDistrictData.Slot s = d.slot(i);
            if (s == null || !s.isBuilt()) continue;
            anyBuilt = true;
            FactoryDistrictSpec.Farm farm = spec.farm(s.factoryId);
            if (farm == null) continue;
            if (s.broken) broken = true;
            if (FactoryDistrictManager.exhausted(farm, s)) exhausted = true;
            if (!FactoryDistrictManager.staffed(farm, s)) understaffed = true;
        }
        int free = FactoryDistrictManager.workersFree(spec, d);
        int cap = FactoryDistrictManager.workerCapacity(spec, d);
        long vault = d.vaultMass();
        long vcap = spec.vaultCapForTier(d.tier());
        if (!anyBuilt)                       return "<yellow>➤ Next: build your first farm — click an empty slot below.";
        if (broken)                          return "<yellow>➤ Next: a farm caved in — open it and click Repair.";
        if (cap == 0)                        return "<yellow>➤ Next: build a House to get workers.";
        if (spec.populationWorkers() && d.workerPopulation() < cap && d.creatureCount(org.bukkit.entity.EntityType.VILLAGER) == 0)
            return "<yellow>➤ Next: get villagers (build a Breeder, or capture some) to fill your houses.";
        if (understaffed && free > 0)        return "<yellow>➤ Next: assign your <gold>" + free + "<yellow> free worker(s) to a farm.";
        if (understaffed && free == 0 && spec.populationWorkers() && d.workerPopulation() < cap)
            return "<yellow>➤ Next: villagers are moving into your houses — give it a moment (or build more houses).";
        if (exhausted)                       return "<yellow>➤ Next: a farm is exhausted — repair/rotate it.";
        if (vcap > 0 && vault >= vcap * 0.9) return "<yellow>➤ Next: vault almost full — open it and withdraw.";
        return "<green>➤ Running well — collect output from the Quantum Vault.";
    }

    /** "2 Copper +1 Iron → 1 Gold +1 Gunpowder" one-liner for a recipe. */
    private static String recipeShort(FactoryDistrictSpec.CraftRecipe r) {
        StringBuilder in = new StringBuilder(), out = new StringBuilder();
        for (java.util.Map.Entry<Material, Integer> e : r.inputs.entrySet()) {
            if (in.length() > 0) in.append("<dark_gray>+");
            in.append("<white>").append(e.getValue()).append(" ").append(FactoryDistrictManager.pretty(e.getKey().name()));
        }
        for (java.util.Map.Entry<Material, Integer> e : r.outputs.entrySet()) {
            if (out.length() > 0) out.append("<dark_gray>+");
            out.append("<aqua>").append(e.getValue()).append(" ").append(FactoryDistrictManager.pretty(e.getKey().name()));
        }
        return in + " <dark_gray>→ " + out;
    }

    /** First other built, healthy slot running the same farm id — the merge partner (-1 if none). */
    private static int findMergePartner(FactoryDistrictData d, int selfIdx, String farmId) {
        for (int i = 0; i < d.slots().size(); i++) {
            if (i == selfIdx) continue;
            FactoryDistrictData.Slot s = d.slot(i);
            if (s != null && s.isBuilt() && !s.broken && farmId.equals(s.factoryId)) return i;
        }
        return -1;
    }

    private static ItemStack icon(ItemStack base, String name, List<String> loreLines) {
        ItemStack it = base == null ? new ItemStack(Material.STONE) : base.clone();
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
