package dev.servereer.machineconstruct.grinder.econ;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * The per-item auto-sell contract shared by the Dimensional Grinder (sells its loot pool) and the
 * Virtual Factory District (sells its Quantum Vault). Both expose the same per-item rules + hourly
 * payout accumulator; {@link #autoSellSource()} is the item map being sold from (pool or vault), so
 * one selling routine in the manager drives both.
 */
public interface AutoSellState {

    boolean autoSellEnabled();
    void setAutoSellEnabled(boolean v);

    Map<Material, long[]> autoSellRules();
    boolean isAutoSellItem(Material m);
    long autoSellRate(Material m);
    boolean autoSellItemFull(Material m);
    long autoSellItemAllowance(Material m, long now);
    /**
     * Advance the per-item rate clock by the time-worth of {@code sold} items actually sold this sweep,
     * keeping the leftover fraction so a slow rate (e.g. 1/sec, below one item per sweep) still accrues
     * instead of being discarded. Caps the unspent backlog to ~1s to bound bursts after idle.
     */
    void advanceAutoSellFlow(Material m, long now, long sold);
    void setAutoSellItem(Material m, long rate, long now);
    void removeAutoSellItem(Material m);
    void clearAutoSellItems();

    double autoSellAccMoney();
    long autoSellAccItems();
    void addAutoSellAcc(double money, long items);
    void resetAutoSellAcc(long now);
    long autoSellLastPayout();
    void setAutoSellLastPayout(long v);

    /** The item map being auto-sold from: the grinder pool or the district vault (template amount 1 → count). */
    Map<ItemStack, Long> autoSellSource();
}
