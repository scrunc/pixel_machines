package dev.servereer.machineconstruct.grinder;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One virtual spawner installed in a grinder: a mob type, a stack size (how many
 * spawners are merged here), the wall-clock of its last accrual, and its own
 * capped loot buffer + XP accumulator.
 *
 * <p>Buffers hold fractional counts ({@code double}) so rare drops accumulate
 * smoothly across cycles and across collections (the fractional remainder is
 * carried — see {@link GrinderManager}). Collecting floors to whole items and
 * moves them into the grinder's shared pool, leaving the remainder behind.
 */
public final class InstalledSpawner {

    private String type;                 // entity name, upper-case
    private long stackSize;
    private long lastAccrualMillis;

    // Item template (amount 1) → fractional accumulated count.
    private final Map<ItemStack, Double> buffer = new LinkedHashMap<>();
    private double accruedExp;

    public InstalledSpawner(String type, long stackSize, long lastAccrualMillis) {
        this.type = type;
        this.stackSize = Math.max(1, stackSize);
        this.lastAccrualMillis = lastAccrualMillis;
    }

    public String type() { return type; }
    public void setType(String type) { this.type = type; }

    public long stackSize() { return stackSize; }
    public void setStackSize(long stackSize) { this.stackSize = Math.max(1, stackSize); }
    public void addStack(long n) { this.stackSize = Math.max(1, this.stackSize + n); }

    public long lastAccrualMillis() { return lastAccrualMillis; }
    public void setLastAccrualMillis(long t) { this.lastAccrualMillis = t; }

    public Map<ItemStack, Double> buffer() { return buffer; }
    public double accruedExp() { return accruedExp; }
    public void setAccruedExp(double v) { this.accruedExp = Math.max(0, v); }
    public void addExp(double v) { this.accruedExp = Math.max(0, this.accruedExp + v); }

    public void addBuffer(ItemStack template, double amount) {
        buffer.merge(template, amount, Double::sum);
    }

    /** Sum of (fractional) buffered item counts — the figure compared against the item cap. */
    public double bufferedItems() {
        double s = 0;
        for (double v : buffer.values()) s += v;
        return s;
    }

    /** Whole buffered items currently collectable. */
    public long collectableItems() {
        long s = 0;
        for (double v : buffer.values()) s += (long) Math.floor(v);
        return s;
    }
}
