package dev.servereer.machineconstruct.factorydistrict;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Admin authoring tool (ADR 0014): scan a real in-world farm with WorldEdit into a catalog
 * farm. {@code /mc scanfarm}:
 * <ol>
 *   <li>{@code new <name>} — start a draft.</li>
 *   <li>{@code structure} — tally the current WE selection's blocks → build requirements.</li>
 *   <li>{@code output} — snapshot the current WE selection's chests, wait 10 min, snapshot
 *       again; the delta = output per cycle.</li>
 *   <li>{@code fuel} — the held item becomes a per-cycle fuel cost (from the vault); repeatable.</li>
 *   <li>{@code category <id>} · {@code icon <mat>} · {@code save} / {@code cancel} / {@code status}.</li>
 * </ol>
 * Saves into {@code factory_catalog.yml} and triggers a reload. No-ops cleanly without WorldEdit.
 */
public final class FactoryScanWizard {

    private static final long MAX_STRUCTURE_VOLUME = 12_000_000L;   // sanity cap (~228³ box)
    private static final int SCAN_BUDGET_PER_TICK = 120_000;        // blocks scanned per tick (streamed)
    private static final long MEASURE_TICKS = 600L * 20L;           // 10 minutes

    private final JavaPlugin plugin;
    private final Supplier<File> catalogFile;
    private final Runnable reload;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    public FactoryScanWizard(JavaPlugin plugin, Supplier<File> catalogFile, Runnable reload) {
        this.plugin = plugin;
        this.catalogFile = catalogFile;
        this.reload = reload;
    }

    private static final class Draft {
        String name, id, category, iconName;
        final Map<Material, Long> blocks = new EnumMap<>(Material.class);   // tallied requirements
        final List<String> output = new ArrayList<>();                     // "MAT AMT" per cycle
        final List<String> fuel = new ArrayList<>();                       // "MAT AMT" per cycle (vault)
        boolean structureDone, outputDone, measuring, scanning;
    }

    // --- /mc scanfarm --------------------------------------------------------

