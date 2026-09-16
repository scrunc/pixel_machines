package dev.servereer.machineconstruct.gacha;

import dev.servereer.machineconstruct.gui.MenuSkin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Coins are currency, not blocks.
 *
 * <p>Every coin is a player head, and a player head is placeable — so a coin could be stuck on a wall,
 * where it stops being a coin: the block keeps the skin but loses the tag that made it worth anything,
 * and breaking it back gives a plain head. That is a quiet way to destroy money, and it makes a coin
 * wall look like a bank.
 *
 * <p>So placement is cancelled: the ladder coins by their tag, and a series' own custom coin by
 * comparison. Set {@code placeable: true} in {@code coins.yml} to allow it anyway — a server that wants
 * coin decorations can have them.
 */
public final class CoinGuard implements Listener {

    private final GachaManager gacha;
    private final Map<UUID, Long> told = new HashMap<>();   // one telling per second, not one per click

    public CoinGuard(GachaManager gacha) { this.gacha = gacha; }

    /** True if this stack is money: a ladder coin by its tag, or any series' own coin. */
    public boolean isCoin(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (gacha.coins().tierOf(it) != null) return true;
        for (GachaSeries s : gacha.allSeries()) {
            ItemStack c = s.coin();
            if (c != null && c.isSimilar(it)) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent e) {
        if (!isCoin(e.getItemInHand())) return;
        if (gacha.coins().placeable()) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = told.get(p.getUniqueId());
        if (last != null && now - last < 1000) return;
        told.put(p.getUniqueId(), now);
        p.sendActionBar(MenuSkin.mini(gacha.coins().placeMessage()));
    }

    /** A dispenser would place it as a block too — same loss, no player to tell. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDispense(BlockDispenseEvent e) {
        if (!gacha.coins().placeable() && isCoin(e.getItem())) e.setCancelled(true);
    }
}
