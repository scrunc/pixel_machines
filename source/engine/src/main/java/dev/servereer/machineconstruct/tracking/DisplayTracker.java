package dev.servereer.machineconstruct.tracking;

import dev.servereer.machineconstruct.core.PacketDisplay;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P1 viewer tracking (the ZNPCsPlus pattern, minimal). Holds the live set of
 * {@link PacketDisplay}s and, on a cheap repeating task, diffs each player's
 * "seen set" against what's now within render distance — spawning newly-near
 * displays and despawning ones that left range. Lifecycle events keep the seen
 * set honest across quit / respawn / world change (the client drops entities on
 * those, so we forget them and let the task re-spawn).
 *
 * <p>This is the foundation the immediate-mode reconciler (P3+) plugs into; for
 * P1 it tracks standalone debug displays directly.
 */
public final class DisplayTracker implements Listener {

    private final Plugin plugin;
    private final int renderDistance;
    private final long periodTicks;

    private final List<PacketDisplay> displays = new CopyOnWriteArrayList<>();
    private final Map<UUID, Set<Integer>> seen = new HashMap<>();
    private BukkitTask task;

    public DisplayTracker(Plugin plugin, int renderDistance, long periodTicks) {
        this.plugin = plugin;
        this.renderDistance = renderDistance;
        this.periodTicks = periodTicks;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, periodTicks, periodTicks);
    }

    public void stop() {
        if (task != null) task.cancel();
        clearAll();
    }

    public PacketDisplay register(PacketDisplay display) {
        displays.add(display);
        return display;
    }

    public int count() {
        return displays.size();
    }

    public int renderDistance() {
        return renderDistance;
    }

    /** Re-send a display's (animated) transform to every viewer that sees it. */
    public void refresh(PacketDisplay display) { refresh(display, 2); }   // tween over the 2-tick clock period

    /** Resend a display's metadata to everyone seeing it, tweening over {@code interpTicks} (0 = snap). */
    public void refresh(PacketDisplay display, int interpTicks) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Set<Integer> s = seen.get(p.getUniqueId());
            if (s != null && s.contains(display.entityId())) display.sendMetadata(p, interpTicks);
        }
    }

    /** Run something for every player currently seeing a display. */
    public void forEachViewer(PacketDisplay display, java.util.function.Consumer<Player> fn) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Set<Integer> s = seen.get(p.getUniqueId());
            if (s != null && s.contains(display.entityId())) fn.accept(p);
        }
    }

    /** Despawn one display from every viewer that sees it and forget it. */
    public void unregister(PacketDisplay display) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Set<Integer> s = seen.get(p.getUniqueId());
            if (s != null && s.remove(display.entityId())) {
                display.despawn(p);
            }
        }
        displays.remove(display);
    }

    /** Despawn everything from every viewer and forget all displays. */
    public void clearAll() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Set<Integer> s = seen.get(p.getUniqueId());
            if (s == null) continue;
            for (PacketDisplay d : displays) {
                if (s.contains(d.entityId())) d.despawn(p);
            }
        }
        displays.clear();
        seen.clear();
    }

    private void tick() {
        int rdSq = renderDistance * renderDistance;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Set<Integer> s = seen.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            Location pl = p.getLocation();
            for (PacketDisplay d : displays) {
                Location dl = d.origin();
                boolean inRange = dl.getWorld() != null
                        && dl.getWorld().equals(pl.getWorld())
                        && dl.distanceSquared(pl) <= rdSq;
                boolean shown = s.contains(d.entityId());
                if (inRange && !shown) {
                    d.spawn(p);
                    s.add(d.entityId());
                } else if (!inRange && shown) {
                    d.despawn(p);
                    s.remove(d.entityId());
                }
            }
        }
    }

    // --- lifecycle: the client discards entities on these, so forget them ---

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        seen.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        seen.remove(e.getPlayer().getUniqueId());   // task re-spawns in-range displays
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        seen.remove(e.getPlayer().getUniqueId());
    }
}
