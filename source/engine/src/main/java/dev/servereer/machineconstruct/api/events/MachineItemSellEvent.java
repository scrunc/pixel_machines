package dev.servereer.machineconstruct.api.events;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Fired whenever a machine sells items through the economy, carrying a <b>per-material breakdown</b>
 * (how many of each material was sold, and the money it earned). Unlike {@link GrinderSellEvent} —
 * which reports the hourly lump-sum payout for the transaction log — this fires per sell sweep with the
 * item-level detail, so an analytics consumer (PixelProfiler's per-item sales rollup) can answer "how
 * much sugar cane did the server sell today" without parsing free text.
 *
 * <p>Auto-sell fires this once per sweep (owner may be offline → {@link #getPlayerName()} may be null,
 * resolve it from the UUID); a manual sell fires it once for the whole vault. Maps use only JDK types
 * (material name → count / money) so cross-classloader listeners can read them reflectively without a
 * compile dependency. {@code money} is the actual amount credited (quarry sells already include the
 * Market-Link multiplier).
 */
public class MachineItemSellEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;   // may be null for an offline auto-sell owner
    private final boolean auto;
    private final Map<String, Long> quantities;    // MATERIAL name -> items sold
    private final Map<String, Double> revenues;    // MATERIAL name -> money earned

    public MachineItemSellEvent(UUID playerId, String playerName, boolean auto,
                                Map<String, Long> quantities, Map<String, Double> revenues) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.auto = auto;
        this.quantities = quantities == null ? Collections.emptyMap() : quantities;
        this.revenues = revenues == null ? Collections.emptyMap() : revenues;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public boolean isAuto() { return auto; }

    /** MATERIAL name → items sold this event. */
    public Map<String, Long> getQuantities() { return quantities; }
    /** MATERIAL name → money earned this event (actual amount credited). */
    public Map<String, Double> getRevenues() { return revenues; }

    @NotNull @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
