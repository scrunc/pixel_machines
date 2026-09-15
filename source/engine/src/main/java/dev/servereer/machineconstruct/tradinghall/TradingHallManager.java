package dev.servereer.machineconstruct.tradinghall;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Virtual Trading Hall mechanic (ADR 0011) — stateless helpers operating on a
 * {@link TradingHallData}. Capture villagers, pay station block-recipes from the
 * player's inventory + the hall vault, roll a profession's trades from config pools,
 * and bridge to Bukkit's native virtual {@link Merchant} for real trading.
 */
public final class TradingHallManager {

    private TradingHallManager() {}

    /** Villager-XP thresholds for levels 1..5 (Novice..Master), like vanilla. */
    private static final int[] LEVEL_XP = { 0, 10, 70, 150, 250 };

    // --- capture -------------------------------------------------------------

    /**
     * Capture ONE specific villager into the roster (shift-right-click flow). Returns true if it was
     * consumed. A regular {@link Villager} only — WanderingTrader is a separate class, excluded.
     */
    public static boolean captureOne(TradingHallData d, TradingHallSpec spec, Villager v) {
        if (v == null || v.isDead()) return false;
        if (spec.rosterCap() > 0 && d.blankVillagers() >= spec.rosterCap()) return false;
        v.remove();
        d.addBlankVillagers(1);
        return true;
    }

    // --- station block-recipe payment (inventory + vault) --------------------

    /** True if the player's inventory + the hall vault together cover the whole recipe. */
    public static boolean affords(Player p, TradingHallData d, List<ItemStack> recipe) {
        for (ItemStack req : recipe) {
            if (req == null) continue;
            if (invCount(p, req) + d.vaultCount(req) < req.getAmount()) return false;
        }
        return true;
    }

    /** Consume the recipe — inventory first, remainder from the vault. Call only after {@link #affords}. */
    public static void consume(Player p, TradingHallData d, List<ItemStack> recipe) {
        for (ItemStack req : recipe) {
            if (req == null) continue;
            int need = req.getAmount();
            need -= removeFromInv(p, req, need);
            if (need > 0) d.removeFromVault(req, need);
        }
    }

    /** How many of {@code req}'s item the player + vault have together. */
    public static long available(Player p, TradingHallData d, ItemStack req) {
        return invCount(p, req) + d.vaultCount(req);
    }

