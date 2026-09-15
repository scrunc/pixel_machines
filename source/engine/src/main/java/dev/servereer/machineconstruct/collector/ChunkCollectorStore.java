package dev.servereer.machineconstruct.collector;

import dev.servereer.machineconstruct.grinder.ChestLink;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Serializes {@link ChunkCollectorData} to/from a YAML string for PDC storage (the
 * collector analogue of {@code GrinderStore}/{@code QuarryStore}). Uses
 * integer-indexed sections so nested {@link ItemStack}s round-trip reliably.
 * Written on every mutation → crash-safe.
 */
public final class ChunkCollectorStore {

    private ChunkCollectorStore() {}

    public static String serialize(ChunkCollectorData d) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("lastVacuum", d.lastVacuum());
        y.set("collected", d.collectedTotal());

        int vi = 0;
        for (Map.Entry<ItemStack, Long> e : d.vault().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            String base = "vault." + (vi++);
            y.set(base + ".item", e.getKey());
            y.set(base + ".n", e.getValue());
        }

        int li = 0;
        for (ChestLink l : d.links()) {
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
                List<String> mats = new java.util.ArrayList<>();
                for (Material m : l.filter()) mats.add(m.name());
                y.set(base + ".filter", mats);
            }
        }

        if (d.autoSellEnabled() || d.autoSellAccMoney() > 0 || !d.autoSellRules().isEmpty()) {
            y.set("autosell.enabled", d.autoSellEnabled());
            y.set("autosell.accMoney", d.autoSellAccMoney());
            y.set("autosell.accItems", d.autoSellAccItems());
            y.set("autosell.lastPayout", d.autoSellLastPayout());
            for (Map.Entry<Material, long[]> e : d.autoSellRules().entrySet())
                y.set("autosell.rules." + e.getKey().name(), e.getValue()[0]);   // mat → rate
        }

        List<String> log = d.logLines();
        if (!log.isEmpty()) y.set("log", log);
        return y.saveToString();
    }

    /** Load a collector's full state from PDC into a fresh {@link ChunkCollectorData}; never throws. */
    public static ChunkCollectorData load(String data) {
        ChunkCollectorData d = new ChunkCollectorData();
        if (data == null || data.isEmpty()) return d;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(data);
            d.setLastVacuum(y.getLong("lastVacuum", System.currentTimeMillis()));
            d.setCollectedTotal(y.getLong("collected", 0));

            ConfigurationSection vault = y.getConfigurationSection("vault");
            if (vault != null) for (String k : vault.getKeys(false)) {
                ConfigurationSection v = vault.getConfigurationSection(k);
                if (v == null) continue;
                ItemStack item = v.getItemStack("item");
                long n = v.getLong("n", 0);
                if (item == null || n <= 0) continue;
                ItemStack tmpl = item.clone(); tmpl.setAmount(1);
                d.loadIntoVault(tmpl, n);
            }

            ConfigurationSection links = y.getConfigurationSection("links");
            if (links != null) for (String k : links.getKeys(false)) {
                ConfigurationSection l = links.getConfigurationSection(k);
                if (l == null) continue;
                org.bukkit.World w = org.bukkit.Bukkit.getWorld(l.getString("world", ""));
                if (w == null) continue;
                java.util.Set<Material> filter = new java.util.HashSet<>();
                for (String mn : l.getStringList("filter")) { Material mm = Material.matchMaterial(mn); if (mm != null) filter.add(mm); }
                try {
                    ChestLink.Type t = ChestLink.Type.valueOf(l.getString("type", "OUTPUT"));
                    org.bukkit.Location loc = new org.bukkit.Location(w, l.getInt("x"), l.getInt("y"), l.getInt("z"));
                    d.addLink(new ChestLink(t, loc, filter, l.getLong("rate", 0)));
                } catch (IllegalArgumentException ignored) { }
            }

            ConfigurationSection as = y.getConfigurationSection("autosell");
            if (as != null) {
                d.setAutoSellEnabled(as.getBoolean("enabled", false));
                d.addAutoSellAcc(as.getDouble("accMoney", 0), as.getLong("accItems", 0));
                d.setAutoSellLastPayout(as.getLong("lastPayout", System.currentTimeMillis()));
                ConfigurationSection rules = as.getConfigurationSection("rules");
                if (rules != null) for (String mn : rules.getKeys(false)) {
                    Material mm = Material.matchMaterial(mn);
                    if (mm != null) d.setAutoSellItem(mm, rules.getLong(mn, 0), System.currentTimeMillis());
                }
            }

            List<String> stored = y.getStringList("log");
            for (int i = stored.size() - 1; i >= 0; i--) d.logEvent(stored.get(i));
        } catch (Throwable t) {
            throw new IllegalStateException("corrupt chunk-collector blob", t);   // quarantine + preserve, don't silently reset
        }
        return d;
    }
}
