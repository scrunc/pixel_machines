package dev.servereer.machineconstruct;

import dev.servereer.machineconstruct.api.MachineConstructAPI;
import dev.servereer.machineconstruct.core.Heads;
import dev.servereer.machineconstruct.core.PacketDisplay;
import dev.servereer.machineconstruct.gui.BrowserLayout;
import dev.servereer.machineconstruct.gui.GuiLayout;
import dev.servereer.machineconstruct.gui.GuiLoader;
import dev.servereer.machineconstruct.machine.MachineManager;
import dev.servereer.machineconstruct.machine.MachineType;
import dev.servereer.machineconstruct.machine.Placer;
import dev.servereer.machineconstruct.machine.Recipe;
import dev.servereer.machineconstruct.machine.RecipeText;
import dev.servereer.machineconstruct.machine.Tiers;
import dev.servereer.machineconstruct.model.Model;
import dev.servereer.machineconstruct.model.ModelLoader;
import dev.servereer.machineconstruct.tracking.DisplayTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.configuration.MemoryConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * MachineConstruct — the engine plugin ("the system").
 *
 * <p>P1: packet display layer + viewer tracking. {@code /mc debug spawn [block]}
 * spawns a packet-only block_display at the player; it survives relog/range via
 * {@link DisplayTracker}. Real machines (chest anchor, models, recipes) build on
 * this from P2.
 */
public final class MachineConstruct extends JavaPlugin implements MachineConstructAPI {

    private DisplayTracker tracker;
    private MachineManager machines;
    private dev.servereer.machineconstruct.music.TrackLibrary trackLibrary;
    private dev.servereer.machineconstruct.music.PlaylistLibrary playlistLibrary;
    private dev.servereer.machineconstruct.music.MusicPlayer musicPlayer;
    private dev.servereer.machineconstruct.music.DiscItem discItem;

    private final Map<String, Model> models = new ConcurrentHashMap<>();
    private final Map<String, MachineType> types = new ConcurrentHashMap<>();
    private final List<File> contentPacks = new CopyOnWriteArrayList<>();
    private ModelLoader modelLoader;
    private GuiLoader guiLoader;
    private dev.servereer.machineconstruct.factorydistrict.FactoryScanWizard scanWizard;

    /** factory_district/factory_catalog.yml in the first content pack (admin-scanned farms + categories). */
    private File factoryCatalogFile() {
        return contentPacks.isEmpty() ? null : new File(new File(contentPacks.get(0), "factory_district"), "factory_catalog.yml");
    }

    @Override
    public void onEnable() {
        getServer().getServicesManager()
                .register(MachineConstructAPI.class, this, this, ServicePriority.Normal);

        saveDefaultConfig();
        int renderDistance = getConfig().getInt("render-distance", 48);
        long trackerInterval = getConfig().getLong("tracker-interval", 10);
        int animationInterval = getConfig().getInt("animation-interval", 2);
        int processInterval = getConfig().getInt("process-interval", 10);

        modelLoader = new ModelLoader(getLogger());
        guiLoader = new GuiLoader(getLogger());

        // Music subsystem (ADR 0024): track library + Simple Voice Chat playback + custom discs.
        dev.servereer.machineconstruct.audio.TrackIngest ingest = new dev.servereer.machineconstruct.audio.TrackIngest(this);
        // Audio comes from the shared PixelAudio plugin — this engine carries no SVC bridge of its own.
        // Only reach for its types once the plugin is enabled: they live in ITS classloader.
        dev.servereer.machineconstruct.audio.MusicAudio musicAudio = null;
        if (getServer().getPluginManager().isPluginEnabled("PixelAudio"))
            musicAudio = dev.servereer.machineconstruct.audio.PixelAudioMusic.load(this);
        if (musicAudio == null) {
            String why = getServer().getPluginManager().getPlugin("PixelAudio") == null
                    ? "PixelAudio not installed — jukeboxes/music will save tracks but play silently."
                    : "PixelAudio has not published its audio service — music will play silently.";
            getLogger().info(why);
            musicAudio = new dev.servereer.machineconstruct.audio.MusicAudio.MusicPlayerless(why);
        } else getLogger().info("Music audio via " + musicAudio.describe() + ".");
        ingest.useShared(musicAudio);
        trackLibrary = new dev.servereer.machineconstruct.music.TrackLibrary(this, ingest, musicAudio);
        getLogger().info("Media tools (ffmpeg/yt-dlp): " + ingest.toolsSource() + ".");
        trackLibrary.load();
        playlistLibrary = new dev.servereer.machineconstruct.music.PlaylistLibrary(this);
        playlistLibrary.load();
        discItem = new dev.servereer.machineconstruct.music.DiscItem(this);
        musicPlayer = new dev.servereer.machineconstruct.music.MusicPlayer(this, trackLibrary, musicAudio);
        getServer().getPluginManager().registerEvents(musicPlayer, this);
        dev.servereer.machineconstruct.music.MusicCommand musicCommand =
                new dev.servereer.machineconstruct.music.MusicCommand(this, ingest, trackLibrary, playlistLibrary, musicPlayer, discItem);
        musicCommand.register(this);

        // P1 tracking: render within configured distance, diff on the configured interval.
        tracker = new DisplayTracker(this, renderDistance, trackerInterval);
        tracker.start();

        // P2 chest-anchor lifecycle + P3 model rendering (via the registries below).
        machines = new MachineManager(this, tracker, this, animationInterval, processInterval,
                trackLibrary, playlistLibrary, musicPlayer, discItem);
        // machines may play real audio files as sound effects (`sfx: { track: … }`), not only vanilla keys
        machines.gacha().useAudio(trackLibrary, musicAudio);
        machines.start();

        // PixelProfiler shop-price bridge: prefer /shop's sell price for auto-sell/vault value,
        // re-read on an interval so /pishop edits propagate. No hard dependency (reads its files).
        setupPixelProfilerPricing();

        // Spawner mining — let players break a spawner and collect it as a typed
        // spawner item (no SmartSpawner needed). Config-gated under spawner-mining:.
        dev.servereer.machineconstruct.spawner.SpawnerMiningListener spawnerMining =
                new dev.servereer.machineconstruct.spawner.SpawnerMiningListener(this);
        if (spawnerMining.enabled()) {
            getServer().getPluginManager().registerEvents(spawnerMining, this);
            getLogger().info("Spawner mining enabled (" + spawnerMining.summary() + ").");
        }

        // Factory District authoring tool (ADR 0014) — /mc scanfarm + /mc scancategory.
        scanWizard = new dev.servereer.machineconstruct.factorydistrict.FactoryScanWizard(
                this, this::factoryCatalogFile, this::reload);

        getLogger().info("MachineConstruct engine enabled (P3) — packet displays + tracking + chest-anchor + model registry online.");
    }