    public boolean handleScanFarm(Player p, String[] args) {
        if (!weAvailable()) { p.sendMessage(msg("§cWorldEdit isn't installed — scanning is unavailable.")); return true; }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "new" -> {
                if (args.length < 3) { p.sendMessage(msg("§7usage: §f/mc scanfarm new <name…>")); return true; }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                Draft d = new Draft();
                d.name = name;
                d.id = slug(name);
                drafts.put(p.getUniqueId(), d);
                p.sendMessage(msg("§aStarted farm draft §f" + name + " §7(§f" + d.id + "§7)."));
                p.sendMessage(msg("§7Select the structure with WE, then §f/mc scanfarm structure"));
            }
            case "structure" -> scanStructure(p);
            case "output" -> {
                boolean instant = args.length >= 3 && (args[2].equalsIgnoreCase("now") || args[2].equalsIgnoreCase("instant"));
                if (instant) scanOutputInstant(p); else scanOutput(p);
            }
            case "fuel" -> addFuel(p, args);
            case "category" -> {
                Draft d = drafts.get(p.getUniqueId());
                if (d == null) { p.sendMessage(msg("§cStart one with §f/mc scanfarm new <name>")); return true; }
                if (args.length < 3) { p.sendMessage(msg("§7usage: §f/mc scanfarm category <id>")); return true; }
                d.category = args[2].toLowerCase(Locale.ROOT);
                p.sendMessage(msg("§aCategory set: §f" + d.category));
            }
            case "icon" -> {
                Draft d = drafts.get(p.getUniqueId());
                if (d == null) { p.sendMessage(msg("§cNo draft.")); return true; }
                Material m = args.length >= 3 ? Material.matchMaterial(args[2].toUpperCase(Locale.ROOT)) : null;
                if (m == null) { p.sendMessage(msg("§cUnknown material.")); return true; }
                d.iconName = m.name();
                p.sendMessage(msg("§aIcon set: §f" + m.name()));
            }
            case "status" -> status(p);
            case "cancel" -> { drafts.remove(p.getUniqueId()); p.sendMessage(msg("§eDraft cancelled.")); }
            case "save" -> save(p);
            default -> p.sendMessage(msg("§7/mc scanfarm §fnew|structure|output|fuel|category|icon|status|save|cancel"));
        }
        return true;
    }

    private void scanStructure(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) { p.sendMessage(msg("§cStart one with §f/mc scanfarm new <name>")); return; }
        if (d.scanning) { p.sendMessage(msg("§eAlready scanning the structure — hang on.")); return; }
        int[] b = bounds(p);
        if (b == null) { p.sendMessage(msg("§cMake a WorldEdit selection first (//wand).")); return; }
        final long dx = b[3] - b[0] + 1L, dy = b[4] - b[1] + 1L, dz = b[5] - b[2] + 1L;
        final long volume = dx * dy * dz;
        if (volume > MAX_STRUCTURE_VOLUME) {
            p.sendMessage(msg("§cSelection too large (" + volume + " blocks). Tighten the box around the build (max "
                    + MAX_STRUCTURE_VOLUME + ")."));
            return;
        }
        final World w = p.getWorld();
        d.blocks.clear();
        d.scanning = true;
        p.sendMessage(msg("§7Scanning §f" + volume + "§7 blocks…"));
        final long plane = dy * dz;
        final long[] idx = { 0 };
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int budget = SCAN_BUDGET_PER_TICK;
            while (budget-- > 0 && idx[0] < volume) {
                long i = idx[0]++;
                int x = b[0] + (int) (i / plane);
                long rem = i % plane;
                int y = b[1] + (int) (rem / dz);
                int z = b[2] + (int) (rem % dz);
                Material m = w.getBlockAt(x, y, z).getType();
                if (m.isAir() || isFluid(m) || !m.isItem()) continue;
                d.blocks.merge(m, 1L, Long::sum);
            }
            if (idx[0] >= volume) {
                task.cancel();
                d.scanning = false;
                d.structureDone = !d.blocks.isEmpty();
                if (d.iconName == null && !d.blocks.isEmpty()) d.iconName = d.blocks.keySet().iterator().next().name();
                if (p.isOnline()) p.sendMessage(msg("§aStructure scanned: §f" + d.blocks.size()
                        + "§a block type(s) tallied as the build cost. §7(/mc scanfarm status)"));
            }
        }, 1L, 1L);
    }

    private void scanOutput(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) { p.sendMessage(msg("§cStart one with §f/mc scanfarm new <name>")); return; }
        if (d.measuring) { p.sendMessage(msg("§eAlready measuring — wait for it to finish.")); return; }
        int[] b = bounds(p);
        if (b == null) { p.sendMessage(msg("§cSelect the output chests with WorldEdit first.")); return; }
        World w = p.getWorld();
        Map<Material, Long> baseline = snapshotChests(w, b);
        if (baseline.isEmpty()) p.sendMessage(msg("§7(No items in the selected chests yet — measuring net production.)"));
        d.measuring = true;
        String worldName = w.getName();
        p.sendMessage(msg("§aMeasuring output for §f10 minutes§a — leave the farm running…"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World ww = Bukkit.getWorld(worldName);
            d.measuring = false;
            if (ww == null) { p.sendMessage(msg("§cOutput world unloaded — measurement aborted.")); return; }
            Map<Material, Long> after = snapshotChests(ww, b);
            d.output.clear();
            for (Map.Entry<Material, Long> e : after.entrySet()) {
                long delta = e.getValue() - baseline.getOrDefault(e.getKey(), 0L);
                if (delta > 0) d.output.add(e.getKey().name().toLowerCase(Locale.ROOT) + " " + delta);
            }
            d.outputDone = !d.output.isEmpty();
            if (p.isOnline()) p.sendMessage(msg(d.outputDone
                    ? "§aMeasured §f" + d.output.size() + "§a output type(s) over 10 min: §f" + String.join(", ", d.output)
                    : "§eNo net output measured — is the farm producing into those chests?"));
        }, MEASURE_TICKS);
    }

    /** Instant output: treat the chests' CURRENT contents as one cycle's (10-min) output. */
    private void scanOutputInstant(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) { p.sendMessage(msg("§cStart one with §f/mc scanfarm new <name>")); return; }
        int[] b = bounds(p);
        if (b == null) { p.sendMessage(msg("§cSelect the output chests with WorldEdit first.")); return; }
        Map<Material, Long> now = snapshotChests(p.getWorld(), b);
        d.output.clear();
        for (Map.Entry<Material, Long> e : now.entrySet())
            if (e.getValue() > 0) d.output.add(e.getKey().name().toLowerCase(Locale.ROOT) + " " + e.getValue());
        d.outputDone = !d.output.isEmpty();
        p.sendMessage(msg(d.outputDone
                ? "§aOutput set instantly from chest contents (§f" + d.output.size() + "§a type(s)): §f" + String.join(", ", d.output)
                : "§eThose chests are empty — fill them with ~10 min of output first."));
    }

    private void addFuel(Player p, String[] args) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) { p.sendMessage(msg("§cNo draft.")); return; }
        if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) { d.fuel.clear(); p.sendMessage(msg("§eFuel cleared.")); return; }
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) { p.sendMessage(msg("§cHold the fuel item (its stack amount = per-cycle cost). Or §f/mc scanfarm fuel clear")); return; }
        d.fuel.add(held.getType().name().toLowerCase(Locale.ROOT) + " " + held.getAmount());
        p.sendMessage(msg("§aFuel +§f" + held.getAmount() + "x " + held.getType().name() + "§a per cycle (from vault)."));
    }

    private void status(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) { p.sendMessage(msg("§7No draft. Start: §f/mc scanfarm new <name>")); return; }
        p.sendMessage(msg("§7Draft §f" + d.name + " §7(§f" + d.id + "§7)"));
        p.sendMessage(msg("§7 structure: " + (d.structureDone ? "§a" + d.blocks.size() + " block types" : "§c—")
                + "§7 · output: " + (d.outputDone ? "§a" + d.output.size() + " types" : d.measuring ? "§emeasuring…" : "§c—")
                + "§7 · category: " + (d.category != null ? "§f" + d.category : "§c—")
                + "§7 · fuel: §f" + (d.fuel.isEmpty() ? "none" : String.join(", ", d.fuel))));
    }

    private void save(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) { p.sendMessage(msg("§cNo draft.")); return; }
        if (!d.structureDone) { p.sendMessage(msg("§cScan the structure first (§f/mc scanfarm structure§c).")); return; }
        if (!d.outputDone) { p.sendMessage(msg("§cMeasure the output first (§f/mc scanfarm output§c).")); return; }
        if (d.category == null) { p.sendMessage(msg("§cSet a category (§f/mc scanfarm category <id>§c).")); return; }
        File file = catalogFile.get();
        if (file == null) { p.sendMessage(msg("§cNo content pack loaded — can't save.")); return; }
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        YamlConfiguration y = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        String base = "factories." + d.id;
        y.set(base + ".name", d.name);
        y.set(base + ".icon", d.iconName != null ? d.iconName : "FURNACE");
        y.set(base + ".category", d.category);
        y.set(base + ".cycle-seconds", 600);
        List<String> blocks = new ArrayList<>();
        for (Map.Entry<Material, Long> e : d.blocks.entrySet())
            blocks.add(e.getKey().name().toLowerCase(Locale.ROOT) + " " + e.getValue());
        y.set(base + ".build.blocks", blocks);
        y.set(base + ".output", new ArrayList<>(d.output));
        if (!d.fuel.isEmpty()) y.set(base + ".inputs", new ArrayList<>(d.fuel));
        try {
            y.save(file);
        } catch (IOException e) {
            p.sendMessage(msg("§cSave failed: " + e.getMessage()));
            return;
        }
        drafts.remove(p.getUniqueId());
        if (reload != null) reload.run();
        p.sendMessage(msg("§aSaved farm §f" + d.name + "§a to the catalog and reloaded. It's now buildable under §f" + d.category + "§a."));
    }

    // --- /mc scancategory ----------------------------------------------------

    public boolean handleScanCategory(Player p, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("create")) {
            if (args.length < 5) { p.sendMessage(msg("§7usage: §f/mc scancategory create <id> <ICON_MATERIAL> <name…>")); return true; }
            String id = args[2].toLowerCase(Locale.ROOT);
            Material icon = Material.matchMaterial(args[3].toUpperCase(Locale.ROOT));
            if (icon == null) { p.sendMessage(msg("§cUnknown icon material: §f" + args[3])); return true; }
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
            File file = catalogFile.get();
            if (file == null) { p.sendMessage(msg("§cNo content pack loaded.")); return true; }
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            YamlConfiguration y = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
            y.set("categories." + id + ".name", name);
            y.set("categories." + id + ".icon", icon.name());
            try { y.save(file); } catch (IOException e) { p.sendMessage(msg("§cSave failed: " + e.getMessage())); return true; }
            if (reload != null) reload.run();
            p.sendMessage(msg("§aCreated category §f" + id + " §7(" + name + ")§a and reloaded."));
            return true;
        }
        p.sendMessage(msg("§7usage: §f/mc scancategory create <id> <ICON_MATERIAL> <name…>"));
        return true;
    }

    // --- WorldEdit + helpers -------------------------------------------------

    private static boolean weAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("WorldEdit")
                || Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit");
    }

    /** The player's WE selection as {minX,minY,minZ,maxX,maxY,maxZ}, or null if none. */
    private static int[] bounds(Player p) {
        try {
            com.sk89q.worldedit.entity.Player wp = BukkitAdapter.adapt(p);
            Region r = WorldEdit.getInstance().getSessionManager().get(wp).getSelection(wp.getWorld());
            BlockVector3 lo = r.getMinimumPoint(), hi = r.getMaximumPoint();
            return new int[]{ lo.x(), lo.y(), lo.z(), hi.x(), hi.y(), hi.z() };
        } catch (Throwable t) {
            return null;   // no/incomplete selection
        }
    }

    /** Tally all container contents in the region by material. */
    private static Map<Material, Long> snapshotChests(World w, int[] b) {
        Map<Material, Long> out = new EnumMap<>(Material.class);
        for (int x = b[0]; x <= b[3]; x++) for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) {
            Block blk = w.getBlockAt(x, y, z);
            if (!(blk.getState() instanceof Container c)) continue;
            for (ItemStack it : c.getInventory().getContents())
                if (it != null && !it.getType().isAir()) out.merge(it.getType(), (long) it.getAmount(), Long::sum);
        }
        return out;
    }

    private static boolean isFluid(Material m) {
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN;
    }

    private static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "farm_" + System.currentTimeMillis() : s;
    }

    private static String msg(String s) { return "§8[§bFactory§8] §r" + s; }
}
