package dev.servereer.machineconstruct.factorydistrict;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serializes {@link FactoryDistrictData} to/from a YAML string for PDC storage (ADR 0013),
 * mirroring {@code TradingHallStore}. Built slots are stored compacted; the manager re-pads
 * to the tier's slot count via {@link FactoryDistrictData#ensureSlots(int)} after load.
 */
public final class FactoryDistrictStore {

    private FactoryDistrictStore() {}

    public static String serialize(FactoryDistrictData d) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("tier", d.tier());
        if (d.workerPopulation() > 0) y.set("worker-population", d.workerPopulation());

        int ri = 0;
        for (Map.Entry<EntityType, Integer> e : d.roster().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            String base = "roster." + (ri++);
            y.set(base + ".type", e.getKey().name());
            y.set(base + ".n", e.getValue());
        }

        int si = 0;
        for (FactoryDistrictData.Slot s : d.slots()) {
            if (!s.isBuilt()) continue;
            String base = "slots." + (si++);
            y.set(base + ".farm", s.factoryId);
            if (s.sizeId != null) y.set(base + ".size", s.sizeId);
            y.set(base + ".last", s.lastProduce);
            if (s.offspring > 0) y.set(base + ".off", s.offspring);
            if (s.lastGenerate > 0) y.set(base + ".lgen", s.lastGenerate);
            if (s.workersAssigned > 0) y.set(base + ".wrk", s.workersAssigned);
            if (s.level > 0) y.set(base + ".lvl", s.level);
            if (s.cyclesProduced > 0) y.set(base + ".cyc", s.cyclesProduced);
            if (s.richness != 1.0) y.set(base + ".rich", s.richness);
            if (s.soil != 0) y.set(base + ".soil", s.soil);
            if (s.broken) y.set(base + ".broken", true);
            if (s.mergeStars > 0) y.set(base + ".mstars", s.mergeStars);
            if (s.mergeBonus != 0) y.set(base + ".mbonus", s.mergeBonus);
            if (s.selectedRecipes != null && !s.selectedRecipes.isEmpty()) y.set(base + ".recipes", s.selectedRecipes);
            for (Map.Entry<String, Long> e : s.producedLog.entrySet())
                if (e.getValue() != null && e.getValue() > 0) y.set(base + ".log." + e.getKey(), e.getValue());
        }

        int vi = 0;
        for (Map.Entry<ItemStack, Long> e : d.vault().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            String base = "vault." + (vi++);
            y.set(base + ".item", e.getKey());
            y.set(base + ".n", e.getValue());
        }

        int li = 0;
        for (dev.servereer.machineconstruct.grinder.ChestLink l : d.links()) {
            org.bukkit.Location loc = l.loc();
            if (loc.getWorld() == null) continue;
            String base = "links." + (li++);
            y.set(base + ".type", l.type().name());
            y.set(base + ".world", loc.getWorld().getName());
            y.set(base + ".x", loc.getBlockX());
            y.set(base + ".y", loc.getBlockY());
            y.set(base + ".z", loc.getBlockZ());
            y.set(base + ".rate", l.maxPerSec());
            if (l.filtered()) {
                List<String> mats = new ArrayList<>();
                for (org.bukkit.Material mm : l.filter()) mats.add(mm.name());
                y.set(base + ".filter", mats);
            }
        }

