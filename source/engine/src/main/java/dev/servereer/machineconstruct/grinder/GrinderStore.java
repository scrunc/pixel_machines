package dev.servereer.machineconstruct.grinder;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Serializes a {@link GrinderData} to/from a YAML string for PDC storage — the
 * grinder analogue of {@link dev.servereer.machineconstruct.machine.MachineStore}.
 * Uses integer-indexed sections (not lists) so nested {@link ItemStack}s round-trip
 * reliably through Bukkit's {@code ConfigurationSerializable} handling.
 *
 * <p>Written on every mutation, so a crash or restart never loses a grinder's
 * spawners, buffers, pool, XP, cores, or log.
 */
public final class GrinderStore {

    private GrinderStore() {}

    public static String serialize(GrinderData d) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("exp", d.storedExp());

        int si = 0;
        for (InstalledSpawner s : d.spawners()) {
            String base = "spawners." + (si++);
            y.set(base + ".type", s.type());
            y.set(base + ".stack", s.stackSize());
            y.set(base + ".last", s.lastAccrualMillis());
            y.set(base + ".exp", s.accruedExp());
            int bi = 0;
            for (Map.Entry<ItemStack, Double> e : s.buffer().entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                y.set(base + ".buf." + bi + ".item", e.getKey());
                y.set(base + ".buf." + bi + ".n", e.getValue());
                bi++;
            }
        }

        int pi = 0;
        for (Map.Entry<ItemStack, Long> e : d.pool().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            y.set("pool." + pi + ".item", e.getKey());
            y.set("pool." + pi + ".n", e.getValue());
            pi++;
        }

        for (Map.Entry<Material, Integer> e : d.cores().entrySet())
            y.set("cores." + e.getKey().name(), e.getValue());

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

    /** Load a grinder's full state from PDC into a fresh {@link GrinderData}; never throws. */
    public static GrinderData load(String data) {
        GrinderData d = new GrinderData();
        if (data == null || data.isEmpty()) return d;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(data);
            d.setStoredExp(y.getLong("exp", 0));

            ConfigurationSection sp = y.getConfigurationSection("spawners");
            if (sp != null) {
                for (String key : sp.getKeys(false)) {
                    ConfigurationSection s = sp.getConfigurationSection(key);
                    if (s == null) continue;
                    InstalledSpawner is = new InstalledSpawner(
                            s.getString("type", "ZOMBIE"),
                            s.getLong("stack", 1),
                            s.getLong("last", System.currentTimeMillis()));
                    is.setAccruedExp(s.getDouble("exp", 0));
                    ConfigurationSection buf = s.getConfigurationSection("buf");
                    if (buf != null) {
                        for (String bk : buf.getKeys(false)) {
                            ConfigurationSection b = buf.getConfigurationSection(bk);
                            if (b == null) continue;
                            ItemStack item = b.getItemStack("item");
                            double n = b.getDouble("n", 0);
                            if (item != null && n > 0) is.addBuffer(normalize(item), n);
                        }
                    }
                    d.spawners().add(is);
                }
            }

            ConfigurationSection pool = y.getConfigurationSection("pool");
            if (pool != null) {
                for (String key : pool.getKeys(false)) {
                    ConfigurationSection p = pool.getConfigurationSection(key);
                    if (p == null) continue;
                    ItemStack item = p.getItemStack("item");
                    long n = p.getLong("n", 0);
                    if (item != null && n > 0) d.addToPool(normalize(item), n);
                }
            }

            ConfigurationSection cores = y.getConfigurationSection("cores");
            if (cores != null) {
                for (String key : cores.getKeys(false)) {
                    Material mat = Material.matchMaterial(key);
                    if (mat != null) d.addCore(mat, cores.getInt(key, 0));
                }
            }

            ConfigurationSection links = y.getConfigurationSection("links");
            if (links != null) {
                for (String key : links.getKeys(false)) {
                    ConfigurationSection l = links.getConfigurationSection(key);
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

            // Stored most-recent-first; replay oldest-first so logEvent (addFirst) restores order.
            List<String> stored = y.getStringList("log");
            for (int i = stored.size() - 1; i >= 0; i--) d.logEvent(stored.get(i));
        } catch (Throwable t) {
            throw new IllegalStateException("corrupt grinder blob", t);   // quarantine + preserve, don't silently reset
        }
        return d;
    }

    /** A 1-amount copy so templates compare equal regardless of source amount. */
    private static ItemStack normalize(ItemStack item) {
        ItemStack c = item.clone();
        c.setAmount(1);
        return c;
    }
}
