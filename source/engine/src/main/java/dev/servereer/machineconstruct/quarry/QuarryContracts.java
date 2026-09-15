package dev.servereer.machineconstruct.quarry;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rotating quarry contracts — the Rift-Point / money sink chase. Alternates each
 * completion between a <b>throughput</b> goal ("extract N items total", tracked via
 * lifetime mined) and an <b>item bounty</b> ("bank N of a specific material", tracked
 * via the live vault count and consumed on claim). Fully driven by
 * {@link QuarrySpec.ContractConfig}: bounty targets are <b>rarity-scaled</b> (a rare
 * item asks for far fewer than a common one), the pick is weighted toward common items,
 * and ultra-rares can be excluded outright. Stateless apart from {@link QuarryData}, so
 * it survives reloads — and {@link #ensure} self-heals any contract that's now invalid
 * (e.g. a bounty on a now-excluded item) by rerolling it.
 */
public final class QuarryContracts {

    private QuarryContracts() {}

    /** Roll an initial contract if none is active, or reroll one that's no longer valid/reachable. */
    public static void ensure(QuarryData d, QuarrySpec spec) {
        QuarrySpec.ContractConfig c = spec.contracts();
        boolean invalid = d.contractTarget() <= 0
                || (d.contractType() == 1 && (d.contractItem() == null
                    || c.exclude.contains(d.contractItem().getType())
                    || d.contractTarget() > c.bountyMax
                    || !reachable(d, spec, c, d.contractItem())));   // bounty item no longer mineable (e.g. dimension relocked by prestige)
        if (invalid) roll(d, spec);
    }

    /** Issue the next contract — even count = throughput, odd = bounty (rarity-scaled). */
    public static void roll(QuarryData d, QuarrySpec spec) {
        QuarrySpec.ContractConfig c = spec.contracts();
        long n = d.contractsDone();
        if (n % 2 == 1) {
            QuarrySpec.LootEntry pick = pickEligible(d, spec, c);
            if (pick != null) {
                double maxW = maxWeight(d, spec, c);
                long base = c.bountyBase + n * c.bountyPer;
                long target = maxW <= 0 ? c.bountyMin : Math.round(base * (pick.weight / maxW));
                target = Math.max(c.bountyMin, Math.min(target, c.bountyMax));
                ItemStack item = pick.template.clone();
                item.setAmount(1);
                d.setContractType(1);
                d.setContractItem(item);
                d.setContractTarget(target);
                return;
            }
            // no eligible bounty items → fall through to a throughput contract
        }
        d.setContractType(0);
        d.setContractItem(null);
        d.setContractBaseMined(d.totalMined());
        d.setContractTarget(c.throughputBase + n * c.throughputPer);
    }

    public static long progress(QuarryData d) {
        if (d.contractType() == 1) {
            ItemStack it = d.contractItem();
            return it == null ? 0 : d.vault().getOrDefault(it, 0L);
        }
        return Math.max(0, d.totalMined() - d.contractBaseMined());
    }

    public static boolean ready(QuarryData d) {
        return d.contractTarget() > 0 && progress(d) >= d.contractTarget();
    }

    public static long rpReward(QuarryData d, QuarrySpec spec) {
        QuarrySpec.ContractConfig c = spec.contracts();
        return c.rpBase + d.contractsDone() * c.rpPer + d.contractTarget() / Math.max(1, c.rpTargetDiv);
    }

    public static double moneyReward(QuarryData d, QuarrySpec spec) {
        QuarrySpec.ContractConfig c = spec.contracts();
        return c.moneyBase + d.contractTarget() * c.moneyTargetMult;
    }

    // --- helpers ------------------------------------------------------------

    /** Items the player can actually obtain right now: loot from UNLOCKED dimensions (gated by
     *  Rift Drill) + the sea. This is what keeps a bounty from asking for a relocked-dimension item. */
    private static List<QuarrySpec.LootEntry> eligible(QuarryData d, QuarrySpec spec, QuarrySpec.ContractConfig c) {
        List<QuarrySpec.LootEntry> pool = new ArrayList<>();
        int maxDim = Math.min(spec.dimensionCount() - 1, QuarrySkill.maxDimensionIndex(d));
        for (int i = 0; i <= maxDim; i++) {
            QuarrySpec.Table t = spec.dimension(i);
            if (t != null) for (QuarrySpec.LootEntry e : t.loot)
                if (e.weight > 0 && !c.exclude.contains(e.template.getType())) pool.add(e);
        }
        if (spec.fishing() != null) for (QuarrySpec.LootEntry e : spec.fishing().loot)
            if (e.weight > 0 && !c.exclude.contains(e.template.getType())) pool.add(e);
        return pool;
    }

    /** Is {@code item} currently obtainable (in the reachable loot pool)? */
    private static boolean reachable(QuarryData d, QuarrySpec spec, QuarrySpec.ContractConfig c, ItemStack item) {
        for (QuarrySpec.LootEntry e : eligible(d, spec, c))
            if (e.template.getType() == item.getType()) return true;
        return false;
    }

    /** Pick weighted by drop weight, so common items are far more likely to be the bounty. */
    private static QuarrySpec.LootEntry pickEligible(QuarryData d, QuarrySpec spec, QuarrySpec.ContractConfig c) {
        List<QuarrySpec.LootEntry> pool = eligible(d, spec, c);
        if (pool.isEmpty()) return null;
        double total = 0; for (QuarrySpec.LootEntry e : pool) total += e.weight;
        if (total <= 0) return pool.get(0);
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (QuarrySpec.LootEntry e : pool) { r -= e.weight; if (r <= 0) return e; }
        return pool.get(pool.size() - 1);
    }

    private static double maxWeight(QuarryData d, QuarrySpec spec, QuarrySpec.ContractConfig c) {
        double max = 0;
        for (QuarrySpec.LootEntry e : eligible(d, spec, c)) max = Math.max(max, e.weight);
        return max;
    }
}
