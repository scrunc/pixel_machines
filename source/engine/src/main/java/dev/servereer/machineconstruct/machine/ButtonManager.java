package dev.servereer.machineconstruct.machine;

import dev.servereer.machineconstruct.core.ButtonSpec;
import dev.servereer.machineconstruct.core.MTransform;
import dev.servereer.machineconstruct.core.PacketDisplay;
import dev.servereer.machineconstruct.tracking.DisplayTracker;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Physical buttons on machines. Every couple of ticks each player near a machine that has
 * button parts gets a ray cast from their eyes; the first button box the ray hits is that
 * player's HOVER — it glows (per-viewer outline, the button's own colour) until they look
 * away. A right- or left-click while hovering PRESSES it: the part sinks along its facing for
 * a few ticks, a button click sounds at it, and its action runs via {@link Handler}.
 * Boxes come from the display's live world transform, so buttons follow facing and tiers.
 */
public final class ButtonManager implements Listener {

    /** Runs a pressed button's action. */
    public interface Handler { void onPress(Machine machine, Player player, PacketDisplay button, String action); }

    private static final double REACH = 5.5;          // blocks from the eye
    private static final double NEAR = 10.0;          // only players this close to an anchor are ray-cast
    private static final long PRESS_TICKS = 5;

    private final Plugin plugin;
    private final DisplayTracker tracker;
    private final Supplier<Collection<Machine>> machines;
    private final Handler handler;
    private final Map<UUID, PacketDisplay> hover = new HashMap<>();
    private final Map<UUID, Long> lastPress = new HashMap<>();
    private final Map<UUID, Machine> hoverMachine = new HashMap<>();