        if (d.autoSellEnabled() || d.autoSellAccMoney() > 0 || !d.autoSellRules().isEmpty()) {
            y.set("autosell.enabled", d.autoSellEnabled());
            y.set("autosell.accMoney", d.autoSellAccMoney());
            y.set("autosell.accItems", d.autoSellAccItems());
            y.set("autosell.lastPayout", d.autoSellLastPayout());
            for (Map.Entry<org.bukkit.Material, long[]> e : d.autoSellRules().entrySet())
                y.set("autosell.rules." + e.getKey().name(), e.getValue()[0]);   // mat → rate
        }
        return y.saveToString();
    }

    public static FactoryDistrictData load(String data) {
        FactoryDistrictData d = new FactoryDistrictData();
        if (data == null || data.isEmpty()) return d;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(data);
            d.setTier(y.getInt("tier", 1));
            d.setWorkerPopulation(y.getInt("worker-population", 0));

            ConfigurationSection roster = y.getConfigurationSection("roster");
            if (roster != null) for (String k : roster.getKeys(false)) {
                ConfigurationSection r = roster.getConfigurationSection(k);
                if (r == null) continue;
                EntityType type = matchEntity(r.getString("type"));
                int n = r.getInt("n", 0);
                if (type != null && n > 0) d.addCreatures(type, n);
            }

            ConfigurationSection slots = y.getConfigurationSection("slots");
            if (slots != null) {
                for (String k : sortedNumeric(slots)) {
                    ConfigurationSection s = slots.getConfigurationSection(k);
                    if (s == null) continue;
                    String farm = s.getString("farm");
                    if (farm == null) continue;
                    FactoryDistrictData.Slot slot = new FactoryDistrictData.Slot();
                    slot.factoryId = farm;
                    slot.sizeId = s.getString("size");
                    slot.lastProduce = s.getLong("last", 0L);
                    slot.offspring = s.getInt("off", 0);
                    slot.lastGenerate = s.getLong("lgen", 0L);
                    slot.workersAssigned = s.getInt("wrk", 0);
                    slot.level = s.getInt("lvl", 0);
                    slot.cyclesProduced = s.getLong("cyc", 0L);
                    slot.richness = s.getDouble("rich", 1.0);
                    slot.soil = s.getDouble("soil", 0);
                    slot.broken = s.getBoolean("broken", false);
                    slot.mergeStars = s.getInt("mstars", 0);
                    slot.mergeBonus = s.getDouble("mbonus", 0);
                    if (s.isList("recipes")) slot.selectedRecipes = new java.util.ArrayList<>(s.getStringList("recipes"));
                    ConfigurationSection log = s.getConfigurationSection("log");
                    if (log != null) for (String lk : log.getKeys(false)) {
                        long v = log.getLong(lk, 0L);
                        if (v > 0) slot.producedLog.put(lk, v);
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

            ConfigurationSection links = y.getConfigurationSection("links");
            if (links != null) for (String k : links.getKeys(false)) {
                ConfigurationSection l = links.getConfigurationSection(k);
                if (l == null) continue;
                org.bukkit.World w = org.bukkit.Bukkit.getWorld(l.getString("world", ""));
                if (w == null) continue;
                java.util.Set<org.bukkit.Material> filter = new java.util.HashSet<>();
                for (String mn : l.getStringList("filter")) { org.bukkit.Material mm = org.bukkit.Material.matchMaterial(mn); if (mm != null) filter.add(mm); }
                try {
                    dev.servereer.machineconstruct.grinder.ChestLink.Type t =
                            dev.servereer.machineconstruct.grinder.ChestLink.Type.valueOf(l.getString("type", "OUTPUT"));
                    org.bukkit.Location loc = new org.bukkit.Location(w, l.getInt("x"), l.getInt("y"), l.getInt("z"));
                    d.addLink(new dev.servereer.machineconstruct.grinder.ChestLink(t, loc, filter, l.getLong("rate", 0)));
                } catch (IllegalArgumentException ignored2) { }
            }

            ConfigurationSection as = y.getConfigurationSection("autosell");
            if (as != null) {
                d.setAutoSellEnabled(as.getBoolean("enabled", false));
                d.addAutoSellAcc(as.getDouble("accMoney", 0), as.getLong("accItems", 0));
                d.setAutoSellLastPayout(as.getLong("lastPayout", System.currentTimeMillis()));
                ConfigurationSection rules = as.getConfigurationSection("rules");
                if (rules != null) for (String mn : rules.getKeys(false)) {
                    org.bukkit.Material mm = org.bukkit.Material.matchMaterial(mn);
                    if (mm != null) d.setAutoSellItem(mm, rules.getLong(mn, 0), System.currentTimeMillis());
                }
            }
        } catch (Throwable t) {
            throw new IllegalStateException("corrupt factory-district blob", t);   // quarantine + preserve, don't silently reset
        }
        return d;
    }

    private static EntityType matchEntity(String name) {
        if (name == null) return null;
        try { return EntityType.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Throwable t) { return null; }
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
