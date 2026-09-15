package dev.servereer.machineconstruct.factorydistrict;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The menu↔manager bridge for the Virtual Factory District (ADR 0013), mirroring
 * {@code TradingHallActions}. The menu calls these; {@code MachineManager} implements them
 * (entity/world access, build payment, persistence).
 */
public interface FactoryDistrictActions {

    /** Vacuum living mobs within the capture radius into the creature roster. Returns how many. */
    int captureCreatures(Machine machine, Player player);

    /** Build a farm at a chosen size in an empty slot, paying its (scaled) blocks + creatures. */
    boolean buildFarm(Machine machine, Player player, int slot, String farmId, String sizeId);

    /** Tear down a built farm, freeing the slot (no refund). */
    boolean demolishFarm(Machine machine, Player player, int slot);

    /** Employ (+1) or lay off (-1) a worker at a machine; returns the new assigned count. */
    int assignWorker(Machine machine, Player player, int slot, int delta);

    /** Upgrade a house/machine one level (pays its upgrade-cost); returns true if upgraded. */
    boolean upgradeHouse(Machine machine, Player player, int slot);

    /** Apply a convert-upgrade (morph this slot into another farm id), paying its cost. */
    boolean convertFarm(Machine machine, Player player, int slot, int upgradeIndex);

    /** Fuse the same-farm in {@code fromSlot} into {@code intoSlot} (★ up; yields sum, frees a slot). */
    boolean mergeFarm(Machine machine, Player player, int intoSlot, int fromSlot);

    /** Toggle a crafting recipe on/off for a slot, respecting its stack-mode selection cap. */
    boolean selectRecipe(Machine machine, Player player, int slot, String recipeId);

    /** Vanilla bench: craft {@code result} straight from the vault (all = craft as many as affordable). */
    int craftVanilla(Machine machine, Player player, int slot, org.bukkit.Material result, boolean all);

    /** Repair a broken (caved-in) machine, paying its hazard repair cost. */
    boolean repairFarm(Machine machine, Player player, int slot);

    /** Upgrade the district one tier by feeding its block recipe — raises slots + vault cap. */
    boolean upgradeDistrict(Machine machine, Player player);

    /** Deposit a stack into the Quantum Vault; returns how many were actually stored. */
    long depositVault(Machine machine, Player player, ItemStack stack);

    /** Withdraw from the vault to the player (a stack, or all of that type); returns amount given. */
    long withdrawVault(Machine machine, Player player, ItemStack template, boolean all);

    /** Flush the machine's factory-district state to its anchor PDC (crash-safe). */
    void persistFactory(Machine machine);

    // --- output chests (vault → chest routing; shared with the grinder impl) ---

    /** Enter chest-selection mode: the player's next chest right-click (in range) becomes a link of this type. */
    void beginChestSelection(Machine machine, Player player, dev.servereer.machineconstruct.grinder.ChestLink.Type type);

    /** Remove the chest link at {@code index} (no-op if out of range). */
    void removeChestLink(Machine machine, int index);

    /** Chat-prompt the player for an OUTPUT link's flow rate ("full" or items/second). */
    void promptLinkRate(Machine machine, Player player, int index);

    /** Toggle an item in an OUTPUT link's filter (empty filter = push everything). */
    void toggleLinkFilter(Machine machine, int index, org.bukkit.Material mat);

    /** Clear an OUTPUT link's filter (push everything). */
    void clearLinkFilter(Machine machine, int index);

    // --- auto-sell (vault → economy; shared with the grinder impl) ---

    /** Master auto-sell on/off (per-item rules still apply when on). */
    void toggleAutoSell(Machine machine);

    /** Add/remove an item from the per-item auto-sell list (default rate = all). */
    void toggleAutoSellItem(Machine machine, org.bukkit.Material mat);

    /** Chat-prompt the per-item auto-sell rate ("all" or items/second) for one item. */
    void promptAutoSellItemRate(Machine machine, Player player, org.bukkit.Material mat);

    /** Stop auto-selling all items (clear the per-item list). */
    void clearAutoSellItems(Machine machine);
}
