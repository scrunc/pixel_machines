package dev.servereer.machineconstruct.machine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Builds and detects the machine-placer item — a player head (look = its skin,
 * no resource pack) carrying a PDC tag with the machine type id. P2: plain head
 * + name + tag; per-type head-skins arrive with content (Foundry, P3+).
 */
public final class Placer {

    private Placer() {}

    public static ItemStack create(NamespacedKey placerKey, String typeId) {
        return create(placerKey, typeId, null);
    }

    /**
     * Build a placer. {@code prototype} (if non-null) supplies the configurable icon/head,
     * name, and lore; anything it leaves unset falls back to the default head + labels.
     */
    public static ItemStack create(NamespacedKey placerKey, String typeId, ItemStack prototype) {
        ItemStack head = (prototype != null && !prototype.getType().isAir())
                ? prototype.clone() : new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (!meta.hasDisplayName()) {
            meta.displayName(Component.text("Machine Placer", NamedTextColor.GOLD)
                    .append(Component.text(" · " + typeId, NamedTextColor.GRAY)));
        }
        if (meta.lore() == null || meta.lore().isEmpty()) {
            meta.lore(List.of(
                    Component.text("Right-click a block face to build.", NamedTextColor.GRAY),
                    Component.text("Break it to pick it back up.", NamedTextColor.DARK_GRAY)));
        }
        meta.getPersistentDataContainer().set(placerKey, PersistentDataType.STRING, typeId);
        head.setItemMeta(meta);
        return head;
    }

    /** Returns the placer's machine type id, or null if the item isn't a placer. */
    public static String typeOf(NamespacedKey placerKey, ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(placerKey, PersistentDataType.STRING);
    }
}
