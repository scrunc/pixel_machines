package dev.servereer.machineconstruct.gacha;

import dev.servereer.machineconstruct.core.Heads;
import dev.servereer.machineconstruct.gui.MenuSkin;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's coin ladder — ten tiered tokens ({@code plugins/MachineConstruct/coins.yml}, written
 * with defaults on first run so names / colours / head textures can be changed). A capsule machine
 * says which tier it takes ({@code price: { coins: 1, coin: rare }}); a series can still override
 * that with its own held-item coin. Every coin is a player head tagged {@code machineconstruct:coin =
 * <tier>} so nothing else ever matches it. {@code /mc coin give <player> <n> <tier>} hands them out.
 */
public final class Coins {

    public record Tier(String key, String label, String color, String texture, List<String> lore) { }

    private static final String[][] DEFAULTS = {
        // key, label, colour, texture (minecraft-heads.com "Coin (…)" heads)
        { "common",    "Common Coin",    "#9fb4c7", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTgxOGU2NzI1YmI4NDUwMDBlZTU2NWFmMGE0ZTJkNjY3NTU4MzkxMTU0MWVjNWE0ZmIyYWNiZDg5MTNhMDM3YSJ9fX0=" },
        { "uncommon",  "Uncommon Coin",  "#35e0d0", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDJlZWM1YzVkNTAzMDJmZjEwZDBiZGI2MmQ3OWU2N2EwYWIxMTAxNjk2YWUyN2VmOWQ4MmIzNzk0M2MyYTY1YyJ9fX0=" },
        { "rare",      "Rare Coin",      "#4f7fe8", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTM0YjI3YmZjYzhmOWI5NjQ1OTRiNjE4YjExNDZhZjY5ZGUyNzhjZTVlMmUzMDEyY2I0NzFhOWEzY2YzODcxIn19fQ==" },
        { "epic",      "Epic Coin",      "#b07cff", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGZkOWNiYmU0ZThmNzZmMDk0MGZmNzljYWIwZDg3NWMxYmNiOWRjMzhhM2Y1MjIxMzU4Njc3ZjUyMTJjYmMwIn19fQ==" },
        { "legendary", "Legendary Coin", "#ffc24d", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjBhN2I5NGM0ZTU4MWI2OTkxNTlkNDg4NDZlYzA5MTM5MjUwNjIzN2M4OWE5N2M5MzI0OGEwZDhhYmM5MTZkNSJ9fX0=" },
        { "mythic",    "Mythic Coin",    "#ff4d6d", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTgwMjU5NzA5ODg4OGM1OTJlMDlkNTlhMmFkZTU3NmQ3NWQzZTQ5NDY1ZDE1NzI0YjRhODc4OWQ4NjNmNWJkNCJ9fX0=" },
        { "ancient",   "Ancient Coin",   "#c9a27a", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTU4YjZkYzhkMDQ5Mzg3YWJiZTY5OGQ0ODBlNjExNzcyN2NmZjRhYTQyOGEyYTQyMzY1NWE5OTI5ZTkwMmRiOSJ9fX0=" },
        { "abyssal",   "Abyssal Coin",   "#4ade80", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTFlZGYxNmM0MWQxOTRjNzMxZTMzZmRkOWMyYjllNWVkZDQ1MGJjMzNjYTcwNDM2NTI4YTA1Mzg5ZDdmY2RhMiJ9fX0=" },
        { "kraken",    "Kraken Coin",    "#8e7cc3", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWJkYTVmMzE5MzdiMmZmNzU1MjcxZDk3ZjAxYmU4NGQ1MmE0MDdiMzZjYTc3NDUxODU2MTYyYWM2Y2ZiYjM0ZiJ9fX0=" },
        // named coins outside the ladder — machines pick them by key too
        { "armor",     "Armor Coin",     "#cfd8dc", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWNiZDlmNWVjMWVkMDA3MjU5OTk2NDkxZTY5ZmY2NDlhMzEwNmNmOTIwMjI3YjFiYjNhNzFlZTdhODk4NjNmIn19fQ==" },
        { "pearl",     "Pearl Coin",     "#f0d8ff", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDE0N2MyMGM0ZjM1YmQyN2QzZDQxOTEyNzkyYTc5OGU5ZjRmOWJiZmUwNGYwZDMyNTVkOWJjYWRmOGE0MWFhZSJ9fX0=" },
        { "leviathan", "Leviathan Coin", "#7ff0e0", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjA2ZWUxZDM3MDRmNDQ1ODc2MTlhMjI1MmMzMzM5YWY3ODU1MjgwMjAzYjI1OTE4MWVmZDE4NzI0NWFiZjgyNCJ9fX0=" },
    };

    private final Plugin plugin;
    private final NamespacedKey key;
    private boolean placeable;          // coins.yml: may a coin be stuck on a wall as a head?
    private String placeMessage = "<yellow>That is a coin, not a building block.";
    private final Map<String, Tier> tiers = new LinkedHashMap<>();

    public Coins(Plugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "coin");
        load();
    }

    public NamespacedKey pdcKey() { return key; }
    /** Whether a coin may be PLACED as a head block. Off by default — see {@link CoinGuard}. */
    public boolean placeable() { return placeable; }
    /** What a player is told when they try. */
    public String placeMessage() { return placeMessage; }
    public List<String> keys() { return new ArrayList<>(tiers.keySet()); }
    public Tier tier(String k) { return k == null ? null : tiers.get(k.toLowerCase()); }

    /** The tier's coin, amount 1 — a tagged head (nothing untagged ever matches it). */
    public ItemStack item(String tierKey) {
        Tier t = tier(tierKey);
        if (t == null) t = tiers.values().iterator().next();
        ItemStack it = Heads.create(t.texture());
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(MenuSkin.mini("<!italic><" + t.color() + ">" + t.label()));
            List<String> lore = new ArrayList<>();
            for (String l : t.lore()) lore.add("<!italic>" + l);
            meta.lore(MenuSkin.miniList(lore));
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, t.key());
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Which tier a stack is (null = not a coin). */
    public String tierOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private void load() {
        File f = new File(plugin.getDataFolder(), "coins.yml");
        if (!f.exists()) writeDefaults(f);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        placeable = y.getBoolean("placeable", false);
        placeMessage = y.getString("place-message", placeMessage);
        ConfigurationSection cs = y.getConfigurationSection("coins");
        if (cs != null) for (String k : cs.getKeys(false)) {
            ConfigurationSection one = cs.getConfigurationSection(k);
            if (one == null) continue;
            List<String> lore = one.getStringList("lore");
            if (lore.isEmpty()) lore = List.of("<gray>Feeds a capsule machine.", "<dark_gray>Turn the dial to spend it.");
            tiers.put(k.toLowerCase(), new Tier(k.toLowerCase(), one.getString("name", k), one.getString("color", "#ffffff"), one.getString("texture", DEFAULTS[0][3]), lore));
        }
        boolean added = false;   // a newer engine may ship coins the file predates — add them, never overwrite edits
        for (String[] d : DEFAULTS) if (!tiers.containsKey(d[0])) {
            tiers.put(d[0], new Tier(d[0], d[1], d[2], d[3], List.of("<gray>Feeds a capsule machine.", "<dark_gray>Turn the dial to spend it.")));
            ConfigurationSection one = y.createSection("coins." + d[0]);
            one.set("name", d[1]); one.set("color", d[2]); one.set("texture", d[3]);
            one.set("lore", List.of("<gray>Feeds a capsule machine.", "<dark_gray>Turn the dial to spend it."));
            added = true;
        }
        if (added) { try { y.save(f); } catch (Exception ignored) { } }
    }

    private void writeDefaults(File f) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().header("The coin ladder — ten tiered tokens capsule machines take (price: { coins: 1, coin: <key> }).\n"
                + "name / color are MiniMessage-ish (#hex); texture is a player-head textures value or URL. Keys are the\n"
                + "order of the ladder (worst → best). /mc coin give <player> <n> <key> hands them out; /mc reload re-reads.\n\n"
                + "placeable: a coin is a player head, and a head is placeable — stick one on a wall and it stops\n"
                + "being a coin (the block keeps the skin, loses the tag). Left false, placing one is refused.");
        y.set("placeable", false);
        y.set("place-message", "<yellow>That is a coin, not a building block.");
        for (String[] d : DEFAULTS) {
            ConfigurationSection one = y.createSection("coins." + d[0]);
            one.set("name", d[1]); one.set("color", d[2]); one.set("texture", d[3]);
            one.set("lore", List.of("<gray>Feeds a capsule machine.", "<dark_gray>Turn the dial to spend it."));
        }
        try { f.getParentFile().mkdirs(); y.save(f); } catch (Exception ignored) { }
    }
}
