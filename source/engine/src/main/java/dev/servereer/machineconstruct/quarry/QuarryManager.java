package dev.servereer.machineconstruct.quarry;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The quarry mechanic — expected-value accrual for mining (a pickaxe + dimension
 * table) and fishing (a rod + the sea table). Each runs on its own clock so
 * {@code BOTH} mode advances both independently. Bounded per call by time, vault
 * room, and tool durability, so offline catch-up is O(table). Enchants scale it:
 * Efficiency/Lure → speed, Fortune/Luck-of-the-Sea → yield/treasure, Unbreaking → lifespan.
 */
public final class QuarryManager {

    private QuarryManager() {}

    /** No change this tick. */
    public static final int CH_NONE = 0;
    /** Only passive, time-based drift changed (heat cooling, vein regen) — refresh the GUI, but
     *  don't bother persisting: it self-heals on reload from the {@code lastHeat}/{@code lastVein} clocks. */
    public static final int CH_DRIFT = 1;
    /** A real state change (deposits, lava produced, surge) — persist now so it survives a crash. */
    public static final int CH_MEANINGFUL = 2;

    public static int accrue(QuarryData d, QuarrySpec spec, int tier, Location anchor, long now) {
        if (d == null || spec == null) return CH_NONE;
        boolean mining = d.mode() == QuarryData.Mode.MINING || d.mode() == QuarryData.Mode.BOTH;
        boolean fishing = d.mode() == QuarryData.Mode.FISHING || d.mode() == QuarryData.Mode.BOTH;
        int r = CH_NONE;
        if (maybeSurge(d, spec, now)) r |= CH_MEANINGFUL;
        if (mining) { if (mine(d, spec, anchor, now)) r |= CH_MEANINGFUL; } else d.setLastAccrual(now);
        if (fishing) { if (fish(d, spec, now)) r |= CH_MEANINGFUL; } else d.setLastFish(now);
        r |= lavaTick(d, spec, now);
        if (regenVeins(d, spec, now)) r |= CH_DRIFT;
        return r;
    }

    /** Roll for a global Rift Surge (time-correct via the elapsed window). */
    private static boolean maybeSurge(QuarryData d, QuarrySpec spec, long now) {
        long el = now - d.lastSurgeCheck();
        if (el <= 0) return false;
        d.setLastSurgeCheck(now);
        if (d.surgeActive(now) || spec.surgeChancePerMin() <= 0) return false;
        double ratePerMs = spec.surgeChancePerMin() / 60_000.0;
        double pTrig = 1.0 - Math.exp(-ratePerMs * el);
        if (ThreadLocalRandom.current().nextDouble() < pTrig) {
            d.setSurgeUntil(now + spec.surgeDurationMillis());
            d.logEvent("Rift surge — ×" + spec.surgeMult() + " yield for " + (spec.surgeDurationMillis() / 1000) + "s");
            return true;
        }
        return false;
    }

    /** Continuous vein regeneration for all depleted dimensions. */
    private static boolean regenVeins(QuarryData d, QuarrySpec spec, long now) {
        long el = now - d.lastVein();
        if (el <= 0) return false;
        d.setLastVein(now);
        double add = spec.regenPerSec() * (el / 1000.0);
        if (add <= 0 || d.veinHealth().isEmpty()) return false;
        boolean changed = false;
        for (Integer k : new ArrayList<>(d.veinHealth().keySet())) {
            double nh = Math.min(1.0, d.veinHealth(k) + add);
            if (nh >= 1.0) d.veinHealth().remove(k); else d.veinHealth().put(k, nh);
            changed = true;
        }
        return changed;
    }

