package dev.servereer.machineconstruct.gacha;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * A capsule SERIES — {@code plugins/MachineConstruct/gacha/<series>.yml}: the loot list (every
 * entry is a real ItemStack, bytes-serialised so custom NBT / components survive, plus its rarity,
 * weight, name, an optional capsule-texture override and {@code once} for collectibles) and every
 * player's record in it (pulls, pity counter, owned pieces). The engine writes it (admins feed held
 * items in-game), Dev's Diary and hand edits change names / rarities / weights / textures, and
 * {@code /mc gacha reload} picks the edits up. Entries carry a display {@code material} + {@code display}
 * name purely so external editors can show them without decoding the item bytes.
 */
public final class GachaSeries {

    /** One collectible / drop. */
    public static final class Entry {
        public final String id;
        public String name;         // shown on the capsule + in menus (MiniMessage)
        public String rarity;       // a key of the machine's gacha.rarities
        public double weight = 1;   // within its rarity
        public boolean once;        // collectible: a repeat pays the consolation instead
        public String capsule = ""; // head texture override for this entry's capsule ("" = the rarity's)
        public ItemStack item;      // the reward (amount included) — null for a command reward
        public final List<String> commands = new ArrayList<>();   // console commands ({player}) — a reward that isn't an item (money, keys, ranks…)
        public ItemStack icon;      // what a command reward shows as (the capsule menu / the prize display)
        Entry(String id) { this.id = id; }
        public ItemStack item() { return item == null ? null : item.clone(); }
        public boolean isCommand() { return item == null && !commands.isEmpty(); }
        /** The stack shown for this entry: the item itself, or the command reward's icon. */
        public ItemStack shown() { return item != null ? item.clone() : icon != null ? icon.clone() : new ItemStack(org.bukkit.Material.PAPER); }
    }

    /** A player's record in this series. */
    public static final class Record {
        public int pulls;            // total pulls
        public int sinceRare;        // pulls since the pity rarity (or better) last dropped
        public int sinceGrab;        // claw machines: failed plays in a row (pity_grabs forces the next one)
        public final List<String> owned = new ArrayList<>();   // entry ids owned (once pieces + anything pulled)
        public final Map<String, Integer> counts = new HashMap<>();   // entry id → times pulled
    }

    private final String key;
    private final File file;
    private String title;
    private final List<Entry> loot = new ArrayList<>();
    private final Map<UUID, Record> players = new HashMap<>();
    private double duplicateMoney;   // consolation for a repeat `once` piece (0 = give the item again)
    private ItemStack coin;          // the series' coin (a held item set in-game); null = the engine's default token
    private final List<String> seeded = new ArrayList<>();   // crates already imported by seed_crate (so it happens once)
    public List<String> seeded() { return seeded; }
    private final Random rng = new Random();

    public GachaSeries(File dir, String key, String title) {
        this.key = key; this.title = title;
        this.file = new File(dir, key + ".yml");
    }

    public String key() { return key; }
    public String title() { return title; }
    public File file() { return file; }
    public List<Entry> loot() { return Collections.unmodifiableList(loot); }
    public double duplicateMoney() { return duplicateMoney; }
    public ItemStack coin() { return coin == null ? null : coin.clone(); }
    public void setCoin(ItemStack it) { this.coin = it == null ? null : it.clone(); if (this.coin != null) this.coin.setAmount(1); save(); }
    public Entry entry(String id) { for (Entry e : loot) if (e.id.equals(id)) return e; return null; }
    public List<Entry> byRarity(String rarity) { List<Entry> l = new ArrayList<>(); for (Entry e : loot) if (e.rarity.equals(rarity)) l.add(e); return l; }
    public Record record(UUID player) { return players.computeIfAbsent(player, k -> new Record()); }
    public boolean owns(UUID player, String entryId) { Record r = players.get(player); return r != null && r.owned.contains(entryId); }

    /** Add a held item as a new entry (the stack is copied as-is, amount included). */
    public Entry add(ItemStack item, String rarity, String name) {
        Entry e = new Entry(UUID.randomUUID().toString().substring(0, 8));
        e.item = item.clone(); e.rarity = rarity;
        e.name = name != null ? name : displayName(item);
        loot.add(e); save();
        return e;
    }

