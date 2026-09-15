package dev.servereer.machineconstruct.factorydistrict;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.RecipeChoice.MaterialChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Virtual Factory District mechanic (ADR 0013) — stateless helpers on a
 * {@link FactoryDistrictData}. Capture creatures into the roster, pay a farm's build cost
 * (blocks from inventory + vault, creatures from the roster), and accrue offline production
 * into the Quantum Vault (Grinder-style time clock).
 */
public final class FactoryDistrictManager {

    private FactoryDistrictManager() {}

    // --- creature capture ----------------------------------------------------

    /**
     * Vacuum living mobs within the capture radius into the roster (consumes them). Returns count.
     * <p>{@code allowed} gates each mob by location so a protection plugin (GriefPrevention,
     * WorldGuard, Towny, …) can veto vacuuming mobs out of claims the player can't build in.
     * Tamed/leashed/named mobs are always skipped (someone's pets, not fair game).
     */
    public static int capture(FactoryDistrictData d, FactoryDistrictSpec spec, Location anchor,
                              java.util.function.Predicate<Entity> allowed) {
        if (anchor == null || anchor.getWorld() == null) return 0;
        int r = spec.captureRadius();
        Location centre = anchor.clone().add(0.5, 0.5, 0.5);
        int captured = 0;
        for (Entity e : anchor.getWorld().getNearbyEntities(centre, r, r, r)) {
            if (!(e instanceof Mob mob)) continue;               // mobs only (excludes players, items, displays)
            if (isProtectedPet(mob)) continue;                   // tamed / leashed / named — leave them be
            if (allowed != null && !allowed.test(mob)) continue; // GriefPrevention / WorldGuard / Towny claim veto
            if (spec.rosterCap() > 0 && d.rosterTotal() >= spec.rosterCap()) break;
            d.addCreatures(mob.getType(), 1);
            mob.remove();
            captured++;
        }
        return captured;
    }

    /** A mob that clearly belongs to someone — never vacuum it. */
    private static boolean isProtectedPet(Mob mob) {
        if (mob.isLeashed() || mob.customName() != null) return true;
        return (mob instanceof org.bukkit.entity.Tameable t) && t.isTamed();
    }

    // --- size scaling (small..huge) -----------------------------------------

