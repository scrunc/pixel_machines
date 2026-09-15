package dev.servereer.machineconstruct.grinder;

import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Shared chest I/O for every vault-backed machine (grinder pool, collector vault, district vault):
 * {@link #routeOutputs} pushes the machine's items into OUTPUT chests, {@link #routeInputs} pulls
 * items out of INPUT chests into the machine. Both honour each link's item filter and flow rate
 * (full transfer or N/sec, carried across sweeps via {@link ChestLink#advanceFlow}), respect free
 * chest space / the sink's mass cap, and never touch another machine's anchor chest (the
 * {@code isMachineAnchor} guard). Distance is a cheap coordinate check and chests in unloaded
 * chunks are skipped — never force-loaded — so reach never costs CPU at runtime.
 */
public final class ChestRouter {

    private ChestRouter() {}

    /** Grinder OUTPUT routing: push the grinder's pool into linked chests. */
    public static boolean routeOutputs(GrinderData d, Location anchor, int radius,
                                       Predicate<Location> isMachineAnchor, long now) {
        return routeOutputs(d.links(), d.pool(), anchor, radius, isMachineAnchor, now);
    }

    /**
     * Generic OUTPUT routing: push items out of {@code source} (a grinder pool or a district vault)
     * into each OUTPUT link's chest within range, honouring its item filter + flow rate and free
     * chest space. INPUT links are skipped (the manager handles those with spec/tier context).
     */
    public static boolean routeOutputs(List<ChestLink> links, Map<ItemStack, Long> source, Location anchor,
                                       int radius, Predicate<Location> isMachineAnchor, long now) {
        if (links.isEmpty()) return false;
        boolean changed = false;
        long r2 = (long) radius * radius;
        for (ChestLink link : links) {
            if (link.type() != ChestLink.Type.OUTPUT) continue;
            Location loc = link.loc();
            if (loc.getWorld() == null || anchor == null || anchor.getWorld() == null) continue;
            if (!loc.getWorld().getUID().equals(anchor.getWorld().getUID())) continue;
            if (loc.distanceSquared(anchor) > r2 + 2) continue;                       // out of range (safety)
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            if (isMachineAnchor != null && isMachineAnchor.test(loc)) continue;       // never feed a machine anchor
            if (!(loc.getBlock().getState() instanceof Container container)) continue; // chest/barrel/hopper/etc
            long moved = push(source, link, container.getInventory(), now);
            changed |= moved > 0;
            link.advanceFlow(now, moved);   // carry leftover fraction so slow rates (e.g. 1/sec) still flow
        }
        return changed;
    }

    /**
     * Generic INPUT routing: pull items OUT of each INPUT link's chest within range INTO {@code sink}
     * (a grinder pool, collector vault, or district vault), honouring its item filter + flow rate, the
     * sink's mass {@code cap} (0 = unlimited; a full sink stops the pull), and a per-chest
     * {@code allowedToPull} grief gate ({@code null} = always allowed). Items land as the documented
     * "amount-1 template → count" contract. Returns true if anything moved.
     */
    public static boolean routeInputs(List<ChestLink> links, Map<ItemStack, Long> sink, long cap, long currentMass,
                                      Location anchor, int radius, Predicate<Location> isMachineAnchor,
                                      Predicate<Location> allowedToPull, long now) {
        if (links.isEmpty()) return false;
        boolean changed = false;
        long r2 = (long) radius * radius;
        long mass = currentMass;
        for (ChestLink link : links) {
            if (link.type() != ChestLink.Type.INPUT) continue;
            Location loc = link.loc();
            if (loc.getWorld() == null || anchor == null || anchor.getWorld() == null) continue;
            if (!loc.getWorld().getUID().equals(anchor.getWorld().getUID())) continue;
            if (loc.distanceSquared(anchor) > r2 + 2) continue;                       // out of range (safety)
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            if (isMachineAnchor != null && isMachineAnchor.test(loc)) continue;       // never drain a machine anchor
            if (allowedToPull != null && !allowedToPull.test(loc)) continue;          // grief gate (claim/region)
            if (!(loc.getBlock().getState() instanceof Container container)) continue; // chest/barrel/hopper/etc
            long moved = pull(container.getInventory(), link, sink, cap, mass, now);
            if (moved > 0) { mass += moved; changed = true; }
            link.advanceFlow(now, moved);   // carry leftover fraction so slow rates (e.g. 1/sec) still flow
        }
        return changed;
    }

    /** Pull as much as the link + sink cap allow this sweep; returns the number of items actually moved. */
    private static long pull(Inventory inv, ChestLink link, Map<ItemStack, Long> sink, long cap, long mass, long now) {
        long allowance = link.allowance(now);
        long moved = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (!link.fullTransfer() && moved >= allowance) break;
            if (cap > 0 && mass + moved >= cap) break;                                 // sink full → hold
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) continue;
            if (!link.matches(it.getType())) continue;                                 // filter (empty = pull all)
            long room = cap > 0 ? Math.max(0, cap - (mass + moved)) : Long.MAX_VALUE;
            long limit = link.fullTransfer() ? room : Math.min(room, allowance - moved);
            int take = (int) Math.min(it.getAmount(), limit);
            if (take <= 0) continue;
            ItemStack tmpl = it.clone();
            tmpl.setAmount(1);
            sink.merge(tmpl, (long) take, Long::sum);                                  // amount-1 template → count
            if (take >= it.getAmount()) inv.setItem(i, null);
            else { it.setAmount(it.getAmount() - take); inv.setItem(i, it); }
            moved += take;
        }
        return moved;
    }

    /** Push as much as the link allows this sweep; returns the number of items actually moved. */
    private static long push(Map<ItemStack, Long> source, ChestLink link, Inventory inv, long now) {
        long allowance = link.allowance(now);
        long moved = 0;
        for (Map.Entry<ItemStack, Long> e : new ArrayList<>(source.entrySet())) {
            if (!link.fullTransfer() && moved >= allowance) break;
            ItemStack tmpl = e.getKey();
            long have = e.getValue() == null ? 0 : e.getValue();
            if (have <= 0 || !link.matches(tmpl.getType())) continue;
            long want = link.fullTransfer() ? have : Math.min(have, allowance - moved);
            if (want <= 0) continue;
            long placed = insert(inv, tmpl, want);
            if (placed > 0) {
                long left = have - placed;
                if (left <= 0) source.remove(tmpl); else source.put(tmpl, left);
                moved += placed;
            }
        }
        return moved;
    }

    /** Add up to {@code want} of one template into the inventory; returns how many fit. */
    private static long insert(Inventory inv, ItemStack tmpl, long want) {
        long placed = 0;
        int max = Math.max(1, tmpl.getMaxStackSize());
        while (want > 0) {
            int amt = (int) Math.min(want, max);
            ItemStack give = tmpl.clone();
            give.setAmount(amt);
            Map<Integer, ItemStack> left = inv.addItem(give);
            int leftover = 0;
            for (ItemStack ls : left.values()) leftover += ls.getAmount();
            placed += amt - leftover;
            want -= amt;
            if (leftover > 0) break;   // chest full
        }
        return placed;
    }
}