    /** Add a command reward (console commands with {player}); {@code icon} is what players see. */
    public Entry addCommand(String name, String rarity, List<String> commands, ItemStack icon) {
        Entry e = new Entry(UUID.randomUUID().toString().substring(0, 8));
        e.name = name; e.rarity = rarity; e.commands.addAll(commands); e.icon = icon == null ? null : icon.clone();
        loot.add(e); save();
        return e;
    }
    public Entry byName(String name) { for (Entry e : loot) if (e.name.equalsIgnoreCase(name)) return e; return null; }
    public void removeNoSave(String id) { loot.removeIf(e -> e.id.equals(id)); }

    public void remove(String id) { loot.removeIf(e -> e.id.equals(id)); save(); }

    /** A weighted pick inside a rarity, or null if the rarity has no entries. */
    public Entry roll(String rarity) {
        List<Entry> pool = byRarity(rarity);
        double total = 0; for (Entry e : pool) total += Math.max(0, e.weight);
        if (pool.isEmpty() || total <= 0) return null;
        double r = rng.nextDouble() * total;
        for (Entry e : pool) { r -= Math.max(0, e.weight); if (r <= 0) return e; }
        return pool.get(pool.size() - 1);
    }

    /** "minecraft:diamond_sword[minecraft:enchantments={levels:{'minecraft:sharpness':4}}] x3" → an ItemStack (null if it won't parse). */
    public static ItemStack itemFromString(String s) {
        try {
            String t = s.trim(); int amount = 1;
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\s+[x×](\\d+)$").matcher(t);
            if (mm.find()) { amount = Math.max(1, Integer.parseInt(mm.group(1))); t = t.substring(0, mm.start()).trim(); }
            ItemStack it = org.bukkit.Bukkit.getItemFactory().createItemStack(t);
            it.setAmount(amount);
            return it;
        } catch (Throwable ex) { return null; }
    }