    /** Human-readable shortfall list (e.g. "150x Oak Planks, 1x Bed"), or "" if affordable. */
    public static String missingText(Player p, TradingHallData d, List<ItemStack> recipe) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack req : recipe) {
            if (req == null) continue;
            long have = available(p, d, req);
            if (have < req.getAmount()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(req.getAmount() - have).append("x ").append(pretty(req.getType()));
            }
        }
        return sb.toString();
    }

    /** OAK_PLANKS -> "Oak Planks". */
    public static String pretty(org.bukkit.Material mat) {
        String[] w = mat.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : w) sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        return sb.toString().trim();
    }

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

    // --- trade rolling -------------------------------------------------------

    /** Roll the trader's offers from its profession's pools, for every level it has reached. */
    public static void rollTrades(TradingHallSpec.Profession prof, TradingHallData.Trader trader) {
        trader.offers.clear();
        for (int lvl = 1; lvl <= trader.level; lvl++) {
            List<TradingHallSpec.Offer> pool = prof.pools.get(lvl);
            if (pool == null || pool.isEmpty()) continue;
            for (TradingHallSpec.Offer o : pick(pool, prof.rollPerLevel))
                trader.offers.add(materialize(o, lvl));
        }
    }

    /** All enchant keys a librarian/gear trade may roll (vanilla-tradeable set). */
    private static final String[] ENCHANTS = {
            "protection","fire_protection","blast_protection","projectile_protection","feather_falling",
            "respiration","aqua_affinity","thorns","depth_strider","frost_walker","soul_speed","swift_sneak",
            "sharpness","smite","bane_of_arthropods","knockback","fire_aspect","looting","sweeping_edge",
            "efficiency","silk_touch","unbreaking","fortune","power","punch","flame","infinity",
            "multishot","piercing","quick_charge","loyalty","impaling","riptide","channeling",
            "luck_of_the_sea","lure","mending" };

    /** Build a live trade from a template — resolving a random price (buyMin..buyMax) + random enchant. */
    public static TradingHallData.LiveOffer materialize(TradingHallSpec.Offer o, int level) {
        TradingHallData.LiveOffer l = TradingHallData.LiveOffer.from(o, level);
        if (o.buyMax > o.buyMin && l.buyA != null)
            l.buyA.setAmount(ThreadLocalRandom.current().nextInt(o.buyMin, o.buyMax + 1));
        if (o.randomEnchant && l.sell != null) applyRandomEnchant(l.sell);
        return l;
    }

    /** Apply a random enchant: stored on a book; 1–2 random applicable enchants on gear. */
    private static void applyRandomEnchant(ItemStack item) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (item.getType() == Material.ENCHANTED_BOOK && item.getItemMeta() instanceof EnchantmentStorageMeta esm) {
            Enchantment e = randomEnchant(null);
            if (e != null) {
                esm.addStoredEnchant(e, 1 + rnd.nextInt(Math.max(1, e.getMaxLevel())), true);
                item.setItemMeta(esm);
            }
        } else {
            int count = 1 + rnd.nextInt(2);
            for (int i = 0; i < count; i++) {
                Enchantment e = randomEnchant(item);
                if (e != null) item.addUnsafeEnchantment(e, 1 + rnd.nextInt(Math.max(1, e.getMaxLevel())));
            }
        }
    }

    private static Enchantment randomEnchant(ItemStack applicableTo) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int tries = 0; tries < 12; tries++) {
            Enchantment e = resolveEnch(ENCHANTS[rnd.nextInt(ENCHANTS.length)]);
            if (e == null) continue;
            if (applicableTo == null || e.canEnchantItem(applicableTo)) return e;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Enchantment resolveEnch(String name) {
        try { return Enchantment.getByKey(NamespacedKey.minecraft(name)); } catch (Throwable t) { return null; }
    }

    /**
     * Re-roll a single offer to another from the profession's WHOLE pool (every tier), so any
     * vanilla trade — including Master-tier books like Mending — is reachable from any slot.
     * Per-trade, no lock.
     */
    public static boolean rerollOffer(TradingHallSpec.Profession prof, TradingHallData.Trader trader, int index) {
        if (index < 0 || index >= trader.offers.size()) return false;
        if (trader.offers.get(index).locked) return false;   // traded → locked until a full stall reset
        List<TradingHallSpec.Offer> flat = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (Map.Entry<Integer, List<TradingHallSpec.Offer>> e : prof.pools.entrySet()) {
            for (TradingHallSpec.Offer o : e.getValue()) { flat.add(o); levels.add(e.getKey()); }
        }
        if (flat.isEmpty()) return false;
        int i = ThreadLocalRandom.current().nextInt(flat.size());
        trader.offers.set(index, materialize(flat.get(i), levels.get(i)));
        return true;
    }

    /** Append the offers a newly-reached level unlocks (on level-up from trading). */
    private static void unlockLevel(TradingHallSpec.Profession prof, TradingHallData.Trader trader, int lvl) {
        List<TradingHallSpec.Offer> pool = prof.pools.get(lvl);
        if (pool == null || pool.isEmpty()) return;
        for (TradingHallSpec.Offer o : pick(pool, prof.rollPerLevel))
            trader.offers.add(materialize(o, lvl));
    }

    private static List<TradingHallSpec.Offer> pick(List<TradingHallSpec.Offer> pool, int n) {
        if (n <= 0 || n >= pool.size()) return pool;
        List<TradingHallSpec.Offer> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, ThreadLocalRandom.current());
        return copy.subList(0, n);
    }

    // --- direct trade execution (no native merchant) ------------------------

    /**
     * Execute a trade directly: pay ingredients from the <b>vault first, then the player's
     * inventory</b>, give the result to the player, bump uses + XP, and <b>lock that slot</b>
     * (no more re-roll until a full stall reset). {@code bulk} repeats while affordable and in
     * stock. Levels the trader up on enough XP. Returns the number of trades completed.
     */
    public static int trade(TradingHallSpec.Profession prof, TradingHallData d,
                            TradingHallData.Trader trader, int index, Player p, boolean bulk, long vaultCap) {
        if (index < 0 || index >= trader.offers.size()) return 0;
        TradingHallData.LiveOffer o = trader.offers.get(index);
        int maxUses = trader.effectiveMaxUses(o);
        int done = 0;
        int limit = bulk ? Math.max(0, maxUses - o.uses) : 1;
        for (int n = 0; n < limit; n++) {
            if (o.uses >= maxUses) break;                                    // sold out → wait for restock
            if (available(p, d, o.buyA) < o.buyA.getAmount()) break;
            if (o.buyB != null && available(p, d, o.buyB) < o.buyB.getAmount()) break;
            consumePreferVault(p, d, o.buyA);
            if (o.buyB != null) consumePreferVault(p, d, o.buyB);
            ItemStack result = o.sell.clone();
            if (d.outputToVault()) {                                         // vault mode: vault → (full) inv → drop
                long stored = addCapped(d, vaultCap, result, result.getAmount());
                if (stored < result.getAmount()) giveOrDrop(p, result, (int) (result.getAmount() - stored));
            } else {                                                          // inv mode: inv → (full) vault → drop
                for (ItemStack lo : p.getInventory().addItem(result).values()) {
                    long stored = addCapped(d, vaultCap, lo, lo.getAmount());
                    if (stored < lo.getAmount()) giveOrDrop(p, lo, (int) (lo.getAmount() - stored));
                }
            }
            o.uses++;
            trader.xp += Math.max(0, o.villagerXp);
            done++;
        }
        if (done > 0) {
            o.locked = true;                                                 // traded → locked until full reset
            int target = levelFor(trader.xp, prof.maxLevel());
            while (trader.level < target) { trader.level++; unlockLevel(prof, trader, trader.level); }
        }
        return done;
    }

    /**
     * Shopfront customer trade (ADR 0012). A non-owner pays ingredients from <b>their own
     * inventory only</b> (never the hall vault), the result is conjured into their inventory,
     * and the ingredient they paid flows into the owner's <b>earnings till</b> (not the vault).
     * The trade is <b>refused if their inventory can't hold the result</b> (customers have no
     * vault to overflow into). Bumps uses + XP and locks the slot exactly like a manual trade.
     * Returns the number of trades completed.
     */
    public static int customerTrade(TradingHallSpec.Profession prof, TradingHallData d,
                                    TradingHallData.Trader trader, int index, Player p, boolean bulk) {
        if (index < 0 || index >= trader.offers.size()) return 0;
        TradingHallData.LiveOffer o = trader.offers.get(index);
        int maxUses = trader.effectiveMaxUses(o);
        int done = 0;
        int limit = bulk ? Math.max(0, maxUses - o.uses) : 1;
        for (int n = 0; n < limit; n++) {
            if (o.uses >= maxUses) break;                                    // sold out → wait for restock
            if (invCount(p, o.buyA) < o.buyA.getAmount()) break;            // customer pays from inventory only
            if (o.buyB != null && invCount(p, o.buyB) < o.buyB.getAmount()) break;
            if (!canFit(p, o.sell)) break;                                   // no room → refuse (no drop, no vault)
            removeFromInv(p, o.buyA, o.buyA.getAmount());
            d.addToTill(o.buyA, o.buyA.getAmount());                         // payment → owner's till
            if (o.buyB != null) { removeFromInv(p, o.buyB, o.buyB.getAmount()); d.addToTill(o.buyB, o.buyB.getAmount()); }
            p.getInventory().addItem(o.sell.clone());                        // conjured good → customer (fits, checked)
            o.uses++;
            trader.xp += Math.max(0, o.villagerXp);
            done++;
        }
        if (done > 0) {
            o.locked = true;
            int target = levelFor(trader.xp, prof.maxLevel());
            while (trader.level < target) { trader.level++; unlockLevel(prof, trader, trader.level); }
        }
        return done;
    }

    /** Whether the player's inventory can fully hold {@code item} (empty slots + matching partials). */
    private static boolean canFit(Player p, ItemStack item) {
        int need = item.getAmount();
        int max = Math.max(1, item.getMaxStackSize());
        ItemStack key = single(item);
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) need -= max;
            else if (it.isSimilar(key)) need -= Math.max(0, max - it.getAmount());
            if (need <= 0) return true;
        }
        return need <= 0;
    }

    /** Add up to the vault cap (0 = unlimited); returns how many were actually stored. */
    private static long addCapped(TradingHallData d, long cap, ItemStack item, long amount) {
        if (cap <= 0) { d.addToVault(item, amount); return amount; }
        long room = cap - d.vaultMass();
        if (room <= 0) return 0;
        long store = Math.min(room, amount);
        if (store > 0) d.addToVault(item, store);
        return store;
    }

    /** Give n of a template to the player; drop whatever doesn't fit at their feet (never lost). */
    private static void giveOrDrop(Player p, ItemStack template, int n) {
        ItemStack give = template.clone();
        give.setAmount(Math.max(1, n));
        for (ItemStack lo : p.getInventory().addItem(give).values()) p.getWorld().dropItem(p.getLocation(), lo);
    }

    /**
     * Auto-trade (T7): for a trader with auto-trade on, run every offer from the VAULT alone —
     * pay ingredients from the vault, deposit results to the vault — while in stock, affordable,
     * and the vault has room. Unattended (no player). Locks traded slots + levels up like manual.
     * Bounded: each offer drains at most its remaining uses (refilled by the timed restock).
     */
    public static boolean autoTradeSweep(TradingHallSpec.Profession prof, TradingHallData d,
                                         TradingHallData.Trader trader, long vaultCap) {
        if (trader.offers.isEmpty()) return false;
        boolean changed = false;
        // Per-trade auto: one trade per auto-enabled offer per tick (a steady drip). Hard-bounds
        // throughput and makes the uses cap bind — the trade sells out at maxUses and waits for
        // the restock, instead of draining the whole vault in one tick.
        for (TradingHallData.LiveOffer o : trader.offers) {
            if (!o.autoTrade) continue;                                     // only auto-enabled trades
            if (o.uses >= trader.effectiveMaxUses(o)) continue;              // sold out → wait for restock
            if (d.vaultCount(o.buyA) < o.buyA.getAmount()) continue;
            if (o.buyB != null && d.vaultCount(o.buyB) < o.buyB.getAmount()) continue;
            long room = vaultCap <= 0 ? Long.MAX_VALUE : vaultCap - d.vaultMass();
            if (room < o.sell.getAmount()) continue;                        // vault full → skip
            d.removeFromVault(o.buyA, o.buyA.getAmount());
            if (o.buyB != null) d.removeFromVault(o.buyB, o.buyB.getAmount());
            d.addToVault(o.sell, o.sell.getAmount());
            o.uses++;
            o.locked = true;
            trader.xp += Math.max(0, o.villagerXp);
            changed = true;
        }
        if (changed) {
            int target = levelFor(trader.xp, prof.maxLevel());
            while (trader.level < target) { trader.level++; unlockLevel(prof, trader, trader.level); }
        }
        return changed;
    }

    /** Consume one ingredient stack, taking from the vault first, then the player's inventory. */
    private static void consumePreferVault(Player p, TradingHallData d, ItemStack req) {
        int need = req.getAmount();
        need -= (int) d.removeFromVault(req, need);
        if (need > 0) removeFromInv(p, req, need);
    }

    private static int levelFor(int xp, int maxLevel) {
        int lvl = 1;
        for (int l = 2; l <= maxLevel && l <= LEVEL_XP.length; l++) {
            if (xp >= LEVEL_XP[l - 1]) lvl = l;
        }
        return lvl;
    }
}