    /** Scaled block cost for a size: each base amount × the size's cost multiplier (rounded up). */
    public static List<ItemStack> blocksFor(FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack b : farm.blocks) {
            if (b == null) continue;
            ItemStack c = b.clone();
            c.setAmount((int) Math.max(1, Math.ceil(b.getAmount() * size.cost)));
            out.add(c);
        }
        return out;
    }

    /** Scaled creature cost for a size (rounded up, min 1 per required type). */
    public static Map<EntityType, Integer> creaturesFor(FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size) {
        Map<EntityType, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<EntityType, Integer> e : farm.creatures.entrySet())
            out.put(e.getKey(), (int) Math.max(1, Math.ceil(e.getValue() * size.creatures)));
        return out;
    }

    /** Scaled per-cycle rewards for a size (amount ranges scaled, chance kept). */
    public static List<FactoryDistrictSpec.Reward> outputFor(FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size) {
        List<FactoryDistrictSpec.Reward> out = new ArrayList<>();
        for (FactoryDistrictSpec.Reward r : farm.output) out.add(r.scaled(size.output));
        return out;
    }

    /** Scaled per-cycle inputs for a size (scale with production rate; rounded up, min 1). */
    public static List<ItemStack> inputsFor(FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack in : farm.inputs) {
            if (in == null) continue;
            ItemStack c = in.clone();
            c.setAmount((int) Math.max(1, Math.ceil(in.getAmount() * size.output)));
            out.add(c);
        }
        return out;
    }

    // --- build cost (blocks: inventory + vault; creatures: roster) -----------

    /** Scaled MMOItems build cost for a size. */
    public static List<FactoryDistrictSpec.MmoCost> mmoBlocksFor(FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size) {
        List<FactoryDistrictSpec.MmoCost> out = new ArrayList<>();
        for (FactoryDistrictSpec.MmoCost c : farm.mmoBlocks) out.add(c.scaled(size.cost));
        return out;
    }

    /** True if the player + vault cover the blocks + MMOItems AND the roster covers the creatures. */
    public static boolean affords(Player p, FactoryDistrictData d, FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size) {
        for (ItemStack req : blocksFor(farm, size)) {
            if (available(p, d, req) < req.getAmount()) return false;
        }
        for (FactoryDistrictSpec.MmoCost c : mmoBlocksFor(farm, size)) {
            if (mmoAvailable(p, d, c.type, c.id) < c.amount) return false;
        }
        for (Map.Entry<EntityType, Integer> c : creaturesFor(farm, size).entrySet()) {
            if (d.creatureCount(c.getKey()) < c.getValue()) return false;
        }
        return true;
    }

    /** Consume the build cost — blocks + MMOItems (inventory first, then vault) + creatures (roster). */
    public static void consume(Player p, FactoryDistrictData d, FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size) {
        for (ItemStack req : blocksFor(farm, size)) {
            int need = req.getAmount();
            need -= removeFromInv(p, req, need);
            if (need > 0) d.removeFromVault(req, need);
        }
        for (FactoryDistrictSpec.MmoCost c : mmoBlocksFor(farm, size)) {
            int need = c.amount;
            need -= mmoRemoveFromInv(p, c.type, c.id, need);
            if (need > 0) mmoRemoveFromVault(d, c.type, c.id, need);
        }
        for (Map.Entry<EntityType, Integer> c : creaturesFor(farm, size).entrySet()) {
            d.removeCreatures(c.getKey(), c.getValue());
        }
    }

    // --- MMOItems build-cost matching (by identity; falls back to similarity) ----

    public static long mmoAvailable(Player p, FactoryDistrictData d, String type, String id) {
        return mmoInvCount(p, type, id) + mmoVaultCount(d, type, id);
    }

    /** Consume one MMOItems cost — inventory first, then vault. */
    public static void consumeMmo(Player p, FactoryDistrictData d, FactoryDistrictSpec.MmoCost c) {
        int need = c.amount;
        need -= mmoRemoveFromInv(p, c.type, c.id, need);
        if (need > 0) mmoRemoveFromVault(d, c.type, c.id, need);
    }

    private static long mmoInvCount(Player p, String type, String id) {
        long n = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it != null && !it.getType().isAir() && MMOItemsBridge.matches(it, type, id)) n += it.getAmount();
        return n;
    }

    private static long mmoVaultCount(FactoryDistrictData d, String type, String id) {
        long n = 0;
        for (Map.Entry<ItemStack, Long> e : d.vault().entrySet())
            if (MMOItemsBridge.matches(e.getKey(), type, id)) n += e.getValue();
        return n;
    }

    private static int mmoRemoveFromInv(Player p, String type, String id, int need) {
        int removed = 0;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && removed < need; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || !MMOItemsBridge.matches(it, type, id)) continue;
            int take = Math.min(it.getAmount(), need - removed);
            it.setAmount(it.getAmount() - take);
            removed += take;
            if (it.getAmount() <= 0) contents[i] = null;
        }
        p.getInventory().setStorageContents(contents);
        return removed;
    }

    private static void mmoRemoveFromVault(FactoryDistrictData d, String type, String id, int need) {
        for (ItemStack key : new ArrayList<>(d.vault().keySet())) {
            if (need <= 0) break;
            if (!MMOItemsBridge.matches(key, type, id)) continue;
            need -= (int) d.removeFromVault(key, need);
        }
    }

    public static long available(Player p, FactoryDistrictData d, ItemStack req) {
        return invCount(p, req) + d.vaultCount(req);
    }

    /** Human-readable shortfall (blocks + creatures) at this size, or "" if affordable. */
    public static String missingText(Player p, FactoryDistrictData d, FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack req : blocksFor(farm, size)) {
            long have = available(p, d, req);
            if (have < req.getAmount()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(req.getAmount() - have).append("x ").append(pretty(req.getType().name()));
            }
        }
        for (FactoryDistrictSpec.MmoCost c : mmoBlocksFor(farm, size)) {
            long have = mmoAvailable(p, d, c.type, c.id);
            if (have < c.amount) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(c.amount - have).append("x ").append(pretty(c.id)).append(" (MMO)");
            }
        }
        for (Map.Entry<EntityType, Integer> c : creaturesFor(farm, size).entrySet()) {
            int have = d.creatureCount(c.getKey());
            if (have < c.getValue()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(c.getValue() - have).append("x ").append(pretty(c.getKey().name()));
            }
        }
        return sb.toString();
    }

    // --- offline production accrual (Grinder-style) --------------------------

    /**
     * Advance one farm's production clock and deposit outputs into the vault. Cycles are bounded
     * by elapsed time (clamped to the max-accrue cap), the vault's remaining room, and — if the
     * farm declares per-cycle inputs — the inputs available in the vault. Returns true if it produced.
     */
    private static final int COMMAND_BUDGET_PER_ACCRUE = 64;   // cap command runs on offline catch-up

    public static boolean accrue(FactoryDistrictSpec spec, FactoryDistrictSpec.Farm farm, FactoryDistrictSpec.Size size,
                                 FactoryDistrictData d, FactoryDistrictData.Slot slot, long now, long vaultCap,
                                 List<String> commandsOut) {
        if (farm == null) return false;
        if (!farm.recipes.isEmpty()) return accrueCraft(spec, farm, d, slot, now, vaultCap);   // recipe processor (ADR 0021)
        if (exhausted(farm, slot)) return false;                      // depleted → inert until demolished
        long cycleMs = farm.cycleSeconds * 1000L;
        if (slot.lastProduce <= 0) { slot.lastProduce = now; return false; }

        long elapsed = now - slot.lastProduce;
        long maxElapsed = spec.maxAccrueHours() > 0 ? (long) spec.maxAccrueHours() * 3_600_000L : Long.MAX_VALUE;
        if (elapsed > maxElapsed) { slot.lastProduce = now - maxElapsed; elapsed = maxElapsed; } // drop excess once
        if (elapsed < cycleMs) return false;

        List<FactoryDistrictSpec.Reward> rewards = outputFor(farm, size);
        List<ItemStack> inputs = inputsFor(farm, size);

        long cycles = elapsed / cycleMs;
        if (!inputs.isEmpty()) cycles = Math.min(cycles, cyclesFromInputs(d, inputs));
        if (farm.depletes()) cycles = Math.min(cycles, farm.depleteCycles - slot.cyclesProduced);   // can't out-produce the deposit
        if (cycles <= 0) return false;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int cmdBudget = COMMAND_BUDGET_PER_ACCRUE;
        // (own richness × worker-proficiency + fused-unit bonus) × district boosters. Soil applied per-cycle below.
        double mult = (slot.richness * proficiencyMult(farm, slot) + slot.mergeBonus) * outputBoost(spec, d);
        long done = 0;
        for (long c = 0; c < cycles; c++) {
            if (vaultCap > 0 && d.vaultMass() >= vaultCap) break;     // vault full → stop, hold the clock
            if (farm.hasHazard() && rng.nextDouble() < farm.hazardChance) {   // cave-in/blight → break, repair to resume
                slot.broken = true;
                break;
            }
            double cycleMult = mult * soilFactor(spec, farm, d, slot);  // reversible-soil yield modifier (farming)
            if (farm.outputRolls > 0) {                               // weighted-table mode: N picks by ratio
                for (int roll = 0; roll < farm.outputRolls; roll++) {
                    FactoryDistrictSpec.Reward r = weightedPick(rewards, rng);
                    if (r != null) cmdBudget = grant(d, slot, vaultCap, r, rng, commandsOut, cmdBudget, cycleMult);
                }
            } else {                                                  // independent-chance mode (per entry)
                for (FactoryDistrictSpec.Reward r : rewards) {
                    if (r.chance < 1.0 && rng.nextDouble() >= r.chance) continue;
                    cmdBudget = grant(d, slot, vaultCap, r, rng, commandsOut, cmdBudget, cycleMult);
                }
            }
            done++;
        }
        if (done > 0) {
            for (ItemStack in : inputs) d.removeFromVault(in, in.getAmount() * done);
            slot.lastProduce += done * cycleMs;
            slot.cyclesProduced += done;
        }
        return done > 0;
    }

    /** Recipe-processor accrual (ADR 0021): each cycle, run the selected recipe(s) from the vault. */
    private static boolean accrueCraft(FactoryDistrictSpec spec, FactoryDistrictSpec.Farm farm,
                                       FactoryDistrictData d, FactoryDistrictData.Slot slot, long now, long vaultCap) {
        long cycleMs = farm.cycleSeconds * 1000L;
        if (slot.lastProduce <= 0) { slot.lastProduce = now; return false; }
        long elapsed = now - slot.lastProduce;
        long maxElapsed = spec.maxAccrueHours() > 0 ? (long) spec.maxAccrueHours() * 3_600_000L : Long.MAX_VALUE;
        if (elapsed > maxElapsed) { slot.lastProduce = now - maxElapsed; elapsed = maxElapsed; }
        if (elapsed < cycleMs) return false;
        long cycles = elapsed / cycleMs;

        List<FactoryDistrictSpec.CraftRecipe> active = activeRecipes(farm, slot);
        if (active.isEmpty()) { slot.lastProduce = now; return false; }
        int runsPerRecipe = (farm.stackMode == FactoryDistrictSpec.StackMode.RATE) ? unitCount(slot) : 1;

        long done = 0;
        for (long c = 0; c < cycles; c++) {
            if (vaultCap > 0 && d.vaultMass() >= vaultCap) break;
            boolean any = false;
            for (FactoryDistrictSpec.CraftRecipe rec : active) {
                for (int run = 0; run < runsPerRecipe; run++) {
                    if (!hasInputs(d, rec)) break;
                    for (Map.Entry<Material, Integer> in : rec.inputs.entrySet())
                        d.removeFromVault(new ItemStack(in.getKey()), in.getValue());
                    for (Map.Entry<Material, Integer> out : rec.outputs.entrySet())
                        d.addToVault(new ItemStack(out.getKey()), out.getValue());
                    any = true;
                }
            }
            if (!any) break;     // ran out of inputs — hold the clock
            done++;
        }
        if (done > 0) { slot.lastProduce += done * cycleMs; slot.cyclesProduced += done; }
        return done > 0;
    }

    private static boolean hasInputs(FactoryDistrictData d, FactoryDistrictSpec.CraftRecipe rec) {
        for (Map.Entry<Material, Integer> in : rec.inputs.entrySet())
            if (d.vaultCount(new ItemStack(in.getKey())) < in.getValue()) return false;
        return true;
    }

    /** Resolve a crafter's active recipes: chosen ones (capped by stack mode), or auto-select if none chosen. */
    public static List<FactoryDistrictSpec.CraftRecipe> activeRecipes(FactoryDistrictSpec.Farm farm, FactoryDistrictData.Slot slot) {
        List<FactoryDistrictSpec.CraftRecipe> all = farm.recipes;
        if (all.isEmpty()) return java.util.List.of();
        int cap = (farm.stackMode == FactoryDistrictSpec.StackMode.MULTI) ? unitCount(slot) : 1;
        List<FactoryDistrictSpec.CraftRecipe> out = new java.util.ArrayList<>();
        if (slot.selectedRecipes != null && !slot.selectedRecipes.isEmpty()) {
            for (String id : slot.selectedRecipes) {
                if (out.size() >= cap) break;
                for (FactoryDistrictSpec.CraftRecipe r : all) if (r.id.equals(id)) { out.add(r); break; }
            }
        }
        if (out.isEmpty()) for (int i = 0; i < all.size() && out.size() < cap; i++) out.add(all.get(i)); // auto
        return out;
    }

    /** True if a depleting farm has reached its lifetime output. */
    public static boolean exhausted(FactoryDistrictSpec.Farm farm, FactoryDistrictData.Slot slot) {
        return farm.depletes() && slot.cyclesProduced >= farm.depleteCycles;
    }

    /** Grant one reward (item/mmo → vault, command → queued) + log it, scaled by {@code mult}. */
    private static int grant(FactoryDistrictData d, FactoryDistrictData.Slot slot, long vaultCap, FactoryDistrictSpec.Reward r,
                             ThreadLocalRandom rng, List<String> commandsOut, int cmdBudget, double mult) {
        int base = r.min >= r.max ? r.min : rng.nextInt(r.min, r.max + 1);
        int amt = (int) Math.round(base * mult);
        if (amt <= 0) return cmdBudget;
        switch (r.kind) {
            case ITEM -> { long stored = addCapped(d, vaultCap, r.item, amt); slot.logProduced(r.item.getType().name(), stored); }
            case MMOITEM -> {
                ItemStack mi = MMOItemsBridge.get(r.mmoType, r.mmoId);
                if (mi != null) { long stored = addCapped(d, vaultCap, mi, amt); slot.logProduced(r.mmoType + "/" + r.mmoId, stored); }
            }
            case COMMAND -> {
                if (cmdBudget > 0 && commandsOut != null) {
                    commandsOut.add(r.command.replace("%amount%", String.valueOf(amt)));
                    slot.logProduced("⚡ command", 1);
                    return cmdBudget - 1;
                }
            }
        }
        return cmdBudget;
    }

    private static FactoryDistrictSpec.Reward weightedPick(List<FactoryDistrictSpec.Reward> rewards, ThreadLocalRandom rng) {
        int total = 0;
        for (FactoryDistrictSpec.Reward r : rewards) total += r.weight;
        if (total <= 0) return null;
        int x = rng.nextInt(total);
        for (FactoryDistrictSpec.Reward r : rewards) { x -= r.weight; if (x < 0) return r; }
        return rewards.isEmpty() ? null : rewards.get(0);
    }

    /**
     * Machine-breeds-machine (ADR 0014): on its own interval, a generator farm rolls a chance to
     * spawn {@code count} child machines (weighted random from its outcomes) into empty usable
     * slots, bounded by its lifetime {@code max}. Returns the first spawned farm id, or null.
     */
    public static String generate(FactoryDistrictSpec spec, FactoryDistrictSpec.Farm farm, FactoryDistrictData d,
                                  FactoryDistrictData.Slot parent, long now, int usableSlots) {
        FactoryDistrictSpec.Generation gen = farm.gen;
        if (gen == null) return null;
        if (parent.lastGenerate <= 0) { parent.lastGenerate = now; return null; }   // arm the clock at build
        if (now - parent.lastGenerate < gen.everySeconds * 1000L) return null;
        parent.lastGenerate = now;                                  // advance regardless of outcome
        if (parent.offspring >= gen.max) return null;
        double chance = gen.workerScaled ? Math.min(1.0, gen.chance * Math.max(1, parent.workersAssigned)) : gen.chance;
        if (ThreadLocalRandom.current().nextDouble() >= chance) return null;

        String first = null;
        int toSpawn = Math.min(gen.count, gen.max - parent.offspring);
        for (int n = 0; n < toSpawn; n++) {
            FactoryDistrictData.Slot empty = firstEmptySlot(d, usableSlots);
            if (empty == null) break;                               // district full → stop
            String farmId = pickOutcome(gen.outcomes);
            if (farmId == null || spec.farm(farmId) == null) break;
            empty.factoryId = farmId;
            empty.sizeId = null;
            empty.lastProduce = now;
            empty.offspring = 0;
            empty.lastGenerate = now;
            empty.richness = rollRichness(spec.farm(farmId));   // a discovered deposit rolls its yield
            empty.cyclesProduced = 0;
            empty.workersAssigned = 0;
            empty.broken = false;
            empty.producedLog.clear();
            parent.offspring++;
            if (first == null) first = farmId;
        }
        return first;
    }

    /**
     * Population growth (ADR 0018, housing): on its interval, a breeder structure rolls a chance to
     * add {@code count} creatures of its type to the capture roster (worker-scaled if declared),
     * bounded by its lifetime {@code max} (tracked in {@code offspring}) and the district roster cap.
     * Returns the bred type, or null. Reuses the {@code lastGenerate}/{@code offspring} slot fields
     * (a breeder never also {@code generates:} machines).
     */
    public static EntityType breed(FactoryDistrictSpec spec, FactoryDistrictSpec.Farm farm, FactoryDistrictData d,
                                   FactoryDistrictData.Slot slot, long now) {
        FactoryDistrictSpec.Breeding b = farm.breeding;
        if (b == null) return null;
        if (slot.lastGenerate <= 0) { slot.lastGenerate = now; return null; }   // arm the clock at build
        if (now - slot.lastGenerate < b.everySeconds * 1000L) return null;
        slot.lastGenerate = now;                                                // advance regardless of outcome
        if (b.max > 0 && slot.offspring >= b.max) return null;                  // this structure's lifetime cap
        if (spec.rosterCap() > 0 && d.rosterTotal() >= spec.rosterCap()) return null;   // roster full
        double chance = b.workerScaled ? Math.min(1.0, b.chance * Math.max(1, slot.workersAssigned)) : b.chance;
        if (ThreadLocalRandom.current().nextDouble() >= chance) return null;

        int room = spec.rosterCap() > 0 ? spec.rosterCap() - d.rosterTotal() : Integer.MAX_VALUE;
        int n = Math.min(b.count, room);
        if (b.max > 0) n = Math.min(n, b.max - slot.offspring);
        if (n <= 0) return null;
        d.addCreatures(b.type, n);
        slot.offspring += n;
        return b.type;
    }

    /** Roll a farm's build/spawn richness multiplier (1.0 if it has no richness range). */
    public static double rollRichness(FactoryDistrictSpec.Farm farm) {
        if (farm == null || !farm.hasRichness()) return 1.0;
        return farm.richnessMin + ThreadLocalRandom.current().nextDouble() * (farm.richnessMax - farm.richnessMin);
    }

    private static FactoryDistrictData.Slot firstEmptySlot(FactoryDistrictData d, int usableSlots) {
        int limit = Math.min(usableSlots, d.slots().size());
        for (int i = 0; i < limit; i++) {
            FactoryDistrictData.Slot s = d.slots().get(i);
            if (!s.isBuilt()) return s;
        }
        return null;
    }

    private static String pickOutcome(List<FactoryDistrictSpec.Outcome> outcomes) {
        int total = 0;
        for (FactoryDistrictSpec.Outcome o : outcomes) total += o.weight;
        if (total <= 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (FactoryDistrictSpec.Outcome o : outcomes) { r -= o.weight; if (r < 0) return o.farmId; }
        return outcomes.get(0).farmId;
    }

    // --- colony layer: workers, prerequisites, build caps (ADR 0015) --------

    /**
     * Total worker capacity: Σ (provides-workers × (1 + level)) over houses, then multiplied by any
     * civic worker-capacity boosters (ADR 0018, e.g. a Town Hall ×1.15 — stacks).
     */
    public static int workerCapacity(FactoryDistrictSpec spec, FactoryDistrictData d) {
        int cap = 0;
        double mult = 1.0;
        for (FactoryDistrictData.Slot s : d.slots()) {
            if (!s.isBuilt()) continue;
            FactoryDistrictSpec.Farm f = spec.farm(s.factoryId);
            if (f == null) continue;
            int units = unitCount(s);   // merged (★) houses/boosters count as that many (ADR 0020)
            if (f.providesWorkers > 0) cap += (f.providesWorkers + Math.max(0, s.level) * f.workersPerLevel) * units;
            if (f.boostsWorkers()) mult *= Math.pow(f.boostsWorkers, units);
        }
        return (int) Math.round(cap * mult);
    }

    /** Workers currently employed across all built machines. */
    public static int workersAssigned(FactoryDistrictData d) {
        int n = 0;
        for (FactoryDistrictData.Slot s : d.slots()) if (s.isBuilt()) n += Math.max(0, s.workersAssigned);
        return n;
    }

    /**
     * Workers actually available to employ. In capacity mode (default) this is the raw housing
     * capacity — houses provide instant workers. In population mode (ADR 0022, opt-in
     * {@code population-workers: true}) it's the number of villagers <em>housed</em> as labor (a house
     * fills with villagers from the roster; housed villagers ARE the workers).
     */
    public static int employableWorkers(FactoryDistrictSpec spec, FactoryDistrictData d) {
        int housing = workerCapacity(spec, d);
        if (!spec.populationWorkers()) return housing;
        return Math.min(d.workerPopulation(), housing);
    }

    public static int workersFree(FactoryDistrictSpec spec, FactoryDistrictData d) {
        return Math.max(0, employableWorkers(spec, d) - workersAssigned(d));
    }

    /**
     * Population mode (ADR 0022): move villagers from the roster INTO houses, and out again if homes
     * were lost. Housed villagers become labor ({@code workerPopulation}); free villagers stay in the
     * roster (still spendable on builds). Call each district tick. Returns the net change (0 = none).
     * No-op outside population mode. A Breeder is just any structure that breeds villagers into the
     * roster ({@code breeds: { type: villager }}); houses drain that roster up to their capacity.
     */
    public static int houseVillagers(FactoryDistrictSpec spec, FactoryDistrictData d) {
        if (!spec.populationWorkers()) return 0;
        int housing = workerCapacity(spec, d);
        int pop = d.workerPopulation();
        if (pop < housing) {                                   // free homes → take villagers from the roster
            int move = Math.min(housing - pop, d.creatureCount(EntityType.VILLAGER));
            if (move > 0) { d.removeCreatures(EntityType.VILLAGER, move); d.addWorkerPopulation(move); return move; }
        } else if (pop > housing) {                            // lost homes → evict villagers back to the roster
            int evict = pop - housing;
            d.setWorkerPopulation(housing); d.addCreatures(EntityType.VILLAGER, evict); return -evict;
        }
        return 0;
    }

    // --- vault-sourced vanilla crafting (ADR 0021) --------------------------

    /** A vanilla recipe the vault can currently afford, with its per-craft cost + how many it affords. */
    public static final class CraftOption {
        public final ItemStack result;            // result stack (amount = per-craft yield)
        public final Map<Material, Integer> need; // material → qty consumed per craft
        public final int maxCrafts;               // how many crafts the current vault stock allows
        CraftOption(ItemStack result, Map<Material, Integer> need, int maxCrafts) {
            this.result = result; this.need = need; this.maxCrafts = maxCrafts;
        }
    }

    /** Tally the vault's plain (no-meta, fungible) items by material — the craftable stock. */
    private static Map<Material, Long> vaultStock(FactoryDistrictData d) {
        Map<Material, Long> stock = new HashMap<>();
        for (Map.Entry<ItemStack, Long> e : d.vault().entrySet()) {
            ItemStack k = e.getKey();
            if (k == null || k.getType() == Material.AIR || k.hasItemMeta()) continue;  // only plain items craft
            stock.merge(k.getType(), e.getValue(), Long::sum);
        }
        return stock;
    }

    /** Per-craft material cost of a shaped/shapeless recipe, resolving each choice against vault stock; null if unaffordable. */
    private static Map<Material, Integer> computeNeed(Recipe r, Map<Material, Long> stock) {
        List<RecipeChoice> choices = new ArrayList<>();
        if (r instanceof ShapedRecipe sr) {
            Map<Character, RecipeChoice> cm = sr.getChoiceMap();
            for (String row : sr.getShape()) for (char c : row.toCharArray()) {
                if (c == ' ') continue;
                RecipeChoice rc = cm.get(c);
                if (rc != null) choices.add(rc);
            }
        } else if (r instanceof ShapelessRecipe sl) {
            choices.addAll(sl.getChoiceList());
        } else return null;
        Map<Material, Integer> need = new HashMap<>();
        for (RecipeChoice rc : choices) {
            Material m = pickMaterial(rc, stock, need);
            if (m == null) return null;          // a slot can't be satisfied from the vault
            need.merge(m, 1, Integer::sum);
        }
        return need.isEmpty() ? null : need;
    }

    /** Pick the vault material in a choice with the most still-available stock (after already-tallied need); null if none. */
    private static Material pickMaterial(RecipeChoice rc, Map<Material, Long> stock, Map<Material, Integer> need) {
        List<Material> options = new ArrayList<>();
        if (rc instanceof MaterialChoice mc) options.addAll(mc.getChoices());
        else if (rc.getItemStack() != null) options.add(rc.getItemStack().getType());
        Material best = null; long bestRemain = 0;
        for (Material m : options) {
            long remain = stock.getOrDefault(m, 0L) - need.getOrDefault(m, 0);
            if (remain > bestRemain) { bestRemain = remain; best = m; }
        }
        return best;
    }

    private static int maxCrafts(Map<Material, Integer> need, Map<Material, Long> stock) {
        int max = Integer.MAX_VALUE;
        for (Map.Entry<Material, Integer> e : need.entrySet()) {
            long have = stock.getOrDefault(e.getKey(), 0L);
            max = (int) Math.min(max, have / e.getValue());
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    /** Every vanilla shaped/shapeless recipe the vault can currently afford (deduped by result, sorted by name). */
    public static List<CraftOption> craftableFromVault(FactoryDistrictData d) {
        Map<Material, Long> stock = vaultStock(d);
        List<CraftOption> out = new ArrayList<>();
        if (stock.isEmpty()) return out;
        Set<Material> seen = new HashSet<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r;
            try { r = it.next(); } catch (Throwable t) { continue; }
            if (!(r instanceof ShapedRecipe || r instanceof ShapelessRecipe)) continue;
            ItemStack res = r.getResult();
            if (res == null || res.getType() == Material.AIR) continue;
            if (seen.contains(res.getType())) continue;
            Map<Material, Integer> need = computeNeed(r, stock);
            if (need == null) continue;
            int max = maxCrafts(need, stock);
            if (max <= 0) continue;
            seen.add(res.getType());
            out.add(new CraftOption(res.clone(), need, max));
        }
        out.sort((a, b) -> pretty(a.result.getType().name()).compareToIgnoreCase(pretty(b.result.getType().name())));
        return out;
    }

    /**
     * Craft a vanilla recipe straight from the vault: consume the inputs, deposit the result into the
     * vault, and spill whatever the vault can't hold to {@code overflow} (the player). Recomputes
     * affordability live and clamps {@code times} to what the vault <em>stock</em> allows — a full
     * vault no longer blocks the craft (a mass-increasing recipe like 1 log → 4 planks just overflows
     * to the player instead of silently failing). Crafting stops early once {@code overflow} reports it
     * can take no more. Returns the number of crafts actually performed (0 = no stock for even one).
     */
    public static int craftFromVault(FactoryDistrictData d, long vaultCap, Material resultMat, int times,
                                     java.util.function.Predicate<ItemStack> overflow) {
        Map<Material, Long> stock = vaultStock(d);
        Recipe chosen = null; Map<Material, Integer> need = null;
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r;
            try { r = it.next(); } catch (Throwable t) { continue; }
            if (!(r instanceof ShapedRecipe || r instanceof ShapelessRecipe)) continue;
            ItemStack res = r.getResult();
            if (res == null || res.getType() != resultMat) continue;
            Map<Material, Integer> nd = computeNeed(r, stock);
            if (nd == null) continue;
            chosen = r; need = nd; break;
        }
        if (chosen == null) return 0;
        ItemStack result = chosen.getResult();
        int max = maxCrafts(need, stock);
        if (max <= 0) return 0;
        times = Math.min(times, max);
        if (times <= 0) return 0;
        int done = 0;
        for (int i = 0; i < times; i++) {
            for (Map.Entry<Material, Integer> e : need.entrySet())
                d.removeFromVault(new ItemStack(e.getKey()), e.getValue());   // inputs leave the vault (frees mass)
            long produced = result.getAmount();
            long stored = addCapped(d, vaultCap, result, produced);           // as much as fits stays in the vault
            done++;
            long leftover = produced - stored;
            if (leftover > 0) {                                              // vault full → spill to the player
                ItemStack over = result.clone();
                over.setAmount((int) leftover);
                if (overflow == null || !overflow.test(over)) break;          // player can't take more → stop
            }
        }
        return done;
    }

    /** How many built slots run the given farm id. */
    public static int countOf(FactoryDistrictData d, String farmId) {
        int n = 0;
        for (FactoryDistrictData.Slot s : d.slots()) if (s.isBuilt() && farmId.equalsIgnoreCase(s.factoryId)) n++;
        return n;
    }

    /** Effective build cap for a farm: base max-count + outpost bonuses (Integer.MAX_VALUE = unlimited). */
    public static int effectiveMaxCount(FactoryDistrictSpec spec, FactoryDistrictData d, FactoryDistrictSpec.Farm farm) {
        if (farm.maxCount <= 0) return Integer.MAX_VALUE;
        int cap = farm.maxCount;
        for (FactoryDistrictData.Slot s : d.slots()) {
            if (!s.isBuilt()) continue;
            FactoryDistrictSpec.Farm f = spec.farm(s.factoryId);
            if (f != null && farm.id.equalsIgnoreCase(f.raisesMaxFarm)) cap += f.raisesMaxBy;
        }
        return cap;
    }

    /** True if the slot has enough workers assigned to run. */
    public static boolean staffed(FactoryDistrictSpec.Farm farm, FactoryDistrictData.Slot slot) {
        return farm.workers <= 0 || slot.workersAssigned >= farm.workers;
    }

    /** True if every {@code requires} farm has a built, staffed instance in the district. */
    public static boolean requiresMet(FactoryDistrictSpec spec, FactoryDistrictData d, FactoryDistrictSpec.Farm farm) {
        if (farm.requires == null || farm.requires.isEmpty()) return true;
        for (String req : farm.requires) {
            boolean ok = false;
            for (FactoryDistrictData.Slot s : d.slots()) {
                if (!s.isBuilt() || !req.equalsIgnoreCase(s.factoryId)) continue;
                FactoryDistrictSpec.Farm rf = spec.farm(s.factoryId);
                if (rf != null && staffed(rf, s)) { ok = true; break; }
            }
            if (!ok) return false;
        }
        return true;
    }

    /** A built machine operates (produces/generates) only when staffed, prereq-met, and not broken. */
    public static boolean operational(FactoryDistrictSpec spec, FactoryDistrictData d, FactoryDistrictData.Slot slot, FactoryDistrictSpec.Farm farm) {
        return !slot.broken && staffed(farm, slot) && requiresMet(spec, d, farm);
    }

    /** Worker proficiency tier (0..maxTier) derived from cycles produced. */
    public static int workerTier(FactoryDistrictSpec.Farm farm, FactoryDistrictData.Slot slot) {
        if (!farm.hasProficiency()) return 0;
        return (int) Math.min(farm.wpMaxTier, slot.cyclesProduced / farm.wpCyclesPerTier);
    }

    /** Output multiplier from worker proficiency: 1 + tier × bonus. */
    public static double proficiencyMult(FactoryDistrictSpec.Farm farm, FactoryDistrictData.Slot slot) {
        return farm.hasProficiency() ? 1.0 + workerTier(farm, slot) * farm.wpBonusPerTier : 1.0;
    }

    /**
     * Advance one cycle of the reversible-soil meter (ADR 0017) and return this cycle's yield multiplier.
     * Regen ("legume/fallow") farms add nutrients toward their cap and produce at full. Consuming farms
     * drain nutrients each cycle; at empty they auto-pull fertilizer from the vault if available, and
     * otherwise crater to {@code soilDepletedMult} until restored. Farms without a soil section are 1.0.
     */
    public static double soilFactor(FactoryDistrictSpec spec, FactoryDistrictSpec.Farm farm,
                                    FactoryDistrictData d, FactoryDistrictData.Slot slot) {
        if (!farm.hasSoil()) return 1.0;
        if (farm.regensSoil()) {                                  // clover/fallow: restore the slot's soil
            slot.soil = Math.min(farm.soilStart > 0 ? farm.soilStart : Double.MAX_VALUE, slot.soil + farm.soilRegenPerCycle);
            return 1.0;
        }
        slot.soil -= farm.soilPerCycle;
        if (slot.soil <= 0 && farm.hasFertilizer()
                && d.vaultCount(farm.fertilizerItem) >= farm.fertilizerItem.getAmount()) {   // auto-fertilize from vault
            d.removeFromVault(farm.fertilizerItem, farm.fertilizerItem.getAmount());
            slot.soil += farm.fertilizerRestores;
        }
        if (slot.soil <= 0) { slot.soil = 0; return farm.soilDepletedMult; }   // exhausted → reduced yield until restored
        return 1.0;
    }

    /** District-wide output multiplier from built+staffed booster buildings (apiary, ADR 0017). */
    public static double outputBoost(FactoryDistrictSpec spec, FactoryDistrictData d) {
        double m = 1.0;
        for (FactoryDistrictData.Slot s : d.slots()) {
            if (!s.isBuilt() || s.broken) continue;
            FactoryDistrictSpec.Farm f = spec.farm(s.factoryId);
            if (f != null && f.boostsOutput() && staffed(f, s)) m *= Math.pow(f.boostsOutput, unitCount(s));
        }
        return m;
    }

    // --- merging (★, ADR 0020) ----------------------------------------------

    /** Fused units in a slot (1 = unmerged). */
    public static int unitCount(FactoryDistrictData.Slot slot) { return slot.mergeStars + 1; }

    /** A slot's total output contribution (own richness×proficiency + absorbed bonus), pre-district & pre-soil. */
    public static double contribution(FactoryDistrictSpec.Farm farm, FactoryDistrictData.Slot slot) {
        return slot.richness * proficiencyMult(farm, slot) + slot.mergeBonus;
    }

    /**
     * Fuse {@code absorbed} into {@code survivor} (must be the same farm id, both built & healthy):
     * survivor gains the absorbed unit's whole yield + a ★, and the absorbed slot is cleared (its
     * workers return to the pool). Returns false if not mergeable.
     */
    public static boolean merge(FactoryDistrictData.Slot survivor, FactoryDistrictData.Slot absorbed,
                                FactoryDistrictSpec.Farm farm) {
        if (survivor == null || absorbed == null || survivor == absorbed) return false;
        if (!survivor.isBuilt() || !absorbed.isBuilt()) return false;
        if (!survivor.factoryId.equals(absorbed.factoryId)) return false;
        if (survivor.broken || absorbed.broken) return false;
        survivor.mergeBonus += contribution(farm, absorbed);   // absorb its full yield
        survivor.mergeStars += unitCount(absorbed);            // + however many units it already was
        clearSlot(absorbed);                                   // free the slot (workers freed via workersAssigned=0)
        return true;
    }

    /** Reset a slot to empty/unbuilt. */
    public static void clearSlot(FactoryDistrictData.Slot s) {
        s.factoryId = null; s.sizeId = null; s.workersAssigned = 0; s.level = 0;
        s.cyclesProduced = 0; s.richness = 1.0; s.soil = 0; s.broken = false;
        s.offspring = 0; s.lastGenerate = 0L; s.lastProduce = 0L;
        s.mergeStars = 0; s.mergeBonus = 0;
        s.producedLog.clear();
    }

    /** Whether a convert-upgrade's gates (produced + buildings) are satisfied. */
    public static boolean upgradeUnlocked(FactoryDistrictSpec spec, FactoryDistrictData d, FactoryDistrictData.Slot slot, FactoryDistrictSpec.Upgrade up) {
        for (Map.Entry<String, Long> req : up.requiresProduced.entrySet())
            if (slot.producedLog.getOrDefault(req.getKey(), 0L) < req.getValue()) return false;
        for (String b : up.requiresBuilding)
            if (countOf(d, b) <= 0) return false;
        return true;
    }

    // INPUT chests (chest → vault feeding) now route through the shared
    // dev.servereer.machineconstruct.grinder.ChestRouter#routeInputs, used by the grinder pool and
    // collector vault too, so all three machines share one item-pull path.

    private static long addCapped(FactoryDistrictData d, long cap, ItemStack item, long amount) {
        if (cap <= 0) { d.addToVault(item, amount); return amount; }
        long room = cap - d.vaultMass();
        if (room <= 0) return 0;
        long store = Math.min(room, amount);
        if (store > 0) d.addToVault(item, store);
        return store;
    }

    /** How many full cycles the vault's stock of the farm's inputs can sustain. */
    private static long cyclesFromInputs(FactoryDistrictData d, List<ItemStack> inputs) {
        long min = Long.MAX_VALUE;
        for (ItemStack in : inputs) {
            if (in == null || in.getAmount() <= 0) continue;
            min = Math.min(min, d.vaultCount(in) / in.getAmount());
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    private static long outputMass(List<ItemStack> output) {
        long m = 0;
        for (ItemStack o : output) if (o != null) m += o.getAmount();
        return m;
    }

    // --- inventory helpers ---------------------------------------------------

    private static long invCount(Player p, ItemStack like) {
        long n = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it != null && it.isSimilar(single(like))) n += it.getAmount();
        return n;
    }

    private static int removeFromInv(Player p, ItemStack like, int need) {
        int removed = 0;
        ItemStack[] contents = p.getInventory().getStorageContents();
        ItemStack key = single(like);
        for (int i = 0; i < contents.length && removed < need; i++) {
            ItemStack it = contents[i];
            if (it == null || !it.isSimilar(key)) continue;
            int take = Math.min(it.getAmount(), need - removed);
            it.setAmount(it.getAmount() - take);
            removed += take;
            if (it.getAmount() <= 0) contents[i] = null;
        }
        p.getInventory().setStorageContents(contents);
        return removed;
    }

    private static ItemStack single(ItemStack s) { ItemStack c = s.clone(); c.setAmount(1); return c; }

    /** OAK_PLANKS / ZOMBIE -> "Oak Planks" / "Zombie". */
    public static String pretty(String enumName) {
        String[] w = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : w) if (!s.isEmpty()) sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        return sb.toString().trim();
    }
}
