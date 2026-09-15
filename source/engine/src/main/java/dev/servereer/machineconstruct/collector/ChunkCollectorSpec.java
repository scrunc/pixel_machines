package dev.servereer.machineconstruct.collector;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The {@code chunk_collector:} section of a machine config — everything that turns
 * a machine into a Chunk Collector (a vacuum-hopper replacement). Immutable, shared
 * across instances; the per-machine mutable state lives in {@link ChunkCollectorData}.
 *
 * <p>A Chunk Collector sweeps dropped-item entities in the chunks around its anchor
 * (a {@code radius_chunks[tier]} ring — 0 = its own chunk, 1 = 3×3, 2 = 5×5) into a
 * shared virtual vault, capped by {@code cap.base_items × per_tier_mult[tier]}. The
 * vault is collected/sold from a menu, auto-sold per item, and routed to OUTPUT
 * chests — all reusing the grinder/factory economy machinery. Runs free (no fuel),
 * offline-safe (only loaded chunks are swept).
 */
public final class ChunkCollectorSpec {

    /** What happens once the vault is full. */
    public enum Overflow { STOP, WASTE }

    /** How {@code filter.items} is interpreted. */
    public enum FilterMode { ALL, WHITELIST, BLACKLIST }

    private final long intervalMillis;     // how often it vacuums
    private final int[] radiusChunks;      // per tier (index = tier-1)
    private final double baseItems;        // tier-1 vault cap (0 = unlimited)
    private final double[] perTierMult;    // index = tier-1
    private final Overflow overflow;
    private final int maxPerPlayer;        // how many of this collector a player may place (0 = unlimited)
    private final FilterMode filterMode;
    private final Set<Material> filterItems;
    private final long graceTicks;         // skip item entities younger than this (anti-snatch); 0 = grab everything
    private final boolean skipNoPickup;    // skip items flagged never-pickup (held/owned by another plugin)
    private final boolean respectClaims;   // only vacuum where the owner is allowed to build (grief-aware)

    private ChunkCollectorSpec(long intervalMillis, int[] radiusChunks, double baseItems, double[] perTierMult,
                               Overflow overflow, int maxPerPlayer, FilterMode filterMode, Set<Material> filterItems,
                               long graceTicks, boolean skipNoPickup, boolean respectClaims) {
        this.intervalMillis = intervalMillis;
        this.radiusChunks = radiusChunks;
        this.baseItems = baseItems;
        this.perTierMult = perTierMult;
        this.overflow = overflow;
        this.maxPerPlayer = maxPerPlayer;
        this.filterMode = filterMode;
        this.filterItems = filterItems;
        this.graceTicks = graceTicks;
        this.skipNoPickup = skipNoPickup;
        this.respectClaims = respectClaims;
    }

    // --- accessors ----------------------------------------------------------

    public long intervalMillis() { return intervalMillis; }
    public Overflow overflow() { return overflow; }
    public int maxPerPlayer() { return maxPerPlayer; }
    public long graceTicks() { return graceTicks; }
    public boolean skipNoPickup() { return skipNoPickup; }
    public boolean respectClaims() { return respectClaims; }
    public FilterMode filterMode() { return filterMode; }
    public Set<Material> filterItems() { return filterItems; }

    /** The chunk-ring radius at a tier (0 = the anchor's chunk only, 1 = 3×3, 2 = 5×5). */
    public int radiusChunks(int tier) {
        if (radiusChunks.length == 0) return 0;
        return Math.max(0, radiusChunks[Math.min(Math.max(1, tier) - 1, radiusChunks.length - 1)]);
    }

    /** The largest chunk-ring across all tiers (for menu copy). */
    public int maxRadiusChunks() {
        int m = 0;
        for (int r : radiusChunks) m = Math.max(m, r);
        return m;
    }

    /** Vault capacity at a tier (0 = unlimited). */
    public long capacity(int tier) {
        if (baseItems <= 0) return 0;
        double mult = perTierMult.length == 0 ? 1.0 : perTierMult[Math.min(Math.max(1, tier) - 1, perTierMult.length - 1)];
        return (long) Math.max(1, Math.round(baseItems * mult));
    }

    /** Whether a vacuumed material passes the configured filter. */
    public boolean accepts(Material mat) {
        if (mat == null) return false;
        return switch (filterMode) {
            case WHITELIST -> filterItems.contains(mat);
            case BLACKLIST -> !filterItems.contains(mat);
            default -> true;
        };
    }

    // --- parse --------------------------------------------------------------

    /** Parse a {@code chunk_collector:} section, or null if absent. Never throws. */
    public static ChunkCollectorSpec parse(ConfigurationSection sec) {
        if (sec == null) return null;
        long interval = parseTimeMillis(sec.getString("interval", "2s"));

        int[] radius = toIntArray(sec.getIntegerList("radius_chunks"));
        if (radius.length == 0) radius = new int[]{ 0, 1, 2 };   // 1×1 → 3×3 → 5×5

        ConfigurationSection cap = sec.getConfigurationSection("cap");
        double baseItems = cap == null ? 27648 : cap.getDouble("base_items", 27648);
        double[] perTier = cap == null ? new double[]{ 1.0 } : toDoubleArray(cap.getDoubleList("per_tier_mult"));
        if (perTier.length == 0) perTier = new double[]{ 1.0, 2.5, 6.0 };

        Overflow overflow = "waste".equalsIgnoreCase(sec.getString("overflow", "stop"))
                ? Overflow.WASTE : Overflow.STOP;
        int maxPerPlayer = sec.getInt("max_per_player", 1);
        long graceTicks = Math.max(0, sec.getLong("grace_ticks", 0));
        boolean skipNoPickup = sec.getBoolean("skip_no_pickup", true);
        boolean respectClaims = sec.getBoolean("respect_claims", true);

        FilterMode mode = FilterMode.ALL;
        Set<Material> items = new LinkedHashSet<>();
        ConfigurationSection filter = sec.getConfigurationSection("filter");
        if (filter != null) {
            try { mode = FilterMode.valueOf(filter.getString("mode", "all").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { mode = FilterMode.ALL; }
            for (String s : filter.getStringList("items")) {
                Material mm = Material.matchMaterial(s);
                if (mm != null) items.add(mm);
            }
        }

        return new ChunkCollectorSpec(interval, radius, baseItems, perTier, overflow, maxPerPlayer,
                mode, items, graceTicks, skipNoPickup, respectClaims);
    }

    private static double[] toDoubleArray(java.util.List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static int[] toIntArray(java.util.List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    /** Parse "2s" / "5m" / "1h" into milliseconds. Defaults to 2s on failure. */
    public static long parseTimeMillis(String s) {
        if (s == null || s.isBlank()) return 2_000L;
        long total = 0;
        boolean any = false;
        for (String part : s.trim().toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            int i = 0;
            while (i < part.length() && Character.isDigit(part.charAt(i))) i++;
            if (i == 0) continue;
            try {
                long n = Long.parseLong(part.substring(0, i));
                long mult = switch (part.substring(i)) {
                    case "s" -> 1_000L;
                    case "m" -> 60_000L;
                    case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L;
                    default -> 1_000L;
                };
                total += n * mult;
                any = true;
            } catch (NumberFormatException ignored) { }
        }
        return any ? total : 2_000L;
    }
}
