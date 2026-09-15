package dev.servereer.machineconstruct.spawner;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lets a player mine a spawner and collect it as a typed spawner item — no
 * SmartSpawner (or any spawner plugin) needed. Vanilla drops nothing when a
 * spawner is broken; this fills that gap.
 *
 * <p>Everything is config-gated under {@code spawner-mining:} (see config.yml).
 * Settings are read once at enable — a config change needs a server restart,
 * like the engine's other tunables.
 */
public final class SpawnerMiningListener implements Listener {

    private final boolean enabled;
    private final boolean anyPickaxe;
    private final boolean includeNatural;
    private final boolean dropAtBlock;
    private final NamespacedKey placedKey;

    public SpawnerMiningListener(JavaPlugin plugin) {
        var c = plugin.getConfig();
        this.enabled        = c.getBoolean("spawner-mining.enabled", true);
        this.anyPickaxe     = c.getBoolean("spawner-mining.any-pickaxe", true);
        this.includeNatural = c.getBoolean("spawner-mining.include-natural", true);
        this.dropAtBlock    = c.getBoolean("spawner-mining.drop-at-block", true);
        this.placedKey      = new NamespacedKey(plugin, "player_placed_spawner");
    }

    /** Whether the feature is on — the plugin only registers this listener if so. */
    public boolean enabled() { return enabled; }

    public String summary() {
        return "any-pickaxe=" + anyPickaxe + ", include-natural=" + includeNatural
                + ", drop=" + (dropAtBlock ? "ground" : "inventory");
    }

    /**
     * Tag spawners a player places, so include-natural:false can tell them from
     * dungeon ones. HIGH + ignoreCancelled so it runs after (and yields to) the
     * engine's block-spawner-placement veto — we never tag a rejected placement.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
        if (e.getBlockPlaced().getState() instanceof CreatureSpawner cs) {
            cs.getPersistentDataContainer().set(placedKey, PersistentDataType.BYTE, (byte) 1);
            cs.update(true, false);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;

        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;   // creative build shouldn't spam spawner drops

        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool == null || !tool.getType().name().endsWith("_PICKAXE")) return;
        if (!anyPickaxe && !isIronPlus(tool.getType())) return;

        if (!(b.getState() instanceof CreatureSpawner cs)) return;

        if (!includeNatural
                && cs.getPersistentDataContainer().get(placedKey, PersistentDataType.BYTE) == null) {
            return;   // natural/dungeon spawner and we're only collecting player-placed ones
        }

        // We are the sole drop source for this spawner — suppress the block's native drop so a
        // Silk-Touch (or datapack/server-config) spawner drop can't stack on top of ours (→ 2 spawners).
        e.setDropItems(false);

        EntityType type = cs.getSpawnedType();
        ItemStack item = new ItemStack(Material.SPAWNER);
        if (item.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof CreatureSpawner state) {
            if (type != null) state.setSpawnedType(type);
            bsm.setBlockState(state);
            item.setItemMeta(bsm);
        }

        var loc = b.getLocation().add(0.5, 0.5, 0.5);
        if (dropAtBlock) {
            b.getWorld().dropItemNaturally(loc, item);
        } else {
            for (ItemStack overflow : p.getInventory().addItem(item).values()) {
                b.getWorld().dropItemNaturally(loc, overflow);
            }
        }
    }

    private static boolean isIronPlus(Material m) {
        return switch (m) {
            case IRON_PICKAXE, GOLDEN_PICKAXE, DIAMOND_PICKAXE, NETHERITE_PICKAXE -> true;
            default -> false;
        };
    }
}
