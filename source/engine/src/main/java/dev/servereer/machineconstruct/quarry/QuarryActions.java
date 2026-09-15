package dev.servereer.machineconstruct.quarry;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.entity.Player;

/**
 * Quarry operations the menu invokes — implemented by the machine manager.
 * Grows per phase: Q1 = persist / accrue / mode toggle; later phases add vault
 * withdraw/sell, skill purchase, lava take, etc.
 */
public interface QuarryActions {

    /** Persist quarry + machine state to PDC. */
    void persist(Machine m);

    /** Recompute mining/fishing accrual up to now (Q2). */
    void accrue(Machine m);

    /** Cycle the active mode MINING → FISHING → BOTH. */
    void toggleMode(Machine m, Player p);

    /** Withdraw up to {@code amount} of one vault type into the player's inventory. Returns moved. */
    long withdraw(Machine m, Player p, org.bukkit.inventory.ItemStack template, long amount);

    /** Withdraw everything that fits into the player's inventory. Returns total moved. */
    long collectAll(Machine m, Player p);

    /** Sell the whole vault via the type's prices + Vault. Returns money earned (-1 = no economy). */
    double sellAll(Machine m, Player p);

    /** Attempt to buy the next level of a skill (Rift Points + money + nether stars). Returns a result. */
    BuyResult buySkill(Machine m, Player p, QuarrySkill skill);

    /** Cycle the active mining dimension to the next unlocked one (Rift Drill gates the ceiling). */
    void cycleDimension(Machine m, Player p);

    /** Toggle lava generation on/off. */
    void toggleLava(Machine m, Player p);

    /** Fill lava buckets from the tank using empty buckets in the player's inventory. Returns buckets filled. */
    int collectLava(Machine m, Player p);

    /** Sell the entire lava tank via the lava sell price + Vault. Returns money earned (-1 = no economy). */
    double sellLava(Machine m, Player p);

    /** Claim the active contract if its goal is met (rewards RP + money; rolls the next). */
    void claimContract(Machine m, Player p);

    /** Reroll the active contract (no reward) — escape hatch for an unwanted/impossible goal. */
    void rerollContract(Machine m, Player p);

    /** Prestige (Ascendance): reset skills + Rift Points for a permanent +10% global yield. */
    void prestige(Machine m, Player p);

    /** Outcome of a skill purchase, for the menu to message. */
    enum BuyResult { OK, MAXED, NEED_RIFT_POINTS, NEED_MONEY, NEED_STARS, NO_ECONOMY, ERROR }
}
