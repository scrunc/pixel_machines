package dev.servereer.machineconstruct.tradinghall;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serializes {@link TradingHallData} to/from a YAML string for PDC storage (ADR 0011),
 * mirroring {@code QuarryStore}. Integer-indexed sections so nested {@link ItemStack}s
 * round-trip. Built slots are stored compacted (empties dropped); the manager re-pads to
 * the tier's slot count via {@link TradingHallData#ensureSlots(int)} after load.
 */
public final class TradingHallStore {

    private TradingHallStore() {}

    public static String serialize(TradingHallData d) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("tier", d.tier());
        y.set("blanks", d.blankVillagers());
        y.set("outVault", d.outputToVault());
        y.set("open", d.open());
        y.set("lastRestock", d.lastRestock());

        int si = 0;
        for (TradingHallData.Slot s : d.slots()) {
            if (!s.isBuilt() && !s.hasTrader()) continue;
            String base = "slots." + (si++);
            if (s.stationProfessionId != null) y.set(base + ".prof", s.stationProfessionId);
            TradingHallData.Trader t = s.trader;
            if (t != null) {
                y.set(base + ".t.prof", t.professionId);
                y.set(base + ".t.level", t.level);
                y.set(base + ".t.xp", t.xp);
                y.set(base + ".t.locked", t.locked);
                y.set(base + ".t.count", t.count);
                if (t.stockLevel > 0) y.set(base + ".t.stock", t.stockLevel);
                int oi = 0;
                for (TradingHallData.LiveOffer o : t.offers) {
                    String ob = base + ".t.o." + (oi++);
                    y.set(ob + ".a", o.buyA);
                    if (o.buyB != null) y.set(ob + ".b", o.buyB);
                    y.set(ob + ".s", o.sell);
                    y.set(ob + ".max", o.maxUses);
                    y.set(ob + ".use", o.uses);
                    y.set(ob + ".xp", o.villagerXp);
                    y.set(ob + ".pm", o.priceMultiplier);
                    y.set(ob + ".lvl", o.level);
                    if (o.locked) y.set(ob + ".lk", true);
                    if (o.autoTrade) y.set(ob + ".au", true);
                }
            }
        }

        int vi = 0;
        for (Map.Entry<ItemStack, Long> e : d.vault().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            String base = "vault." + (vi++);
            y.set(base + ".item", e.getKey());
            y.set(base + ".n", e.getValue());
        }

        int ti = 0;
        for (Map.Entry<ItemStack, Long> e : d.till().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            String base = "till." + (ti++);
            y.set(base + ".item", e.getKey());
            y.set(base + ".n", e.getValue());
        }
        return y.saveToString();
    }

    public static TradingHallData load(String data) {
        TradingHallData d = new TradingHallData();
        if (data == null || data.isEmpty()) return d;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(data);
            d.setTier(y.getInt("tier", 1));
            d.addBlankVillagers(y.getInt("blanks", 0));
            d.setOutputToVault(y.getBoolean("outVault", false));
            d.setOpen(y.getBoolean("open", false));
            d.setLastRestock(y.getLong("lastRestock", 0));

            ConfigurationSection slots = y.getConfigurationSection("slots");
            if (slots != null) {
                for (String k : sortedNumeric(slots)) {
                    ConfigurationSection s = slots.getConfigurationSection(k);
                    if (s == null) continue;
                    TradingHallData.Slot slot = new TradingHallData.Slot();
                    slot.stationProfessionId = s.getString("prof");
                    ConfigurationSection ts = s.getConfigurationSection("t");
                    if (ts != null) {
                        TradingHallData.Trader t = new TradingHallData.Trader();
                        t.professionId = ts.getString("prof", slot.stationProfessionId);
                        t.level = ts.getInt("level", 1);
                        t.xp = ts.getInt("xp", 0);
                        t.locked = ts.getBoolean("locked", false);
                        t.count = Math.max(1, ts.getInt("count", 1));
                        t.stockLevel = ts.getInt("stock", 0);
                        ConfigurationSection os = ts.getConfigurationSection("o");
                        if (os != null) for (String ok : sortedNumeric(os)) {
                            ConfigurationSection o = os.getConfigurationSection(ok);
                            if (o == null) continue;
                            TradingHallData.LiveOffer lo = new TradingHallData.LiveOffer();
                            lo.buyA = o.getItemStack("a");
                            lo.buyB = o.getItemStack("b");
                            lo.sell = o.getItemStack("s");
                            lo.maxUses = o.getInt("max", 16);
                            lo.uses = o.getInt("use", 0);
                            lo.villagerXp = o.getInt("xp", 2);
                            lo.priceMultiplier = (float) o.getDouble("pm", 0.05);
                            lo.level = o.getInt("lvl", 1);
                            lo.locked = o.getBoolean("lk", false);
                            lo.autoTrade = o.getBoolean("au", false);
                            if (lo.buyA != null && lo.sell != null) t.offers.add(lo);
                        }
                        slot.trader = t;
                    }
                    d.slots().add(slot);
                }
            }

            ConfigurationSection vault = y.getConfigurationSection("vault");
            if (vault != null) for (String k : vault.getKeys(false)) {
                ConfigurationSection v = vault.getConfigurationSection(k);
                if (v == null) continue;
                ItemStack item = v.getItemStack("item");
                long n = v.getLong("n", 0);
                if (item == null || n <= 0) continue;
                d.addToVault(item, n);
            }

            ConfigurationSection till = y.getConfigurationSection("till");
            if (till != null) for (String k : till.getKeys(false)) {
                ConfigurationSection v = till.getConfigurationSection(k);
                if (v == null) continue;
                ItemStack item = v.getItemStack("item");
                long n = v.getLong("n", 0);
                if (item == null || n <= 0) continue;
                d.addToTill(item, n);
            }
        } catch (Throwable t) {
            // A parse/deserialize failure here used to silently return partial/empty data, which the
            // engine would then persist back over the good blob — a "full reset". Signal it instead so
            // the manager quarantines the machine and PRESERVES the saved blob.
            throw new IllegalStateException("corrupt trading-hall blob", t);
        }
        return d;
    }

    private static List<String> sortedNumeric(ConfigurationSection sec) {
        List<String> keys = new ArrayList<>(sec.getKeys(false));
        keys.sort((a, b) -> {
            try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
            catch (NumberFormatException e) { return a.compareTo(b); }
        });
        return keys;
    }
}