    /**
     * Lava generation + heat. Cooling runs continuously off {@code lastHeat}; production
     * runs in cycle batches off {@code lastLava} (remainder retained, like mining). Generation
     * self-throttles: it can only run while there's heat headroom, so the tank fills in bursts
     * that overheat, then cools — a natural rate limit and the "togglable lava" duty cycle.
     */
    private static int lavaTick(QuarryData d, QuarrySpec spec, long now) {
        int r = CH_NONE;

        // continuous cooling (passive drift — not worth persisting on its own)
        long he = now - d.lastHeat();
        if (he > 0) {
            double cooled = Math.max(0, d.heat() - spec.coolPerSec() * (he / 1000.0));
            if (cooled != d.heat()) { d.setHeat(cooled); r |= CH_DRIFT; }
            d.setLastHeat(now);
        }

        if (!d.lavaOn()) { d.setLastLava(now); return r; }

        long elapsed = now - d.lastLava();
        long cyclesByTime = elapsed / spec.cycleMillis();
        if (cyclesByTime <= 0) return r;                 // retain remainder until a full cycle elapses

        long room = Math.max(0, spec.lavaCap() - d.lava());
        long cyclesByRoom = room / Math.max(1, spec.lavaPerCycle());
        double headroom = spec.heatMax() - d.heat();
        long cyclesByHeat = spec.lavaHeatPerCycle() <= 0
                ? Long.MAX_VALUE : (long) Math.floor(headroom / spec.lavaHeatPerCycle());
        long consumed = Math.min(cyclesByTime, Math.min(cyclesByRoom, cyclesByHeat));
        if (consumed <= 0) { d.setLastLava(now); return r; }   // tank full or overheated → freeze clock

        d.setLava(d.lava() + consumed * spec.lavaPerCycle());
        d.setHeat(d.heat() + consumed * spec.lavaHeatPerCycle());
        d.setLastLava(consumed < cyclesByTime ? now : d.lastLava() + consumed * spec.cycleMillis());
        return r | CH_MEANINGFUL;          // produced lava → persist
    }

    private static boolean mine(QuarryData d, QuarrySpec spec, Location anchor, long now) {
        ItemStack pick = d.pickaxe();
        if (pick == null || durabilityLeft(pick) <= 0) { d.setLastAccrual(now); return false; }
        int dimIdx = Math.min(d.dimension(), QuarrySkill.maxDimensionIndex(d));   // gate by Rift Drill
        QuarrySpec.Table dim = spec.dimension(dimIdx);
        if (dim == null || dim.totalWeight <= 0) { d.setLastAccrual(now); return false; }

        long elapsed = now - d.lastAccrual();
        int eff = enchant(pick, "efficiency"), fort = enchant(pick, "fortune"), unbr = enchant(pick, "unbreaking");
        long effCycle = Math.max(50, (long) (spec.cycleMillis() / ((1.0 + eff * 0.5) * QuarrySkill.miningSpeedMult(d))));
        long cyclesByTime = elapsed / effCycle;
        if (cyclesByTime <= 0) return false;

        double health = d.veinHealth(dimIdx);
        double surge = d.surgeActive(now) ? spec.surgeMult() : 1.0;
        double mult = (1.0 + fort * 0.4) * QuarrySkill.oreFortuneMult(d) * QuarrySkill.miningYieldMult(d)
                * QuarrySkill.prestigeMult(d) * surge * health;
        double yieldPerCycle = tableYield(dim, null) * mult;
        if (yieldPerCycle <= 0) { d.setLastAccrual(now); return false; }

        long room = Math.max(0, QuarrySkill.vaultCap(spec, d) - d.vaultMass());
        long cyclesByVault = (long) Math.floor(room / yieldPerCycle);
        int dpc = spec.miningDuraPerCycle();
        long cyclesByDura = dpc <= 0 ? Long.MAX_VALUE : (long) Math.floor(durabilityLeft(pick) * (1.0 + unbr) / dpc);
        long consumed = Math.min(cyclesByTime, Math.min(cyclesByVault, cyclesByDura));
        if (consumed <= 0) { d.setLastAccrual(now); return false; }

        long added = deposit(d, dim, consumed, mult, null);
        d.addMined(added);
        d.addRiftPoints(Math.round(consumed / 10.0 * QuarrySkill.rpGainMult(d)));
        if (spec.depletePerCycle() > 0)   // wear the vein down toward its floor
            d.setVeinHealth(dimIdx, Math.max(spec.veinFloor(), health - consumed * spec.depletePerCycle()));
        double wear = (d.mode() == QuarryData.Mode.BOTH) ? QuarrySkill.dualDriveWearMult(d) : 1.0;
        int drain = dpc <= 0 ? 0 : (int) Math.min(durabilityLeft(pick), Math.round(consumed * dpc * wear / (1.0 + unbr)));
        if (drain > 0) applyDamage(d, pick, drain, true);
        d.setLastAccrual(consumed < cyclesByTime ? now : d.lastAccrual() + consumed * effCycle);
        crackChests(d, spec, anchor, consumed);   // rare jackpot: a whole vanilla loot chest into the vault
        return true;
    }

