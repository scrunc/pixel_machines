package dev.servereer.machineconstruct.quarry;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Serializes {@link QuarryData} to/from a YAML string for PDC storage (the quarry
 * analogue of {@code GrinderStore}). Uses integer-indexed sections so nested
 * {@link ItemStack}s round-trip reliably. Written on every mutation → crash-safe.
 */
public final class QuarryStore {

    private QuarryStore() {}

    public static String serialize(QuarryData d) {
        YamlConfiguration y = new YamlConfiguration();
        if (d.pickaxe() != null) y.set("pick", d.pickaxe());
        if (d.rod() != null) y.set("rod", d.rod());
        putList(y, "pickRack", d.pickRack());
        putList(y, "rodRack", d.rodRack());
        y.set("mode", d.mode().name());
        y.set("dim", d.dimension());
        y.set("lava", d.lava());
        y.set("lavaOn", d.lavaOn());
        y.set("lastLava", d.lastLava());
        y.set("lastHeat", d.lastHeat());
        y.set("heat", d.heat());
        y.set("rp", d.riftPoints());
        y.set("prestige", d.prestige());
        // Q6b
        y.set("surgeUntil", d.surgeUntil());
        y.set("lastSurge", d.lastSurgeCheck());
        y.set("lastVein", d.lastVein());
        y.set("contractType", d.contractType());
        y.set("contractTarget", d.contractTarget());
        y.set("contractBase", d.contractBaseMined());
        y.set("contractsDone", d.contractsDone());
        if (d.contractItem() != null) y.set("contractItem", d.contractItem());
        for (Map.Entry<Integer, Double> e : d.veinHealth().entrySet())
            if (e.getValue() != null && e.getValue() < 1.0) y.set("vein." + e.getKey(), e.getValue());
        y.set("mined", d.totalMined());
        y.set("last", d.lastAccrual());
        y.set("lastFish", d.lastFish());

        int vi = 0;
        for (Map.Entry<ItemStack, Long> e : d.vault().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            String base = "vault." + (vi++);
            y.set(base + ".item", e.getKey());
            y.set(base + ".n", e.getValue());
            QuarryData.Rule r = d.rules().get(e.getKey());
            if (r != null && r != QuarryData.Rule.KEEP) y.set(base + ".rule", r.name());
        }
        for (Map.Entry<String, Integer> e : d.skills().entrySet())
            if (e.getValue() != null && e.getValue() > 0) y.set("skills." + e.getKey(), e.getValue());

        List<String> log = d.logLines();
        if (!log.isEmpty()) y.set("log", log);
        return y.saveToString();
    }

    public static QuarryData load(String data) {
        QuarryData d = new QuarryData();
        if (data == null || data.isEmpty()) return d;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(data);
            d.setPickaxe(y.getItemStack("pick"));
            d.setRod(y.getItemStack("rod"));
            readList(y, "pickRack", d.pickRack());
            readList(y, "rodRack", d.rodRack());
            try { d.setMode(QuarryData.Mode.valueOf(y.getString("mode", "MINING"))); } catch (Exception ignored) {}
            d.setDimension(y.getInt("dim", 0));
            d.setLava(y.getLong("lava", 0));
            d.setLavaOn(y.getBoolean("lavaOn", false));
            d.setLastLava(y.getLong("lastLava", System.currentTimeMillis()));
            d.setLastHeat(y.getLong("lastHeat", System.currentTimeMillis()));
            d.setHeat(y.getDouble("heat", 0));
            d.addRiftPoints(y.getLong("rp", 0));
            d.setPrestige(y.getLong("prestige", 0));
            // Q6b
            d.setSurgeUntil(y.getLong("surgeUntil", 0));
            d.setLastSurgeCheck(y.getLong("lastSurge", System.currentTimeMillis()));
            d.setLastVein(y.getLong("lastVein", System.currentTimeMillis()));
            d.setContractType(y.getInt("contractType", 0));
            d.setContractTarget(y.getLong("contractTarget", 0));
            d.setContractBaseMined(y.getLong("contractBase", 0));
            d.setContractsDone(y.getLong("contractsDone", 0));
            d.setContractItem(y.getItemStack("contractItem"));
            ConfigurationSection vein = y.getConfigurationSection("vein");
            if (vein != null) for (String k : vein.getKeys(false)) {
                try { d.setVeinHealth(Integer.parseInt(k), vein.getDouble(k, 1.0)); } catch (NumberFormatException ignored) {}
            }
            d.addMined(y.getLong("mined", 0));
            d.setLastAccrual(y.getLong("last", System.currentTimeMillis()));
            d.setLastFish(y.getLong("lastFish", System.currentTimeMillis()));

            ConfigurationSection vault = y.getConfigurationSection("vault");
            if (vault != null) for (String k : vault.getKeys(false)) {
                ConfigurationSection v = vault.getConfigurationSection(k);
                if (v == null) continue;
                ItemStack item = v.getItemStack("item");
                long n = v.getLong("n", 0);
                if (item == null || n <= 0) continue;
                ItemStack tmpl = item.clone(); tmpl.setAmount(1);
                d.addToVault(tmpl, n);
                String rule = v.getString("rule");
                if (rule != null) try { d.setRule(tmpl, QuarryData.Rule.valueOf(rule)); } catch (Exception ignored) {}
            }
            ConfigurationSection skills = y.getConfigurationSection("skills");
            if (skills != null) for (String k : skills.getKeys(false)) d.skills().put(k, skills.getInt(k, 0));

            List<String> stored = y.getStringList("log");
            for (int i = stored.size() - 1; i >= 0; i--) d.logEvent(stored.get(i));
        } catch (Throwable t) {
            throw new IllegalStateException("corrupt quarry blob", t);   // quarantine + preserve, don't silently reset
        }
        return d;
    }

    private static void putList(YamlConfiguration y, String key, List<ItemStack> list) {
        int i = 0;
        for (ItemStack it : list) if (it != null) y.set(key + "." + (i++), it);
    }

    private static void readList(YamlConfiguration y, String key, List<ItemStack> out) {
        ConfigurationSection sec = y.getConfigurationSection(key);
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            ItemStack it = sec.getItemStack(k);
            if (it != null) out.add(it);
        }
    }
}
