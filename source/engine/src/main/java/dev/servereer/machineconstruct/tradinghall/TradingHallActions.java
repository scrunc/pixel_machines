package dev.servereer.machineconstruct.tradinghall;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The menu↔manager bridge for the Virtual Trading Hall (ADR 0011), mirroring
 * {@code QuarryActions}. The menu calls these; {@code MachineManager} implements them
 * (entity/world access, inventory payment, native-merchant bridge, persistence).
 */
public interface TradingHallActions {

    // Villagers are captured by SHIFT-RIGHT-CLICKING a wild villager near an owned hall
    // (handled in MachineManager's interact listener), not via a menu button.

    /** Build a profession's station in an empty slot, paying its blocks (inventory + vault). */
    boolean buildStation(Machine machine, Player player, int slot, String professionId);

    /** Insert a blank villager from the roster into a built (trader-less) station slot. */
    boolean insertVillager(Machine machine, Player player, int slot);

    /** Re-roll a single trade offer to another from its tier's pool (per-trade, no lock). */
    boolean rerollOffer(Machine machine, Player player, int slot, int offerIndex);

    /** Execute a trade directly (pay from vault first, then inventory; result to the player).
     *  {@code bulk} trades as many times as affordable + in stock. Returns trades done. */
    int executeTrade(Machine machine, Player player, int slot, int offerIndex, boolean bulk);

    /** Full stall reset — re-roll ALL of a trader's trades fresh (clears the per-slot trade locks). */
    boolean resetStall(Machine machine, Player player, int slot);

    /** Toggle auto-trade for a single trade offer (vault-fed, unattended). Returns the new state. */
    boolean toggleAuto(Machine machine, Player player, int slot, int offerIndex);

    /** Per-shop stock upgrade: feed items to raise THIS trader's stock (maxUses ×(1+level)). */
    boolean upgradeStock(Machine machine, Player player, int slot);

    /** Upgrade the stall one tier by feeding its block recipe — raises slots + vault cap. */
    boolean upgradeStall(Machine machine, Player player);

    /** Toggle where trade results go: vault vs inventory. Returns the new "to vault" state. */
    boolean toggleOutput(Machine machine, Player player);

    /** Toggle the shopfront open/closed (owner only) — lets non-owners trade. Returns new "open" state. */
    boolean toggleShopfront(Machine machine, Player player);

    /** Collect shop earnings from the till to the owner (a stack, or all of that type); returns amount given. */
    long collectTill(Machine machine, Player player, ItemStack template, boolean all);

    /** Sweep the entire earnings till into the operating vault (owner). Returns amount moved. */
    long sweepTillToVault(Machine machine, Player player);

    /** Deposit a stack into the Quantum Vault; returns how many were actually stored. */
    long deposit(Machine machine, Player player, ItemStack stack);

    /** Withdraw from the vault to the player (a stack, or all of that type); returns amount given. */
    long withdraw(Machine machine, Player player, ItemStack template, boolean all);

    /** Pull a trader's villager back out of a slot, returning it to the blank roster. */
    boolean removeVillager(Machine machine, Player player, int slot);

    /** Flush the machine's trading-hall state to its anchor PDC (crash-safe). */
    void persist(Machine machine);
}
