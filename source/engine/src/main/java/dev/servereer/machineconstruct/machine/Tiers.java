package dev.servereer.machineconstruct.machine;

import dev.servereer.machineconstruct.gui.GuiLayout;
import dev.servereer.machineconstruct.model.Model;
import org.bukkit.inventory.ItemStack;

/**
 * A machine type's upgrade ladder (DESIGN.md §tiers). A machine starts at tier 1
 * and is upgraded by consuming {@link #upgradeItem()} from its upgrade slot, up
 * to {@link #max()}. Each tier scales processing {@code speed}, output {@code
 * batch}, and {@code capacity}, and may override the {@code model}/{@code gui}
 * for a visually upgraded machine. Arrays/overrides are indexed tier-1; missing
 * entries fall back (stats hold the last value; model/gui use the nearest lower
 * override, else the base). 0-hardcoded — all from YAML.
 */
public final class Tiers {

    private final int max;
    private final ItemStack upgradeItem;   // consumed to advance one tier
    private final double[] speed;          // processing-speed multiplier per tier
    private final int[] batch;             // output-amount multiplier per tier
    private final double[] capacity;       // capacity multiplier per tier
    private final Model[] models;          // per-tier model override (null = inherit)
    private final GuiLayout[] guis;        // per-tier GUI override (null = inherit)

    public Tiers(int max, ItemStack upgradeItem, double[] speed, int[] batch, double[] capacity,
                 Model[] models, GuiLayout[] guis) {
        this.max = Math.max(1, max);
        this.upgradeItem = upgradeItem;
        this.speed = speed;
        this.batch = batch;
        this.capacity = capacity;
        this.models = models;
        this.guis = guis;
    }

    public int max() { return max; }
    public ItemStack upgradeItem() { return upgradeItem; }

    /** The model override for this tier (or the nearest lower one), or null to use the base. */
    public Model modelOverride(int tier) {
        for (int t = Math.min(tier, models.length); t >= 1; t--) if (models[t - 1] != null) return models[t - 1];
        return null;
    }

    /** The GUI override for this tier (or the nearest lower one), or null to use the base. */
    public GuiLayout guiOverride(int tier) {
        for (int t = Math.min(tier, guis.length); t >= 1; t--) if (guis[t - 1] != null) return guis[t - 1];
        return null;
    }

    public double speed(int tier)    { return at(speed, tier, 1.0); }
    public int batch(int tier)       { return (int) Math.max(1, at(batch, tier, 1)); }
    public double capacity(int tier) { return at(capacity, tier, 1.0); }

    private static double at(double[] a, int tier, double def) {
        if (a == null || a.length == 0) return def;
        int i = Math.min(Math.max(0, tier - 1), a.length - 1);   // clamp + hold last
        return a[i];
    }

    private static double at(int[] a, int tier, double def) {
        if (a == null || a.length == 0) return def;
        int i = Math.min(Math.max(0, tier - 1), a.length - 1);
        return a[i];
    }
}