    /**
     * Wire the PixelProfiler price bridge from config and schedule its hourly refresh. Reads
     * {@code plugins/<plugin-folder>/prices.yml} + {@code /shops} straight from disk — no PixelProfiler
     * restart needed. An initial delay lets PixelProfiler finish loading before the first read.
     */
    private void setupPixelProfilerPricing() {
        boolean enabled = getConfig().getBoolean("pixelprofiler-pricing.enabled", true);
        String folder = getConfig().getString("pixelprofiler-pricing.plugin-folder", "PixelProfiler");
        int minutes = Math.max(1, getConfig().getInt("pixelprofiler-pricing.refresh-minutes", 60));
        File pluginDir = new File(getDataFolder().getParentFile(), folder);
        dev.servereer.machineconstruct.grinder.econ.PixelProfilerPrices.configure(pluginDir, enabled);
        if (!enabled) return;
        long period = minutes * 60L * 20L;   // minutes → ticks
        getServer().getScheduler().runTaskTimer(this,
                () -> dev.servereer.machineconstruct.grinder.econ.PixelProfilerPrices.refresh(getLogger()),
                200L, period);
        getLogger().info("PixelProfiler price bridge enabled — reading " + folder
                + "/prices.yml + /shops every " + minutes + "m.");
    }

    @Override
    public void onDisable() {
        if (musicPlayer != null) musicPlayer.stopAll();
        if (machines != null) machines.persistAll();   // flush state to PDC before the world saves
        if (machines != null) machines.clearAll();
        if (tracker != null) tracker.stop();
        getServer().getServicesManager().unregisterAll(this);
        getLogger().info("MachineConstruct engine disabled.");
    }

    // --- MachineConstructAPI ------------------------------------------------

    @Override
    public String version() {
        return getPluginMeta().getVersion();
    }

    @Override
    public void registerModel(Model model) {
        models.put(model.id(), model);
    }

    @Override
    public Model getModel(String id) {
        return models.get(id);
    }

    @Override
    public void registerMachineType(MachineType type) {
        types.put(type.id(), type);
    }

    @Override
    public MachineType getMachineType(String id) {
        return types.get(id);
    }

    @Override
    public void registerContentPack(File folder) {
        if (!contentPacks.contains(folder)) contentPacks.add(folder);
        loadPack(folder);
    }

    @Override
    public void refreshPrices() {
        dev.servereer.machineconstruct.grinder.econ.PixelProfilerPrices.refresh(getLogger());
    }

