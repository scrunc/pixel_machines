package dev.servereer.machineconstruct.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.util.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One packet-only display entity — block, item, head, or text per its
 * {@link DisplayContent} — positioned/oriented/scaled by an {@link MTransform}.
 * All entities of a model spawn at the same origin (the chest anchor) and differ
 * only by transform + content metadata (DESIGN.md §6, §10.7).
 *
 * <p>Shared display metadata indices (1.20.2–1.21.x): 8/9 interpolation,
 * 11 translation, 12 scale, 13 left-rotation, 15 billboard. Content-specific
 * fields (23+) come from {@link DisplayContent}.
 */
public final class PacketDisplay {

    private static final int META_INTERP_DELAY = 8;
    private static final int META_INTERP_DURATION = 9;
    private static final int META_TRANSLATION = 11;
    private static final int META_SCALE = 12;
    private static final int META_LEFT_ROTATION = 13;
    private static final int META_BILLBOARD = 15;

    private final int entityId = SpigotReflectionUtil.generateEntityId();
    private final UUID uuid = UUID.randomUUID();
    private final Location origin;
    private DisplayContent baseContent;             // spawn-time content; reverted to when no swap is active (live text rebases it)
    private volatile DisplayContent content;        // current content (may be a swap frame)
    private volatile MTransform transform;
    private byte billboard = 0;   // 0 fixed | 1 vertical | 2 horizontal | 3 center
    // Billboarded displays (labels) rotate their WHOLE transform to face the viewer, translation
    // included — so a label placed by translation swings around the machine as the camera moves.
    // For those the model-space offset is baked into the entity's spawn position instead and the
    // metadata translation only carries what's left (animation deltas), which is then tiny.
    private float bakeX, bakeY, bakeZ;
    private volatile boolean dirty = true;          // needs a metadata resend (true = send once on first tick)

    public PacketDisplay(Location origin, DisplayContent content, MTransform transform) {
        this.origin = origin.clone();
        this.baseContent = content;
        this.content = content;
        this.transform = transform;
    }

    /** Convenience: a uniformly-scaled block at the origin (debug / fallback). */
    public static PacketDisplay block(Location origin, BlockData block, float uniformScale) {
        return new PacketDisplay(origin, new BlockContent(block),
                new MTransform(0, 0, 0, uniformScale, uniformScale, uniformScale, 0, 0, 0, 1));
    }

    public int entityId() {
        return entityId;
    }

    public Location origin() {
        return origin.clone();
    }

    private String partName;   // the model part this leaf came from (for editors), or null
    private ButtonSpec button; // set when this part is a physical button

    public PacketDisplay button(ButtonSpec b) { this.button = b; return this; }
    public ButtonSpec button() { return button; }
    public MTransform transform() { return transform; }

    private static final int META_FLAGS = 0;          // entity flags: 0x40 = glowing
    private static final int META_GLOW_COLOR = 22;    // display: glow colour override (ARGB)

    private static final int META_TEXT_OPACITY = 26;

