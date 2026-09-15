package dev.servereer.machineconstruct.music;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Mints and reads custom music discs — a music disc {@link ItemStack} NBT-bound to a saved track id via
 * a {@code mc:disc} PDC tag. This is the minimal custom-item layer the jukebox needs (MachineConstruct
 * has no general item registry); it mirrors how the engine already tags placer heads.
 */
public final class DiscItem {

    private static final Material BASE = Material.MUSIC_DISC_11;   // neutral vanilla disc as the carrier

    private final NamespacedKey key;

    public DiscItem(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "disc");
    }

    /** Mint {@code amount} discs bound to {@code track}. */
    public ItemStack mint(TrackLibrary.Track track, int amount) {
        ItemStack disc = new ItemStack(BASE, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = disc.getItemMeta();
        if (meta == null) return disc;
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, track.id);
        meta.displayName(MiniMessage.miniMessage()
                .deserialize("<gradient:#f9d423:#ff4e50>♪ " + track.title + "</gradient>")
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Custom Music Disc", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (track.durationMs > 0) {
            long s = track.durationMs / 1000;
            lore.add(Component.text("Length: " + (s / 60) + ":" + String.format("%02d", s % 60), NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (track.sourceUrl != null && !track.sourceUrl.isBlank()) {
            lore.add(Component.text("Source: " + track.sourceUrl, NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Insert into a Jukebox to play", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        disc.setItemMeta(meta);
        return disc;
    }

    /** The bound track id, or null if {@code item} isn't one of our discs. */
    public String trackId(ItemStack item) {
        if (item == null || item.getType() != BASE || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public boolean isDisc(ItemStack item) {
        return trackId(item) != null;
    }
}