    /**
     * Rare "loot chest" jackpot — over {@code consumed} mining cycles, roll the configured
     * per-cycle chance to crack a whole vanilla loot table (dungeon, mineshaft, buried treasure, …)
     * straight into the vault, exactly as if a dungeon chest had been mined. Main-thread only
     * (Bukkit loot API); guarded + capped so offline catch-up can't roll thousands.
     */
    private static void crackChests(QuarryData d, QuarrySpec spec, Location anchor, long consumed) {
        QuarrySpec.LootChestsConfig lc = spec.lootChests();
        if (lc == null || !lc.enabled || lc.chancePerCycle <= 0 || lc.totalWeight <= 0) return;
        if (anchor == null || anchor.getWorld() == null) return;

        double expected = consumed * lc.chancePerCycle;
        long pops = (long) Math.floor(expected);
        if (ThreadLocalRandom.current().nextDouble() < (expected - pops)) pops++;
        pops = Math.min(pops, lc.maxPerAccrual);
        for (long i = 0; i < pops; i++) crackOneChest(d, spec, anchor);
    }

    private static void crackOneChest(QuarryData d, QuarrySpec spec, Location anchor) {
        QuarrySpec.LootChestsConfig lc = spec.lootChests();
        // weighted pick of which table to crack
        double r = ThreadLocalRandom.current().nextDouble() * lc.totalWeight;
        QuarrySpec.ChestTable chosen = lc.tables.get(0);
        for (QuarrySpec.ChestTable t : lc.tables) { r -= t.weight; if (r <= 0) { chosen = t; break; } }
        try {
            LootTable table = Bukkit.getLootTable(chosen.key);
            if (table == null) return;
            LootContext ctx = new LootContext.Builder(anchor).build();
            Collection<ItemStack> loot = table.populateLoot(ThreadLocalRandom.current(), ctx);
            long added = 0;
            for (ItemStack it : loot) {
                if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
                ItemStack tmpl = it.clone(); tmpl.setAmount(1);
                d.addToVault(tmpl, it.getAmount());
                added += it.getAmount();
            }
            if (added > 0) {
                d.addMined(added);
                d.logEvent("✦ Cracked a loot chest (" + chosen.key.getKey() + ") — +" + added + " items");
            }
        } catch (Throwable ignored) {
            // some loot tables demand extra context (entity/luck); skip rather than break the sweep
        }
    }