    public ButtonManager(Plugin plugin, DisplayTracker tracker, Supplier<Collection<Machine>> machines, Handler handler) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.machines = machines;
        this.handler = handler;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
    }

    // --- hover ------------------------------------------------------------------

    private void tick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            PacketDisplay hit = null; Machine hitMachine = null;
            double best = Double.MAX_VALUE;
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection();
            World w = eye.getWorld();
            for (Machine m : machines.get()) {
                if (m.buttons().isEmpty()) continue;
                Location a = m.anchor();
                if (a.getWorld() != w || a.distanceSquared(eye) > NEAR * NEAR) continue;
                for (PacketDisplay d : m.buttons()) {
                    double t = rayBox(eye, dir, d);
                    if (t >= 0 && t < best) { best = t; hit = d; hitMachine = m; }
                }
            }
            PacketDisplay was = hover.get(p.getUniqueId());
            if (was == hit) continue;
            if (was != null) was.sendGlow(p, false);
            if (hit != null) hit.sendGlow(p, true);
            if (hit == null) { hover.remove(p.getUniqueId()); hoverMachine.remove(p.getUniqueId()); }
            else { hover.put(p.getUniqueId(), hit); hoverMachine.put(p.getUniqueId(), hitMachine); }
        }
    }

    /**
     * Ray → the display's EXACT oriented box (the block's 0..scale box under its rotation), distance
     * along the ray or -1. The ray is moved into the display's local frame (inverse rotation), so a
     * button on a 45°-facing panel is tested against its true outline, not a fat world-axis box
     * that would overlap its neighbours.
     */
    private static double rayBox(Location eye, Vector dir, PacketDisplay d) {
        MTransform t = d.transform();
        Location o = d.origin();
        // ray origin relative to the display's position (world axes) → local frame
        float[] p = t.unrotateVec((float) (eye.getX() - o.getX() - t.tx), (float) (eye.getY() - o.getY() - t.ty), (float) (eye.getZ() - o.getZ() - t.tz));
        float[] v = t.unrotateVec((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        double[] mn, mx;
        if (d.baseContent() != null && d.baseContent().centerAnchored()) {
            // item / head part (head themes): the engine centres it in its box and scales it by the
            // content's intrinsic fill — so the visible box is ±(scale/fill)/2 around the translation
            float[] f = d.baseContent().intrinsicFill();
            double hx = t.sx / f[0] / 2, hy = t.sy / f[1] / 2, hz = t.sz / f[2] / 2;
            mn = new double[]{-hx - SLACK, -hy - SLACK, -hz - SLACK}; mx = new double[]{hx + SLACK, hy + SLACK, hz + SLACK};
        } else {
            // block part: corner-anchored, 0..scale
            mn = new double[]{-SLACK, -SLACK, -SLACK}; mx = new double[]{t.sx + SLACK, t.sy + SLACK, t.sz + SLACK};
        }
        double tmin = 0, tmax = REACH;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(v[i]) < 1e-9) { if (p[i] < mn[i] || p[i] > mx[i]) return -1; continue; }
            double t1 = (mn[i] - p[i]) / v[i], t2 = (mx[i] - p[i]) / v[i];
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
        return tmin;
    }
    /** World-space (origin-relative) centre of a button part, whichever way its content is anchored. */
    private static float[] centre(PacketDisplay d, MTransform t) {
        if (d.baseContent() != null && d.baseContent().centerAnchored()) return new float[]{t.tx, t.ty, t.tz};
        return t.apply(t.sx / 2, t.sy / 2, t.sz / 2);
    }
    private static final double SLACK = 0.015;   // a hair of forgiveness on thin buttons — under half the gap between neighbours

    // --- press ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(PlayerInteractEvent e) {
        if (e.getHand() != null && e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_AIR && a != Action.LEFT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        PacketDisplay d = hover.get(p.getUniqueId());
        Machine m = hoverMachine.get(p.getUniqueId());
        if (d == null || m == null) return;
        // re-check right now (the hover tick is 2 ticks stale)
        if (rayBox(p.getEyeLocation(), p.getEyeLocation().getDirection(), d) < 0) return;
        e.setCancelled(true);
        long now = plugin.getServer().getCurrentTick();
        Long last = lastPress.get(p.getUniqueId());
        if (last != null && now - last < PRESS_TICKS + 1) return;
        lastPress.put(p.getUniqueId(), now);
        press(m, p, d);
    }

    private void press(Machine m, Player p, PacketDisplay d) {
        ButtonSpec spec = d.button();
        if (spec == null) return;
        // The action runs FIRST: if it starts a cue on this part (a dial that turns), the cue records the
        // true rest pose and owns the motion — pushing it as well left the dial a notch deeper each press.
        try { handler.onPress(m, p, d, spec.action()); }
        catch (Throwable t) { plugin.getLogger().warning("[MachineConstruct] button action failed: " + t); }
        if (d.locked()) return;
        MTransform rest = d.transform();
        double[] dir = spec.push();   // which way this one sinks: into its face by default, DOWN on a console shelf
        float[] push = rest.rotateVec((float) (dir[0] * spec.depth()), (float) (dir[1] * spec.depth()), (float) (dir[2] * spec.depth()));
        d.setTransform(rest.translated(push[0], push[1], push[2]));
        d.consumeDirty(); tracker.refresh(d);
        // the caption sinks with it (text parts named by the spec's label, e.g. lbl_daily for btn_daily)
        List<PacketDisplay> caps = new ArrayList<>(); List<MTransform> capRest = new ArrayList<>();
        if (spec.label() != null) for (PacketDisplay c : m.displays()) {
            if (c == d || !spec.label().equals(c.partName())) continue;
            MTransform r = c.transform();
            caps.add(c); capRest.add(r);
            c.setTransform(r.translated(push[0], push[1], push[2])); c.consumeDirty(); tracker.refresh(c);
        }
        Location at = d.origin();
        float[] c = centre(d, rest);
        Location snd = at.clone().add(c[0], c[1], c[2]);
        try { snd.getWorld().playSound(snd, Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.8f, 1.3f); } catch (Throwable ignored) { }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            d.setTransform(rest); d.consumeDirty(); tracker.refresh(d);
            for (int i = 0; i < caps.size(); i++) { caps.get(i).setTransform(capRest.get(i)); caps.get(i).consumeDirty(); tracker.refresh(caps.get(i)); }
            try { snd.getWorld().playSound(snd, Sound.BLOCK_STONE_BUTTON_CLICK_OFF, 0.5f, 1.2f); } catch (Throwable ignored) { }
        }, PRESS_TICKS);
    }

    /** /mc btn — what the ray-cast sees from this player's eyes right now (aim at a button, run it). */
    public String debug(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("§7eye §f%.3f %.3f %.3f §7(eyeH %.2f, pose %s) dir §f%.3f %.3f %.3f §7yaw %.1f pitch %.1f%n",
                eye.getX(), eye.getY(), eye.getZ(), p.getEyeHeight(), p.getPose(), dir.getX(), dir.getY(), dir.getZ(), eye.getYaw(), eye.getPitch()));
        try {   // what the SERVER's ray hits in the real world — compare with the block under your crosshair
            var rt = p.rayTraceBlocks(8.0);
            if (rt != null && rt.getHitBlock() != null) sb.append(String.format("§7server ray hits §f%s §7at %.3f %.3f %.3f (%s face)%n", rt.getHitBlock().getType().name().toLowerCase(),
                    rt.getHitPosition().getX(), rt.getHitPosition().getY(), rt.getHitPosition().getZ(), rt.getHitBlockFace()));
            else sb.append("§7server ray hits no block within 8").append(System.lineSeparator());
        } catch (Throwable ignored) { }
        for (Machine m : machines.get()) {
            if (m.buttons().isEmpty() || m.anchor().getWorld() != eye.getWorld() || m.anchor().distanceSquared(eye) > NEAR * NEAR) continue;
            sb.append("§b").append(m.typeId()).append(" §7@ ").append(m.anchor().getBlockX()).append(' ').append(m.anchor().getBlockY()).append(' ').append(m.anchor().getBlockZ())
              .append(" facing ").append(m.facing()).append(System.lineSeparator());
            for (PacketDisplay d : m.buttons()) {
                MTransform t = d.transform(); Location o = d.origin();
                float[] c = centre(d, t);
                double cx = o.getX() + c[0], cy = o.getY() + c[1], cz = o.getZ() + c[2];
                // closest approach of the ray to the box centre (how far off the aim is, in blocks)
                double px = cx - eye.getX(), py = cy - eye.getY(), pz = cz - eye.getZ();
                double along = px * dir.getX() + py * dir.getY() + pz * dir.getZ();
                double mx = px - along * dir.getX(), my = py - along * dir.getY(), mz = pz - along * dir.getZ();
                double miss = Math.sqrt(mx * mx + my * my + mz * mz);
                double hit = rayBox(eye, dir, d);
                sb.append(String.format("  §f%-12s §7centre %.3f %.3f %.3f  scale %.2f %.2f %.2f  q %.3f %.3f %.3f %.3f  miss §e%.3f§7 hit %s%n",
                        d.partName(), cx, cy, cz, t.sx, t.sy, t.sz, t.qx, t.qy, t.qz, t.qw, miss, hit < 0 ? "§c-" : String.format("§a%.3f", hit)));
            }
        }
        PacketDisplay h = hover.get(p.getUniqueId());
        sb.append("§7hover now: §f").append(h == null ? "-" : h.partName());
        return sb.toString();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { hover.remove(e.getPlayer().getUniqueId()); hoverMachine.remove(e.getPlayer().getUniqueId()); lastPress.remove(e.getPlayer().getUniqueId()); }

    /** Forget a machine's buttons (it was broken / re-rendered). */
    public void forget(Machine m) {
        hover.entrySet().removeIf(en -> m.buttons().contains(en.getValue()));
        hoverMachine.entrySet().removeIf(en -> en.getValue() == m);
    }
}
