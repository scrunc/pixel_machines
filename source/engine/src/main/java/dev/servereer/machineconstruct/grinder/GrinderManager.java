package dev.servereer.machineconstruct.grinder;

import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * The grinder mechanic — expected-value time accrual (ADR 0008) plus collect.
 * Pure on the data: given a {@link GrinderData}, its {@link GrinderSpec}, the
 * shared {@link LootTable}, and the machine tier, it advances each installed
 * spawner's buffer by however many whole cycles have elapsed since its last
 * accrual, capping per spawner and honouring the overflow policy.
 *
 * <p>No per-tick work and no per-cycle loop: a spawner idle for a week is one
 * O(drops) computation on next sweep/open. Accrual is statistically equal to
 * per-roll generation but variance-free (a deliberate, recorded trade-off).
 */
public final class GrinderManager {

    private GrinderManager() {}

    /**
     * Accrue every spawner up to its caps. Returns true if anything changed
     * (buffer, XP, or the accrual clock) so the caller can persist.
     */
    public static boolean accrue(GrinderData d, GrinderSpec spec, LootTable loot, int tier, long now) {
        if (d == null || spec == null || loot == null) return false;
        boolean changed = false;
        double itemCap = spec.itemCap(tier, d.cores());
        double expCap = spec.expCap(tier, d.cores());

        for (InstalledSpawner s : d.spawners()) {
            LootTable.MobLoot ml = loot.get(s.type());
            long oldClock = s.lastAccrualMillis();
            if (ml == null) { s.setLastAccrualMillis(now); if (oldClock != now) changed = true; continue; }

            long elapsed = now - s.lastAccrualMillis();
            if (elapsed < spec.delayMillis()) continue;
            long cycles = elapsed / spec.delayMillis();
            if (cycles <= 0) continue;

            double scale = spec.avgMobs() * s.stackSize();
            double itemsPerCycle = scale * ml.expectedItemsPerCycle();
            double expPerCycle = scale * ml.experience();

            // Item production is limited only by the ITEM cap + time. The exp buffer is a
            // secondary resource: it's clamped at its own cap, but a full exp buffer must NOT
            // freeze item production (that's the "stuck at 2% items / 96% XP" bug). Collect to
            // drain exp and it resumes.
            double cyclesUntilItemCap = itemsPerCycle > 0
                    ? (itemCap - s.bufferedItems()) / itemsPerCycle : Double.MAX_VALUE;
            long allowed = (long) Math.floor(Math.max(0, cyclesUntilItemCap));
            long consumed = Math.min(cycles, allowed);

            if (consumed > 0) {
                for (LootTable.LootDrop drop : ml.drops()) {
                    double add = consumed * scale * drop.expectedPerCycle();
                    if (add > 0) s.addBuffer(drop.template(), add);
                }
                if (expPerCycle > 0) {
                    double expRoom = Math.max(0, expCap - s.accruedExp());
                    double expAdd = Math.min(consumed * expPerCycle, expRoom);   // clamp, never limit item cycles
                    if (expAdd > 0) s.addExp(expAdd);
                }
            }

            boolean capped = consumed < cycles;
            if (spec.overflow() == GrinderSpec.Overflow.WASTE) {
                s.setLastAccrualMillis(now);                       // waste remainder + overflow
            } else if (capped) {
                s.setLastAccrualMillis(now);                       // STOP: discard time spent full
            } else {
                s.setLastAccrualMillis(oldClock + consumed * spec.delayMillis());   // retain sub-cycle remainder
            }
            if (consumed > 0 || s.lastAccrualMillis() != oldClock) changed = true;
        }
        return changed;
    }

    /**
     * Move all whole buffered items into the shared pool and all whole accrued XP
     * into stored XP, leaving fractional remainders behind. Returns
     * {@code [items, xp]} collected (for logging / feedback).
     */
    public static long[] collectAll(GrinderData d) {
        long items = 0, exp = 0;
        for (InstalledSpawner s : d.spawners()) {
            for (Map.Entry<ItemStack, Double> e : s.buffer().entrySet()) {
                long whole = (long) Math.floor(e.getValue());
                if (whole > 0) {
                    ItemStack tmpl = e.getKey().clone();
                    tmpl.setAmount(1);
                    d.addToPool(tmpl, whole);
                    e.setValue(e.getValue() - whole);
                    items += whole;
                }
            }
            long wholeExp = (long) Math.floor(s.accruedExp());
            if (wholeExp > 0) {
                d.addStoredExp(wholeExp);
                s.setAccruedExp(s.accruedExp() - wholeExp);
                exp += wholeExp;
            }
        }
        return new long[]{items, exp};
    }
}
