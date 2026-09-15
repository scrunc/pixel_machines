package dev.servereer.machineconstruct.grinder;

import dev.servereer.machineconstruct.core.Heads;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * SmartSpawner-format loot table ({@code spawners_settings.yml}), parsed once and
 * shared. Per mob key → {@link MobLoot}: experience-per-mob, a head-texture menu
 * icon, and a list of {@link LootDrop} (item template + amount range + chance).
 *
 * <p>The grinder never spawns mobs; it accrues the <b>expected value</b> of these
 * drops over time (see {@link GrinderManager}). Durability ranges are ignored
 * (items stored at full durability); {@code potion_type} on tipped arrows is kept
 * because it changes the item identity (and therefore its sell price).
 */
public final class LootTable {

    /** One item a mob can drop: a 1-amount template + its average roll × chance. */
    public static final class LootDrop {
        private final ItemStack template;   // amount 1
        private final double expectedPerCycle;   // avgAmount × chance/100

        LootDrop(ItemStack template, double expectedPerCycle) {
            this.template = template;
            this.expectedPerCycle = expectedPerCycle;
        }

        public ItemStack template() { return template.clone(); }
        public double expectedPerCycle() { return expectedPerCycle; }
    }

    /** A mob's full loot definition. */
    public static final class MobLoot {
        private final String type;
        private final double experience;
        private final ItemStack icon;
        private final List<LootDrop> drops;

        MobLoot(String type, double experience, ItemStack icon, List<LootDrop> drops) {
            this.type = type;
            this.experience = experience;
            this.icon = icon;
            this.drops = drops;
        }

        public String type() { return type; }
        public double experience() { return experience; }
        public ItemStack icon() { return icon.clone(); }
        public List<LootDrop> drops() { return drops; }

        /** Sum of expected item count per cycle across all drops (per mob, per stack=1). */
        public double expectedItemsPerCycle() {
            double s = 0;
            for (LootDrop d : drops) s += d.expectedPerCycle;
            return s;
        }
    }

    private final Map<String, MobLoot> mobs = new HashMap<>();

    private LootTable() {}

    public MobLoot get(String type) {
        return type == null ? null : mobs.get(type.toUpperCase(Locale.ROOT));
    }

    public boolean has(String type) { return get(type) != null; }

    public java.util.Set<String> types() { return mobs.keySet(); }

    /** Parse a SmartSpawner-format file. Returns an (empty) table on any failure — never throws. */
    public static LootTable load(File file, Logger log) {
        LootTable t = new LootTable();
        if (file == null || !file.isFile()) {
            if (log != null) log.warning("[Grinder] loot file not found: " + file);
            return t;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            if (key.equals("config_version") || key.equals("default_material")) continue;
            ConfigurationSection sec = y.getConfigurationSection(key);
            if (sec == null) continue;
            try {
                MobLoot ml = parseMob(key.toUpperCase(Locale.ROOT), sec);
                t.mobs.put(ml.type, ml);
            } catch (Exception e) {
                if (log != null) log.warning("[Grinder] skipped loot for '" + key + "': " + e.getMessage());
            }
        }
        if (log != null) log.info("[Grinder] loaded loot for " + t.mobs.size() + " mob type(s) from " + file.getName());
        return t;
    }

    private static MobLoot parseMob(String type, ConfigurationSection sec) {
        double exp = sec.getDouble("experience", 0);
        ItemStack icon = parseIcon(sec.getConfigurationSection("head_texture"));
        List<LootDrop> drops = new ArrayList<>();
        ConfigurationSection loot = sec.getConfigurationSection("loot");
        if (loot != null) {
            for (String mat : loot.getKeys(false)) {
                ConfigurationSection d = loot.getConfigurationSection(mat);
                if (d == null) continue;
                ItemStack tmpl = buildDropTemplate(mat, d);
                if (tmpl == null) continue;
                double[] range = parseRange(d.getString("amount", "1-1"));
                double chance = d.getDouble("chance", 100.0);
                double avg = (range[0] + range[1]) / 2.0;
                double expected = avg * (chance / 100.0);
                if (expected > 0) drops.add(new LootDrop(tmpl, expected));
            }
        }
        return new MobLoot(type, exp, icon, drops);
    }

    private static ItemStack buildDropTemplate(String matName, ConfigurationSection d) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null || !mat.isItem()) return null;
        ItemStack item = new ItemStack(mat, 1);
        String potion = d.getString("potion_type");
        if (potion != null && item.getItemMeta() instanceof PotionMeta pm) {
            try {
                pm.setBasePotionType(PotionType.valueOf(potion.toUpperCase(Locale.ROOT)));
                item.setItemMeta(pm);
            } catch (IllegalArgumentException ignored) { }
        }
        return item;
    }

    private static ItemStack parseIcon(ConfigurationSection head) {
        if (head == null) return new ItemStack(Material.SPAWNER);
        String tex = head.getString("custom_texture");
        if (tex != null && !tex.isEmpty()) {
            // SmartSpawner stores a bare texture hash → the minecraft texture URL.
            return Heads.create("http://textures.minecraft.net/texture/" + tex);
        }
        Material mat = Material.matchMaterial(head.getString("material", "SPAWNER"));
        return new ItemStack(mat == null ? Material.SPAWNER : mat);
    }

    /** Parse "min-max" (or a single number) into [min, max] doubles. */
    private static double[] parseRange(String s) {
        try {
            String t = s.trim();
            int dash = t.indexOf('-', t.startsWith("-") ? 1 : 0);
            if (dash < 0) { double v = Double.parseDouble(t); return new double[]{v, v}; }
            double a = Double.parseDouble(t.substring(0, dash).trim());
            double b = Double.parseDouble(t.substring(dash + 1).trim());
            return new double[]{Math.min(a, b), Math.max(a, b)};
        } catch (Exception e) {
            return new double[]{1, 1};
        }
    }

    /** A display name for a mob type ("WITHER_SKELETON" → "Wither Skeleton"). */
    public static String pretty(String type) {
        String[] parts = type.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return sb.toString().trim();
    }
}