    /** Text displays only: set the text alpha for one viewer (transition effects). */
    public void sendTextOpacity(Player viewer, byte alpha) {
        if (!(content instanceof TextContent)) return;
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(META_TEXT_OPACITY, EntityDataTypes.BYTE, alpha));
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, data));
    }

    /** Per-viewer outline glow (hover feedback) — only this viewer sees it. */
    public void sendGlow(Player viewer, boolean on) {
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(META_FLAGS, EntityDataTypes.BYTE, (byte) (on ? 0x40 : 0)));
        data.add(new EntityData<>(META_GLOW_COLOR, EntityDataTypes.INT, on && button != null ? button.hoverArgb() : -1));
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, data));
    }

    public PacketDisplay partName(String name) { this.partName = name; return this; }
    public String partName() { return partName; }

    /** Replace the BASE content (what a null animation swap reverts to) — live text uses this. */
    public void rebase(DisplayContent next) {
        this.baseContent = next;
        this.content = next;
        this.dirty = true;
    }

    public DisplayContent baseContent() { return baseContent; }

    public PacketDisplay billboard(byte billboard) {
        this.billboard = billboard;
        if (billboard != 0) { bakeX = transform.tx; bakeY = transform.ty; bakeZ = transform.tz; }
        else { bakeX = bakeY = bakeZ = 0f; }
        return this;
    }

    public void setTransform(MTransform transform) {
        if (locked) return;   // a cue owns this part for now — the animation loop's recompute must not fight it
        if (this.transform == null || !this.transform.same(transform)) dirty = true;
        this.transform = transform;
    }

    private boolean locked;   // set by a running cue (CuePlayer): setTransform/setContent from elsewhere are ignored

    /** Lock the part for a cue — only {@link #forceTransform}/{@link #forceContent} change it until unlocked. */
    public void lock(boolean on) { this.locked = on || pinned; }
    public boolean locked() { return locked; }
    private boolean pinned;   // a permanent lock (cue-driven parts hidden at render): cues may change it, nothing else ever does
    public void pin() { this.pinned = true; this.locked = true; }
    public boolean pinned() { return pinned; }
    public void forceTransform(MTransform t) { if (this.transform == null || !this.transform.same(t)) dirty = true; this.transform = t; }
    public void forceContent(DisplayContent c) { DisplayContent next = c == null ? baseContent : c; if (next != this.content) dirty = true; this.content = next; }
    public DisplayContent content() { return content; }

    /** Outline glow in a given colour for one viewer (null = off); machine-wide effects send it to every viewer. */
    public void sendGlowArgb(Player viewer, Integer argb) {
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(META_FLAGS, EntityDataTypes.BYTE, (byte) (argb != null ? 0x40 : 0)));
        data.add(new EntityData<>(META_GLOW_COLOR, EntityDataTypes.INT, argb != null ? argb : -1));
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, data));
    }

    /** Whether this display needs a metadata resend; clears the flag (consume-once). */
    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public void markDirty() { dirty = true; }

    /**
     * Set the current content (a swap frame). {@code null} reverts to the
     * spawn-time base content. Only valid within the same display type — the
     * next {@link #sendMetadata} re-sends the content index to viewers; the
     * entity is never re-typed.
     */
    public void setContent(DisplayContent content) {
        if (locked) return;
        DisplayContent next = (content == null) ? baseContent : content;
        if (next != this.content) dirty = true;
        this.content = next;
    }

    public void spawn(Player viewer) {
        com.github.retrooper.packetevents.protocol.world.Location pos =
                new com.github.retrooper.packetevents.protocol.world.Location(
                        origin.getX() + bakeX, origin.getY() + bakeY, origin.getZ() + bakeZ, 0f, 0f);
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                entityId, uuid, content.entityType(), pos, 0f, 0, null);
        send(viewer, spawn);
        sendMetadata(viewer, 0);
    }

    public void sendMetadata(Player viewer, int interpTicks) {
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(META_INTERP_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(META_INTERP_DURATION, EntityDataTypes.INT, interpTicks));
        data.add(new EntityData<>(META_TRANSLATION, EntityDataTypes.VECTOR3F,
                new Vector3f(transform.tx - bakeX, transform.ty - bakeY, transform.tz - bakeZ)));
        data.add(new EntityData<>(META_SCALE, EntityDataTypes.VECTOR3F, transform.scaleVec()));
        data.add(new EntityData<>(META_LEFT_ROTATION, EntityDataTypes.QUATERNION, transform.leftRotation()));
        data.add(new EntityData<>(META_BILLBOARD, EntityDataTypes.BYTE, billboard));
        content.appendMeta(data);
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, data));
    }

    public void despawn(Player viewer) {
        send(viewer, new WrapperPlayServerDestroyEntities(entityId));
    }

    private void send(Player viewer, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> wrapper) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper);
        } catch (Throwable ignored) {
            // Player disconnecting / channel closed — never let one bad send break a render loop.
        }
    }
}