    private static boolean fish(QuarryData d, QuarrySpec spec, long now) {
        ItemStack rod = d.rod();
        if (rod == null || durabilityLeft(rod) <= 0) { d.setLastFish(now); return false; }
        QuarrySpec.Table sea = spec.fishing();
        if (sea == null || sea.totalWeight <= 0) { d.setLastFish(now); return false; }

        long elapsed = now - d.lastFish();
        int lure = enchant(rod, "lure"), luck = enchant(rod, "luck_of_the_sea"), unbr = enchant(rod, "unbreaking");
        long effCycle = Math.max(50, (long) (spec.cycleMillis() / ((1.0 + lure * 0.5) * QuarrySkill.fishingSpeedMult(d))));
        long cyclesByTime = elapsed / effCycle;
        if (cyclesByTime <= 0) return false;

        Integer luckBoost = luck + QuarrySkill.treasureLuckBonus(d);   // Abyssal Luck adds effective LotS
        double mult = QuarrySkill.prestigeMult(d) * (d.surgeActive(now) ? spec.surgeMult() : 1.0);
        double yieldPerCycle = tableYield(sea, luckBoost) * mult;
        if (yieldPerCycle <= 0) { d.setLastFish(now); return false; }

        long room = Math.max(0, QuarrySkill.vaultCap(spec, d) - d.vaultMass());
        long cyclesByVault = (long) Math.floor(room / yieldPerCycle);
        int dpc = spec.fishingDuraPerCycle();
        long cyclesByDura = dpc <= 0 ? Long.MAX_VALUE : (long) Math.floor(durabilityLeft(rod) * (1.0 + unbr) / dpc);
        long consumed = Math.min(cyclesByTime, Math.min(cyclesByVault, cyclesByDura));
        if (consumed <= 0) { d.setLastFish(now); return false; }

        long added = deposit(d, sea, consumed, mult, luckBoost);
        d.addMined(added);
        d.addRiftPoints(Math.round(consumed / 12.0 * QuarrySkill.rpGainMult(d)));
        double wear = (d.mode() == QuarryData.Mode.BOTH) ? QuarrySkill.dualDriveWearMult(d) : 1.0;
        int drain = dpc <= 0 ? 0 : (int) Math.min(durabilityLeft(rod), Math.round(consumed * dpc * wear / (1.0 + unbr)));
        if (drain > 0) applyDamage(d, rod, drain, false);
        d.setLastFish(consumed < cyclesByTime ? now : d.lastFish() + consumed * effCycle);
        return true;
    }

    /** Treasure entries get heavier with Luck of the Sea (fishing only; luck == null = none). */
    private static double effWeight(QuarrySpec.LootEntry e, Integer luck) {
        return (luck != null && e.treasure) ? e.weight * (1.0 + luck * 0.6) : e.weight;
    }

    private static double tableYield(QuarrySpec.Table t, Integer luck) {
        double total = 0; for (QuarrySpec.LootEntry e : t.loot) total += effWeight(e, luck);
        if (total <= 0) return 0;
        double y = 0; for (QuarrySpec.LootEntry e : t.loot) y += (effWeight(e, luck) / total) * e.avg();
        return y;
    }

    private static long deposit(QuarryData d, QuarrySpec.Table t, long consumed, double mult, Integer luck) {
        double total = 0; for (QuarrySpec.LootEntry e : t.loot) total += effWeight(e, luck);
        if (total <= 0) return 0;
        long added = 0;
        for (QuarrySpec.LootEntry e : t.loot) {
            // Expected items this batch. Stochastic rounding: floor, then +1 with probability
            // equal to the leftover fraction. Without this, rare entries (per-batch EV < 1)
            // always round to 0 and only the heaviest entry ever lands. EV is preserved, and
            // large offline batches stay accurate (the fraction is negligible vs the whole).
            double exact = consumed * (effWeight(e, luck) / total) * e.avg() * mult;
            long whole = (long) Math.floor(exact);
            double frac = exact - whole;
            if (frac > 0 && ThreadLocalRandom.current().nextDouble() < frac) whole++;
            if (whole <= 0) continue;
            if (e.template.getType() == Material.ENCHANTED_BOOK) {
                // Treasure books roll a real random enchant each; identical rolls merge in the vault,
                // so distinct lines are bounded by the enchant pool. Cap the roll loop for offline sanity.
                long rolled = Math.min(whole, BOOK_ROLL_CAP);
                for (long i = 0; i < rolled; i++) d.addToVault(rollEnchantedBook(), 1);
                if (whole > rolled) d.addToVault(new ItemStack(Material.ENCHANTED_BOOK), whole - rolled);
            } else {
                ItemStack tmpl = e.template.clone(); tmpl.setAmount(1);
                d.addToVault(tmpl, whole);
            }
            added += whole;
        }
        return added;
    }

