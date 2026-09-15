package dev.servereer.machineconstruct.collector;

import dev.servereer.machineconstruct.grinder.ChestLink;
import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Chunk Collector operations the menu invokes — implemented by the machine manager.
 * The chest-link + auto-sell methods share their signatures with {@code GrinderActions},
 * so the manager's single implementation (routing through {@code linksOf}/{@code autoSellStateOf})
 * serves both subsystems; only the vault collect/sell/sweep methods are collector-specific.
 */
public interface ChunkCollectorActions {

    /** Persist collector + machine state to PDC. */
    void persist(Machine m);

    /** Run one vacuum pass now (called before rendering so the menu shows fresh items). */
    void collectorVacuum(Machine m);

    /** Withdraw everything that fits from the vault into the player's inventory. Returns items moved. */
    long collectVault(Machine m, Player p);

    /** Withdraw up to {@code amount} of one vault type into the player's inventory. Returns moved. */
    long withdrawVaultItem(Machine m, Player p, ItemStack template, long amount);

    /** Sell the whole vault via the type's prices + Vault. Returns money earned (-1 = no economy). */
    double sellVault(Machine m, Player p);

    /** Advance the collector one tier (consumes the upgrade item from the player's inventory). */
    void upgrade(Machine m, Player p);

    // --- output chest links (shared signatures with GrinderActions) --------------------------------

    void beginChestSelection(Machine m, Player p, ChestLink.Type type);
    void removeChestLink(Machine m, int index);
    void promptLinkRate(Machine m, Player p, int index);
    void toggleLinkFilter(Machine m, int index, Material mat);
    void clearLinkFilter(Machine m, int index);

    // --- auto-sell (shared signatures with GrinderActions) -----------------------------------------

    void toggleAutoSell(Machine m);
    void toggleAutoSellItem(Machine m, Material mat);
    void promptAutoSellItemRate(Machine m, Player p, Material mat);
    void clearAutoSellItems(Machine m);
}
