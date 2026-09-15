package dev.servereer.machineconstruct.jukebox;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code jukebox:} section of a machine config — everything that turns a machine into a Jukebox: a
 * placed speaker that plays a saved library track locationally (Simple Voice Chat) and can mint custom
 * discs bound to a track. Immutable, shared across instances; per-machine mutable state lives in
 * {@link JukeboxData}.
 */
public final class JukeboxSpec {

    private final double[] distancePerTier;        // SVC reach (blocks) by tier (index = tier-1)
    private final Map<Material, Integer> craftCost; // materials consumed to mint one disc
    private final boolean loopDefault;
    private final int maxPerPlayer;                // 0 = unlimited

    private JukeboxSpec(double[] distancePerTier, Map<Material, Integer> craftCost, boolean loopDefault, int maxPerPlayer) {
        this.distancePerTier = distancePerTier;
        this.craftCost = craftCost;
        this.loopDefault = loopDefault;
        this.maxPerPlayer = maxPerPlayer;
    }

    /** Locational reach (blocks) at a tier. */
    public float distance(int tier) {
        if (distancePerTier.length == 0) return 48f;
        return (float) distancePerTier[Math.min(Math.max(1, tier) - 1, distancePerTier.length - 1)];
    }

    public Map<Material, Integer> craftCost() { return craftCost; }
    public boolean loopDefault() { return loopDefault; }
    public int maxPerPlayer() { return maxPerPlayer; }

    /** Parse a {@code jukebox:} section, or null if absent. Never throws. */
    public static JukeboxSpec parse(ConfigurationSection sec) {
        if (sec == null) return null;

        double[] dist = toDoubleArray(sec.getDoubleList("distance"));
        if (dist.length == 0) {
            // allow a single scalar `distance: 48`
            if (sec.isInt("distance") || sec.isDouble("distance")) dist = new double[]{ sec.getDouble("distance") };
            else dist = new double[]{ 32, 48, 64 };
        }

        Map<Material, Integer> cost = new LinkedHashMap<>();
        ConfigurationSection craft = sec.getConfigurationSection("craft");
        if (craft != null) for (String s : craft.getStringList("cost")) {
            String[] parts = s.trim().split("\\s+");
            Material mat = Material.matchMaterial(parts[0]);
            if (mat == null || !mat.isItem()) continue;
            int amt = 1;
            if (parts.length > 1) try { amt = Math.max(1, Integer.parseInt(parts[1])); } catch (NumberFormatException ignored) {}
            cost.merge(mat, amt, Integer::sum);
        }

        boolean loop = sec.getBoolean("loop", false);
        int maxPerPlayer = sec.getInt("max_per_player", 0);
        return new JukeboxSpec(dist, cost, loop, maxPerPlayer);
    }

    private static double[] toDoubleArray(java.util.List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    /** Pretty material name (menu copy). */
    public static String pretty(Material mat) {
        String[] parts = mat.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return sb.toString().trim();
    }
}
