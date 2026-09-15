package dev.servereer.machineconstruct.gacha;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code gacha.claw:} block — a crane the player drives (ADR 0047). Unlike the capsule and reel
 * styles, a play can come up EMPTY: the prizes are expensive, so the claw grabs about one time in
 * five, aiming moves that, and a pity counter guarantees a grab after enough failures.
 *
 * <p>Geometry is in model space (0.5 = the anchor column) and must match the machine's model — the
 * generator writes both, so edit them together.
 * <pre>
 * claw:
 *   control: stick          # stick = the machine seats you at the joystick and reads WASD (ArcadeCab's contract)
 *   speed: 0.55             # how fast the claw travels while a direction is held
 *   seat: [0, 0, -1.1]      # where it stands you, in model space
 *   pad: 1.4                # the plate's size, and how far in front of the machine it sits
 *   pad_offset: 1.30
 *   drop_on_click: true     # any right-click drops, not only the claw button
 *   travel: [0.62, 0.34]    # how far the claw may travel from centre, in x and z
 *   top: 2.05               # the gantry's height (where the cable hangs from)
 *   floor: 1.24             # how deep the claw dives into the pile
 *   cable: 0.22             # the cable's length when parked
 *   chute: [-0.66, -0.16]   # where the claw lets go, in model x/z
 *   tray: [-0.66, 0.74, -0.30]  # where a won prize lands (x, y, z)
 *   grab_chance: 0.20
 *   miss_chance: 0.08
 *   radius: 0.22            # how close to a prize counts as closing ON it
 *   pity_grabs: 6           # this many failures in a row and the next play is a certainty
 *   fail_modes: { miss: 40, slip_early: 35, slip_late: 25 }
 *   timeout: 25s
 * </pre>
 */
public final class ClawSpec {

    public enum Control { STICK, PAD }

    private final Control control;
    private final double pad, padOffset;   // pad: only for control: pad
    private final double speed;            // stick: how fast the claw travels, blocks/second
    private final double[] seat;           // where the player is stood while playing (model space)
    private final boolean dropOnClick;
    private final double travelX, travelZ, top, floor, cable;
    private final double chuteX, chuteZ;
    private final double trayX, trayY, trayZ;
    private final double grabChance, missChance, radius;
    private final int pityGrabs;
    private final Map<String, Double> failModes;
    private final int timeoutTicks;

    private ClawSpec(Control control, double pad, double padOffset, double speed, double[] seat, boolean dropOnClick, double travelX, double travelZ,
                     double top, double floor, double cable, double chuteX, double chuteZ, double trayX, double trayY, double trayZ,
                     double grabChance, double missChance, double radius, int pityGrabs, Map<String, Double> failModes, int timeoutTicks) {
        this.control = control; this.pad = pad; this.padOffset = padOffset; this.speed = speed; this.seat = seat; this.dropOnClick = dropOnClick;
        this.travelX = travelX; this.travelZ = travelZ; this.top = top; this.floor = floor; this.cable = cable;
        this.chuteX = chuteX; this.chuteZ = chuteZ; this.trayX = trayX; this.trayY = trayY; this.trayZ = trayZ;
        this.grabChance = grabChance; this.missChance = missChance; this.radius = radius; this.pityGrabs = pityGrabs;
        this.failModes = failModes; this.timeoutTicks = timeoutTicks;
    }

    public static ClawSpec parse(ConfigurationSection sec) {
        if (sec == null) return null;
        double[] travel = pair(sec, "travel", 0.6, 0.34);
        double[] chute = pair(sec, "chute", -0.66, -0.16);
        double[] tray = triple(sec, "tray", -0.66, 0.74, -0.30);
        Map<String, Double> modes = new LinkedHashMap<>();
        ConfigurationSection fm = sec.getConfigurationSection("fail_modes");
        if (fm != null) for (String k : fm.getKeys(false)) modes.put(k.toLowerCase(), fm.getDouble(k, 0));
        if (modes.isEmpty()) { modes.put("miss", 40.0); modes.put("slip_early", 35.0); modes.put("slip_late", 25.0); }
        long ms = dev.servereer.machineconstruct.grinder.GrinderSpec.parseTimeMillis(sec.getString("timeout", "25s"));
        double[] seat = triple(sec, "seat", 0, 0, -1.1);
        return new ClawSpec(
                "pad".equalsIgnoreCase(sec.getString("control", "stick")) ? Control.PAD : Control.STICK,
                sec.getDouble("pad", 1.4), sec.getDouble("pad_offset", 1.3),
                sec.getDouble("speed", 0.55), seat, sec.getBoolean("drop_on_click", true),
                travel[0], travel[1], sec.getDouble("top", 2.05), sec.getDouble("floor", 1.24), sec.getDouble("cable", 0.22),
                chute[0], chute[1], tray[0], tray[1], tray[2],
                sec.getDouble("grab_chance", 0.20), sec.getDouble("miss_chance", 0.08), sec.getDouble("radius", 0.22),
                sec.getInt("pity_grabs", 6), modes, (int) Math.max(100L, ms / 50L));
    }

    private static double[] pair(ConfigurationSection sec, String key, double a, double b) {
        java.util.List<Double> l = sec.getDoubleList(key);
        return l.size() >= 2 ? new double[]{ l.get(0), l.get(1) } : new double[]{ a, b };
    }
    private static double[] triple(ConfigurationSection sec, String key, double a, double b, double c) {
        java.util.List<Double> l = sec.getDoubleList(key);
        return l.size() >= 3 ? new double[]{ l.get(0), l.get(1), l.get(2) } : new double[]{ a, b, c };
    }

    public Control control() { return control; }
    public double pad() { return pad; }
    public double padOffset() { return padOffset; }
    /** Claw travel speed in blocks a second while a direction is held (stick control). */
    public double speed() { return speed; }
    /** Where the machine stands the player while they play, in model space. */
    public double[] seat() { return seat; }
    public boolean dropOnClick() { return dropOnClick; }
    public double travelX() { return travelX; }
    public double travelZ() { return travelZ; }
    public double top() { return top; }
    public double floor() { return floor; }
    public double cable() { return cable; }
    public double chuteX() { return chuteX; }
    public double chuteZ() { return chuteZ; }
    public double trayX() { return trayX; }
    public double trayY() { return trayY; }
    public double trayZ() { return trayZ; }
    public double grabChance() { return grabChance; }
    public double missChance() { return missChance; }
    public double radius() { return radius; }
    public int pityGrabs() { return pityGrabs; }
    public Map<String, Double> failModes() { return failModes; }
    public int timeoutTicks() { return timeoutTicks; }
}
