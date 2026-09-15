package dev.servereer.machineconstruct.tradinghall;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-machine state for a Virtual Trading Hall (ADR 0011). Mutable, one per placed
 * hall; serialized by {@link TradingHallStore} into the anchor's PDC.
 *
 * <ul>
 *   <li>{@link #blankVillagers} — captured regular villagers awaiting a station (fungible → a count).</li>
 *   <li>{@link #slots} — fixed-size to the largest tier; each may hold a built station + a trader.</li>
 *   <li>{@link #vault} — the bidirectional Quantum Vault (item template → mass).</li>
 *   <li>{@link #tier} — drives slot capacity ({@code baseSlots + (tier-1)*slotsPerTier}).</li>
 * </ul>
 */
public final class TradingHallData {

    /** A concrete, serializable trade (no reference to the shared spec). Live {@code uses} mutate. */
    public static final class LiveOffer {
        public ItemStack buyA;          // primary ingredient (amount = cost)
        public ItemStack buyB;          // optional secondary (null = none)
        public ItemStack sell;          // result
        public int maxUses;
        public int uses;                // current uses since last restock
        public int villagerXp;
        public float priceMultiplier;
        public int level = 1;           // the profession tier this offer came from
        public boolean locked = false;  // traded at least once → re-roll disabled until a full stall reset
        public boolean autoTrade = false; // per-trade auto-trade (vault-fed) toggle

        public LiveOffer() {}

        public static LiveOffer from(TradingHallSpec.Offer o, int level) {
            LiveOffer l = new LiveOffer();
            l.buyA = o.buyA.clone();
            l.buyB = o.buyB == null ? null : o.buyB.clone();
            l.sell = o.sell.clone();
            l.maxUses = o.maxUses;
            l.uses = 0;
            l.villagerXp = o.villagerXp;
            l.priceMultiplier = o.priceMultiplier;
            l.level = level;
            return l;
        }

        public boolean soldOut() { return uses >= maxUses; }

        /** Identity for merge: same ingredients/result/maxUses, ignoring live uses/demand. */
        public boolean sameTemplate(LiveOffer o) {
            return o != null && maxUses == o.maxUses
                    && itemEq(buyA, o.buyA) && itemEq(buyB, o.buyB) && itemEq(sell, o.sell);
        }

        private static boolean itemEq(ItemStack a, ItemStack b) {
            if (a == null || b == null) return a == b;
            return a.isSimilar(b) && a.getAmount() == b.getAmount();
        }
    }

    /** A captured villager turned trader: a profession's rolled offers + level + lock + stack count. */
    public static final class Trader {
        public String professionId;       // matches the slot's built station profession
        public int level = 1;             // 1..maxLevel (Novice..Master)
        public int xp = 0;                // trader XP; thresholds raise the level
        public boolean locked = false;    // true once traded with → Refresh disabled
        public int count = 1;             // merge stack (×throughput)
        public int stockLevel = 0;        // per-shop stock upgrades → maxUses ×(1+stockLevel)
        public final List<LiveOffer> offers = new ArrayList<>();

        /** Stock-upgraded maxUses for an offer: base ×(1 + stockLevel). */
        public int effectiveMaxUses(LiveOffer o) { return o.maxUses * (1 + Math.max(0, stockLevel)); }

        /** Mergeable with another trader: same profession+level and identical offer templates. */
        public boolean mergeableWith(Trader o) {
            if (o == null || !Objects.equals(professionId, o.professionId) || level != o.level) return false;
            if (offers.size() != o.offers.size()) return false;
            for (int i = 0; i < offers.size(); i++) {
                if (!offers.get(i).sameTemplate(o.offers.get(i))) return false;
            }
            return true;
        }
    }

    /** A slot: an (optionally) built profession station, optionally holding a trader. */
    public static final class Slot {
        public String stationProfessionId; // null = empty/unbuilt; else the profession this station serves
        public Trader trader;               // null = station built but no villager inserted yet

        public boolean isBuilt() { return stationProfessionId != null; }
        public boolean hasTrader() { return trader != null; }
    }

    private int tier = 1;
    private int blankVillagers = 0;
    private boolean outputToVault = false; // trade results: true = into vault; false = into inventory (overflow → vault)
    private boolean open = false;          // shopfront: true = non-owners may trade (customer mode) (ADR 0012)
    private final List<Slot> slots = new ArrayList<>();
    private final Map<ItemStack, Long> vault = new LinkedHashMap<>();
    private final Map<ItemStack, Long> till = new LinkedHashMap<>(); // shopfront earnings — separate from the vault
    private long lastRestock = 0L; // epoch ms of the last timed restock

    /** Size the slot list to the largest tier so it never shrinks (only the first slotsForTier are usable). */
    public void ensureSlots(int maxSlots) {
        while (slots.size() < maxSlots) slots.add(new Slot());
    }

    public int tier() { return tier; }
    public void setTier(int t) { this.tier = Math.max(1, t); }

    public int blankVillagers() { return blankVillagers; }
    public void addBlankVillagers(int n) { blankVillagers = Math.max(0, blankVillagers + n); }

    public boolean outputToVault() { return outputToVault; }
    public void setOutputToVault(boolean v) { this.outputToVault = v; }

    public boolean open() { return open; }
    public void setOpen(boolean v) { this.open = v; }

    public List<Slot> slots() { return slots; }
    public Slot slot(int i) { return (i >= 0 && i < slots.size()) ? slots.get(i) : null; }

    public long lastRestock() { return lastRestock; }
    public void setLastRestock(long t) { this.lastRestock = t; }

    // --- quantum vault (bidirectional) --------------------------------------

    public Map<ItemStack, Long> vault() { return vault; }

    public void addToVault(ItemStack template, long amount) {
        if (template == null || amount <= 0) return;
        ItemStack key = template.clone();
        key.setAmount(1);
        vault.merge(key, amount, Long::sum);
    }

    /** Remove up to {@code amount} of an item; returns how many were actually removed. */
    public long removeFromVault(ItemStack template, long amount) {
        if (template == null || amount <= 0) return 0;
        ItemStack key = template.clone();
        key.setAmount(1);
        Long have = vault.get(key);
        if (have == null) return 0;
        long take = Math.min(have, amount);
        if (take >= have) vault.remove(key); else vault.put(key, have - take);
        return take;
    }

    public long vaultCount(ItemStack template) {
        if (template == null) return 0;
        ItemStack key = template.clone();
        key.setAmount(1);
        return vault.getOrDefault(key, 0L);
    }

    public long vaultMass() {
        long s = 0;
        for (long v : vault.values()) s += v;
        return s;
    }

    // --- shopfront earnings till (ADR 0012) ---------------------------------
    // Mirrors the vault, but holds customer payments only. Nothing auto-spends it;
    // the owner collects from it. Kept separate so shop revenue never feeds recipes/auto-trade.

    public Map<ItemStack, Long> till() { return till; }

    public void addToTill(ItemStack template, long amount) {
        if (template == null || amount <= 0) return;
        ItemStack key = template.clone();
        key.setAmount(1);
        till.merge(key, amount, Long::sum);
    }

    public long removeFromTill(ItemStack template, long amount) {
        if (template == null || amount <= 0) return 0;
        ItemStack key = template.clone();
        key.setAmount(1);
        Long have = till.get(key);
        if (have == null) return 0;
        long take = Math.min(have, amount);
        if (take >= have) till.remove(key); else till.put(key, have - take);
        return take;
    }

    public long tillCount(ItemStack template) {
        if (template == null) return 0;
        ItemStack key = template.clone();
        key.setAmount(1);
        return till.getOrDefault(key, 0L);
    }

    public long tillMass() {
        long s = 0;
        for (long v : till.values()) s += v;
        return s;
    }
}