    private static final int BOOK_ROLL_CAP = 4000;   // max individually-rolled books per accrual

    /** Treasure-flavoured enchant pool for fished books (incl. book-only treasures like Mending). */
    private static final String[] BOOK_ENCHANTS = {
            "mending", "unbreaking", "fortune", "efficiency", "silk_touch", "looting", "sharpness",
            "smite", "bane_of_arthropods", "protection", "blast_protection", "projectile_protection",
            "fire_protection", "feather_falling", "respiration", "aqua_affinity", "depth_strider",
            "frost_walker", "soul_speed", "swift_sneak", "lure", "luck_of_the_sea", "power", "punch",
            "flame", "infinity", "loyalty", "channeling", "impaling", "multishot", "quick_charge",
            "piercing", "fire_aspect", "knockback", "thorns"
    };

    private static ItemStack rollEnchantedBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        Enchantment ench = null;
        for (int tries = 0; tries < 5 && ench == null; tries++) {
            String key = BOOK_ENCHANTS[ThreadLocalRandom.current().nextInt(BOOK_ENCHANTS.length)];
            ench = Enchantment.getByKey(NamespacedKey.minecraft(key));
        }
        if (ench == null) return book;   // unknown key on this version → blank fallback
        int level = 1 + ThreadLocalRandom.current().nextInt(Math.max(1, ench.getMaxLevel()));
        if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            meta.addStoredEnchant(ench, level, true);
            book.setItemMeta(meta);
        }
        return book;
    }

    private static int enchant(ItemStack item, String key) {
        if (item == null) return 0;
        Enchantment e = Enchantment.getByKey(NamespacedKey.minecraft(key));
        return e == null ? 0 : item.getEnchantmentLevel(e);
    }

    /** Apply durability; on break, auto-swap the next spare from the matching rack (Q5 fills racks). */
    private static void applyDamage(QuarryData d, ItemStack tool, int drain, boolean pick) {
        if (!(tool.getItemMeta() instanceof Damageable dmg)) return;
        int max = tool.getType().getMaxDurability();
        int next = dmg.getDamage() + drain;
        if (next >= max) {
            d.logEvent((pick ? "Pickaxe" : "Rod") + " broke");
            List<ItemStack> rack = pick ? d.pickRack() : d.rodRack();
            ItemStack repl = rack.isEmpty() ? null : rack.remove(0);
            if (pick) d.setPickaxe(repl); else d.setRod(repl);
            return;
        }
        dmg.setDamage(next);
        tool.setItemMeta((ItemMeta) dmg);
    }

    // --- shared helpers ------------------------------------------------------

    public static boolean isPickaxe(ItemStack item) {
        if (item == null) return false;
        return switch (item.getType()) {
            case WOODEN_PICKAXE, STONE_PICKAXE, IRON_PICKAXE, GOLDEN_PICKAXE, DIAMOND_PICKAXE, NETHERITE_PICKAXE -> true;
            default -> false;
        };
    }

    public static boolean isRod(ItemStack item) {
        return item != null && item.getType() == Material.FISHING_ROD;
    }

    public static int durabilityLeft(ItemStack item) {
        if (item == null) return 0;
        int max = item.getType().getMaxDurability();
        if (max <= 0) return 0;
        if (item.getItemMeta() instanceof Damageable dmg) return Math.max(0, max - dmg.getDamage());
        return max;
    }

    public static int durabilityMax(ItemStack item) {
        return item == null ? 0 : item.getType().getMaxDurability();
    }

    public static double durability01(ItemStack item) {
        int max = durabilityMax(item);
        return max <= 0 ? 0 : (double) durabilityLeft(item) / max;
    }
}
