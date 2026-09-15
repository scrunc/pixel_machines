package dev.servereer.machineconstruct.gacha;

import dev.servereer.machineconstruct.core.DisplayContent;
import dev.servereer.machineconstruct.core.ItemContent;
import dev.servereer.machineconstruct.core.MTransform;
import dev.servereer.machineconstruct.core.PacketDisplay;
import dev.servereer.machineconstruct.gui.MenuSkin;
import dev.servereer.machineconstruct.machine.CuePlayer;
import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import dev.servereer.machineconstruct.model.RenderedModel;
import dev.servereer.machineconstruct.tracking.DisplayTracker;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The claw machine (ADR 0047): one player at a time drives a crane over a pile of prizes by WALKING
 * on the machine's control pad — where they stand on the plate is where the claw sits over the box —
 * then clicks to drop it. The claw grabs about one play in five; aiming raises that, and a pity
 * counter guarantees a grab after enough failures, because the prizes are expensive.
 *
 * <p>The outcome is decided the instant the prongs close, and the FAILURE MODE is rolled from a table
 * so the machine never fails the same way twice in a row: the prongs close on nothing, or the prize
 * slips on the way up, or it slips at the very last moment over the chute. What the claw carries is
 * the real rolled item, so a slip drops the thing the player actually nearly won.
 *
 * <p>Parts are driven directly (locked, transforms written per tick) rather than through a cue —
 * a cue is a fixed timeline and this is a machine being steered.
 */
public final class ClawManager implements Listener {

    private static final int TICK = 2;                   // the session clock, in server ticks
    private static final double DROP_S = 1.6, CLOSE_S = 0.75, LIFT_S = 1.3, RETURN_S = 1.5, RELEASE_S = 0.7, RESET_S = 1.0;
    private static final double DROP_FALL = 0.80;    // the dive is over at 80% of DROP_S; the rest is the claw hanging there

    private enum Phase { DRIVE, DROP, CLOSE, LIFT, RETURN, RELEASE, RESET }

    private final class Session {
        final Machine m; final MachineType t; final ClawSpec claw; final GachaSpec spec;
        final UUID player; final String playerName;
        Phase phase = Phase.DRIVE;
        double phaseStart;                    // seconds (server clock) the phase began
        double x, z;                          // where the claw is, in model space relative to centre
        double open;                          // prongs: 0 shut, 1 wide
        boolean landed;                       // the dive is done (one sound, not one per tick)
        PacketDisplay nearest;                // the pile head the prongs closed over
        PacketDisplay taken;                  // ...and the one actually lifted out of the pile
        double wantX, wantZ;                  // where the controls say it should be
        double stickX, stickZ;                // the joystick's lean, -1..1 on each axis
        Location seatLoc; Float walkWas;      // the player is held at the cabinet while they play
        double deadline;
        boolean grabbed; String failMode = "miss";
        GachaManager.Result prize;            // rolled when the prongs close, only if grabbed
        double releaseAt;                     // progress at which a slip lets go
        final Map<PacketDisplay, MTransform> rest = new LinkedHashMap<>();
        final MTransform root, inv;           // model → world and back, for this machine's facing
        Session(Machine m, MachineType t, Player p) {
            this.m = m; this.t = t; this.claw = t.gacha().claw(); this.spec = t.gacha();
            this.player = p.getUniqueId(); this.playerName = p.getName();
            this.root = RenderedModel.yawAboutColumn(m.facing());
            this.inv = RenderedModel.yawAboutColumn((360 - m.facing()) % 360);
        }
    }

    private final Plugin plugin;
    private final DisplayTracker tracker;
    private final GachaManager gacha;
    private final Map<Machine, Session> sessions = new HashMap<>();
    private final java.util.Set<PacketDisplay> empty = new java.util.HashSet<>();          // pile slots waiting to be refilled
    private final Map<PacketDisplay, DisplayContent> takenWas = new HashMap<>();           // ...and what was in them
    private final Random rng = new Random();

