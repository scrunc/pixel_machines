package dev.servereer.machineconstruct.core;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import java.util.List;

/**
 * An {@code item_display}'s payload — any Bukkit item (a plain item, a player
 * head with a skin, or an item carrying custom_model_data). Display context
 * (none/head/gui/fixed/ground…) is a byte at index 24.
 */
public final class ItemContent implements DisplayContent {

    private static final int META_ITEM = 23;
    private static final int META_DISPLAY_TYPE = 24;

    private final ItemStack item;
    private final byte displayType;
    private final float[] fill;

    public ItemContent(org.bukkit.inventory.ItemStack bukkit, byte displayType) {
        this.item = SpigotConversionUtil.fromBukkitItemStack(bukkit);
        this.displayType = displayType;
        // A player head renders as a ½-block skull model — ×2 so a head at scale
        // (1,1,1) fills a full block, matching a block_display footprint.
        this.fill = bukkit.getType() == org.bukkit.Material.PLAYER_HEAD
                ? new float[]{2f, 2f, 2f} : new float[]{1f, 1f, 1f};
    }

    public byte displayType() { return displayType; }

    /** Map a display-context name to its protocol byte. */
    public static byte context(String name) {
        if (name == null) return 8; // default: fixed
        return switch (name.toLowerCase()) {
            case "none" -> 0;
            case "thirdperson_lefthand" -> 1;
            case "thirdperson_righthand" -> 2;
            case "firstperson_lefthand" -> 3;
            case "firstperson_righthand" -> 4;
            case "head" -> 5;
            case "gui" -> 6;
            case "ground" -> 7;
            default -> 8; // fixed
        };
    }

    @Override
    public boolean centerAnchored() {
        return true;   // item displays render centered on their position
    }

    @Override
    public float[] intrinsicFill() {
        return fill;
    }

    @Override
    public EntityType entityType() {
        return EntityTypes.ITEM_DISPLAY;
    }

    @Override
    public void appendMeta(List<EntityData<?>> data) {
        data.add(new EntityData<>(META_ITEM, EntityDataTypes.ITEMSTACK, item));
        data.add(new EntityData<>(META_DISPLAY_TYPE, EntityDataTypes.BYTE, displayType));
    }
}
