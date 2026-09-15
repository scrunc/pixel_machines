package dev.servereer.machineconstruct.grinder;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.entity.Player;

/**
 * The grinder operations the menu invokes — implemented by the machine manager
 * (which owns persistence, accrual, economy, and tier upgrades). Keeps
 * {@code GrinderMenus} a pure render/route layer (the same split as
 * {@code MachineMenus} → {@code MachineManager}).
 */
public interface GrinderActions {

    /** Recompute time accrual for the machine (call before rendering). */
    void accrue(Machine m);

    /** Persist grinder + machine state to PDC. */
    void persist(Machine m);

    /** Move all whole buffered loot into the shared pool + XP into stored XP. Returns {@code [items, xp]}. */
    long[] collectAll(Machine m);

    /** Withdraw all collected items from the pool into the player's inventory. Returns items withdrawn. */
    long withdrawPool(Machine m, Player p);

    /** Withdraw up to {@code amount} of one pool type into the player's inventory. Returns moved. */
    long withdrawPoolItem(Machine m, Player p, org.bukkit.inventory.ItemStack template, long amount);

    /** Grant up to {@code amount} stored XP to the player, applying Mending. Returns XP granted. */
    long claimXp(Machine m, Player p, long amount);

    /** Sell the shared pool via the economy/shop bridge. Returns money earned (-1 if no economy). */
    double sellAll(Machine m, Player p);

    /** Buy and install/stack a spawner of the given type from the catalog. */
    void buySpawner(Machine m, Player p, String type);

    /** Advance the grinder one tier (consumes the upgrade item from the player's inventory). */
    void upgrade(Machine m, Player p);

    /** Install a spawner from the item on the player's cursor (a vanilla/typed spawner item). */
    void insertHeld(Machine m, Player p);

    /** Remove {@code amount} from the slot at {@code slotIndex}, returning vanilla spawner items. */
    void destackSlot(Machine m, Player p, int slotIndex, long amount);

    // --- input/output chest links ------------------------------------------------------------------

    /** Enter chest-selection mode: the player's next chest click (within range) becomes a link of this type. */
    void beginChestSelection(Machine m, Player p, ChestLink.Type type);

    /** Remove the chest link at {@code index} (no-op if out of range). */
    void removeChestLink(Machine m, int index);

    /** Chat-prompt the player for an OUTPUT link's flow rate ("full" or items/second). */
    void promptLinkRate(Machine m, Player p, int index);

    /** Toggle an item in an OUTPUT link's filter (empty filter = all items). */
    void toggleLinkFilter(Machine m, int index, org.bukkit.Material mat);

    /** Clear an OUTPUT link's filter (route everything). */
    void clearLinkFilter(Machine m, int index);

    // --- auto-sell ---------------------------------------------------------------------------------

    /** Master auto-sell on/off (per-item rules still apply when on). */
    void toggleAutoSell(Machine m);

    /** Add/remove an item from the per-item auto-sell list (default rate = all). */
    void toggleAutoSellItem(Machine m, org.bukkit.Material mat);

    /** Chat-prompt the per-item auto-sell rate ("all" or items/second) for one item. */
    void promptAutoSellItemRate(Machine m, Player p, org.bukkit.Material mat);

    /** Stop auto-selling all items (clear the per-item list). */
    void clearAutoSellItems(Machine m);
}
