package dev.servereer.machineconstruct.collector;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.function.Predicate;

/**
 * The Chunk Collector's vacuum logic — pure functions over a {@link ChunkCollectorData}.
 * No persistence/economy here (the manager in the engine owns those); this just sucks
 * up dropped-item entities in the tier's chunk ring into the vault, respecting the cap.
 *
 * <p>Must run on the main thread (it touches live entities) — the engine calls it from
 * the synchronous processing tick.
 */
public final class ChunkCollectorManager {

    private ChunkCollectorManager() {}

    /**
     * Sweep every loaded chunk in the radius ring around {@code anchor}, pulling matching
     * dropped items into the vault up to {@code cap} (0 = unlimited). Partial stacks are
     * left on the ground when the cap is hit (overflow = stop). Returns how many items
     * were vacuumed this pass.
     *
     * <p>{@code allowed} is a grief gate: an item is only collected if {@code allowed.test(itemLocation)}
     * is true (null = no restriction). The engine supplies an owner build-probe so the collector
     * never vacuums drops from areas the owner can't build in.
     */
    public static int vacuum(ChunkCollectorData d, ChunkCollectorSpec spec, int tier, Location anchor, long cap,
                             Predicate<Location> allowed) {
        if (d == null || spec == null || anchor == null) return 0;
        World w = anchor.getWorld();
        if (w == null) return 0;
        int radius = spec.radiusChunks(tier);
        int cx = anchor.getBlockX() >> 4;
        int cz = anchor.getBlockZ() >> 4;
        long mass = d.vaultMass();
        int collected = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = cx + dx, chunkZ = cz + dz;
                if (!w.isChunkLoaded(chunkX, chunkZ)) continue;
                Chunk c = w.getChunkAt(chunkX, chunkZ);
                for (Entity e : c.getEntities()) {
                    if (!(e instanceof Item item)) continue;
                    if (!item.isValid() || item.isDead()) continue;
                    if (spec.skipNoPickup() && item.getPickupDelay() >= Short.MAX_VALUE) continue;   // never-pickup flag
                    if (spec.graceTicks() > 0 && item.getTicksLived() < spec.graceTicks()) continue;  // too fresh (anti-snatch)
                    ItemStack stack = item.getItemStack();
                    if (stack == null || stack.getType().isAir()) continue;
                    if (!spec.accepts(stack.getType())) continue;
                    if (allowed != null && !allowed.test(item.getLocation())) continue;   // grief: skip protected drops

                    int amt = stack.getAmount();
                    int take = amt;
                    if (cap > 0) {
                        long room = cap - mass;
                        if (room <= 0) {
                            if (spec.overflow() == ChunkCollectorSpec.Overflow.STOP) return collected;   // vault full, keep drops
                            continue;
                        }
                        take = (int) Math.min(amt, room);
                    }
                    if (take <= 0) continue;

                    ItemStack tmpl = stack.clone();
                    tmpl.setAmount(1);
                    if (!d.addToVault(tmpl, take)) continue;   // distinct-type cap hit → leave this drop on the ground
                    mass += take;
                    collected += take;

                    if (take >= amt) {
                        item.remove();
                    } else {                       // cap clipped the stack — leave the remainder on the ground
                        stack.setAmount(amt - take);
                        item.setItemStack(stack);
                    }
                }
            }
        }
        return collected;
    }
}