    /** What a plain item looks like in menus — its display name or a prettified material. */
    public static String displayName(ItemStack it) {
        if (it == null) return "?";
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            try { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName()); } catch (Throwable ignored) { }
        }
        String n = it.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // --- persistence -----------------------------------------------------------------

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().header("Capsule series '" + key + "' — loot fed in-game (each entry is the exact item, bytes-serialised)\n"
                + "plus every player's record. Edit name / rarity / weight / once / capsule here or in Dev's Diary, then /mc gacha reload.\n"
                + "material + display are for editors only (derived from the item; the engine rewrites them).");
        y.set("title", title);
        y.set("duplicate_money", duplicateMoney);
        y.set("seeded_crates", new ArrayList<>(seeded));
        if (coin != null) {
            y.set("coin_material", coin.getType().name().toLowerCase()); y.set("coin_display", displayName(coin));
            try { y.set("coin", Base64.getEncoder().encodeToString(coin.serializeAsBytes())); } catch (Throwable ex) { y.set("coin_yaml", coin.serialize()); }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry e : loot) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id); m.put("name", e.name); m.put("rarity", e.rarity); m.put("weight", e.weight); m.put("once", e.once);
            m.put("capsule", e.capsule == null ? "" : e.capsule);
            if (e.item != null) {
                m.put("material", e.item.getType().name().toLowerCase()); m.put("amount", e.item.getAmount()); m.put("display", displayName(e.item));
                try { m.put("item", Base64.getEncoder().encodeToString(e.item.serializeAsBytes())); } catch (Throwable ex) { m.put("item_yaml", e.item.serialize()); }
            } else if (!e.commands.isEmpty()) {
                m.put("commands", new ArrayList<>(e.commands));
                ItemStack ic = e.icon != null ? e.icon : new ItemStack(org.bukkit.Material.PAPER);
                m.put("material", ic.getType().name().toLowerCase()); m.put("amount", 1); m.put("display", e.name);
                try { m.put("icon", Base64.getEncoder().encodeToString(ic.serializeAsBytes())); } catch (Throwable ex) { }
            }
            list.add(m);
        }
        y.set("loot", list);
        ConfigurationSection ps = y.createSection("players");
        for (Map.Entry<UUID, Record> pe : players.entrySet()) {
            ConfigurationSection one = ps.createSection(pe.getKey().toString());
            one.set("pulls", pe.getValue().pulls); one.set("since_rare", pe.getValue().sinceRare);
            if (pe.getValue().sinceGrab > 0) one.set("since_grab", pe.getValue().sinceGrab);
            one.set("owned", new ArrayList<>(pe.getValue().owned));
            ConfigurationSection cs = one.createSection("counts");
            for (Map.Entry<String, Integer> c : pe.getValue().counts.entrySet()) cs.set(c.getKey(), c.getValue());
        }
        try { file.getParentFile().mkdirs(); y.save(file); } catch (Exception ignored) { }
    }

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        loot.clear(); players.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        if (y.isString("title")) title = y.getString("title");
        duplicateMoney = y.getDouble("duplicate_money", 0);
        seeded.clear(); seeded.addAll(y.getStringList("seeded_crates"));
        coin = null;
        try {
            if (y.isString("coin") && !y.getString("coin", "").isEmpty()) coin = ItemStack.deserializeBytes(Base64.getDecoder().decode(y.getString("coin")));
            else if (y.isConfigurationSection("coin_yaml")) coin = ItemStack.deserialize(y.getConfigurationSection("coin_yaml").getValues(true));
        } catch (Throwable ex) { coin = null; }
        for (Map<?, ?> m : y.getMapList("loot")) {
            Entry e = new Entry(m.get("id") == null ? UUID.randomUUID().toString().substring(0, 8) : String.valueOf(m.get("id")));
            e.name = m.get("name") == null ? "?" : String.valueOf(m.get("name"));
            e.rarity = m.get("rarity") == null ? "common" : String.valueOf(m.get("rarity"));
            e.weight = m.get("weight") instanceof Number n ? n.doubleValue() : 1;
            e.once = Boolean.TRUE.equals(m.get("once"));
            e.capsule = m.get("capsule") == null ? "" : String.valueOf(m.get("capsule"));
            try {
                if (m.get("item") instanceof String b64 && !b64.isEmpty()) e.item = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
                else if (m.get("item_yaml") instanceof Map<?, ?> im) e.item = ItemStack.deserialize((Map<String, Object>) im);
            } catch (Throwable ex) { e.item = null; }
            if (e.item == null && m.get("item_string") instanceof String is && !is.isBlank()) {   // authored form: minecraft:diamond_sword[components] ×n
                e.item = itemFromString(is);
            }
            if (m.get("commands") instanceof List<?> cl) for (Object c : cl) e.commands.add(String.valueOf(c));
            try { if (m.get("icon") instanceof String ib && !ib.isEmpty()) e.icon = ItemStack.deserializeBytes(Base64.getDecoder().decode(ib)); } catch (Throwable ex) { e.icon = null; }
            if (e.icon == null && e.item == null && m.get("material") instanceof String mat) {
                org.bukkit.Material mm = org.bukkit.Material.matchMaterial(mat.toUpperCase());
                if (mm != null) e.icon = new ItemStack(mm);
            }
            if (e.item != null || !e.commands.isEmpty()) loot.add(e);
        }
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) for (String k : ps.getKeys(false)) {
            try {
                Record r = new Record();
                ConfigurationSection one = ps.getConfigurationSection(k);
                if (one == null) continue;
                r.pulls = one.getInt("pulls"); r.sinceRare = one.getInt("since_rare"); r.sinceGrab = one.getInt("since_grab");
                r.owned.addAll(one.getStringList("owned"));
                ConfigurationSection cs = one.getConfigurationSection("counts");
                if (cs != null) for (String id : cs.getKeys(false)) r.counts.put(id, cs.getInt(id));
                players.put(UUID.fromString(k), r);
            } catch (IllegalArgumentException ignored) { }
        }
    }
}
