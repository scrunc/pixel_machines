package dev.servereer.machineconstruct.core;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.block.data.BlockData;

import java.util.List;

/** A {@code block_display}'s payload — a vanilla block state. */
public final class BlockContent implements DisplayContent {

    private static final int META_BLOCK_STATE = 23;
    private final int blockStateId;

    public BlockContent(BlockData block) {
        this.blockStateId = SpigotConversionUtil.fromBukkitBlockData(block).getGlobalId();
    }

    @Override
    public EntityType entityType() {
        return EntityTypes.BLOCK_DISPLAY;
    }

    @Override
    public void appendMeta(List<EntityData<?>> data) {
        data.add(new EntityData<>(META_BLOCK_STATE, EntityDataTypes.BLOCK_STATE, blockStateId));
    }
}