    public ClawManager(Plugin plugin, DisplayTracker tracker, GachaManager gacha) {
        this.plugin = plugin; this.tracker = tracker; this.gacha = gacha;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICK, TICK);
    }

    public boolean busy(Machine m) { return sessions.containsKey(m); }

    /** Take a coin and hand the machine over to this player. */
    public boolean start(Player p, Machine m, MachineType t) {
        MenuSkin sk = t.skin();
        Session live = sessions.get(m);
        if (live != null) {
            if (live.player.equals(p.getUniqueId())) { drop(p, m); return true; }   // clicking again drops
            p.sendMessage(GachaManager.msg(sk, "claw_busy", "<yellow>{player} is playing this one.", MenuSkin.vars("player", live.playerName)));
            return false;
        }
        GachaSeries s = gacha.series(t.gacha());
        if (s.loot().isEmpty()) { p.sendMessage(GachaManager.msg(sk, "empty", "<red>This machine has nothing loaded yet.")); return false; }
        if (!gacha.chargeFor(p, t)) return false;
        Session ss = new Session(m, t, p);
        double now = now();
        ss.phaseStart = now; ss.deadline = now + ss.claw.timeoutTicks() / 20.0;
        for (PacketDisplay d : driven(m)) { ss.rest.put(d, d.transform()); d.lock(true); }
        sessions.put(m, ss);
        show(ss, "held", false);
        if (ss.claw.control() == ClawSpec.Control.STICK) seat(ss, p);
        p.sendMessage(GachaManager.msg(sk, "claw_start",
                "<aqua>WASD moves the claw <dark_gray>·<aqua> Space or click drops it. <gray>It holds about {pct} times in 100.",
                MenuSkin.vars("pct", String.valueOf(Math.round(ss.claw.holdChance() * 100)))));
        sound(ss, "block.copper_bulb.turn_on", 0.7f, 1.3f);
        return true;
    }

    /** The player clicked: send the claw down. Returns true if this machine was theirs to drop. */
    public boolean drop(Player p, Machine m) {
        Session ss = sessions.get(m);
        if (ss == null || !ss.player.equals(p.getUniqueId()) || ss.phase != Phase.DRIVE) return ss != null;
        ss.phase = Phase.DROP; ss.phaseStart = now();
        sound(ss, "block.piston.extend", 0.8f, 1.4f);
        return true;
    }

    /**
     * Hold the player at the cabinet while they play — the same contract ArcadeCab uses: stand them on
     * the machine's seat looking at it and take their walk speed away, so WASD becomes pure input and
     * they cannot wander off mid-play. Restored in {@link #unseat}.
     */
    private void seat(Session ss, Player p) {
        Location a = ss.m.anchor();
        float[] w = ss.root.apply((float) (0.5 + ss.claw.seat()[0]), (float) ss.claw.seat()[1], (float) (0.5 + ss.claw.seat()[2]));
        Location loc = a.clone().add(w[0], w[1], w[2]);
        loc.setYaw((float) Math.toDegrees(Math.atan2(-(a.getX() + 0.5 - loc.getX()), a.getZ() + 0.5 - loc.getZ())));
        loc.setPitch(12f);
        ss.seatLoc = loc; ss.walkWas = p.getWalkSpeed();
        p.teleport(loc);
        p.setWalkSpeed(0f);
    }

    private void unseat(Session ss) {
        Player p = plugin.getServer().getPlayer(ss.player);
        if (p == null || ss.walkWas == null) return;
        p.setWalkSpeed(ss.walkWas);
        ss.walkWas = null;
    }

    /** Real WASD, straight off the client (Paper's input packet) — the joystick leans with it. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onInput(PlayerInputEvent e) {
        if (sessions.isEmpty()) return;
        for (Map.Entry<Machine, Session> en : sessions.entrySet()) {
            Session ss = en.getValue();
            if (!ss.player.equals(e.getPlayer().getUniqueId())) continue;
            if (ss.claw.control() != ClawSpec.Control.STICK) return;
            org.bukkit.util.Vector in = new org.bukkit.util.Vector(
                    (e.getInput().isRight() ? 1 : 0) - (e.getInput().isLeft() ? 1 : 0), 0,
                    (e.getInput().isBackward() ? 1 : 0) - (e.getInput().isForward() ? 1 : 0));
            if (ss.phase == Phase.DRIVE) {
                // The player faces the machine, so their forward is the machine's +z (deeper into the box)
                // and their right is model -x. `in` reads (right-left, 0, backward-forward), hence both flips.
                ss.stickX = -in.getX(); ss.stickZ = -in.getZ();
                if (e.getInput().isJump()) drop(e.getPlayer(), en.getKey());
            } else { ss.stickX = 0; ss.stickZ = 0; }
            return;
        }
    }

    /** Where the player stands on the pad is where the claw goes (control: pad). */
    public void onPlayerMoved(Player p, Location to) {
        for (Session ss : sessions.values()) {
            if (!ss.player.equals(p.getUniqueId())) continue;
            if (ss.phase != Phase.DRIVE) return;
            Location a = ss.m.anchor();
            if (to.getWorld() != a.getWorld()) return;
            // the player's offset from the pad's centre, turned back into model space
            float[] rel = ss.inv.rotateVec((float) (to.getX() - a.getX() - 0.5), 0f, (float) (to.getZ() - a.getZ() - 0.5));
            double px = rel[0], pz = rel[2] + ss.claw.padOffset();   // pad sits in FRONT (−z), so shift it back to centre
            double half = ss.claw.pad() / 2;
            if (Math.abs(px) > half * 1.6 || Math.abs(pz) > half * 1.6) return;   // stepped off: the claw just holds
            ss.wantX = clamp(px / half, -1, 1) * ss.claw.travelX();
            ss.wantZ = clamp(pz / half, -1, 1) * ss.claw.travelZ();
            return;
        }
    }

    public void forget(Machine m) {
        Session ss = sessions.remove(m);
        if (ss != null) { restock(ss, 1); unseat(ss); release(ss, false); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
        if (sessions.isEmpty() || e.getTo() == null) return;
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()
                && Math.abs(e.getFrom().getX() - e.getTo().getX()) < 0.02 && Math.abs(e.getFrom().getZ() - e.getTo().getZ()) < 0.02) return;
        onPlayerMoved(e.getPlayer(), e.getTo());
    }

    /** While you are driving, ANY click drops the claw — you are stood on the pad, not looking at a button. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW)
    public void onClick(org.bukkit.event.player.PlayerInteractEvent e) {
        if (sessions.isEmpty()) return;
        if (e.getHand() != null && e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        for (Map.Entry<Machine, Session> en : sessions.entrySet()) {
            Session ss = en.getValue();
            if (!ss.player.equals(e.getPlayer().getUniqueId())) continue;
            if (ss.phase != Phase.DRIVE || !ss.claw.dropOnClick()) return;
            e.setCancelled(true);
            drop(e.getPlayer(), en.getKey());
            return;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        for (Session ss : sessions.values()) if (ss.player.equals(e.getPlayer().getUniqueId()) && ss.walkWas != null) {
            e.getPlayer().setWalkSpeed(ss.walkWas); ss.walkWas = null;   // never leave someone frozen
        }
        finishFor(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { finishFor(e.getEntity().getUniqueId()); }

    /** A player who leaves mid-play does not strand the machine — the play finishes itself. */
    private void finishFor(UUID id) {
        for (Map.Entry<Machine, Session> e : new ArrayList<>(sessions.entrySet())) {
            Session ss = e.getValue();
            if (!ss.player.equals(id)) continue;
            if (ss.phase == Phase.DRIVE) { ss.phase = Phase.DROP; ss.phaseStart = now(); }
        }
    }

    // --- the clock ---------------------------------------------------------------

    private void tick() {
        if (sessions.isEmpty()) return;
        double now = now();
        for (Map.Entry<Machine, Session> e : new ArrayList<>(sessions.entrySet())) {
            Session ss = e.getValue();
            try { advance(ss, now); } catch (Throwable ex) {
                plugin.getLogger().warning("[MachineConstruct] claw session failed: " + ex);
                sessions.remove(e.getKey()); release(ss, false);
            }
        }
    }

    private void advance(Session ss, double now) {
        double t = now - ss.phaseStart;
        ClawSpec c = ss.claw;
        switch (ss.phase) {
            case DRIVE -> {
                if (c.control() == ClawSpec.Control.STICK) {   // hold a direction and the claw travels
                    double step = c.speed() * TICK / 20.0;
                    ss.wantX = clamp(ss.wantX + ss.stickX * step, -c.travelX(), c.travelX());
                    ss.wantZ = clamp(ss.wantZ + ss.stickZ * step, -c.travelZ(), c.travelZ());
                    stick(ss);
                    Player at = plugin.getServer().getPlayer(ss.player);
                    if (at != null && ss.seatLoc != null && at.getLocation().distanceSquared(ss.seatLoc) > 1.5) at.teleport(ss.seatLoc);
                }
                ss.x += (ss.wantX - ss.x) * 0.35;   // the gantry catches up rather than snapping
                ss.z += (ss.wantZ - ss.z) * 0.35;
                drawGantry(ss, c.cable());
                if (now > ss.deadline) { ss.phase = Phase.DROP; ss.phaseStart = now; sound(ss, "block.piston.extend", 0.8f, 1.4f); }
            }
            case DROP -> {
                double u = Math.min(1, t / DROP_S);
                double fall = Math.min(1, u / DROP_FALL);
                ss.open = Math.min(1, 0.25 + fall * 1.5);        // the prongs spread as it goes down, wide before it lands
                // eased at BOTH ends: the winch takes up the slack, runs, and sets the claw down rather than slamming it
                drawGantry(ss, c.cable() + (c.top() - c.floor() - c.cable()) * smooth(fall));
                if (!ss.landed && fall >= 1) { ss.landed = true; sound(ss, "block.chain.place", 0.7f, 0.9f); }
                if (u >= 1) { ss.phase = Phase.CLOSE; ss.phaseStart = now; decide(ss); sound(ss, "block.iron_trapdoor.close", 0.8f, 0.8f); }
            }
            case CLOSE -> {
                double u = Math.min(1, t / CLOSE_S);
                ss.open = 1 - smooth(Math.min(1, u / 0.6));      // shuts over the first 60%, then holds what it has
                drawGantry(ss, c.top() - c.floor());
                if (u >= 1) {
                    ss.phase = Phase.LIFT; ss.phaseStart = now;
                    if (ss.grabbed || !"miss".equals(ss.failMode)) carry(ss, true);   // a miss comes up with nothing
                    sound(ss, "block.chain.hit", 0.7f, 1.1f);
                }
            }
            case LIFT -> {
                double u = Math.min(1, t / LIFT_S);
                ss.open = ss.grabbed ? 0.10 : jitterClosed(ss);   // a hair of slop, never the same twice
                drawGantry(ss, (c.top() - c.floor()) * (1 - ease(u)) + c.cable() * ease(u));
                if (!ss.grabbed && "slip_early".equals(ss.failMode) && u >= 0.55) { slip(ss); return; }
                if (u >= 1) { ss.phase = Phase.RETURN; ss.phaseStart = now; }
            }
            case RETURN -> {
                double u = Math.min(1, t / RETURN_S);
                double e = ease(u);
                ss.x = ss.x + (c.chuteX() - ss.x) * Math.min(1, e * 1.05);
                ss.z = ss.z + (c.chuteZ() - ss.z) * Math.min(1, e * 1.05);
                drawGantry(ss, c.cable());
                if (!ss.grabbed && "slip_late".equals(ss.failMode) && u >= 0.72) { slip(ss); return; }
                if (u >= 1) { ss.phase = Phase.RELEASE; ss.phaseStart = now; sound(ss, "block.bamboo_wood_trapdoor.open", 0.8f, 1.5f); }
            }
            case RELEASE -> {
                double u = Math.min(1, t / RELEASE_S);
                ss.open = u;
                drawGantry(ss, c.cable());
                if (ss.grabbed) dropPrize(ss, u, c.trayX(), c.trayY(), c.trayZ());
                if (u >= 1) {
                    if (ss.grabbed) payOut(ss); else finishFail(ss);
                    ss.phase = Phase.RESET; ss.phaseStart = now;
                }
            }
            case RESET -> {
                double u = Math.min(1, t / RESET_S);
                ss.x += (0 - ss.x) * 0.25; ss.z += (0 - ss.z) * 0.25;
                ss.open = 1 - u;
                drawGantry(ss, c.cable());
                if (u >= 1) { sessions.remove(ss.m); unseat(ss); release(ss, true); }
            }
        }
    }

    // --- the decision ------------------------------------------------------------

    /** Roll the grab the moment the prongs close: aim, then the base chance, then pity. */
    private void decide(Session ss) {
        ClawSpec c = ss.claw;
        double best = Double.MAX_VALUE;
        ss.nearest = null;
        for (PacketDisplay d : CuePlayer.select(ss.m, "pile_*")) {
            if (empty.contains(d)) continue;             // that slot is still waiting to be restocked
            float[] p = modelOf(ss, d.transform());
            double dx = p[0] - (0.5 + ss.x), dz = p[2] - (0.5 + ss.z);
            double r = Math.sqrt(dx * dx + dz * dz);
            if (r < best) { best = r; ss.nearest = d; }
        }
        boolean onPrize = best <= c.radius();
        if (!onPrize) ss.nearest = null;
        double chance = onPrize ? c.grabChance() : c.missChance();
        GachaSeries s = gacha.series(ss.spec);
        GachaSeries.Record rec = s.record(ss.player);
        boolean pity = c.pityGrabs() > 0 && rec.sinceGrab + 1 >= c.pityGrabs();
        boolean held = rng.nextDouble() < chance;
        // It closed on the prize — but holding it all the way to the chute is a second roll. A guaranteed
        // play (pity) never slips, or the guarantee would be a lie.
        boolean slipped = held && !pity && rng.nextDouble() < c.slipChance();
        ss.grabbed = pity || (held && !slipped);
        if (ss.grabbed) {
            rec.sinceGrab = 0;
            ss.prize = gacha.rollFor(ss.spec, s, rec);
            if (ss.prize == null) { ss.grabbed = false; }   // nothing loaded after all
        }
        if (!ss.grabbed) {
            rec.sinceGrab++;
            // a slip is a slip: it had the thing, so it can only lose it on the way, never "close on nothing"
            ss.failMode = slipped ? pickSlip(c) : pickFail(c, onPrize);
        }
        s.save();
        gacha.refreshBoard(ss.m);   // the guarantee counter moved — the board should say so before the claw even lifts
    }

    /** Which way it fails. Closing nowhere near a prize can only ever be a clean miss. */
    private String pickFail(ClawSpec c, boolean onPrize) {
        if (!onPrize) return "miss";
        double total = 0; for (double w : c.failModes().values()) total += Math.max(0, w);
        double x = rng.nextDouble() * total;
        for (Map.Entry<String, Double> e : c.failModes().entrySet()) { x -= Math.max(0, e.getValue()); if (x <= 0) return e.getKey(); }
        return "miss";
    }

    /** Which kind of slip — early, or the cruel one over the chute. */
    private String pickSlip(ClawSpec c) {
        double early = Math.max(0, c.failModes().getOrDefault("slip_early", 1.0));
        double late = Math.max(0, c.failModes().getOrDefault("slip_late", 1.0));
        if (early + late <= 0) return "slip_early";
        return rng.nextDouble() * (early + late) < early ? "slip_early" : "slip_late";
    }

    /** It had it, and lost it: the prongs sag, the prize falls back into the pile. */
    private void slip(Session ss) {
        sound(ss, "entity.item.pickup", 0.5f, 0.6f);
        sound(ss, "block.bamboo_wood_trapdoor.open", 0.7f, 0.9f);
        ss.phase = Phase.RELEASE; ss.phaseStart = now(); ss.releaseAt = 0;
        ss.grabbed = false;
        // let it fall from wherever the claw is to the top of the pile, then shake what it lands on
        dropPrize(ss, 0, ss.x, ss.claw.floor() + 0.10, ss.z);
    }

    private void finishFail(Session ss) {
        carry(ss, false);
        restock(ss, 1);          // it tumbled back into the heap
        jostlePile(ss);
        Player p = plugin.getServer().getPlayer(ss.player);
        if (p != null) {
            GachaSeries.Record rec = gacha.series(ss.spec).record(ss.player);
            int left = Math.max(0, ss.claw.pityGrabs() - rec.sinceGrab);
            p.sendMessage(GachaManager.msg(ss.t.skin(), "claw_miss",
                    "<gray>It slipped. <dark_gray>({left} more and the claw is guaranteed to hold.)",
                    MenuSkin.vars("left", String.valueOf(left))));
        }
        sound(ss, "block.stone_button.click_off", 0.8f, 0.7f);
    }

    private void payOut(Session ss) {
        carry(ss, false);
        restock(ss, ss.claw.restockTicks());
        Player p = plugin.getServer().getPlayer(ss.player);
        if (p == null || ss.prize == null) return;
        gacha.deliverOne(ss.m, ss.t, p, ss.prize);
        sound(ss, "entity.player.levelup", 0.8f, 1.2f);
    }

    // --- drawing -----------------------------------------------------------------

    private List<PacketDisplay> driven(Machine m) {
        List<PacketDisplay> out = new ArrayList<>();
        out.addAll(CuePlayer.select(m, "rail*|carriage|cable|claw_head|held|stick|stick_ball"));
        out.addAll(CuePlayer.select(m, "prong_*"));
        return out;
    }

    /** Put the gantry, the cable and the claw where the session says they are. */
    private void drawGantry(Session ss, double cableLen) {
        ClawSpec c = ss.claw;
        move(ss, "rail*", 0, 0, ss.z);
        move(ss, "carriage", ss.x, 0, ss.z);
        move(ss, "claw_head", ss.x, -(cableLen - c.cable()), ss.z);
        prongs(ss, cableLen);
        for (PacketDisplay d : CuePlayer.select(ss.m, "cable")) {
            MTransform rest = ss.rest.get(d);
            if (rest == null) continue;
            float grow = (float) (cableLen - c.cable());
            float[] w = ss.root.rotateVec((float) ss.x, -grow, (float) ss.z);
            MTransform t = new MTransform(rest.tx + w[0], rest.ty + w[1], rest.tz + w[2],
                    rest.sx, (float) cableLen, rest.sz, rest.qx, rest.qy, rest.qz, rest.qw);
            push(d, t);
        }
        if (ss.prize != null || !"miss".equals(ss.failMode)) {
            for (PacketDisplay d : CuePlayer.select(ss.m, "held")) moveOne(ss, d, ss.x, -(cableLen - c.cable()), ss.z);
        }
    }

    private void move(Session ss, String part, double dx, double dy, double dz) {
        for (PacketDisplay d : CuePlayer.select(ss.m, part)) moveOne(ss, d, dx, dy, dz);
    }

    private void moveOne(Session ss, PacketDisplay d, double dx, double dy, double dz) {
        MTransform rest = ss.rest.get(d);
        if (rest == null) return;
        float[] w = ss.root.rotateVec((float) dx, (float) dy, (float) dz);
        push(d, rest.translated(w[0], w[1], w[2]));
    }

    /**
     * The prongs hinge where they MEET the claw head — each about its own top, on an axis square to the
     * line from the claw's centre out to that prong, so it splays straight outwards and its top never
     * leaves the head. Read off each part's own position rather than its index: a claw may carry any
     * number of fingers, anywhere around the head, and the selector's order is not a promise.
     */
    private void prongs(Session ss, double cableLen) {
        ClawSpec c = ss.claw;
        double deg = 46 * clamp(ss.open, 0, 1.2);
        for (PacketDisplay d : CuePlayer.select(ss.m, "prong_*")) {
            MTransform rest = ss.rest.get(d);
            if (rest == null) continue;
            float[] w = ss.root.rotateVec((float) ss.x, (float) -(cableLen - c.cable()), (float) ss.z);
            MTransform base = rest.translated(w[0], w[1], w[2]);
            // where this prong sits relative to the claw's axis, in model space
            float[] at = modelOf(ss, base);
            double rx = at[0] + rest.sx / 2 - (0.5 + ss.x), rz = at[2] + rest.sz / 2 - (0.5 + ss.z);
            double len = Math.hypot(rx, rz);
            if (len < 1e-4) { rx = 0; rz = -1; len = 1; }        // one dead under the centre: send it forward
            float[] axis = ss.root.rotateVec((float) (-rz / len), 0, (float) (rx / len));
            float[] mid = ss.root.rotateVec(rest.sx / 2, 0, rest.sz / 2);
            push(d, base.rotatedAbout(axis[0], axis[1], axis[2], deg,
                    base.tx + mid[0], base.ty + rest.sy, base.tz + mid[2]));
        }
    }

    /**
     * The joystick leans the way the player is pushing — shaft and ball together, hinged at the FOOT of
     * the shaft, so the ball swings in an arc instead of spinning on the spot.
     */
    private void stick(Session ss) {
        List<PacketDisplay> parts = CuePlayer.select(ss.m, "stick|stick_ball");
        if (parts.isEmpty()) return;
        float[] pivot = null;
        for (PacketDisplay d : parts) {
            if (!"stick".equals(d.partName())) continue;
            MTransform rest = ss.rest.get(d);
            if (rest == null) continue;
            float[] mid = ss.root.rotateVec(rest.sx / 2, 0, rest.sz / 2);   // the shaft's bottom centre
            pivot = new float[]{ rest.tx + mid[0], rest.ty, rest.tz + mid[2] };
        }
        if (pivot == null) return;
        float[] ax = ss.root.rotateVec(1, 0, 0), az = ss.root.rotateVec(0, 0, 1);
        for (PacketDisplay d : parts) {
            MTransform rest = ss.rest.get(d);
            if (rest == null) continue;
            MTransform t = rest;
            if (Math.abs(ss.stickZ) > 0.01) t = t.rotatedAbout(ax[0], ax[1], ax[2], 20 * ss.stickZ, pivot[0], pivot[1], pivot[2]);
            if (Math.abs(ss.stickX) > 0.01) t = t.rotatedAbout(az[0], az[1], az[2], -20 * ss.stickX, pivot[0], pivot[1], pivot[2]);
            push(d, t);
        }
    }

    /** A hair of slop so no two grabs close identically. */
    private double jitterClosed(Session ss) { return 0.04 + rng.nextDouble() * 0.05; }

    /**
     * Show or hide what the claw is carrying. When the prongs closed over a head that is actually SITTING in
     * the box, that very head is what comes up: it vanishes from the pile and rides the claw, so the player
     * watches the thing they were aiming at leave the heap. The slot is restocked later ({@link #restock}) —
     * ten seconds after a win, or the instant a slip drops it back in.
     */
    private void carry(Session ss, boolean on) {
        DisplayContent lifted = null;
        if (on && ss.nearest != null && ss.taken == null && !empty.contains(ss.nearest)) {
            lifted = ss.nearest.baseContent();
            ss.taken = ss.nearest;
            empty.add(ss.nearest);
            ss.nearest.forceContent(CuePlayer.blank(ss.nearest));
            ss.nearest.consumeDirty(); tracker.refresh(ss.nearest, 0);
        } else if (on && ss.taken != null) lifted = takenWas.get(ss.taken);
        for (PacketDisplay d : CuePlayer.select(ss.m, "held")) {
            if (on && lifted != null) d.forceContent(lifted);
            else if (on && ss.prize != null) d.forceContent(new ItemContent(ss.prize.entry().shown(), ItemContent.context("fixed")));
            else if (on) d.forceContent(new ItemContent(pilePrize(ss), ItemContent.context("fixed")));
            else d.forceContent(CuePlayer.blank(d));
            d.consumeDirty(); tracker.refresh(d, 0);
        }
        if (lifted != null && ss.taken != null) takenWas.put(ss.taken, lifted);
    }

    /**
     * Put a lifted head back in the box. A won prize leaves a gap the machine fills a moment later (the
     * default ten seconds, so the pile visibly empties as people win); a dropped one is back at once,
     * because the player just watched it fall in.
     */
    private void restock(Session ss, long delayTicks) {
        PacketDisplay d = ss.taken;
        if (d == null) return;
        DisplayContent was = takenWas.remove(d);
        ss.taken = null; ss.nearest = null;
        if (was == null) { empty.remove(d); return; }
        Machine m = ss.m;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            empty.remove(d);
            if (!m.displays().contains(d)) return;   // the machine was picked up or re-rendered in the meantime
            d.forceContent(was); d.consumeDirty(); tracker.refresh(d, 0);
            try {
                Location at = m.anchor().clone().add(0.5, 1.3, 0.5);
                at.getWorld().playSound(at, "entity.item.pickup", 0.4f, 0.7f);
            } catch (Throwable ignored) { }
        }, Math.max(1, delayTicks));
    }

    /** What a failing claw appears to be holding: a random piece of the series. */
    private org.bukkit.inventory.ItemStack pilePrize(Session ss) {
        GachaSeries s = gacha.series(ss.spec);
        List<GachaSeries.Entry> loot = s.loot();
        return loot.isEmpty() ? new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER)
                : loot.get(rng.nextInt(loot.size())).shown();
    }

    /** The carried prize falling — into the tray on a win, back onto the pile on a slip. */
    private void dropPrize(Session ss, double u, double tx, double ty, double tz) {
        for (PacketDisplay d : CuePlayer.select(ss.m, "held")) {
            MTransform rest = ss.rest.get(d);
            if (rest == null) continue;
            double fx = ss.x, fz = ss.z;
            double x = fx + (tx - fx) * u, z = fz + (tz - fz) * u;
            double y = -(1 - Math.pow(1 - u, 2)) * 0.55;    // accelerating downward
            float[] w = ss.root.rotateVec((float) x, (float) y, (float) z);
            push(d, rest.translated(w[0], w[1], w[2]).rotatedLocal('x', u * 220));
        }
    }

    /** The pile settles where the claw has been rummaging. */
    private void jostlePile(Session ss) {
        List<PacketDisplay> pile = CuePlayer.select(ss.m, "pile_*");
        for (PacketDisplay d : pile) {
            float[] p = modelOf(ss, d.transform());
            double dx = p[0] - (0.5 + ss.x), dz = p[2] - (0.5 + ss.z);
            if (dx * dx + dz * dz > 0.09) continue;
            MTransform t = d.transform();
            d.forceTransform(t.translated((rng.nextFloat() - 0.5f) * 0.04f, 0, (rng.nextFloat() - 0.5f) * 0.04f)
                    .rotatedLocal('y', (rng.nextDouble() - 0.5) * 24));
            d.consumeDirty(); tracker.refresh(d, 2);
        }
    }

    private void release(Session ss, boolean restore) {
        for (Map.Entry<PacketDisplay, MTransform> e : ss.rest.entrySet()) {
            PacketDisplay d = e.getKey();
            if (restore) { d.forceTransform(e.getValue()); d.consumeDirty(); tracker.refresh(d, 2); }
            if (!d.pinned()) d.lock(false);
        }
        show(ss, "held", false);
    }

    private void show(Session ss, String part, boolean on) {
        for (PacketDisplay d : CuePlayer.select(ss.m, part)) {
            if (!on) { d.forceContent(CuePlayer.blank(d)); d.consumeDirty(); tracker.refresh(d, 0); }
        }
    }

    // --- helpers -----------------------------------------------------------------

    private void push(PacketDisplay d, MTransform t) { d.forceTransform(t); d.consumeDirty(); tracker.refresh(d, TICK); }

    /** A display's position back in model space (0.5 = the anchor column), whatever way the machine faces. */
    private float[] modelOf(Session ss, MTransform t) { return ss.inv.apply(t.tx, t.ty, t.tz); }

    private void sound(Session ss, String key, float vol, float pitch) {
        Location at = ss.m.anchor().clone().add(0.5, 1.0, 0.5);
        try { at.getWorld().playSound(at, key, vol, pitch); } catch (Throwable ignored) { }
    }

    private double now() { return plugin.getServer().getCurrentTick() / 20.0; }
    private static double ease(double u) { return 1 - Math.pow(1 - Math.min(1, Math.max(0, u)), 3); }

    /** Eased at both ends — a winch starting and stopping, not a lift snapping between floors. */
    private static double smooth(double u) {
        double x = Math.min(1, Math.max(0, u));
        return x * x * (3 - 2 * x);
    }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
}
