package dev.servereer.machineconstruct.core;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;

import java.util.List;

/**
 * The type-specific payload of a {@link PacketDisplay}: which display entity to
 * spawn and the metadata that distinguishes it (block state / item / text).
 * Lets one PacketDisplay render a block, item, player-head, or text display
 * while sharing all the transform + interpolation plumbing.
 */
public interface DisplayContent {

    /** The display entity type (BLOCK_DISPLAY / ITEM_DISPLAY / TEXT_DISPLAY). */
    EntityType entityType();

    /** Append the content-specific metadata (index 23+) for this display. */
    void appendMeta(List<EntityData<?>> data);

    /**
     * Whether this content renders centered on its position (item/text) vs from
     * a corner (block). Center-anchored parts get auto-offset by +scale/2 so
     * their {@code offset}/{@code scale} occupy the same box a block would —
     * making block↔item swaps behave predictably (DESIGN.md §9).
     */
    default boolean centerAnchored() {
        return false;
    }

    /**
     * Per-axis factor that makes this content <b>fill</b> its scale box like a
     * block does. A {@code block_display} fills exactly (1,1,1); a player-head
     * {@code item_display} renders as a ½-block skull, so it reports (2,2,2) to
     * cancel that out. Applied to the render scale in the center-anchor path so
     * a {@code head:} at the same {@code offset}/{@code scale} as a {@code block:}
     * occupies the identical footprint — swapping one for the other is a no-op.
     */
    default float[] intrinsicFill() {
        return new float[]{1f, 1f, 1f};
    }
}