    /** Scan {@code <folder>/machines/*.yml} → register model + machine type for each. */
    private void loadPack(File folder) {
        File machinesDir = new File(folder, "machines");
        File[] files = machinesDir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files == null) return;
        int n = 0;
        for (File f : files) {
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                // Variable layer: a `vars:` map + ${name} references (whole-value or
                // inline). Resolve the whole config with the base vars; per-tier
                // `tiers.vars` re-resolve the model later (see parseTiers).
                // Variable/template/grid layer is scoped to the model: subtree (its
                // keys are safe identifiers). GUI/recipes/etc. are read straight from
                // the config — their keys can be '.'/'#' which is Bukkit's path char.
                Map<String, Object> baseVars = selfResolveVars(sectionToMap(cfg.getConfigurationSection("vars")));
                Map<String, Object> templates = sectionToMap(cfg.getConfigurationSection("templates"));

                String id = cfg.getString("id", f.getName().replaceFirst("\\.yml$", ""));
                if (!cfg.getBoolean("enabled", true)) {   // enabled: false parks a shipped machine without deleting its file
                    getLogger().info("Content: " + f.getName() + " is disabled (enabled: false), skipping.");
                    continue;
                }
                ConfigurationSection modelSec = cfg.getConfigurationSection("model");
                if (modelSec == null) {
                    getLogger().warning("Content: " + f.getName() + " has no model: section, skipping.");
                    continue;
                }
                Map<String, Object> rawModel = sectionToMap(modelSec);
                ConfigurationSection model = toSection(asMap(resolveVars(rawModel, baseVars, templates)));
                registerModel(modelLoader.load(id, model));
                List<Recipe> recipes = parseRecipes(id, cfg.getMapList("recipes"));
                GuiLayout gui = guiLoader.load(id, cfg.getConfigurationSection("gui"));
                Map<Material, Integer> fuel = parseFuel(id, cfg.getConfigurationSection("fuel"));
                Material anchor = parseAnchor(id, cfg.getString("anchor", "chest"));
                BrowserLayout browser = guiLoader.loadBrowser(id, cfg.getConfigurationSection("browser"));
                int capacity = Math.max(0, cfg.getInt("capacity", 0));
                Tiers tiers = parseTiers(id, cfg.getConfigurationSection("tiers"), rawModel, baseVars, templates);
                dev.servereer.machineconstruct.grinder.GrinderSpec grinder =
                        dev.servereer.machineconstruct.grinder.GrinderSpec.parse(cfg.getConfigurationSection("grinder"));
                dev.servereer.machineconstruct.collector.ChunkCollectorSpec chunkCollector =
                        dev.servereer.machineconstruct.collector.ChunkCollectorSpec.parse(cfg.getConfigurationSection("chunk_collector"));
                dev.servereer.machineconstruct.jukebox.JukeboxSpec jukebox =
                        dev.servereer.machineconstruct.jukebox.JukeboxSpec.parse(cfg.getConfigurationSection("jukebox"));
                dev.servereer.machineconstruct.grinder.LootTable loot = null;
                dev.servereer.machineconstruct.grinder.econ.PriceService pricing = null;
                if (grinder != null) {
                    loot = dev.servereer.machineconstruct.grinder.LootTable.load(
                            new File(folder, grinder.lootFile()), getLogger());
                    pricing = dev.servereer.machineconstruct.grinder.econ.PriceService.load(
                            folder, grinder.economy(), getLogger());
                } else {
                    ConfigurationSection econ = cfg.getConfigurationSection("economy");
                    if (econ == null) {   // a chunk-collector nests its economy under chunk_collector:
                        ConfigurationSection ccSec = cfg.getConfigurationSection("chunk_collector");
                        if (ccSec != null) econ = ccSec.getConfigurationSection("economy");
                    }
                    if (econ != null) pricing = dev.servereer.machineconstruct.grinder.econ.PriceService.load(
                            folder, econ, getLogger());   // a sell button needs prices
                }
                dev.servereer.machineconstruct.quarry.QuarrySpec quarry =
                        dev.servereer.machineconstruct.quarry.QuarrySpec.parse(cfg.getConfigurationSection("quarry"));
                dev.servereer.machineconstruct.tradinghall.TradingHallSpec tradingHall =
                        dev.servereer.machineconstruct.tradinghall.TradingHallSpec.parse(cfg.getConfigurationSection("tradinghall"));
                ConfigurationSection facSec = cfg.getConfigurationSection("factory");
                dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec factory = null;
                if (facSec != null) {
                    // Merge every catalog file in factory_district/ (built-in farms + admin-scanned),
                    // sorted by name so built-in (e.g. builtin.yml) loads before scanned files.
                    List<ConfigurationSection> catalogs = new java.util.ArrayList<>();
                    File catDir = new File(folder, "factory_district");
                    File[] catFiles = catDir.listFiles((d, nm) -> nm.toLowerCase().endsWith(".yml"));
                    if (catFiles != null) {
                        java.util.Arrays.sort(catFiles, java.util.Comparator.comparing(File::getName));
                        for (File cf : catFiles) catalogs.add(YamlConfiguration.loadConfiguration(cf));
                    }
                    factory = dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec.parse(facSec, catalogs);
                }
                MachineType mt = new MachineType(id, id, recipes, gui, fuel, anchor, browser, capacity, tiers, grinder, loot, pricing, quarry, tradingHall, factory, chunkCollector, jukebox);
                ConfigurationSection placerSec = cfg.getConfigurationSection("placer");
                if (placerSec != null) {   // ${vars} resolve here too (palette / tname in the item name + lore)
                    Object placerRes = resolveVars(sectionToMap(placerSec), baseVars, templates);
                    ItemStack placerIcon = buildItem(id, toSection(asMap(placerRes)));   // {head|item, name, lore, model}
                    if (placerIcon != null) mt.setPlacerIcon(placerIcon);
                }
                mt.setFacesPlacer("player".equalsIgnoreCase(cfg.getString("facing", "")));
                mt.setGacha(dev.servereer.machineconstruct.gacha.GachaSpec.parse(cfg.getConfigurationSection("gacha")));
                // cues get the ${var} pass too, so a reel strip can live in vars: as one list
                ConfigurationSection cueSec = cfg.getConfigurationSection("cues");
                if (cueSec != null) cueSec = toSection(asMap(resolveVars(sectionToMap(cueSec), baseVars, templates)));
                mt.setCues(dev.servereer.machineconstruct.model.anim.Cue.parseAll(cueSec));
                for (Map.Entry<String, Object> ve : baseVars.entrySet())
                    if (ve.getValue() instanceof String sv) mt.textVars().put(ve.getKey(), sv);
                ConfigurationSection panelSec = cfg.getConfigurationSection("panel");
                if (panelSec != null) {
                    long ms = dev.servereer.machineconstruct.grinder.GrinderSpec.parseTimeMillis(panelSec.getString("refresh", "30s"));
                    mt.setPanelRefreshTicks((int) Math.max(20L, ms / 50L));
                    mt.setPanelTheme(panelSec.getString("theme"));
                    mt.setPanelDefaultState(panelSec.getString("default_state"));
                    mt.setPanelPaged(panelSec.getBoolean("paged", false));
                    // default pages: part → lines (a list, or a |- block); ${var} expanded like any text
                    for (Map<String, List<String>> pg : dev.servereer.machineconstruct.machine.PanelData.pagesFromYaml(panelSec.getList("pages"))) {
                        Map<String, List<String>> ex = new LinkedHashMap<>();
                        for (Map.Entry<String, List<String>> pe : pg.entrySet()) {
                            List<String> lines = new ArrayList<>();
                            for (String l : pe.getValue()) lines.add(mt.expandTextVars(l));
                            ex.put(pe.getKey(), lines);
                        }
                        mt.panelPages().add(ex);
                    }
                    ConfigurationSection stSec = panelSec.getConfigurationSection("states");
                    if (stSec != null) for (String k : stSec.getKeys(false)) {
                        ConfigurationSection one = stSec.getConfigurationSection(k);
                        Map<String, String> vars = new LinkedHashMap<>();
                        if (one != null) for (String vk : one.getKeys(false)) vars.put(vk, String.valueOf(one.get(vk)));
                        mt.panelStates().put(k, vars);
                    }
                    // Each theme is a block palette (var overrides, like tiers.vars) plus a `theme` text colour.
                    // The model is re-rendered once per theme and registered as <id>_theme_<name>.
                    ConfigurationSection th = panelSec.getConfigurationSection("themes");
                    if (th != null) for (String k : th.getKeys(false)) {
                        ConfigurationSection ts = th.getConfigurationSection(k);
                        if (ts == null) continue;
                        Map<String, Object> merged = new LinkedHashMap<>(baseVars);
                        merged.putAll(sectionToMap(ts));
                        merged = selfResolveVars(merged);
                        Object col = merged.get("theme");
                        String hex = col == null ? "#35e0d0" : String.valueOf(col).trim();
                        mt.panelThemes().put(k, hex.matches("#[0-9a-fA-F]{6}") ? hex.toLowerCase() : "#35e0d0");
                        String mid = id + "_theme_" + k;
                        registerModel(modelLoader.load(mid, toSection(asMap(resolveVars(rawModel, merged, templates)))));
                        mt.panelThemeModels().put(k, mid);
                    }
                }
                ConfigurationSection gachaSec = cfg.getConfigurationSection("gacha");
                if (gachaSec != null && gachaSec.isConfigurationSection("themes")) {   // capsule machines take the panels' theme system as-is
                    mt.setPanelTheme(gachaSec.getString("theme"));
                    ConfigurationSection th = gachaSec.getConfigurationSection("themes");
                    for (String k : th.getKeys(false)) {
                        ConfigurationSection ts = th.getConfigurationSection(k);
                        if (ts == null) continue;
                        Map<String, Object> merged = new LinkedHashMap<>(baseVars);
                        merged.putAll(sectionToMap(ts));
                        merged = selfResolveVars(merged);
                        Object col = merged.get("theme");
                        String hex = col == null ? "#35e0d0" : String.valueOf(col).trim();
                        mt.panelThemes().put(k, hex.matches("#[0-9a-fA-F]{6}") ? hex.toLowerCase() : "#35e0d0");
                        String mid = id + "_theme_" + k;
                        registerModel(modelLoader.load(mid, toSection(asMap(resolveVars(rawModel, merged, templates)))));
                        mt.panelThemeModels().put(k, mid);
                    }
                }
                ConfigurationSection skinSec = cfg.getConfigurationSection("skin");
                if (skinSec != null) {   // ${vars} resolve here too, so a skin can reuse the model palette / tname
                    Map<String, Object> rawSkin = sectionToMap(skinSec);
                    mt.setSkin(dev.servereer.machineconstruct.gui.MenuSkin.load(
                            toSection(asMap(resolveVars(rawSkin, baseVars, templates)))));
                }
                registerMachineType(mt);
                n++;
            } catch (Throwable e) {   // Throwable, not Exception: a bad file (even a StackOverflow) must not abort the rest
                getLogger().warning("Content: failed to load " + f.getName() + ": " + e);
            }
        }
        getLogger().info("Content: loaded " + n + " machine(s) from " + folder.getName() + "/machines.");
    }

    /** Parse a {@code tiers:} section into the upgrade ladder, or null if absent. */
    private Tiers parseTiers(String id, ConfigurationSection sec, Object rawModel,
                             Map<String, Object> baseVars, Map<String, Object> templates) {
        if (sec == null) return null;
        int max = Math.max(1, sec.getInt("max", 1));
        ItemStack upgrade = buildItem(id, sec.get("upgrade"));   // string or map item spec
        double[] speed = toDoubleArray(sec.getDoubleList("speed"));
        double[] capacity = toDoubleArray(sec.getDoubleList("capacity"));
        List<Integer> b = sec.getIntegerList("batch");
        int[] batch = new int[b.size()];
        for (int i = 0; i < batch.length; i++) batch[i] = b.get(i);

        // Optional per-tier visual overrides: models: / guis: keyed by tier number.
        Model[] models = new Model[max];
        GuiLayout[] guis = new GuiLayout[max];
        ConfigurationSection mSec = sec.getConfigurationSection("models");
        if (mSec != null) for (String k : mSec.getKeys(false)) {
            int t = parseTierKey(k);
            ConfigurationSection ms = mSec.getConfigurationSection(k);
            if (ms != null && t >= 1 && t <= max) models[t - 1] = modelLoader.load(id + "_t" + t, ms);
        }
        // Per-tier var overrides: re-render the base model with (baseVars + tier vars)
        // — no need to repeat the whole model tree, only the changed values.
        ConfigurationSection vSec = sec.getConfigurationSection("vars");
        if (vSec != null && rawModel instanceof Map<?, ?>) {
            for (String k : vSec.getKeys(false)) {
                int t = parseTierKey(k);
                if (t < 1 || t > max || models[t - 1] != null) continue;   // explicit override wins
                Map<String, Object> merged = new LinkedHashMap<>(baseVars);
                merged.putAll(sectionToMap(vSec.getConfigurationSection(k)));
                merged = selfResolveVars(merged);   // tier vars may reference the palette (${glow} etc.)
                Object resolved = resolveVars(rawModel, merged, templates);
                models[t - 1] = modelLoader.load(id + "_t" + t, toSection(asMap(resolved)));
            }
        }
        ConfigurationSection gSec = sec.getConfigurationSection("guis");
        if (gSec != null) for (String k : gSec.getKeys(false)) {
            int t = parseTierKey(k);
            ConfigurationSection gs = gSec.getConfigurationSection(k);
            if (gs != null && t >= 1 && t <= max) guis[t - 1] = guiLoader.load(id + "_t" + t, gs);
        }
        return new Tiers(max, upgrade, speed, batch, capacity, models, guis);
    }

    // --- variable layer (${name} substitution) ------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /**
     * Deep-convert a ConfigurationSection to a plain nested Map (lists/scalars kept
     * as-is). Recurses on child <b>objects</b> from {@code getValues(false)} rather
     * than {@code get(key)} — so a key containing Bukkit's '.' path separator (e.g.
     * a GUI icon key) can't be misread as a path and loop forever.
     */
    private static Map<String, Object> sectionToMap(ConfigurationSection sec) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (sec == null) return map;
        for (Map.Entry<String, Object> e : sec.getValues(false).entrySet()) {
            Object v = e.getValue();
            map.put(e.getKey(), (v instanceof ConfigurationSection cs) ? sectionToMap(cs) : v);
        }
        return map;
    }

    /** Build a ConfigurationSection from a nested Map (maps → sub-sections). */
    private static ConfigurationSection toSection(Map<String, Object> map) {
        MemoryConfiguration mc = new MemoryConfiguration();
        applyMap(mc, map);
        return mc;
    }

    private static void applyMap(ConfigurationSection sec, Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (e.getValue() instanceof Map<?, ?> mm) applyMap(sec.createSection(k), mm);
            else sec.set(k, e.getValue());
        }
    }

    /**
     * Recursively substitute ${name} references and expand {@code use:}/{@code with:}
     * template instantiations (a deep copy is returned). A node {@code { use: foo,
     * with: {x: 1}, offset: [...] }} deep-merges its own keys over template {@code foo}
     * and resolves it with the base vars plus {@code with}.
     */
    private Object resolveVars(Object node, Map<String, Object> vars, Map<String, Object> templates) {
        return resolveVars(node, vars, templates, 0);
    }

    @SuppressWarnings("unchecked")
    private Object resolveVars(Object node, Map<String, Object> vars, Map<String, Object> templates, int depth) {
        if (node instanceof Map<?, ?> m) {
            if (m.containsKey("use") && depth < 20) return expandUse((Map<String, Object>) m, vars, templates, depth);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (e.getValue() instanceof Map<?, ?> vm && vm.containsKey("grid"))
                    expandGrid(out, k, (Map<String, Object>) vm, vars, templates, depth);
                else
                    out.put(k, resolveVars(e.getValue(), vars, templates, depth));
            }
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) out.add(resolveVars(e, vars, templates, depth));
            return out;
        }
        if (node instanceof String s) {
            Object r = resolveString(s, vars);
            // A whole-value reference can yield a structure (e.g. <fillbar> → a whole
            // part map); resolve its inner ${}/use:/<...> too.
            return (r instanceof String) ? r : resolveVars(r, vars, templates, depth + 1);
        }
        return node;
    }

    /** Expand a {@code use:} node: deep-merge its keys over the named template, resolve with base+with vars. */
    @SuppressWarnings("unchecked")
    private Object expandUse(Map<String, Object> node, Map<String, Object> vars, Map<String, Object> templates, int depth) {
        String name = String.valueOf(node.get("use"));
        Object tmpl = templates.get(name);
        if (!(tmpl instanceof Map)) {
            getLogger().warning("Content: unknown template '" + name + "' (use:) — ignored.");
            tmpl = new LinkedHashMap<String, Object>();
        }
        Map<String, Object> scope = vars;
        if (node.get("with") instanceof Map<?, ?> w) {
            scope = new LinkedHashMap<>(vars);   // arguments override outer vars
            for (Map.Entry<?, ?> e : w.entrySet())   // resolve each arg in the outer scope (so with: { mat: "${elixir}" } works)
                scope.put(String.valueOf(e.getKey()), resolveVars(e.getValue(), vars, templates, depth));
        }
        Map<String, Object> over = new LinkedHashMap<>(node);
        over.remove("use");
        over.remove("with");
        Map<String, Object> merged = deepMerge((Map<String, Object>) tmpl, over);
        return resolveVars(merged, scope, templates, depth + 1);
    }

    /**
     * Expand a {@code grid:} node into many siblings — one per offset. The grid is
     * either an explicit list of {@code [x,y,z]} vectors, or a map of axis values
     * {@code { x:[..], y:.., z:[..] }} (cartesian product; scalars allowed). The
     * node's remaining keys (e.g. {@code use}/{@code block}/{@code scale}) form the
     * body placed at each offset.
     */
    private void expandGrid(Map<String, Object> out, String key, Map<String, Object> node,
                            Map<String, Object> vars, Map<String, Object> templates, int depth) {
        Object gridSpec = resolveVars(node.get("grid"), vars, templates, depth);
        List<double[]> offsets = gridOffsets(gridSpec);
        Map<String, Object> body = new LinkedHashMap<>(node);
        body.remove("grid");
        int i = 0;
        for (double[] v : offsets) {
            Map<String, Object> inst = new LinkedHashMap<>(body);
            inst.put("offset", List.of(v[0], v[1], v[2]));
            out.put(key + "_" + (i++), resolveVars(inst, vars, templates, depth));
        }
    }

    private static List<double[]> gridOffsets(Object grid) {
        List<double[]> out = new ArrayList<>();
        if (grid instanceof List<?> l) {
            for (Object e : l) {
                if (e instanceof List<?> v && v.size() == 3
                        && v.get(0) instanceof Number x && v.get(1) instanceof Number y && v.get(2) instanceof Number z)
                    out.add(new double[]{x.doubleValue(), y.doubleValue(), z.doubleValue()});
            }
        } else if (grid instanceof Map<?, ?> m) {
            List<Double> xs = axisVals(m.get("x")), ys = axisVals(m.get("y")), zs = axisVals(m.get("z"));
            for (double x : xs) for (double y : ys) for (double z : zs) out.add(new double[]{x, y, z});
        }
        return out;
    }

    private static List<Double> axisVals(Object o) {
        List<Double> r = new ArrayList<>();
        if (o instanceof List<?> l) { for (Object e : l) if (e instanceof Number n) r.add(n.doubleValue()); }
        else if (o instanceof Number n) r.add(n.doubleValue());
        if (r.isEmpty()) r.add(0.0);
        return r;
    }

    /** Deep-merge {@code over} onto {@code base} (maps merge recursively; scalars/lists override). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object cur = out.get(e.getKey());
            if (cur instanceof Map<?, ?> cm && e.getValue() instanceof Map<?, ?> om)
                out.put(e.getKey(), deepMerge((Map<String, Object>) cm, (Map<String, Object>) om));
            else out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** {@code <input>}/{@code <output>}/… are live runtime tokens — never treated as vars. */
    private static final Set<String> RESERVED_TOKENS = Set.of("input", "output", "processing", "result", "percent");
    private static final Pattern WHOLE_ANGLE = Pattern.compile("<([a-zA-Z0-9_.]+)>");

    private Object resolveString(String s, Map<String, Object> vars) {
        String t = s.trim();
        // Whole-value reference "<name>" → the thing defined up top (block, list, or a
        // whole part map). Reserved live tokens (<input>/<output>/<percent>) pass through.
        java.util.regex.Matcher angle = WHOLE_ANGLE.matcher(t);
        if (angle.matches()) {
            String name = angle.group(1);
            if (RESERVED_TOKENS.contains(name.toLowerCase())) return s;
            Object v = lookupVar(name, vars);
            return v != null ? v : s;
        }
        if (s.indexOf("${") < 0) return s;
        // Whole-value reference "${name}" → the raw var value (may be a list/map/number).
        if (t.startsWith("${") && t.endsWith("}") && t.indexOf("${", 2) < 0) {
            Object v = lookupVar(t.substring(2, t.length() - 1).trim(), vars);
            return v != null ? v : s;
        }
        // Inline interpolation: "Tier ${n} of ${max}".
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf("${", i);
            if (a < 0) { sb.append(s, i, s.length()); break; }
            sb.append(s, i, a);
            int close = s.indexOf('}', a);
            if (close < 0) { sb.append(s.substring(a)); break; }
            Object v = lookupVar(s.substring(a + 2, close).trim(), vars);
            sb.append(v != null ? String.valueOf(v) : s.substring(a, close + 1));
            i = close + 1;
        }
        return sb.toString();
    }

    /** Look up a var by name, supporting dotted paths into nested maps. */
    /**
     * Let var values reference other vars ({@code tname: "${rule}❖ ${t_name}"}): string values
     * containing {@code ${} are interpolated against the table itself, a few passes deep. Values
     * without {@code ${} (every block-name var) are untouched, so older files resolve as before.
     */
    private Map<String, Object> selfResolveVars(Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) return vars;
        Map<String, Object> out = new LinkedHashMap<>(vars);
        for (int pass = 0; pass < 6; pass++) {
            boolean changed = false;
            for (Map.Entry<String, Object> e : out.entrySet()) {
                if (e.getValue() instanceof String sv && sv.contains("${")) {
                    Object r = resolveString(sv, out);
                    if (!sv.equals(r)) { e.setValue(r); changed = true; }
                }
            }
            if (!changed) break;
        }
        return out;
    }

    private static Object lookupVar(String name, Map<String, Object> vars) {
        if (vars.containsKey(name)) return vars.get(name);
        Object cur = vars;
        for (String part : name.split("\\.")) {
            if (cur instanceof Map<?, ?> mm && mm.containsKey(part)) cur = ((Map<?, ?>) mm).get(part);
            else return null;
        }
        return cur;
    }

    private static int parseTierKey(String k) {
        try { return Integer.parseInt(k.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static double[] toDoubleArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    /** Parse the {@code anchor:} block (a real TileState block: chest/barrel/…), defaulting to CHEST. */
    private Material parseAnchor(String id, String name) {
        Material mat = Material.matchMaterial(name);
        if (mat == null || !mat.isBlock()) {
            getLogger().warning("Content: " + id + " anchor '" + name + "' isn't a block — using CHEST.");
            return Material.CHEST;
        }
        return mat;
    }

    /** Parse a {@code fuel:} section (material → seconds of burn) into material → burn ticks. */
    private Map<Material, Integer> parseFuel(String id, ConfigurationSection fuel) {
        Map<Material, Integer> map = new java.util.HashMap<>();
        if (fuel == null) return map;
        for (String key : fuel.getKeys(false)) {
            Material mat = Material.matchMaterial(key);
            if (mat == null || !mat.isItem()) {
                getLogger().warning("Content: " + id + " fuel has unknown item '" + key + "' — skipped.");
                continue;
            }
            int seconds = fuel.getInt(key, 0);
            if (seconds <= 0) continue;
            map.put(mat, seconds * 20);   // seconds → ticks
        }
        return map;
    }

    /** Parse a {@code recipes:} list (each: input, output, time) into {@link Recipe}s. */
    private List<Recipe> parseRecipes(String id, List<Map<?, ?>> raw) {
        List<Recipe> recipes = new ArrayList<>();
        if (raw == null) return recipes;
        for (Map<?, ?> m : raw) {
            List<ItemStack> in = parseItems(id, m.get("input"));   // may be empty → a generator recipe
            List<ItemStack> out = parseItems(id, m.get("output"));
            if (out.isEmpty()) {
                getLogger().warning("Content: " + id + " recipe needs an output — skipped.");
                continue;
            }
            int time = (m.get("time") instanceof Number num) ? num.intValue() : 40;
            recipes.add(new Recipe(in, out, time));
        }
        return recipes;
    }

    /** Parse one item or a list of items. Each item is "MATERIAL [AMOUNT]" or {item:, amount:}. */
    private List<ItemStack> parseItems(String id, Object o) {
        List<ItemStack> list = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object e : l) addItem(id, list, e);
        } else if (o != null) {
            addItem(id, list, o);
        }
        return list;
    }

    private void addItem(String id, List<ItemStack> list, Object o) {
        ItemStack item = buildItem(id, o);
        if (item != null) list.add(item);
    }

    /**
     * Build a recipe item from a spec. String = "MATERIAL [AMOUNT]" (vanilla).
     * Map supports custom items: one of {@code item}/{@code head}/{@code placer}
     * (+ {@code amount}), plus optional {@code name}/{@code lore} (MiniMessage)
     * and {@code model} (custom_model_data). {@code placer: <type>} mints a
     * machine placer — so a recipe can <b>assemble a machine</b> (e.g. a crusher).
     */
    private ItemStack buildItem(String id, Object o) {
        if (o instanceof String s) {
            String[] parts = s.trim().split("\\s+");
            Material mat = Material.matchMaterial(parts[0]);
            if (mat == null || !mat.isItem()) {
                getLogger().warning("Content: " + id + " recipe has unknown item '" + o + "' — skipped.");
                return null;
            }
            int amt = 1;
            if (parts.length > 1) try { amt = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
            return new ItemStack(mat, Math.max(1, amt));
        }
        if (o instanceof ConfigurationSection cs) o = cs.getValues(false);   // nested map (e.g. tiers.upgrade)
        if (!(o instanceof Map<?, ?> mm)) {
            getLogger().warning("Content: " + id + " recipe item '" + o + "' is not a string or map — skipped.");
            return null;
        }
        int amount = (mm.get("amount") instanceof Number num) ? Math.max(1, num.intValue()) : 1;
        ItemStack item;
        if (mm.get("placer") != null) {
            if (machines == null) {
                getLogger().warning("Content: " + id + " placer recipe loaded before the engine was ready — skipped.");
                return null;
            }
            item = machines.makePlacer(String.valueOf(mm.get("placer")));   // uses the type's configured icon
        } else if (mm.get("head") != null) {
            item = Heads.create(String.valueOf(mm.get("head")));
        } else if (mm.get("item") != null) {
            Material mat = Material.matchMaterial(String.valueOf(mm.get("item")));
            if (mat == null || !mat.isItem()) {
                getLogger().warning("Content: " + id + " recipe has unknown item '" + mm.get("item") + "' — skipped.");
                return null;
            }
            item = new ItemStack(mat);
        } else {
            getLogger().warning("Content: " + id + " recipe item map needs item/head/placer — skipped.");
            return null;
        }
        item.setAmount(amount);
        applyMeta(item, mm);
        return item;
    }

    /** Apply optional name (MiniMessage), lore, and custom_model_data to an item. */
    private void applyMeta(ItemStack item, Map<?, ?> mm) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        boolean touched = false;
        if (mm.get("name") != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(String.valueOf(mm.get("name"))));
            touched = true;
        }
        if (mm.get("lore") instanceof List<?> lore) {
            List<Component> lines = new ArrayList<>();
            for (Object ln : lore) lines.add(MiniMessage.miniMessage().deserialize(String.valueOf(ln)));
            meta.lore(lines);
            touched = true;
        }
        if (mm.get("model") instanceof Number num) {
            meta.setCustomModelData(num.intValue());
            touched = true;
        }
        if (touched) item.setItemMeta(meta);
    }

    /** Reload all content packs from disk and re-render placed machines. */
    public void reload() {
        models.clear();
        types.clear();
        for (File pack : contentPacks) loadPack(pack);
        dev.servereer.machineconstruct.grinder.econ.PixelProfilerPrices.refresh(getLogger());   // pick up /pishop edits now
        if (machines != null) machines.reRenderAll();
    }

    // --- /mc ----------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
            if (!sender.hasPermission("machineconstruct.debug")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            return handleDebug(sender, args);
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("guide") || args[0].equalsIgnoreCase("help"))) {
            if (!(sender instanceof Player player)) { sender.sendMessage(brand() + "§cPlayer only."); return true; }
            player.openBook(dev.servereer.machineconstruct.gui.Guide.book());
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("machineconstruct.reload")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            long t0 = System.currentTimeMillis();
            reload();
            sender.sendMessage(brand() + "§areloaded §f" + models.size()
                    + "§a model(s)/§f" + types.size() + "§a type(s) in §f"
                    + (System.currentTimeMillis() - t0) + "ms§a; re-rendered placed machines.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("panels")) {   // /mc panels [list|apply]
            if (!sender.hasPermission("machineconstruct.reload")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            String sub = args.length >= 2 ? args[1].toLowerCase() : "list";
            if (sub.equals("apply")) sender.sendMessage(brand() + machines.applyPanelIndex());
            else sender.sendMessage(brand() + machines.listPanels());
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("coin")) {   // /mc coin give <player> [n] [series] | set <series> | reset <series> | get [n] [series]
            if (!sender.hasPermission("machineconstruct.admin")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            String sub = args.length >= 2 ? args[1].toLowerCase() : "help";
            dev.servereer.machineconstruct.gacha.GachaManager g = machines.gacha();
            switch (sub) {
                case "give" -> {
                    Player target = args.length >= 3 ? getServer().getPlayer(args[2]) : null;
                    if (target == null) { sender.sendMessage(brand() + "§cPlayer not found."); return true; }
                    int n = 1; try { if (args.length >= 4) n = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) { }
                    String key = args.length >= 5 ? args[4] : "common";
                    ItemStack coin = g.coinByKey(key);
                    if (coin == null) { sender.sendMessage(brand() + "§cUnknown coin §f" + key + "§c — tiers: §f" + String.join(", ", g.coins().keys()) + "§c, or a series key."); return true; }
                    g.giveCoinItem(target, n, coin);
                    sender.sendMessage(brand() + "§aGave §f" + n + "× " + dev.servereer.machineconstruct.gacha.GachaSeries.displayName(coin) + "§a to §f" + target.getName() + "§a.");
                }
                case "get" -> {
                    if (!(sender instanceof Player pl)) { sender.sendMessage(brand() + "§cPlayers only."); return true; }
                    int n = 1; try { if (args.length >= 3) n = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) { }
                    ItemStack coin = g.coinByKey(args.length >= 4 ? args[3] : "common");
                    if (coin == null) { sender.sendMessage(brand() + "§cUnknown coin. Tiers: §f" + String.join(", ", g.coins().keys())); return true; }
                    g.giveCoinItem(pl, n, coin);
                }
                case "set", "reset" -> {
                    if (!(sender instanceof Player pl)) { sender.sendMessage(brand() + "§cPlayers only."); return true; }
                    dev.servereer.machineconstruct.gacha.GachaSeries s = args.length >= 3 ? g.seriesByKey(args[2]) : null;
                    if (s == null) { sender.sendMessage(brand() + "§cUsage: /mc coin " + sub + " <series>  (" + String.join(", ", g.allSeries().stream().map(dev.servereer.machineconstruct.gacha.GachaSeries::key).toList()) + ")"); return true; }
                    if (sub.equals("reset")) { s.setCoin(null); sender.sendMessage(brand() + "§aSeries §f" + s.key() + "§a takes the default token again."); }
                    else {
                        ItemStack hand = pl.getInventory().getItemInMainHand();
                        if (hand == null || hand.getType().isAir()) { sender.sendMessage(brand() + "§cHold the item that should be the coin."); return true; }
                        s.setCoin(hand); sender.sendMessage(brand() + "§aSeries §f" + s.key() + "§a now takes §f" + dev.servereer.machineconstruct.gacha.GachaSeries.displayName(hand) + "§a.");
                    }
                }
                case "list" -> sender.sendMessage(brand() + "§7Coin ladder: §f" + String.join("§7, §f", g.coins().keys()) + "§7 — series coins: §f" + String.join(", ", g.allSeries().stream().map(dev.servereer.machineconstruct.gacha.GachaSeries::key).toList()));
                default -> sender.sendMessage(brand() + "§7/mc coin give <player> [n] [tier|series] §8· §7get [n] [tier|series] §8· §7set <series> §8(held item) §7· reset <series> §8· §7list");
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("gacha")) {   // /mc gacha reload — re-read the series files (loot fed in-game / edited in Dev's Diary)
            if (!sender.hasPermission("machineconstruct.reload")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            if (args.length >= 4 && args[1].equalsIgnoreCase("import")) {   // /mc gacha import <series> <crate> — copy an ExcellentCrates crate's rewards in
                String series = args[2];
                MachineType mt = null;
                for (MachineType t : types.values()) if (t.gacha() != null && t.gacha().series().equalsIgnoreCase(series)) { mt = t; break; }
                if (mt == null) { sender.sendMessage(brand() + "§cNo capsule machine dispenses series §f" + series + "§c."); return true; }
                sender.sendMessage(brand() + machines.gacha().importCrate(mt.gacha(), args[3]));
                return true;
            }
            int n = machines.gacha().reload();
            sender.sendMessage(brand() + "§aReloaded §f" + n + "§a capsule series file(s).");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("btn")) {   // /mc btn — button ray-cast diagnostics (aim at a button first)
            if (!sender.hasPermission("machineconstruct.reload")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            if (!(sender instanceof Player pl)) { sender.sendMessage(brand() + "§cPlayers only."); return true; }
            String out = machines.debugButtons(pl);
            sender.sendMessage(brand() + System.lineSeparator() + out);
            getLogger().info("[btn] " + pl.getName() + System.lineSeparator() + out.replaceAll("§.", ""));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("backup")) {
            if (!sender.hasPermission("machineconstruct.reload")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            String sub = args.length >= 2 ? args[1].toLowerCase() : "now";
            switch (sub) {
                case "list", "status" -> sender.sendMessage(brand() + machines.backupSummary());
                case "info" -> {
                    if (sender instanceof Player p) sender.sendMessage(brand() + machines.backupInfoLookedAt(p));
                    else sender.sendMessage(brand() + "§cRun this in-game, looking at a machine.");
                }
                default -> sender.sendMessage(brand() + machines.backupNow());   // /mc backup [now]
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("restore")) {
            if (!sender.hasPermission("machineconstruct.reload")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            if (args.length >= 2) {   // /mc restore <id> [version] — by id, no line-of-sight
                Integer ver = null;
                if (args.length >= 3) {
                    try { ver = Integer.parseInt(args[2]); }
                    catch (NumberFormatException ex) { sender.sendMessage(brand() + "§cVersion must be a number (1 = oldest kept)."); return true; }
                }
                sender.sendMessage(brand() + machines.restoreById(args[1], ver));
                return true;
            }
            if (!(sender instanceof Player p)) { sender.sendMessage(brand() + "§cRun this in-game looking at the machine, or use §f/mc restore <id> [version]§c."); return true; }
            sender.sendMessage(brand() + machines.restoreLookedAt(p));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("machineconstruct.reload")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            if (!(sender instanceof Player p)) { sender.sendMessage(brand() + "§cPlayers only."); return true; }
            machines.openAdminMenu(p);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("prices")) {
            // /mc prices [reload] — re-read PixelProfiler prices.yml + shops now (no restart, no re-render).
            if (!sender.hasPermission("machineconstruct.reload")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            dev.servereer.machineconstruct.grinder.econ.PixelProfilerPrices.refresh(getLogger());
            int n = dev.servereer.machineconstruct.grinder.econ.PixelProfilerPrices.count();
            if (dev.servereer.machineconstruct.grinder.econ.PixelProfilerPrices.enabled()) {
                sender.sendMessage(brand() + "§areloaded PixelProfiler prices — §f" + n
                        + "§a item price(s) now active (prices.yml + shops).");
            } else {
                sender.sendMessage(brand() + "§7PixelProfiler price bridge is §cdisabled§7 in config.yml.");
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("machineconstruct.give")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            // /mc give <type> [player] [amount] — console + target support (so shops can deliver machines)
            String type = args.length >= 2 ? args[1] : "test";
            if (!types.containsKey(type.toLowerCase())) {
                sender.sendMessage(brand() + "§cNo machine '§f" + type + "§c'. Known: §f" + String.join("§7, §f", types.keySet()));
                return true;
            }
            Player target;
            if (args.length >= 3) {
                target = getServer().getPlayerExact(args[2]);
                if (target == null) { sender.sendMessage(brand() + "§cPlayer '§f" + args[2] + "§c' is not online."); return true; }
            } else if (sender instanceof Player ps) {
                target = ps;
            } else { sender.sendMessage(brand() + "§cFrom console, specify a player: §f/mc give " + type + " <player> [amount]"); return true; }
            int amount = 1;
            if (args.length >= 4) try { amount = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) {}
            for (int i = 0; i < amount; i++) target.getInventory().addItem(machines.makePlacer(type));
            target.sendMessage(brand() + "§aReceived §f" + amount + "× " + type + "§a placer. Right-click a block face to build.");
            if (target != sender) sender.sendMessage(brand() + "§aGave §f" + amount + "× " + type + "§a to §f" + target.getName() + "§a.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("spawner")) {
            // /mc spawner <mob> [player] [amount] — give a vanilla typed spawner (grinder fodder, no SmartSpawner needed)
            if (!sender.hasPermission("machineconstruct.give")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            if (args.length < 2) { sender.sendMessage(brand() + "§7usage: §f/mc spawner <mob> [player] [amount]"); return true; }
            org.bukkit.entity.EntityType et;
            try { et = org.bukkit.entity.EntityType.valueOf(args[1].toUpperCase()); }
            catch (IllegalArgumentException ex) { sender.sendMessage(brand() + "§cUnknown mob '§f" + args[1] + "§c'."); return true; }
            Player target;
            if (args.length >= 3) { target = getServer().getPlayerExact(args[2]); if (target == null) { sender.sendMessage(brand() + "§cPlayer '§f" + args[2] + "§c' is not online."); return true; } }
            else if (sender instanceof Player ps) target = ps;
            else { sender.sendMessage(brand() + "§cFrom console, specify a player."); return true; }
            int amount = 1;
            if (args.length >= 4) try { amount = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) {}
            org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.SPAWNER, Math.max(1, amount));
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta bsm && bsm.getBlockState() instanceof org.bukkit.block.CreatureSpawner cs) {
                cs.setSpawnedType(et); bsm.setBlockState(cs); item.setItemMeta(bsm);
            }
            target.getInventory().addItem(item);
            target.sendMessage(brand() + "§aReceived §f" + amount + "× " + args[1].toLowerCase() + "§a spawner.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("giveonce")) {
            // /mc giveonce <type> [player] — give a machine placer only the FIRST time per player (PDC-tracked)
            if (!sender.hasPermission("machineconstruct.give")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            String gtype = args.length >= 2 ? args[1] : "test";
            if (!types.containsKey(gtype.toLowerCase())) { sender.sendMessage(brand() + "§cNo machine '§f" + gtype + "§c'."); return true; }
            Player target;
            if (args.length >= 3) { target = getServer().getPlayerExact(args[2]); if (target == null) { sender.sendMessage(brand() + "§cPlayer '§f" + args[2] + "§c' is not online."); return true; } }
            else if (sender instanceof Player ps) target = ps;
            else { sender.sendMessage(brand() + "§cFrom console, specify a player."); return true; }
            org.bukkit.NamespacedKey gotKey = new org.bukkit.NamespacedKey(this, "got_" + gtype.toLowerCase());
            var pdc = target.getPersistentDataContainer();
            if (pdc.has(gotKey, org.bukkit.persistence.PersistentDataType.BYTE)) return true;   // already received once
            target.getInventory().addItem(machines.makePlacer(gtype));
            pdc.set(gotKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            target.sendMessage(brand() + "§a✦ Free §f" + gtype + "§a — right-click a block face to build it!");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("recipes")) {
            if (args.length < 2) {
                sender.sendMessage(brand() + "§7usage: §f/mc recipes <machine>§7. Known: §f"
                        + String.join("§7, §f", types.keySet()));
                return true;
            }
            MachineType type = types.get(args[1].toLowerCase());
            if (type == null) {
                sender.sendMessage(brand() + "§cNo machine '§f" + args[1] + "§c'. Known: §f"
                        + String.join("§7, §f", types.keySet()));
                return true;
            }
            sender.sendMessage(brand() + "§7recipes for §f" + type.id() + "§7:");
            for (net.kyori.adventure.text.Component line : RecipeText.lines(type)) sender.sendMessage(line);
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("scanfarm") || args[0].equalsIgnoreCase("scancategory"))) {
            if (!sender.hasPermission("machineconstruct.admin")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage(brand() + "§cPlayer only."); return true; }
            return args[0].equalsIgnoreCase("scanfarm")
                    ? scanWizard.handleScanFarm(player, args)
                    : scanWizard.handleScanCategory(player, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("grinder")) {
            if (!sender.hasPermission("machineconstruct.admin")) { sender.sendMessage(brand() + "§cNo permission."); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage(brand() + "§cPlayer only."); return true; }
            if (args.length >= 3 && args[1].equalsIgnoreCase("add")) {
                long stack = 1;
                if (args.length >= 4) try { stack = Long.parseLong(args[3]); } catch (NumberFormatException ignored) {}
                machines.installSpawner(player, args[2], stack);
                return true;
            }
            player.sendMessage(brand() + "§7usage: §f/mc grinder add <mob> [stack]§7 — look at the grinder first.");
            return true;
        }
        sender.sendMessage(brand() + "§7engine v" + version()
                + " §8— §7try §f/mc guide §8| §f/mc give §8| §f/mc recipes <m> §8| §f/mc prices §8| §f/mc reload§7. Machines: §f" + machines.count());
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(brand() + "§cPlayer only.");
                    return true;
                }
                Material mat = Material.COPPER_BLOCK;
                if (args.length >= 3) {
                    Material parsed = Material.matchMaterial(args[2]);
                    if (parsed == null || !parsed.isBlock()) {
                        sender.sendMessage(brand() + "§cNot a block: §f" + args[2]);
                        return true;
                    }
                    mat = parsed;
                }
                BlockData block = mat.createBlockData();
                tracker.register(PacketDisplay.block(player.getLocation(), block, 1.0f));
                sender.sendMessage(brand() + "§aSpawned §f" + mat + "§a display. Total: §f" + tracker.count());
                return true;
            }
            case "clear" -> {
                int n = tracker.count();
                tracker.clearAll();
                sender.sendMessage(brand() + "§eCleared §f" + n + "§e displays.");
                return true;
            }
            default -> {
                sender.sendMessage(brand() + "§7usage: §f/mc debug spawn [block] §8| §f/mc debug clear");
                return true;
            }
        }
    }

    private String brand() {
        return "§x§d§4§a§f§3§7MachineConstruct §8» ";
    }
}
