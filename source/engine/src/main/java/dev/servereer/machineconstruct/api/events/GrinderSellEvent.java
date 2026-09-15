package dev.servereer.machineconstruct.api.events;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired when a Dimensional Grinder's pool is sold via the economy — either a manual sell
 * (online player) or the hourly aggregated auto-sell payout (owner may be offline). Lets other
 * plugins (e.g. PixelProfiler's transaction log) record machine earnings with a real source label.
 */
public class GrinderSellEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final double amount;   // money paid out
    private final long items;      // items sold
    private final boolean auto;    // true = aggregated auto-sell payout

    public GrinderSellEvent(Player p, double amount, long items) { this(p.getUniqueId(), p.getName(), amount, items, false); }

    public GrinderSellEvent(UUID playerId, String playerName, double amount, long items, boolean auto) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.amount = amount;
        this.items = items;
        this.auto = auto;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public Player getPlayer() { return playerId == null ? null : Bukkit.getPlayer(playerId); }   // null if offline
    public double getAmount() { return amount; }
    public long getItems() { return items; }
    public boolean isAuto() { return auto; }

    @NotNull @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
