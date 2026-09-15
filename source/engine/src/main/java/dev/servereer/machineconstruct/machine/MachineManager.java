package dev.servereer.machineconstruct.machine;

import dev.servereer.machineconstruct.api.MachineConstructAPI;
import dev.servereer.machineconstruct.core.PacketDisplay;
import dev.servereer.machineconstruct.gui.GuiLayout;
import dev.servereer.machineconstruct.gui.MachineMenus;
import dev.servereer.machineconstruct.grinder.ChestLink;
import dev.servereer.machineconstruct.grinder.GrinderData;
import dev.servereer.machineconstruct.grinder.GrinderManager;
import dev.servereer.machineconstruct.grinder.GrinderSpec;
import dev.servereer.machineconstruct.grinder.InstalledSpawner;
import dev.servereer.machineconstruct.grinder.LootTable;
import dev.servereer.machineconstruct.grinder.econ.EconomyBridge;
import dev.servereer.machineconstruct.grinder.econ.PriceService;
import dev.servereer.machineconstruct.quarry.QuarryActions;
import dev.servereer.machineconstruct.quarry.QuarryContracts;
import dev.servereer.machineconstruct.quarry.QuarryData;
import dev.servereer.machineconstruct.quarry.QuarryManager;
import dev.servereer.machineconstruct.quarry.QuarrySkill;
import dev.servereer.machineconstruct.model.Model;
import dev.servereer.machineconstruct.model.RenderedModel;
import dev.servereer.machineconstruct.model.anim.DriverContext;
import dev.servereer.machineconstruct.model.anim.FxRequest;
import dev.servereer.machineconstruct.tracking.DisplayTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.TileState;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P2 chest-anchor lifecycle (DESIGN.md §3–§4). Placer head → place a real chest,
 * tag it (PDC), register a {@link Machine} + render a debug display above it →
 * break the chest to tear down + return the placer. The chest's PDC tag is the
 * source of truth: chunk-load (and an enable-time scan of loaded chunks)
 * re-registers tagged chests, so machines survive restart on the tag alone —
 * the richer state (inventory/progress) + real storage arrive in P5.
 */
public final class MachineManager implements Listener,
        dev.servereer.machineconstruct.grinder.GrinderActions,
        dev.servereer.machineconstruct.quarry.QuarryActions,
        dev.servereer.machineconstruct.tradinghall.TradingHallActions,
        dev.servereer.machineconstruct.factorydistrict.FactoryDistrictActions,
        dev.servereer.machineconstruct.collector.ChunkCollectorActions,
        dev.servereer.machineconstruct.jukebox.JukeboxActions {

    private final Plugin plugin;
    private final DisplayTracker tracker;
    private final MachineConstructAPI registry;   // model + machine-type lookup
    private final NamespacedKey placerKey;   // on the placer item
    private final NamespacedKey machineKey;   // on the anchor chest TileState
    private final NamespacedKey itemsKey;     // serialized sealed store on the chest TileState
    private final NamespacedKey grinderKey;   // serialized grinder state on the chest TileState
    private final NamespacedKey quarryKey;    // serialized quarry state on the chest TileState
    private final NamespacedKey tradingHallKey; // serialized trading-hall state on the chest TileState
    private final NamespacedKey factoryKey;     // serialized factory-district state on the chest TileState
    private final NamespacedKey collectorKey;   // serialized chunk-collector state on the chest TileState
    private final NamespacedKey jukeboxKey;     // serialized jukebox state on the chest TileState
    private final MachineMenus menus;
    private final dev.servereer.machineconstruct.gui.GrinderMenus grinderMenus;
    private final dev.servereer.machineconstruct.gui.QuarryMenus quarryMenus;
    private final dev.servereer.machineconstruct.gui.TradingHallMenus tradingHallMenus;
    private final dev.servereer.machineconstruct.gui.FactoryDistrictMenus factoryMenus;
    private final dev.servereer.machineconstruct.gui.ChunkCollectorMenus collectorMenus;
    private final dev.servereer.machineconstruct.gui.PanelEditor panelEditor;
    private final PanelIndex panelIndex;          // plugins/MachineConstruct/panels.yml — placed panels, for Dev's Diary
    private final ButtonManager buttons;          // physical buttons: hover glow + press + actions
    private final CuePlayer cues;                 // one-shot scripted animations (cues:)
    private final dev.servereer.machineconstruct.gacha.GachaManager gacha;   // capsule machines (ADR 0045)
    private final dev.servereer.machineconstruct.gui.GachaMenus gachaMenus;
    private final dev.servereer.machineconstruct.gacha.ClawManager claw;   // crane sessions (style: claw)
    public dev.servereer.machineconstruct.gacha.GachaManager gacha() { return gacha; }
    private boolean panelIndexDirty;
    private final dev.servereer.machineconstruct.gui.JukeboxMenus jukeboxMenus;
    private final dev.servereer.machineconstruct.music.TrackLibrary trackLibrary;
    private final dev.servereer.machineconstruct.music.PlaylistLibrary playlistLibrary;
    private final dev.servereer.machineconstruct.music.MusicPlayer musicPlayer;
    private final dev.servereer.machineconstruct.music.DiscItem discItem;
    private final java.util.Set<String> jukeboxStarting = java.util.concurrent.ConcurrentHashMap.newKeySet();   // anchors with an in-flight play (dedupe sweep restarts)
    private final ExternalMachineStore externalStore;   // persistence for non-TileState anchors (e.g. BARRIER)
    private MachineBackupStore backups;                 // rolling per-machine state history (reset recovery)
    private final long animTicks;             // ticks between animation frames
    private final int processTicks;           // ticks between processing passes (= progress credited)
    private final int[] ioReachPerTier;       // I/O chest link reach (blocks) by tier (index 0 = tier 1); last applies beyond
    private static final long DEFAULT_OUTPUT_RATE = 10;  // new OUTPUT links start at 10/sec (anti-flood; user-configurable)
    private final Map<UUID, Object[]> pendingChestSelect = new HashMap<>();  // player → [Machine, ChestLink.Type]
    private final Map<UUID, Object[]> pendingChestRate = new HashMap<>();    // player → [Machine, Integer index]
    private final Map<UUID, Object[]> pendingAutoSellRate = new HashMap<>(); // player → [Machine, Material]
    private final double tickDt;              // animTicks in seconds (for rate-gated FX)

    private EconomyBridge economy;   // lazily resolved (Vault may enable after us)

    private EconomyBridge economy() {
        if (economy == null) economy = new EconomyBridge();
        return economy;
    }

    private final Map<String, Machine> byAnchor = new HashMap<>();
    private final java.util.Set<UUID> faulted = new java.util.HashSet<>();   // machines quarantined after a runtime error
    private final Map<UUID, Integer> anchorMiss = new HashMap<>();   // consecutive processing passes a machine's anchor block has gone missing (orphan-teardown debounce)
    private final java.util.Set<UUID> backupDirty = java.util.concurrent.ConcurrentHashMap.newKeySet();   // machines changed since the last backup snapshot (incremental snapshot)
    private final long startMillis = System.currentTimeMillis();

    public MachineManager(Plugin plugin, DisplayTracker tracker, MachineConstructAPI registry,
                          int animationInterval, int processInterval,
                          dev.servereer.machineconstruct.music.TrackLibrary trackLibrary,
                          dev.servereer.machineconstruct.music.PlaylistLibrary playlistLibrary,
                          dev.servereer.machineconstruct.music.MusicPlayer musicPlayer,
                          dev.servereer.machineconstruct.music.DiscItem discItem) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.registry = registry;
        this.trackLibrary = trackLibrary;
        this.playlistLibrary = playlistLibrary;
        this.musicPlayer = musicPlayer;
        this.discItem = discItem;
        this.placerKey = new NamespacedKey(plugin, "placer");
        this.machineKey = new NamespacedKey(plugin, "machine");
        this.itemsKey = new NamespacedKey(plugin, "items");
        this.grinderKey = new NamespacedKey(plugin, "grinder");
        this.quarryKey = new NamespacedKey(plugin, "quarry");
        this.tradingHallKey = new NamespacedKey(plugin, "tradinghall");
        this.factoryKey = new NamespacedKey(plugin, "factory");
        this.collectorKey = new NamespacedKey(plugin, "collector");
        this.jukeboxKey = new NamespacedKey(plugin, "jukebox");
        this.menus = new MachineMenus(plugin, this::persistStore, this::upgradeMachine, this::sellOutputs);
        this.grinderMenus = new dev.servereer.machineconstruct.gui.GrinderMenus(plugin, registry, this);
        this.quarryMenus = new dev.servereer.machineconstruct.gui.QuarryMenus(plugin, registry, this);
        this.tradingHallMenus = new dev.servereer.machineconstruct.gui.TradingHallMenus(plugin, registry, this);
        this.factoryMenus = new dev.servereer.machineconstruct.gui.FactoryDistrictMenus(plugin, registry, this);
        this.collectorMenus = new dev.servereer.machineconstruct.gui.ChunkCollectorMenus(plugin, registry, this);
        this.panelEditor = new dev.servereer.machineconstruct.gui.PanelEditor(plugin, new dev.servereer.machineconstruct.gui.PanelEditor.Host() {
            @Override public MachineType typeOf(Machine m) { return typeOfMachine(m); }
            @Override public void persist(Machine m) { persistStore(m); panelIndexDirty = true; }
            @Override public void refresh(Machine m) { panelResolve(m, true); for (PacketDisplay d : m.displays()) if (d.consumeDirty()) tracker.refresh(d); }
            @Override public void rerender(Machine m) { renderMachine(m); }
        });
        this.jukeboxMenus = new dev.servereer.machineconstruct.gui.JukeboxMenus(plugin, registry, this, trackLibrary, playlistLibrary);
        this.externalStore = new ExternalMachineStore(plugin.getDataFolder());
        this.panelIndex = new PanelIndex(plugin.getDataFolder());
        this.buttons = new ButtonManager(plugin, tracker, () -> byAnchor.values(), this::onButtonPress);
        this.cues = new CuePlayer(plugin, tracker);
        this.gacha = new dev.servereer.machineconstruct.gacha.GachaManager(plugin, cues, this::economy, new dev.servereer.machineconstruct.gacha.GachaManager.Host() {
            @Override public MachineType typeOf(Machine m) { return typeOfMachine(m); }
            @Override public void showResults(Player p, Machine m, List<dev.servereer.machineconstruct.gacha.GachaManager.Result> results) { gachaMenus.showResults(p, m, results); }
            @Override public void persist(Machine m) { persistStore(m); }
            @Override public void rerender(Machine m) { renderMachine(m); }
        });
        this.gachaMenus = new dev.servereer.machineconstruct.gui.GachaMenus(plugin, gacha, this::typeOfMachine);
        this.claw = new dev.servereer.machineconstruct.gacha.ClawManager(plugin, tracker, gacha);
        this.backups = new MachineBackupStore(plugin.getDataFolder(), backupRetention());
        this.animTicks = Math.max(1, animationInterval);
        this.processTicks = Math.max(1, processInterval);
        this.tickDt = this.animTicks / 20.0;
        java.util.List<Integer> reach = plugin.getConfig().getIntegerList("io.reach-per-tier");
        this.ioReachPerTier = (reach == null || reach.isEmpty())
                ? new int[]{16, 24, 32, 48}
                : reach.stream().mapToInt(Integer::intValue).toArray();
    }

    /** I/O chest link reach (blocks) at a machine tier (1-based); the last config value applies to higher tiers. */
    private int ioReach(int tier) {
        if (ioReachPerTier.length == 0) return 16;
        int idx = Math.max(0, Math.min(tier - 1, ioReachPerTier.length - 1));
        return ioReachPerTier[idx];
    }

    public NamespacedKey placerKey() {
        return placerKey;
    }

    /** Mint a fresh placer (no carried state) with this type's configured icon. */
    public ItemStack makePlacer(String type) {
        MachineType mt = registry == null ? null : registry.getMachineType(type);
        return Placer.create(placerKey, type, mt == null ? null : mt.placerIcon());
    }

    /** Mint a placer carrying the machine's FULL serialized state — lossless break→place. */
    private ItemStack makePlacerWithState(String type, Machine m) {
        ItemStack placer = makePlacer(type);
        org.bukkit.inventory.meta.ItemMeta meta = placer.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        pdc.set(itemsKey, PersistentDataType.STRING, MachineStore.serialize(m));
        if (m.hasGrinder()) pdc.set(grinderKey, PersistentDataType.STRING,
                dev.servereer.machineconstruct.grinder.GrinderStore.serialize(m.grinder()));
        if (m.hasQuarry()) pdc.set(quarryKey, PersistentDataType.STRING,
                dev.servereer.machineconstruct.quarry.QuarryStore.serialize(m.quarry()));
        if (m.hasTradingHall()) pdc.set(tradingHallKey, PersistentDataType.STRING,
                dev.servereer.machineconstruct.tradinghall.TradingHallStore.serialize(m.tradingHall()));
        if (m.hasFactory()) pdc.set(factoryKey, PersistentDataType.STRING,
                dev.servereer.machineconstruct.factorydistrict.FactoryDistrictStore.serialize(m.factory()));
        if (m.hasChunkCollector()) pdc.set(collectorKey, PersistentDataType.STRING,
                dev.servereer.machineconstruct.collector.ChunkCollectorStore.serialize(m.chunkCollector()));
        if (m.hasJukebox()) pdc.set(jukeboxKey, PersistentDataType.STRING,
                dev.servereer.machineconstruct.jukebox.JukeboxStore.serialize(m.jukebox()));
        java.util.List<net.kyori.adventure.text.Component> lore = meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(net.kyori.adventure.text.Component.text("✦ Holds this machine's contents + upgrades.",
                net.kyori.adventure.text.format.NamedTextColor.AQUA)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        if (m.tier() > 1) lore.add(net.kyori.adventure.text.Component.text("Tier " + m.tier(),
                net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore);
        placer.setItemMeta(meta);
        return placer;
    }

    /** Copy any carried state strings off a placer onto a freshly-placed machine's tile PDC. */
    private void copyPlacerState(ItemStack placer, TileState tile) {
        if (placer == null) return;
        org.bukkit.inventory.meta.ItemMeta meta = placer.getItemMeta();
        if (meta == null) return;
        var src = meta.getPersistentDataContainer();
        for (NamespacedKey kk : new NamespacedKey[]{itemsKey, grinderKey, quarryKey, tradingHallKey, factoryKey, collectorKey, jukeboxKey}) {
            String v = src.get(kk, PersistentDataType.STRING);
            if (v != null) tile.getPersistentDataContainer().set(kk, PersistentDataType.STRING, v);
        }
    }

    /** Copy any carried state strings off a placer into the external store (non-TileState anchors). */
    private void copyPlacerStateExternal(ItemStack placer, String lk) {
        if (placer == null) return;
        org.bukkit.inventory.meta.ItemMeta meta = placer.getItemMeta();
        if (meta == null) return;
        var src = meta.getPersistentDataContainer();
        String[][] map = {{"items", null}, {"grinder", null}, {"quarry", null}, {"tradinghall", null}, {"factory", null}, {"collector", null}, {"jukebox", null}};
        NamespacedKey[] keys = {itemsKey, grinderKey, quarryKey, tradingHallKey, factoryKey, collectorKey, jukeboxKey};
        for (int i = 0; i < keys.length; i++) {
            String v = src.get(keys[i], PersistentDataType.STRING);
            if (v != null) externalStore.set(lk, map[i][0], v);
        }
    }

    public int count() {
        return byAnchor.size();
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(menus, plugin);
        plugin.getServer().getPluginManager().registerEvents(grinderMenus, plugin);
        plugin.getServer().getPluginManager().registerEvents(quarryMenus, plugin);
        plugin.getServer().getPluginManager().registerEvents(tradingHallMenus, plugin);
        plugin.getServer().getPluginManager().registerEvents(factoryMenus, plugin);
        plugin.getServer().getPluginManager().registerEvents(collectorMenus, plugin);
        plugin.getServer().getPluginManager().registerEvents(panelEditor, plugin);
        plugin.getServer().getPluginManager().registerEvents(jukeboxMenus, plugin);
        plugin.getServer().getPluginManager().registerEvents(gachaMenus, plugin);
        plugin.getServer().getPluginManager().registerEvents(claw, plugin);
        scanLoadedChunks();
        // Shared animation clock: recompute animated machines on the configured
        // interval and re-send their leaves to viewers (interpolated over it).
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAnimations, animTicks, animTicks);
        // Recipe processing on the configured interval.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickProcessing, processTicks, processTicks);
        // Batch-flush the external (barrier-anchor) store to disk every 20s.
        plugin.getServer().getScheduler().runTaskTimer(plugin, externalStore::flush, 400L, 400L);
        // Panels: re-resolve live %placeholders% on each panel's own interval (checked once a second),
        // and flush panels.yml whenever a panel was placed / broken / edited / loaded.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickPanels, 40L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::flushPanelIndex, 60L, 100L);
        buttons.start();
        cues.start();
        // Rolling machine-state backups: snapshot only the machines that CHANGED since last time, and
        // write to disk OFF the main thread (per-machine files). The old "serialize everything + save one
        // multi-MB file on the main-thread tick" was freezing the server and disconnecting players.
        long backupTicks = 20L * 60L * Math.max(1, plugin.getConfig().getInt("backups.interval-minutes", 10));
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::snapshotDirtyAndFlush, backupTicks, backupTicks);
    }
    private final List<FxRequest> fxBuf = new ArrayList<>();

    // --- panels: live placeholder text ---------------------------------------
    private final Map<UUID, Long> panelDue = new HashMap<>();   // machine id → next refresh (ms)
    private java.lang.reflect.Method papiSet;                    // PlaceholderAPI.setPlaceholders(OfflinePlayer, String), if present
    private boolean papiLooked;

    private void tickPanels() {
        if (registry == null) return;
        long now = System.currentTimeMillis();
        for (Machine m : byAnchor.values()) {
            MachineType t = registry.getMachineType(m.typeId());
            if (t == null || !t.isPanel() || faulted.contains(m.id())) continue;
            Long due = panelDue.get(m.id());
            if (due != null && now < due) continue;
            panelDue.put(m.id(), now + t.panelRefreshTicks() * 50L);
            if (!hasViewerNear(m)) continue;   // nobody looking → skip the resolve, keep the schedule
            try { panelResolve(m, false); } catch (Throwable ex) { fault(m, "panel", ex); }
        }
    }

    /**
     * A placed panel's per-part layout overrides (from the Studio / panels.yml): shift the text block
     * dx/dy in model space (rotated with the panel's facing), set its size (around its bottom-centre
     * anchor), justification and wrap width. Applied right after render, before displays spawn.
     */
    private void applyPanelLayouts(Machine m) {
        PanelData pd = m.panel();
        if (pd == null || pd.layouts().isEmpty() || m.rendered() == null) return;
        for (PacketDisplay d : m.rendered().leaves()) {
            if (d.partName() == null || !(d.baseContent() instanceof dev.servereer.machineconstruct.core.TextContent tc)) continue;
            PanelData.TextLayout l = pd.layout(d.partName());
            if (l == null) continue;
            dev.servereer.machineconstruct.core.MTransform t = d.transform();
            // dx is in the VIEWER's frame (+ = their right); the panel's front faces -Z, so that is model -X.
            // Rotate by the panel's FACING only — a text leaf's own rotation includes its 180° read-from-the-front
            // flip, which would send the shift the other way.
            dev.servereer.machineconstruct.core.MTransform yaw = dev.servereer.machineconstruct.core.MTransform.of(
                    new double[]{0, 0, 0}, new double[]{1, 1, 1}, new double[]{m.facing(), 0, 0});
            float[] off = yaw.rotateVec(l.dx() == null ? 0f : -l.dx().floatValue(), l.dy() == null ? 0f : l.dy().floatValue(), 0f);
            float s = l.scale() == null ? t.sx : l.scale().floatValue();
            d.setTransform(new dev.servereer.machineconstruct.core.MTransform(t.tx + off[0], t.ty + off[1], t.tz + off[2], s, s, s, t.qx, t.qy, t.qz, t.qw));
            if (l.width() != null || l.align() != null)
                d.rebase(tc.withLayout(l.width() == null ? tc.lineWidth() : l.width(), l.align() == null ? tc.align() : l.align()));
        }
    }

    /** panelResolve, returning the text leaves whose content changed (for transition effects). */
    private List<PacketDisplay> panelResolveCollect(Machine m) {
        List<PacketDisplay> before = new ArrayList<>();
        RenderedModel r = m.rendered();
        if (r == null) return before;
        Map<PacketDisplay, dev.servereer.machineconstruct.core.DisplayContent> prev = new HashMap<>();
        for (PacketDisplay d : r.leaves()) prev.put(d, d.baseContent());
        panelResolve(m, false);
        for (PacketDisplay d : r.leaves())
            if (d.baseContent() instanceof dev.servereer.machineconstruct.core.TextContent && prev.get(d) != d.baseContent()) before.add(d);
        return before;
    }

    /** Text transition: the new text appears at low opacity and ramps to full over ~6 ticks. */
    private void fadeIn(List<PacketDisplay> texts) {
        if (texts.isEmpty()) return;
        byte[] ramp = { 40, 90, (byte) 150, (byte) 210, (byte) 255 };
        for (int i = 0; i < ramp.length; i++) {
            final byte a = ramp[i];
            Runnable step = () -> { for (PacketDisplay d : texts) tracker.forEachViewer(d, v -> d.sendTextOpacity(v, a)); };
            if (i == 0) step.run(); else plugin.getServer().getScheduler().runTaskLater(plugin, step, i + 1L);
        }
    }

    /**
     * Resolve every text leaf of a panel: the admin's per-panel override (if any) or the authored
     * template, {@code {theme}} → the panel's colour tag, then {@code %placeholders%} through PAPI.
     * Changed leaves are pushed to viewers (unless {@code initial}, when the display hasn't spawned yet).
     */
    private void panelResolve(Machine m, boolean initial) {
        RenderedModel r = m.rendered();
        if (r == null) return;
        MachineType t = typeOfMachine(m);
        PanelData pd = m.panel();
        String theme = pd != null && pd.color() != null ? pd.color()
                : (t == null ? "#35e0d0" : t.panelThemeColor(pd == null ? null : pd.theme()));
        String state = t == null ? "" : panelState(m, t);
        Map<String, String> stateVars = t == null ? Map.of() : t.panelStates().getOrDefault(state, Map.of());
        // paged (info) panels: the current page's section texts win over static overrides and the template
        boolean paged = t != null && t.panelPaged();
        List<Map<String, List<String>>> pages = !paged ? List.of() : (pd != null && !pd.pages().isEmpty() ? pd.pages() : t.panelPages());
        int pageCount = pages.size();
        int page = pd == null ? 0 : Math.min(Math.max(0, pd.page()), Math.max(0, pageCount - 1));
        for (PacketDisplay d : r.leaves()) {
            // buttons: light the one whose lit_when matches the current state
            if (d.button() != null && d.button().hasLit()) {
                dev.servereer.machineconstruct.core.DisplayContent want = d.button().litWhen().equals(state) ? d.button().lit() : d.button().normal();
                if (d.baseContent() != want) { d.rebase(want); if (!initial) { d.consumeDirty(); tracker.refresh(d); } }
            }
            if (!(d.baseContent() instanceof dev.servereer.machineconstruct.core.TextContent tc)) continue;
            String src = tc.template();
            List<String> over = pd == null || d.partName() == null ? null : pd.lines(d.partName());
            if (paged && d.partName() != null) {
                List<String> pl = PanelData.pageLines(pages, page, d.partName());
                if (pl != null) over = pl;
                else if (d.partName().startsWith("sec_")) over = List.of("");   // a section the page doesn't use stays blank
            }
            if (over != null) src = t == null ? String.join("\n", over) : t.expandTextVars(String.join("\n", over));   // custom text never saw the loader's ${var} pass
            src = src.replace("{theme}", "<" + theme + ">").replace("{state}", state);
            if (paged) src = src.replace("{page}", String.valueOf(pageCount == 0 ? 0 : page + 1)).replace("{pages}", String.valueOf(pageCount));
            for (Map.Entry<String, String> sv : stateVars.entrySet()) src = src.replace("{" + sv.getKey() + "}", sv.getValue());
            String resolved = resolvePlaceholders(src);
            if (resolved.equals(tc.current()) && !initial) continue;   // nothing changed
            d.rebase(tc.withText(resolved));
            if (!initial) { d.consumeDirty(); tracker.refresh(d); }
        }
    }

    /** {@code %placeholders%} → values via PlaceholderAPI (global context, no player). Unchanged if PAPI is absent. */
    public String resolvePlaceholders(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        if (!papiLooked) {
            papiLooked = true;
            try {
                if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null)
                    papiSet = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                            .getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            } catch (Throwable ignored) { papiSet = null; }
        }
        if (papiSet == null) return s;
        try {
            // PAPI hands back legacy §-codes for expansions that emit them; keep MiniMessage intact by
            // converting only the § runs it may add.
            Object out = papiSet.invoke(null, (org.bukkit.OfflinePlayer) null, s);
            return out == null ? s : legacyToMini(String.valueOf(out));
        } catch (Throwable ex) {
            return s;
        }
    }

    /** Convert {@code §x} / {@code &#rrggbb} runs (what many expansions return) into MiniMessage tags. */
    private static String legacyToMini(String s) {
        if (s.indexOf('§') < 0 && s.indexOf("&#") < 0) return s;
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '§' || c == '&') && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '#' && i + 7 < s.length()) { sb.append("<#").append(s, i + 2, i + 8).append('>'); i += 7; continue; }
                if (c == '§' && n == 'x' && i + 13 < s.length()) {   // §x§r§r§g§g§b§b
                    StringBuilder hex = new StringBuilder();
                    for (int k = i + 3; k < i + 14 && k < s.length(); k += 2) hex.append(s.charAt(k));
                    sb.append("<#").append(hex).append('>'); i += 13; continue;
                }
                if (c == '§') {
                    String tag = switch (Character.toLowerCase(n)) {
                        case '0' -> "<black>"; case '1' -> "<dark_blue>"; case '2' -> "<dark_green>"; case '3' -> "<dark_aqua>";
                        case '4' -> "<dark_red>"; case '5' -> "<dark_purple>"; case '6' -> "<gold>"; case '7' -> "<gray>";
                        case '8' -> "<dark_gray>"; case '9' -> "<blue>"; case 'a' -> "<green>"; case 'b' -> "<aqua>";
                        case 'c' -> "<red>"; case 'd' -> "<light_purple>"; case 'e' -> "<yellow>"; case 'f' -> "<white>";
                        case 'l' -> "<bold>"; case 'o' -> "<italic>"; case 'n' -> "<underlined>"; case 'm' -> "<strikethrough>";
                        case 'k' -> "<obfuscated>"; case 'r' -> "<reset>"; default -> null;
                    };
                    if (tag != null) { sb.append(tag); i++; continue; }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private void tickAnimations() {
        double clock = (System.currentTimeMillis() - startMillis) / 1000.0;
        for (Machine m : byAnchor.values()) {
            RenderedModel r = m.rendered();
            if (r == null || !r.animated() || faulted.contains(m.id())) continue;
            if (!hasViewerNear(m)) continue;   // no one looking → don't recompute or send anything
            try {
                double tierLevel = 0.0;
                MachineType at = registry == null ? null : registry.getMachineType(m.typeId());
                if (at != null && at.tiers() != null && at.tiers().max() > 1) {
                    tierLevel = (m.tier() - 1) / (double) (at.tiers().max() - 1);
                }
                DriverContext ctx = DriverContext.of(
                        clock + m.phase(), tickDt, m.state().name(), m.progress01(), m.level(),
                        m.tier(), tierLevel, firstNonEmpty(m.inputs()), firstNonEmpty(m.outputs()));
                r.recompute(ctx);
                for (PacketDisplay d : r.leaves()) if (d.consumeDirty()) tracker.refresh(d);   // only moved/changed parts
                if (r.hasFx()) {
                    fxBuf.clear();
                    r.collectFx(ctx, fxBuf);
                    Location origin = m.anchor();
                    for (FxRequest req : fxBuf) FxPlayer.play(origin, req);
                }
            } catch (Throwable t) {
                fault(m, "animation", t);   // quarantine one bad machine; never break the others
            }
        }
    }

    // --- recipe processing (P5d) -------------------------------------------

    private void tickProcessing() {
        if (registry == null) return;
        List<Machine> orphaned = null;
        for (Machine m : byAnchor.values()) {
            if (faulted.contains(m.id())) continue;
            try {
                MachineType type = registry.getMachineType(m.typeId());
                if (type == null) continue;
                // Anchor integrity (all machine types). If the block backing this machine has gone
                // missing while its chunk is loaded, something removed it WITHOUT firing the
                // BlockBreakEvent our teardown listens for — EcoEnchants Dynamite and other AoE
                // break enchants bypass it, as do TNT, WorldEdit and /setblock. Left unhandled the
                // machine leaks: a ghost in byAnchor with no block and no drop, so the owner just
                // loses it (tier + contents). Recover it instead — same lossless state-carrying
                // placer a normal break produces. Debounced one pass so a transient same-tick swap
                // (another plugin briefly changing the block) can't false-trigger a teardown.
                Location a = m.anchor();
                World w = a.getWorld();
                if (w == null || !w.isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)) continue;
                if (a.getBlock().getType() != type.anchorMaterial()) {   // anchor gone/replaced
                    if (anchorMiss.merge(m.id(), 1, Integer::sum) >= 2)
                        (orphaned == null ? (orphaned = new ArrayList<>()) : orphaned).add(m);
                    continue;   // never run behaviour on a machine whose anchor is gone
                }
                anchorMiss.remove(m.id());
                if (type.isGrinder()) { sweepGrinder(m, type); continue; }
                if (type.isQuarry()) { sweepQuarry(m, type); continue; }
                if (type.isTradingHall()) { sweepTradingHall(m, type); continue; }
                if (type.isFactory()) { sweepFactoryDistrict(m, type); continue; }
                if (type.isChunkCollector()) { sweepChunkCollector(m, type); continue; }
                if (type.isJukebox()) { sweepJukebox(m, type); continue; }
                if (type.recipes().isEmpty()) continue;
                int cap = capacityFor(type, m.tier());
                m.setLevel(cap <= 0 ? 0.0 : Math.min(1.0, storedCount(m.outputs()) / (double) cap));
                process(m, type);
            } catch (Throwable t) {
                fault(m, "processing", t);
            }
        }
        // Teardown orphaned machines AFTER iterating (recovery mutates byAnchor).
        if (orphaned != null) for (Machine m : orphaned) recoverOrphanedAnchor(m);
    }

    /**
     * The machine's anchor block was removed without our {@link #onBreak} teardown ever firing
     * (AoE break enchant such as EcoEnchants Dynamite, TNT, WorldEdit, /setblock…). Convert it to
     * its state-carrying placer at the anchor spot so the owner recovers the whole machine — tier,
     * inventory and behaviour state — exactly as a normal break would, then clear it from the live
     * map and the flatfile (barrier anchors) and close any open menus/displays.
     */
    private void recoverOrphanedAnchor(Machine m) {
        String k = key(m.anchor());
        if (byAnchor.get(k) != m) { anchorMiss.remove(m.id()); return; }   // already gone/replaced meanwhile
        anchorMiss.remove(m.id());
        backupSnapshot(m);       // capture data before recovering an orphaned (block-destroyed) machine
        World w = m.anchor().getWorld();
        Location drop = m.anchor().clone().add(0.5, 0.5, 0.5);
        ItemStack placer = teardownToPlacer(m, m.typeId(), w, drop);
        byAnchor.remove(k);
        externalStore.remove(k);
        externalStore.flush();
        // Respect ownership: a normal break is owner-gated, so hand the recovered machine to its
        // (online) owner rather than dropping it where a bystander — e.g. whoever set off the
        // dynamite — could grab it. Fall back to a ground drop at the anchor only with no owner online.
        Player owner = m.owner() == null ? null : plugin.getServer().getPlayer(m.owner());
        boolean returned = owner != null && owner.isOnline();
        if (returned) {
            for (ItemStack overflow : owner.getInventory().addItem(placer).values())
                owner.getWorld().dropItemNaturally(owner.getLocation(), overflow);
            owner.sendMessage(msg("§eYour §f" + m.typeId() + "§e machine was knocked loose by an area/blast break — returned to your inventory."));
        } else if (w != null) {
            w.dropItemNaturally(drop, placer);
        }
        plugin.getLogger().info("[MachineConstruct] recovered orphaned '" + m.typeId() + "' @ " + k
                + " — anchor destroyed without a break event (AoE enchant/TNT/worldedit?); "
                + (returned ? "returned to owner " + owner.getName() : "dropped a state-carrying placer") + ".");
    }

    /** Quarantine a machine after a runtime error — logged once, skipped until /mc reload. */
    private void fault(Machine m, String where, Throwable t) {
        if (faulted.add(m.id())) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "[MachineConstruct] machine '" + m.typeId() + "' @ " + key(m.anchor())
                            + " disabled after a " + where + " error (fix it, then /mc reload):", t);
        }
    }

    /** Run one processing pass against the machine's sealed in-memory I/O arrays. */
    private void process(Machine m, MachineType type) {
        ItemStack[] in = m.inputs();
        ItemStack[] out = m.outputs();
        switch (m.state()) {
            case IDLE -> {
                for (Recipe r : type.recipes()) {
                    if (canRun(m, type, r)) {       // inputs (or none) + room + capacity + fuel
                        m.setActive(r);
                        m.setProgress(0);
                        m.setState(MachineState.WORKING);
                        menus.refresh(m);
                        break;
                    }
                }
            }
            case WORKING -> {
                Recipe r = m.active();
                if (r == null || !hasInputs(in, r.inputs())) {   // inputs pulled mid-craft
                    m.setActive(null);
                    m.setState(MachineState.IDLE);
                    menus.refresh(m);
                    return;
                }
                if (type.requiresFuel() && !burnTick(m, type)) {   // out of fuel → pause
                    m.setState(MachineState.NO_FUEL);
                    menus.refresh(m);
                    return;
                }
                m.addProgress(processTicks);
                if (m.progress() >= timeFor(type, r, m.tier())) produce(m, type, r, in, out);
                else menus.refresh(m);   // animate progress + burn bars
            }
            case NO_FUEL -> {
                Recipe r = m.active();
                if (r == null) { m.setState(MachineState.IDLE); menus.refresh(m); return; }
                if (hasFuelAvailable(m, type)) { m.setState(MachineState.WORKING); menus.refresh(m); }   // fuel returned → resume
            }
            case BLOCKED -> {
                Recipe r = m.active();
                if (r == null) { m.setState(MachineState.IDLE); menus.refresh(m); return; }
                produce(m, type, r, in, out);
            }
        }
    }

    /** Can this recipe run right now? (inputs present-or-none, output room, under capacity, fuelled.) */
    private boolean canRun(Machine m, MachineType type, Recipe r) {
        return hasInputs(m.inputs(), r.inputs())
                && fits(m.outputs(), outputsFor(type, r, m.tier()))
                && !atCapacity(m, type)
                && (!type.requiresFuel() || hasFuelAvailable(m, type));
    }

    /** True once the output store has reached the (tier-scaled) capacity (generators only; 0 = no cap). */
    private boolean atCapacity(Machine m, MachineType type) {
        int cap = capacityFor(type, m.tier());
        if (cap <= 0) return false;
        return storedCount(m.outputs()) >= cap;
    }

    // --- tier scaling (P13) -------------------------------------------------

    /** Recipe time divided by the tier's speed multiplier (≥ 1 tick). */
    private int timeFor(MachineType type, Recipe r, int tier) {
        double sp = (type.tiers() == null) ? 1.0 : type.tiers().speed(tier);
        return (int) Math.max(1, Math.round(r.timeTicks() / Math.max(0.0001, sp)));
    }

    /** Recipe outputs scaled by the tier's batch multiplier. */
    private List<ItemStack> outputsFor(MachineType type, Recipe r, int tier) {
        int batch = (type.tiers() == null) ? 1 : type.tiers().batch(tier);
        if (batch <= 1) return r.outputs();
        List<ItemStack> scaled = new java.util.ArrayList<>(r.outputs().size());
        for (ItemStack o : r.outputs()) {
            ItemStack c = o.clone();
            c.setAmount(o.getAmount() * batch);
            scaled.add(c);
        }
        return scaled;
    }

    /** Capacity scaled by the tier's capacity multiplier (0 = unlimited). */
    private int capacityFor(MachineType type, int tier) {
        int base = type.capacity();
        if (base <= 0) return 0;
        double mult = (type.tiers() == null) ? 1.0 : type.tiers().capacity(tier);
        return (int) Math.max(1, Math.round(base * mult));
    }

    /** Upgrade-button click: consume the required item from the player's inventory and advance a tier. */
    private void upgradeMachine(Machine m, Player p) {
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null) return;
        Tiers t = type.tiers();
        if (t == null) return;
        if (m.tier() >= t.max()) { p.sendMessage(msg("§eThis machine is already at the max tier.")); return; }

        ItemStack req = t.upgradeItem();
        Material mat = (req == null) ? null : req.getType();
        int need = (req == null) ? 1 : Math.max(1, req.getAmount());
        if (mat == null) return;

        int have = 0;
        for (ItemStack s : p.getInventory().getContents())
            if (s != null && s.getType() == mat) have += s.getAmount();
        if (have < need) {
            p.sendMessage(msg("§cNeed §f" + need + "× " + pretty(mat) + " §cto upgrade §7(you have " + have + ")."));
            return;
        }

        p.getInventory().removeItem(new ItemStack(mat, need));
        m.setTier(m.tier() + 1);
        persistStore(m);
        renderMachine(m);                                   // re-render with the tier's (possibly upgraded) model
        menus.rebuild(m, type, type.guiFor(m.tier()));      // reopen any window with the tier's GUI
        try {
            Location a = m.anchor().add(0.5, 1.0, 0.5);
            a.getWorld().playSound(a, org.bukkit.Sound.BLOCK_ANVIL_USE, 0.7f, 1.5f);
            a.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, a, 15, 0.4, 0.6, 0.4, 0.0);
        } catch (Throwable ignored) { }
        p.sendMessage(msg("§a⏫ Upgraded to Tier " + m.tier() + "."));
    }

    private static String pretty(Material mat) {
        String[] parts = mat.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) if (!s.isEmpty()) sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        return sb.toString().trim();
    }

    /** Sell-all button (recipe machines): sell every output-slot item via the type's prices + Vault. */
    private void sellOutputs(Machine m, Player p) {
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        PriceService prices = (type == null) ? null : type.pricing();
        if (prices == null) { p.sendMessage(msg("§cThis machine has no prices configured.")); return; }
        EconomyBridge econ = economy();
        if (!econ.available()) { p.sendMessage(msg("§cNo economy available to sell.")); return; }
        ItemStack[] out = m.outputs();
        double total = 0;
        long sold = 0;
        for (int i = 0; i < out.length; i++) {
            ItemStack s = out[i];
            if (s == null || s.getType().isAir()) continue;
            double each = prices.price(s);
            if (each <= 0) continue;                  // unsellable → leave it in the slot
            total += each * s.getAmount();
            sold += s.getAmount();
            out[i] = null;
        }
        if (total > 0) {
            econ.deposit(p, total);
            persistStore(m);
            menus.refresh(m);
            p.sendMessage(msg("§a$ Sold §f" + sold + "§a items for §f" + fmt(total) + "§a."));
        } else {
            p.sendMessage(msg("§7Nothing sellable in the output."));
        }
    }

    // --- grinder (Dimensional Grinder) -------------------------------------

    /** Accrue a grinder's spawners on the processing clock; persist only when something changed. */
    private void sweepGrinder(Machine m, MachineType type) {
        if (!m.hasGrinder()) m.setGrinder(new GrinderData());
        Location a = m.anchor();
        World w = a.getWorld();
        if (w == null || !w.isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)) return;
        if (a.getBlock().getType() != type.anchorMaterial()) return;
        long now = System.currentTimeMillis();
        boolean changed = GrinderManager.accrue(m.grinder(), type.grinder(), type.loot(), m.tier(), now);
        GrinderData d = m.grinder();
        if (!d.links().isEmpty() || d.autoSellEnabled()) {           // pool up for chest routing / auto-sell
            long[] got = GrinderManager.collectAll(d);
            if (got[0] > 0 || got[1] > 0) changed = true;
        }
        if (!d.links().isEmpty()) {                                  // input/output chest routing
            int reach = ioReach(m.tier());
            java.util.function.Predicate<Location> notAnchor = loc -> byAnchor.containsKey(key(loc));
            if (dev.servereer.machineconstruct.grinder.ChestRouter.routeOutputs(
                    d.links(), d.pool(), a, reach, notAnchor, now)) changed = true;
            // INPUT chests feed items straight into the sellable pool (claim-gated at link time → no runtime probe).
            if (dev.servereer.machineconstruct.grinder.ChestRouter.routeInputs(
                    d.links(), d.pool(), 0L, 0L, a, reach, notAnchor, null, now)) changed = true;
        }
        if (d.autoSellEnabled() && autoSell(d, type, m, now)) changed = true;
        if (changed) { persistStore(m); grinderMenus.refreshThrottled(m); }
    }


    /** Open the grinder's paginated menu (lazy accrual happens inside the menu). */
    private void openGrinder(Player p, Machine m, MachineType type) {
        if (!m.hasGrinder()) m.setGrinder(new GrinderData());
        grinderMenus.open(p, m);
    }

    /** The per-player placement cap for a machine type (grinder or quarry), or 0 = unlimited. */
    private int placementLimit(String type) {
        MachineType mt = registry == null ? null : registry.getMachineType(type);
        if (mt == null) return 0;
        if (mt.grinder() != null) return mt.grinder().maxPerPlayer();
        if (mt.quarry() != null) return mt.quarry().maxPerPlayer();
        if (mt.chunkCollector() != null) return mt.chunkCollector().maxPerPlayer();
        if (mt.jukebox() != null) return mt.jukebox().maxPerPlayer();
        return 0;
    }

    /**
     * Whether the player may place another machine of this type (grinder/quarry per-player cap).
     * Applies to everyone — ops included (no permission bypass, since ops resolve every node to
     * true and would silently dodge the limit).
     */
    private boolean withinPlacementLimit(Player player, String type) {
        int limit = placementLimit(type);
        if (limit <= 0) return true;                                  // 0 = unlimited
        int owned = 0;
        for (Machine m : byAnchor.values())
            if (type.equals(m.typeId()) && player.getUniqueId().equals(m.owner())) owned++;
        return owned < limit;
    }

    /** Drop a grinder's collected pool as item stacks on teardown. */
    private void dropPool(World w, Location drop, GrinderData d) {
        for (java.util.Map.Entry<ItemStack, Long> e : d.pool().entrySet()) {
            long n = e.getValue();
            ItemStack tmpl = e.getKey();
            int max = Math.max(1, tmpl.getMaxStackSize());
            while (n > 0) {
                int amt = (int) Math.min(n, max);
                ItemStack stack = tmpl.clone();
                stack.setAmount(amt);
                w.dropItemNaturally(drop, stack);
                n -= amt;
            }
        }
    }

    /** Admin/test: install (or stack) a spawner into the grinder the player is looking at. */
    public boolean installSpawner(Player p, String type, long stack) {
        Block b = p.getTargetBlockExact(6);
        if (b == null) { p.sendMessage(msg("§cLook at a grinder block.")); return true; }
        Machine m = byAnchor.get(key(b.getLocation()));
        MachineType mt = (m == null || registry == null) ? null : registry.getMachineType(m.typeId());
        if (mt == null || !mt.isGrinder()) { p.sendMessage(smsg(anyGrinderType(), "not_a_grinder", "§cThat isn't a Dimensional Grinder.")); return true; }
        if (!m.hasGrinder()) m.setGrinder(new GrinderData());
        GrinderData d = m.grinder();
        GrinderSpec spec = mt.grinder();
        LootTable loot = mt.loot();
        String t = type.toUpperCase(java.util.Locale.ROOT);
        if (loot == null || !loot.has(t)) { p.sendMessage(msg("§cUnknown mob type: §f" + type)); return true; }
        long want = Math.max(1, stack);
        long added = d.install(spec, m.tier(), t, want, System.currentTimeMillis());
        if (added <= 0) {
            p.sendMessage(smsg(mt, "full_admin", "§cGrinder is full — Tier " + m.tier() + " has "
                    + spec.slots(m.tier()) + " slot(s) of " + spec.stackCap(m.tier()) + ". Upgrade it.",
                    dev.servereer.machineconstruct.gui.MenuSkin.vars("tier", m.tier(), "slots", spec.slots(m.tier()), "stack", spec.stackCap(m.tier()))));
            return true;
        }
        d.logEvent("+" + added + " " + LootTable.pretty(t) + " spawner (" + p.getName() + ")");
        persistStore(m);
        grinderMenus.refresh(m);
        p.sendMessage(smsg(mt, "installed_admin", "§aInstalled §f" + added + "× " + LootTable.pretty(t) + "§a spawner."
                + (added < want ? " §7(" + (want - added) + " didn't fit)" : ""),
                dev.servereer.machineconstruct.gui.MenuSkin.vars("count", added, "mob", LootTable.pretty(t), "left", want - added)));
        return true;
    }

    // --- GrinderActions (called by the grinder menu) ------------------------

    @Override
    public void accrue(Machine m) {
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null) return;
        if (type.isGrinder() && m.hasGrinder())
            GrinderManager.accrue(m.grinder(), type.grinder(), type.loot(), m.tier(), System.currentTimeMillis());
        if (type.isQuarry() && m.hasQuarry())
            QuarryManager.accrue(m.quarry(), type.quarry(), m.tier(), m.anchor(), System.currentTimeMillis());
    }

    // --- QuarryActions (called by the quarry menu) --------------------------

    @Override
    public void toggleMode(Machine m, Player p) {
        if (!m.hasQuarry()) return;
        QuarryData d = m.quarry();
        QuarryData.Mode next = switch (d.mode()) {
            case MINING -> QuarryData.Mode.FISHING;
            case FISHING -> QuarryData.Mode.BOTH;
            case BOTH -> QuarryData.Mode.MINING;
        };
        d.setMode(next);
        persistStore(m);
        p.sendMessage(msg("§dQuarry mode → §f" + next));
    }

    @Override
    public long withdraw(Machine m, Player p, ItemStack template, long amount) {
        if (!m.hasQuarry() || template == null) return 0;
        QuarryData d = m.quarry();
        long have = d.vault().getOrDefault(template, 0L);
        long take = Math.min(Math.max(0, amount), have);
        int max = Math.max(1, template.getMaxStackSize());
        long moved = 0;
        boolean full = false;
        while (take > 0 && !full) {
            int amt = (int) Math.min(take, max);
            ItemStack give = template.clone();
            give.setAmount(amt);
            java.util.Map<Integer, ItemStack> left = p.getInventory().addItem(give);
            int leftover = 0;
            for (ItemStack ls : left.values()) leftover += ls.getAmount();
            int placed = amt - leftover;
            moved += placed;
            take -= placed;
            if (leftover > 0) full = true;
        }
        long now = have - moved;
        if (now <= 0) d.vault().remove(template); else d.vault().put(template, now);
        if (moved > 0) persistStore(m);
        return moved;
    }

    @Override
    public long collectAll(Machine m, Player p) {
        if (!m.hasQuarry()) return 0;
        long total = 0;
        for (ItemStack tmpl : new ArrayList<>(m.quarry().vault().keySet()))
            total += withdraw(m, p, tmpl, Long.MAX_VALUE);
        if (total > 0) m.quarry().logEvent("Collected " + total + " items (" + p.getName() + ")");
        return total;
    }

    private double sellQuarryVault(Machine m, Player p) {
        if (!m.hasQuarry()) return 0;
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        PriceService prices = type == null ? null : type.pricing();
        EconomyBridge econ = economy();
        if (prices == null || !econ.available()) return -1;
        QuarryData d = m.quarry();
        double mult = QuarrySkill.sellPayoutMult(d);            // Market Link bonus (applied to every item)
        double base = 0;
        long sold = 0;
        Map<Material, double[]> breakdown = new java.util.HashMap<>();
        for (ItemStack tmpl : new ArrayList<>(d.vault().keySet())) {
            long n = d.vault().getOrDefault(tmpl, 0L);
            double each = prices.price(tmpl);
            if (each <= 0) continue;                  // unsellable → keep it
            base += each * n;
            sold += n;
            double[] a = breakdown.computeIfAbsent(tmpl.getType(), k -> new double[2]);
            a[0] += n; a[1] += each * n * mult;       // money credited includes the Market-Link multiplier
            d.vault().remove(tmpl);
        }
        double payout = base * mult;
        if (payout > 0) {
            econ.deposit(p, payout);
            d.logEvent("Sold " + sold + " items for " + fmt(payout) + " (" + p.getName() + ")");
            fireMachineItemSell(p.getUniqueId(), p.getName(), false, breakdown);
            persistStore(m);
        }
        return payout;
    }

    @Override
    public QuarryActions.BuyResult buySkill(Machine m, Player p, QuarrySkill skill) {
        if (!m.hasQuarry() || skill == null) return QuarryActions.BuyResult.ERROR;
        QuarryData d = m.quarry();
        int cur = d.skill(skill.name());
        if (cur >= skill.maxLevel) return QuarryActions.BuyResult.MAXED;
        int next = cur + 1;
        long rp = skill.rpCost(next);
        double money = skill.moneyCost(next);
        int stars = skill.starCost(next);

        if (d.riftPoints() < rp) return QuarryActions.BuyResult.NEED_RIFT_POINTS;
        EconomyBridge econ = economy();
        if (money > 0) {
            if (!econ.available()) return QuarryActions.BuyResult.NO_ECONOMY;
            if (!econ.has(p, money)) return QuarryActions.BuyResult.NEED_MONEY;
        }
        if (stars > 0 && countItems(p, Material.NETHER_STAR) < stars) return QuarryActions.BuyResult.NEED_STARS;

        // charge (rift points last so a failed money withdraw doesn't burn them)
        if (money > 0 && !econ.withdraw(p, money)) return QuarryActions.BuyResult.NEED_MONEY;
        if (stars > 0) removeItems(p, Material.NETHER_STAR, stars);
        d.addRiftPoints(-rp);
        d.skills().put(skill.name(), next);
        d.logEvent("Bought " + skill.display + " L" + next + " (" + p.getName() + ")");
        persistStore(m);
        quarryMenus.refresh(m);
        return QuarryActions.BuyResult.OK;
    }

    @Override
    public void cycleDimension(Machine m, Player p) {
        if (!m.hasQuarry()) return;
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null || type.quarry() == null) return;
        QuarryData d = m.quarry();
        int max = Math.min(type.quarry().dimensionCount() - 1, QuarrySkill.maxDimensionIndex(d));
        if (max <= 0) { p.sendMessage(msg("§dUnlock deeper dimensions with §fRift Drill§d.")); return; }
        int next = d.dimension() + 1;
        if (next > max) next = 0;
        d.setDimension(next);
        persistStore(m);
        quarryMenus.refresh(m);
        p.sendMessage(msg("§dDimension → §f" + type.quarry().dimensionName(next)));
    }

    @Override
    public void toggleLava(Machine m, Player p) {
        if (!m.hasQuarry()) return;
        QuarryData d = m.quarry();
        long now = System.currentTimeMillis();
        d.setLastLava(now);                 // reset clocks so toggling doesn't dump a backlog
        d.setLastHeat(now);
        d.setLavaOn(!d.lavaOn());
        persistStore(m);
        quarryMenus.refresh(m);
        p.sendMessage(msg("§dLava generation → " + (d.lavaOn() ? "§aON" : "§cOFF")));
    }

    @Override
    public int collectLava(Machine m, Player p) {
        if (!m.hasQuarry()) return 0;
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null || type.quarry() == null) return 0;
        QuarryData d = m.quarry();
        long perBucket = type.quarry().unitsPerBucket();
        int emptyBuckets = countItems(p, Material.BUCKET);
        long byTank = d.lava() / perBucket;
        int fill = (int) Math.min(emptyBuckets, Math.min(byTank, Integer.MAX_VALUE));
        if (fill <= 0) return 0;
        removeItems(p, Material.BUCKET, fill);
        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(Material.LAVA_BUCKET, fill));
        int leftover = 0;
        for (ItemStack ls : left.values()) leftover += ls.getAmount();
        int given = fill - leftover;
        if (leftover > 0) {                 // inventory filled mid-way → hand back the unused empties
            p.getInventory().addItem(new ItemStack(Material.BUCKET, leftover));
            for (ItemStack ls : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), ls);
        }
        d.setLava(d.lava() - (long) given * perBucket);
        if (given > 0) { d.logEvent("Filled " + given + " lava buckets (" + p.getName() + ")"); persistStore(m); }
        return given;
    }

    @Override
    public double sellLava(Machine m, Player p) {
        if (!m.hasQuarry()) return 0;
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null || type.quarry() == null) return 0;
        EconomyBridge econ = economy();
        if (!econ.available()) return -1;
        QuarryData d = m.quarry();
        long units = d.lava();
        double money = units * type.quarry().lavaSellPrice();
        if (money <= 0) return 0;
        econ.deposit(p, money);
        d.setLava(0);
        d.logEvent("Sold " + units + " lava for " + fmt(money) + " (" + p.getName() + ")");
        persistStore(m);
        return money;
    }

    @Override
    public void claimContract(Machine m, Player p) {
        if (!m.hasQuarry()) return;
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null || type.quarry() == null) return;
        QuarryData d = m.quarry();
        QuarryContracts.ensure(d, type.quarry());
        if (!QuarryContracts.ready(d)) {
            p.sendMessage(msg("§dContract: §f" + QuarryContracts.progress(d) + "§7/§f" + d.contractTarget() + " §7— keep going."));
            return;
        }
        long rp = QuarryContracts.rpReward(d, type.quarry());
        double money = QuarryContracts.moneyReward(d, type.quarry());
        if (d.contractType() == 1 && d.contractItem() != null) {          // bounty → deliver (consume) the items
            long have = d.vault().getOrDefault(d.contractItem(), 0L);
            long left = have - d.contractTarget();
            if (left <= 0) d.vault().remove(d.contractItem()); else d.vault().put(d.contractItem(), left);
        }
        d.addRiftPoints(rp);
        EconomyBridge econ = economy();
        if (money > 0 && econ.available()) econ.deposit(p, money);
        d.setContractsDone(d.contractsDone() + 1);
        d.logEvent("Contract complete (+" + rp + " RP)");
        QuarryContracts.roll(d, type.quarry());
        persistStore(m);
        quarryMenus.refresh(m);
        p.sendMessage(msg("§aContract complete! §f+" + rp + " RP" + (money > 0 && econ.available() ? ", $" + fmt(money) : "")));
    }

    @Override
    public void rerollContract(Machine m, Player p) {
        if (!m.hasQuarry()) return;
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null || type.quarry() == null) return;
        QuarryContracts.roll(m.quarry(), type.quarry());
        persistStore(m);
        quarryMenus.refresh(m);
        p.sendMessage(msg("§dContract rerolled."));
    }

    @Override
    public void prestige(Machine m, Player p) {
        if (!m.hasQuarry()) return;
        QuarryData d = m.quarry();
        if (d.skill(QuarrySkill.ASCENDANCE.name()) < 1) {
            p.sendMessage(msg("§cBuy §fAscendance §cin the Rift branch first."));
            return;
        }
        d.setPrestige(d.prestige() + 1);
        d.skills().clear();
        d.setRiftPoints(0);
        MachineType ptype = registry == null ? null : registry.getMachineType(m.typeId());
        if (ptype != null && ptype.quarry() != null) QuarryContracts.roll(d, ptype.quarry());   // dims relocked → fresh, reachable contract
        d.logEvent("Ascended to prestige " + d.prestige());
        persistStore(m);
        quarryMenus.refresh(m);
        p.sendMessage(msg("§5✦ Ascended! Prestige §f" + d.prestige() + " §5— +" + (int) (d.prestige() * 10) + "% permanent yield."));
    }

    private static int countItems(Player p, Material mat) {
        int c = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == mat) c += it.getAmount();
        return c;
    }

    private static void removeItems(Player p, Material mat, int amount) {
        int left = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            left -= take;
            if (it.getAmount() <= 0) contents[i] = null;
        }
        p.getInventory().setContents(contents);
    }

    /** Accrue a quarry on the processing clock (Q2 logic); persist + refresh on change. */
    private void sweepQuarry(Machine m, MachineType type) {
        if (!m.hasQuarry()) m.setQuarry(new QuarryData());
        Location a = m.anchor();
        World w = a.getWorld();
        if (w == null || !w.isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)) return;
        if (a.getBlock().getType() != type.anchorMaterial()) return;
        int change = QuarryManager.accrue(m.quarry(), type.quarry(), m.tier(), a, System.currentTimeMillis());
        if (change == QuarryManager.CH_NONE) return;
        if ((change & QuarryManager.CH_MEANINGFUL) != 0) {   // deposits/lava/surge → apply rules + persist
            applyVaultRules(m, type);
            persistStore(m);
        }
        quarryMenus.refreshThrottled(m);   // sweep repaint, throttled so it never stomps an active clicker
    }

    /**
     * Trading-hall processing tick (T1: timed restock). When the restock interval elapses,
     * reset every trader's trade uses so stock returns (vanilla-style passive restock).
     * Auto-trade (T7) hooks in here later.
     */
    private void sweepTradingHall(Machine m, MachineType type) {
        if (!m.hasTradingHall()) m.setTradingHall(new dev.servereer.machineconstruct.tradinghall.TradingHallData());
        dev.servereer.machineconstruct.tradinghall.TradingHallSpec spec = type.tradingHall();
        dev.servereer.machineconstruct.tradinghall.TradingHallData d = m.tradingHall();
        d.ensureSlots(spec.slotsForTier(d.tier()));
        long cap = spec.vaultCapForTier(d.tier());
        boolean changed = false;

        // Timed restock — refill every trade's uses.
        long now = System.currentTimeMillis();
        if (now - d.lastRestock() >= Math.max(1, spec.restockSeconds()) * 1000L) {
            d.setLastRestock(now);
            for (var s : d.slots()) {
                if (s.trader == null) continue;
                for (var o : s.trader.offers) if (o.uses != 0) { o.uses = 0; changed = true; }
            }
        }

        // Auto-trade (T7) — per-trade, vault-fed: each auto-enabled offer converts in the vault.
        for (var s : d.slots()) {
            if (s.trader == null) continue;
            var prof = spec.profession(s.trader.professionId);
            if (prof == null) continue;
            if (dev.servereer.machineconstruct.tradinghall.TradingHallManager.autoTradeSweep(prof, d, s.trader, cap))
                changed = true;
        }

        if (changed) persistStore(m);
    }

    // --- Factory District (ADR 0013) ----------------------------------------

    /** Factory-district processing tick: accrue offline production for every built farm. */
    private void sweepFactoryDistrict(Machine m, MachineType type) {
        if (!m.hasFactory()) m.setFactory(new dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData());
        var spec = type.factory();
        var d = m.factory();
        d.ensureSlots(spec.slotsForTier(d.tier()));
        long cap = spec.vaultCapForTier(d.tier());
        int usable = spec.slotsForTier(d.tier());
        long now = System.currentTimeMillis();
        boolean changed = false;
        java.util.List<String> commands = new ArrayList<>();
        for (var s : d.slots()) {
            if (!s.isBuilt()) continue;
            var farm = spec.farm(s.factoryId);
            if (farm == null) continue;
            // Understaffed / prereq-missing machines idle: hold the clock so they bank no offline time.
            if (!dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.operational(spec, d, s, farm)) {
                s.lastProduce = now; s.lastGenerate = now;
                continue;
            }
            var size = spec.size(s.sizeId);
            if (dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.accrue(spec, farm, size, d, s, now, cap, commands))
                changed = true;
            if (farm.gen != null
                    && dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.generate(spec, farm, d, s, now, usable) != null)
                changed = true;
            if (farm.breeds()
                    && dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.breed(spec, farm, d, s, now) != null)
                changed = true;
        }
        // Population mode: villagers in the roster move into houses and become labor (ADR 0022).
        if (dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.houseVillagers(spec, d) != 0)
            changed = true;
        // I/O chests: pull from INPUT chests into the vault, then push the vault into OUTPUT chests
        // (each honours its filter + flow rate; machine anchors are never used as links).
        if (!d.links().isEmpty()) {
            Location a = m.anchor();
            if (a.getWorld() != null && a.getWorld().isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)) {
                int reach = ioReach(m.tier());
                java.util.function.Predicate<Location> notAnchor = loc -> byAnchor.containsKey(key(loc));
                if (dev.servereer.machineconstruct.grinder.ChestRouter.routeInputs(
                        d.links(), d.vault(), cap, d.vaultMass(), a, reach, notAnchor, null, now)) changed = true;
                if (dev.servereer.machineconstruct.grinder.ChestRouter.routeOutputs(
                        d.links(), d.vault(), a, reach, notAnchor, now)) changed = true;
            }
        }
        // Auto-sell the vault at the configured per-item rates (vault → economy, hourly payout).
        if (d.autoSellEnabled() && runAutoSell(d, factoryPrices(type), m, now)) changed = true;
        if (!commands.isEmpty()) runFactoryCommands(m, commands);
        if (changed) persistStore(m);
    }

    // --- Chunk Collector ----------------------------------------------------

    /** Chunk-collector processing tick: vacuum dropped items in the tier's chunk ring, route + auto-sell. */
    private void sweepChunkCollector(Machine m, MachineType type) {
        if (!m.hasChunkCollector()) m.setChunkCollector(new dev.servereer.machineconstruct.collector.ChunkCollectorData());
        Location a = m.anchor();
        World w = a.getWorld();
        if (w == null || !w.isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)) return;
        if (a.getBlock().getType() != type.anchorMaterial()) return;
        dev.servereer.machineconstruct.collector.ChunkCollectorData d = m.chunkCollector();
        dev.servereer.machineconstruct.collector.ChunkCollectorSpec spec = type.chunkCollector();
        long now = System.currentTimeMillis();
        long cap = spec.capacity(m.tier());
        boolean changed = false;

        // Vacuum only on the configured interval, and skip the whole chunk-entity scan when the vault is full.
        if (now - d.lastVacuum() >= spec.intervalMillis()) {
            d.setLastVacuum(now);
            if (cap <= 0 || d.vaultMass() < cap) {
                java.util.function.Predicate<Location> allowed = collectFilter(m, spec);
                if (allowed != null) {   // defensive: collectFilter is now non-null (offline owners still collect)
                    int got = dev.servereer.machineconstruct.collector.ChunkCollectorManager.vacuum(d, spec, m.tier(), a, cap, allowed);
                    if (got > 0) { d.addCollected(got); d.logEvent("Vacuumed " + got + " items"); changed = true; }
                }
            }
        }
        if (!d.links().isEmpty()) {
            int reach = ioReach(m.tier());
            java.util.function.Predicate<Location> notAnchor = loc -> byAnchor.containsKey(key(loc));
            if (dev.servereer.machineconstruct.grinder.ChestRouter.routeOutputs(
                    d.links(), d.vault(), a, reach, notAnchor, now)) changed = true;
            // INPUT chests feed items into the vault (cap-bounded; claim-gated at link time).
            if (dev.servereer.machineconstruct.grinder.ChestRouter.routeInputs(
                    d.links(), d.vault(), cap, d.vaultMass(), a, reach, notAnchor, null, now)) changed = true;
        }
        if (d.autoSellEnabled() && runAutoSell(d, type.pricing(), m, now)) changed = true;
        if (changed) {
            m.setLevel(cap <= 0 ? 0.0 : Math.min(1.0, d.vaultMass() / (double) cap));
            persistStore(m);
            collectorMenus.refreshThrottled(m);
        }
    }

    private boolean isCollectorMachine(Machine m) {
        MachineType t = registry == null ? null : registry.getMachineType(m.typeId());
        return t != null && t.isChunkCollector();
    }

    /**
     * The grief gate for vacuuming. A per-block-cached predicate deciding whether the owner may build at a
     * drop's location, so the collector never sucks items out of areas the owner isn't trusted in. When the
     * owner is <b>online</b> we fire a precise {@link BlockPlaceEvent} probe; when they're <b>offline</b> we
     * fall back to GriefPrevention's offline (owner-UUID) permission check so the machine keeps running for
     * teammates instead of pausing. Never returns null (unclaimed/unknown → allow, matching open-world build).
     */
    private java.util.function.Predicate<Location> collectFilter(Machine m, dev.servereer.machineconstruct.collector.ChunkCollectorSpec spec) {
        if (!spec.respectClaims()) return loc -> true;
        UUID ownerId = m.owner();
        if (ownerId == null) return loc -> true;   // no recorded owner → nothing to gate on
        java.util.Map<Long, Boolean> cache = new java.util.HashMap<>();
        Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner != null) {   // owner online → precise build-probe
            return loc -> cache.computeIfAbsent(blockKey(loc), k -> canBuildAt(owner, loc));
        }
        // Owner OFFLINE — keep collecting (a teammate may be the only one online). We can't fire a per-player
        // build probe, so gate via GriefPrevention's offline UUID check. No GP, or no claim at a drop → allow,
        // matching the online "can build in open land" behaviour.
        if (plugin.getServer().getPluginManager().getPlugin("GriefPrevention") == null) return loc -> true;
        return loc -> cache.computeIfAbsent(blockKey(loc), k -> {
            Boolean trusted = griefBuildTrustOffline(ownerId, loc);
            return trusted == null || trusted;   // null = no claim / unknown → allow
        });
    }

    private static long blockKey(Location l) {
        return ((long) (l.getBlockX() & 0x3FFFFFF) << 38) | ((long) (l.getBlockY() & 0xFFF) << 26) | (l.getBlockZ() & 0x3FFFFFF);
    }

    // --- ChunkCollectorActions (called by the collector menu) ----------------

    @Override
    public void collectorVacuum(Machine m) {
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null || !type.isChunkCollector()) return;
        if (!m.hasChunkCollector()) m.setChunkCollector(new dev.servereer.machineconstruct.collector.ChunkCollectorData());
        var d = m.chunkCollector();
        long cap = type.chunkCollector().capacity(m.tier());
        java.util.function.Predicate<Location> allowed = collectFilter(m, type.chunkCollector());
        if (allowed == null) return;   // defensive: collectFilter is now non-null
        int got = dev.servereer.machineconstruct.collector.ChunkCollectorManager.vacuum(d, type.chunkCollector(), m.tier(), m.anchor(), cap, allowed);
        if (got > 0) { d.addCollected(got); d.setLastVacuum(System.currentTimeMillis()); }
    }

    @Override
    public long collectVault(Machine m, Player p) {
        if (!m.hasChunkCollector()) return 0;
        long total = vaultWithdrawAll(m.chunkCollector().vault(), p);   // SAME shared movement as the grinder pool
        if (total > 0) { m.chunkCollector().logEvent("Collected " + total + " items (" + p.getName() + ")"); persistStore(m); }
        return total;
    }

    @Override
    public long withdrawVaultItem(Machine m, Player p, ItemStack template, long amount) {
        if (!m.hasChunkCollector() || template == null) return 0;
        long moved = vaultWithdraw(m.chunkCollector().vault(), p, template, amount);   // SAME shared movement
        if (moved > 0) persistStore(m);
        return moved;
    }

    @Override
    public double sellVault(Machine m, Player p) {
        if (!m.hasChunkCollector()) return 0;
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        PriceService prices = type == null ? null : type.pricing();
        if (prices == null || !economy().available()) return -1;
        double[] r = sellVaultMap(m.chunkCollector().vault(), prices, p);   // SAME shared sell as the grinder pool
        if (r[0] > 0) { m.chunkCollector().logEvent("Sold " + (long) r[1] + " items for " + fmt(r[0]) + " (" + p.getName() + ")"); persistStore(m); }
        return r[0];
    }

    // --- Jukebox ------------------------------------------------------------

    private String jukeboxKey(Machine m) { return "jukebox:" + key(m.anchor()); }

    /** Build the play queue from the loaded playlist (if any), else the single track. */
    private java.util.List<String> jukeboxQueue(dev.servereer.machineconstruct.jukebox.JukeboxData d) {
        if (d.hasPlaylist() && playlistLibrary != null) {
            if (dev.servereer.machineconstruct.music.PlaylistLibrary.UNLISTED.equals(d.playlistName()))
                return playlistLibrary.unlisted(trackLibrary);
            java.util.List<String> pl = playlistLibrary.get(d.playlistName());
            if (pl != null && !pl.isEmpty()) return pl;
            return java.util.List.of();
        }
        return d.hasTrack() ? java.util.List.of(d.trackId()) : java.util.List.of();
    }

    /** Jukebox tick: keep the locational stream alive while playing (restart after restart/reload), drive the animation. */
    private void sweepJukebox(Machine m, MachineType type) {
        if (!m.hasJukebox()) m.setJukebox(new dev.servereer.machineconstruct.jukebox.JukeboxData());
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        Location a = m.anchor();
        World w = a.getWorld();
        if (w == null || !w.isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)) return;
        if (a.getBlock().getType() != type.anchorMaterial()) return;   // anchor gone/replaced
        String key = jukeboxKey(m);
        if (d.playing() && !d.paused() && d.hasContent()) {
            if (!musicPlayer.isActive(key)) startJukebox(m, type, key);
            m.setState(MachineState.WORKING);
        } else if (m.state() != MachineState.IDLE && !d.paused()) {
            m.setState(MachineState.IDLE);
        }
    }

    /** Decode + start (or restart) a jukebox's locational stream, de-duping concurrent starts. */
    private void startJukebox(Machine m, MachineType type, String key) {
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        if (d == null || !d.hasContent() || !musicPlayer.available()) return;
        java.util.List<String> queue = jukeboxQueue(d);
        if (queue.isEmpty()) { d.setPlaying(false); persistStore(m); return; }
        if (!jukeboxStarting.add(key)) return;   // a start is already in flight
        float dist = type.jukebox().distance(m.tier()) * (float) d.volume();
        boolean shuffle = d.hasPlaylist();   // playlists play shuffled; a single track doesn't
        musicPlayer.playLocational(key, queue, m.anchor(), dist, d.loop(), shuffle, d.speed(),
                () -> {   // whole queue finished on its own (non-loop)
                    d.setPlaying(false);
                    d.setPaused(false);
                    m.setState(MachineState.IDLE);
                    persistStore(m);
                    jukeboxMenus.refresh(m);
                },
                ok -> {
                    jukeboxStarting.remove(key);
                    if (!ok) { d.setPlaying(false); persistStore(m); }
                });
    }

    // --- JukeboxActions (called by the jukebox menu) ------------------------

    @Override
    public void jukeboxSetTrack(Machine m, String trackId) {
        if (!m.hasJukebox()) m.setJukebox(new dev.servereer.machineconstruct.jukebox.JukeboxData());
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        d.setTrackId(trackId);
        d.setHasDisc(false);   // admin-loaded, not a physical disc
        persistStore(m);
        if (d.playing()) jukeboxPlay(m);   // swap live
    }

    @Override
    public void jukeboxSetPlaylist(Machine m, String playlistName) {
        if (!m.hasJukebox()) m.setJukebox(new dev.servereer.machineconstruct.jukebox.JukeboxData());
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        d.setPlaylistName(playlistName);
        d.setHasDisc(false);
        persistStore(m);
        if (d.playing()) jukeboxPlay(m);
    }

    @Override
    public void jukeboxPlay(Machine m) {
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null || !type.isJukebox() || !m.hasJukebox()) return;
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        if (!d.hasContent()) return;
        d.setPlaying(true);
        d.setPaused(false);
        m.setState(MachineState.WORKING);
        String key = jukeboxKey(m);
        musicPlayer.stop(key);
        jukeboxStarting.remove(key);
        startJukebox(m, type, key);
        persistStore(m);
    }

    @Override
    public void jukeboxStop(Machine m) {
        if (!m.hasJukebox()) return;
        m.jukebox().setPlaying(false);
        m.jukebox().setPaused(false);
        m.setState(MachineState.IDLE);
        musicPlayer.stop(jukeboxKey(m));
        persistStore(m);
    }

    @Override
    public void jukeboxPause(Machine m) {
        if (!m.hasJukebox()) return;
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        if (!d.playing()) return;
        if (musicPlayer.pause(jukeboxKey(m))) {
            d.setPaused(true);
            m.setState(MachineState.IDLE);
            persistStore(m);
        }
    }

    @Override
    public void jukeboxResume(Machine m) {
        if (!m.hasJukebox()) return;
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        if (!d.playing()) return;
        String key = jukeboxKey(m);
        boolean ok = musicPlayer.isActive(key) ? musicPlayer.resume(key) : false;
        d.setPaused(false);
        m.setState(MachineState.WORKING);
        if (!ok) {   // session was lost (e.g. after restart) → start fresh
            MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
            if (type != null) startJukebox(m, type, key);
        }
        persistStore(m);
    }

    @Override
    public void jukeboxSkip(Machine m) {
        if (!m.hasJukebox()) return;
        musicPlayer.skip(jukeboxKey(m));
    }

    @Override
    public String jukeboxCurrentTrackId(Machine m) {
        return m.hasJukebox() ? musicPlayer.currentTrackId(jukeboxKey(m)) : null;
    }

    @Override
    public long jukeboxPositionMs(Machine m) {
        return m.hasJukebox() ? musicPlayer.positionMs(jukeboxKey(m)) : 0;
    }

    @Override
    public long jukeboxLengthMs(Machine m) {
        return m.hasJukebox() ? musicPlayer.trackLengthMs(jukeboxKey(m)) : 0;
    }

    @Override
    public void jukeboxAddUrlToPlaylist(Player p, String playlist, String url, Runnable onDone) {
        if (trackLibrary == null || playlistLibrary == null) { if (onDone != null) onDone.run(); return; }
        p.sendMessage(msg("§7downloading §f" + url + "§7…"));
        trackLibrary.downloadUrlAutoId(url, p.getName(), id -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (id != null) { playlistLibrary.add(playlist, id); p.sendMessage(msg("§a♪ added §f" + id + "§a to playlist §f" + playlist)); }
            else p.sendMessage(msg("§ccouldn't download that URL."));
            if (onDone != null) onDone.run();
        }));
    }

    @Override
    public void jukeboxToggleLoop(Machine m) {
        if (!m.hasJukebox()) return;
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        d.setLoop(!d.loop());
        persistStore(m);
        musicPlayer.setLoopQueue(jukeboxKey(m), d.loop());   // live — no track restart
    }

    @Override
    public void jukeboxAdjustVolume(Machine m, double delta) {
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null || !type.isJukebox() || !m.hasJukebox()) return;
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        d.setVolume(d.volume() + delta);
        persistStore(m);
        float dist = type.jukebox().distance(m.tier()) * (float) d.volume();
        musicPlayer.setLocationalDistance(jukeboxKey(m), dist);   // live reach change — no track restart
    }

    @Override
    public void jukeboxSeek(Machine m, long deltaMs) {
        if (!m.hasJukebox()) return;
        musicPlayer.seek(jukeboxKey(m), deltaMs);
    }

    @Override
    public void jukeboxAdjustSpeed(Machine m, double delta) {
        if (!m.hasJukebox()) return;
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        d.setSpeed(d.speed() + delta);
        persistStore(m);
        musicPlayer.setSpeed(jukeboxKey(m), d.speed());   // live — rebuilds the stream at the new speed
    }

    @Override
    public void jukeboxToggleLock(Machine m) {
        if (!m.hasJukebox()) return;
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        d.setLocked(!d.locked());
        persistStore(m);
    }

    @Override
    public void jukeboxEject(Player p, Machine m) {
        if (!m.hasJukebox()) return;
        dev.servereer.machineconstruct.jukebox.JukeboxData d = m.jukebox();
        jukeboxStop(m);
        d.setTrackId(null);
        d.setPlaylistName(null);
        d.setHasDisc(false);
        persistStore(m);
    }

    /** True if the player has at least the given material amounts. */
    private static boolean hasAll(Player p, java.util.Map<Material, Integer> cost) {
        for (java.util.Map.Entry<Material, Integer> e : cost.entrySet())
            if (countItems(p, e.getKey()) < e.getValue()) return false;
        return true;
    }

    private static void removeAll(Player p, java.util.Map<Material, Integer> cost) {
        for (java.util.Map.Entry<Material, Integer> e : cost.entrySet())
            p.getInventory().removeItem(new ItemStack(e.getKey(), e.getValue()));
    }

    /** Run a built farm's reward commands as console, with %player% = the district owner. */
    private void runFactoryCommands(Machine m, java.util.List<String> commands) {
        String owner = m.owner() == null ? "" : String.valueOf(plugin.getServer().getOfflinePlayer(m.owner()).getName());
        if (owner == null) owner = "";
        final String ownerName = owner;
        for (String cmd : commands) {
            String c = cmd.replace("%player%", ownerName).replace("%owner%", ownerName);
            try { plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), c); }
            catch (Throwable t) { plugin.getLogger().warning("[FactoryDistrict] reward command failed: " + c + " — " + t.getMessage()); }
        }
    }

    private dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec factorySpec(Machine m) {
        MachineType t = registry == null ? null : registry.getMachineType(m.typeId());
        return (t == null) ? null : t.factory();
    }

    private dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData.Slot factorySlot(Machine m, int slot) {
        var spec = factorySpec(m);
        if (spec == null) return null;
        if (!m.hasFactory()) m.setFactory(new dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData());
        var d = m.factory();
        d.ensureSlots(spec.slotsForTier(d.tier()));
        if (slot < 0 || slot >= spec.slotsForTier(d.tier())) return null;
        return d.slot(slot);
    }

    @Override
    public int captureCreatures(Machine m, Player p) {
        var spec = factorySpec(m);
        if (spec == null) return 0;
        if (!m.hasFactory()) m.setFactory(new dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData());
        // Only vacuum mobs the player could build over — protection plugins (GriefPrevention/WG/Towny)
        // veto the probe in claims the player isn't trusted in, so we can't suck mobs out of them.
        int n = dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.capture(
                m.factory(), spec, m.anchor(), e -> canBuildAt(p, e.getLocation()));
        if (n > 0) persistStore(m);
        return n;
    }

    /** Probe-based build check at an entity's location (reuses {@link #canBuild}). */
    private boolean canBuildAt(Player p, org.bukkit.Location loc) {
        Block b = loc.getBlock();
        return canBuild(p, b, b, new ItemStack(Material.PLAYER_HEAD), org.bukkit.inventory.EquipmentSlot.HAND);
    }

    @Override
    public boolean buildFarm(Machine m, Player p, int slot, String farmId, String sizeId) {
        var spec = factorySpec(m);
        var s = factorySlot(m, slot);
        if (spec == null || s == null || s.isBuilt()) return false;
        var farm = spec.farm(farmId);
        if (farm == null) return false;
        int cap = dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.effectiveMaxCount(spec, m.factory(), farm);
        if (dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.countOf(m.factory(), farm.id) >= cap) {
            p.sendMessage(msg("§cYou've hit the limit for §f" + farm.display + "§c (" + cap + ")."
                    + (farm.raisesMaxFarm == null && farm.maxCount > 0 ? " §7Build an outpost to raise it." : "")));
            return false;
        }
        var size = spec.size(sizeId);
        if (!dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.affords(p, m.factory(), farm, size)) {
            String missing = dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.missingText(p, m.factory(), farm, size);
            p.sendMessage(msg("§cMissing for the " + size.display + " " + farm.display + ": §f" + missing));
            return false;
        }
        dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.consume(p, m.factory(), farm, size);
        s.factoryId = farm.id;
        s.sizeId = size.id;
        s.lastProduce = System.currentTimeMillis();
        s.richness = rollRichness(farm);
        s.soil = farm.soilStart;       // a freshly built field starts with full soil nutrients
        s.broken = false;
        persistStore(m);
        String rich = farm.hasRichness() ? String.format(" §7(richness ×%.2f)", s.richness) : "";
        p.sendMessage(msg("§aBuilt the §f" + size.display + " " + farm.display + "§a — now producing." + rich));
        return true;
    }

    @Override
    public boolean demolishFarm(Machine m, Player p, int slot) {
        var spec = factorySpec(m);
        var s = factorySlot(m, slot);
        if (s == null || !s.isBuilt()) return false;
        var farm = spec == null ? null : spec.farm(s.factoryId);
        boolean wasExhausted = farm != null && dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.exhausted(farm, s);
        java.util.List<ItemStack> refund = farm == null ? java.util.List.of()
                : (wasExhausted && !farm.scrap.isEmpty() ? farm.scrap : farm.demolishRefund);
        s.factoryId = null;
        s.sizeId = null;
        s.lastProduce = 0L;
        s.offspring = 0;
        s.lastGenerate = 0L;
        s.workersAssigned = 0;   // frees its workers back to the pool; houses lose their capacity
        s.level = 0;
        s.cyclesProduced = 0;
        s.richness = 1.0;
        s.soil = 0;
        s.broken = false;
        s.producedLog.clear();
        persistStore(m);
        if (!refund.isEmpty()) {
            for (ItemStack r : refund) {
                ItemStack give = r.clone();
                for (ItemStack lo : p.getInventory().addItem(give).values()) p.getWorld().dropItem(p.getLocation(), lo);
            }
            p.sendMessage(msg("§eDemolished the farm — recovered some materials."));
        } else {
            p.sendMessage(msg("§eDemolished the farm — slot freed (no refund)."));
        }
        return true;
    }

    @Override
    public int assignWorker(Machine m, Player p, int slot, int delta) {
        var spec = factorySpec(m);
        var s = factorySlot(m, slot);
        if (spec == null || s == null || !s.isBuilt()) return 0;
        var farm = spec.farm(s.factoryId);
        if (farm == null || farm.workers <= 0) { p.sendMessage(msg("§eThis machine doesn't use workers.")); return s == null ? 0 : s.workersAssigned; }
        if (delta > 0) {
            if (s.workersAssigned >= farm.workers) { p.sendMessage(msg("§eFully staffed.")); return s.workersAssigned; }
            if (dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.workersFree(spec, m.factory()) <= 0) {
                p.sendMessage(msg("§cNo free workers — build/upgrade a house.")); return s.workersAssigned;
            }
            s.workersAssigned++;
        } else if (delta < 0) {
            if (s.workersAssigned <= 0) return 0;
            s.workersAssigned--;
        }
        persistStore(m);
        return s.workersAssigned;
    }

    @Override
    public boolean upgradeHouse(Machine m, Player p, int slot) {
        var spec = factorySpec(m);
        var s = factorySlot(m, slot);
        if (spec == null || s == null || !s.isBuilt()) return false;
        var farm = spec.farm(s.factoryId);
        if (farm == null || !farm.upgradeable()) { p.sendMessage(msg("§eThis machine can't be upgraded.")); return false; }
        if (!farm.canUpgrade(s.level)) { p.sendMessage(msg("§eAlready at max level (§f" + farm.maxLevel + "§e).")); return false; }
        var d = m.factory();
        if (!affordsBlocks(p, d, farm.upgradeCost)) {
            p.sendMessage(msg("§cUpgrade needs: §f" + missingBlocks(p, d, farm.upgradeCost)));
            return false;
        }
        consumeBlocks(p, d, farm.upgradeCost);
        s.level++;
        persistStore(m);
        String extra = farm.providesWorkers > 0 ? " §7(+" + farm.workersPerLevel + " worker capacity)" : "";
        p.sendMessage(msg("§a" + farm.display + " upgraded to level §f" + s.level + extra + "§a."));
        return true;
    }

    @Override
    public boolean convertFarm(Machine m, Player p, int slot, int upgradeIndex) {
        var spec = factorySpec(m);
        var s = factorySlot(m, slot);
        if (spec == null || s == null || !s.isBuilt()) return false;
        var farm = spec.farm(s.factoryId);
        if (farm == null || upgradeIndex < 0 || upgradeIndex >= farm.upgrades.size()) return false;
        var up = farm.upgrades.get(upgradeIndex);
        var target = spec.farm(up.to);
        if (target == null) { p.sendMessage(msg("§cThat upgrade's target farm doesn't exist.")); return false; }
        var d = m.factory();
        if (!dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.upgradeUnlocked(spec, d, s, up)) {
            p.sendMessage(msg("§cUpgrade locked — " + upgradeLockText(spec, d, s, up)));
            return false;
        }
        // merged (★) stacks promote ONE unit at a time into a free slot (ADR 0020); a single unit converts in place.
        boolean peel = s.mergeStars > 0;
        int freeIdx = -1;
        if (peel) {
            freeIdx = firstFreeFactorySlot(m);
            if (freeIdx < 0) { p.sendMessage(msg("§cNeed a free slot to promote a unit.")); return false; }
        }
        // pay the upgrade cost: vanilla blocks + MMOItems (inventory + vault)
        if (!affordsBlocks(p, d, up.blocks) || !affordsMmo(p, d, up.mmoBlocks)) {
            p.sendMessage(msg("§cUpgrade needs: §f" + missingUpgrade(p, d, up)));
            return false;
        }
        consumeBlocks(p, d, up.blocks);
        for (var c : up.mmoBlocks)
            dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.consumeMmo(p, d, c);

        if (peel) {
            // peel one unit → fresh target in the free slot; the stack drops one ★ (keeping (N-1)/N of its yield)
            var ns = factorySlot(m, freeIdx);
            ns.factoryId = target.id;
            ns.sizeId = null;
            ns.lastProduce = System.currentTimeMillis();
            ns.cyclesProduced = 0; ns.level = 0; ns.offspring = 0; ns.lastGenerate = 0L; ns.broken = false;
            ns.producedLog.clear();
            ns.richness = target.hasRichness() ? rollRichness(target) : 1.0;
            ns.soil = target.hasSoil() ? target.soilStart : 0;
            ns.workersAssigned = 0;
            ns.mergeStars = 0; ns.mergeBonus = 0;
            int units = s.mergeStars + 1;
            double newTotal = dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.contribution(farm, s) * (units - 1) / units;
            s.mergeStars -= 1;
            s.mergeBonus = Math.max(0, newTotal - s.richness
                    * dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.proficiencyMult(farm, s));
            persistStore(m);
            p.sendMessage(msg("§aPromoted 1 unit into §f" + target.display
                    + "§a. §7" + farm.display + " is now ★" + (s.mergeStars + 1) + "."));
            return true;
        }

        // morph the (single) slot into the target farm — original behaviour
        s.factoryId = target.id;
        s.sizeId = null;
        s.lastProduce = System.currentTimeMillis();
        s.cyclesProduced = 0;
        s.level = 0;
        s.offspring = 0;
        s.lastGenerate = 0L;
        s.broken = false;
        s.producedLog.clear();
        // carry the deposit/source yield forward; only roll if the source had none and the target rolls richness
        if (s.richness == 1.0 && target.hasRichness()) s.richness = rollRichness(target);
        // soil continuity (ADR 0017): a fresh field from a non-soil source (e.g. a plot) starts full;
        // converting between soil-fields (crop rotation / replant) PRESERVES the current nutrient level.
        if (target.hasSoil()) { if (!farm.hasSoil()) s.soil = target.soilStart; }
        else s.soil = 0;
        s.workersAssigned = Math.min(s.workersAssigned, target.workers);   // keep what the new farm can use
        persistStore(m);
        String rich = s.richness != 1.0 ? String.format(" §7(richness ×%.2f)", s.richness) : "";
        p.sendMessage(msg("§aUpgraded into §f" + target.display + "§a." + rich));
        return true;
    }

    private int firstFreeFactorySlot(Machine m) {
        var spec = factorySpec(m);
        var d = m.factory();
        if (spec == null) return -1;
        int usable = spec.slotsForTier(d.tier());
        for (int i = 0; i < usable; i++) {
            var fs = d.slot(i);
            if (fs != null && !fs.isBuilt()) return i;
        }
        return -1;
    }

    @Override
    public boolean mergeFarm(Machine m, Player p, int intoSlot, int fromSlot) {
        var spec = factorySpec(m);
        var into = factorySlot(m, intoSlot);
        var from = factorySlot(m, fromSlot);
        if (spec == null || into == null || from == null || intoSlot == fromSlot) return false;
        if (!into.isBuilt() || !from.isBuilt()) { p.sendMessage(msg("§cBoth slots must hold a built farm.")); return false; }
        if (!into.factoryId.equals(from.factoryId)) { p.sendMessage(msg("§cYou can only merge the same farm.")); return false; }
        if (into.broken || from.broken) { p.sendMessage(msg("§cRepair the farm before merging.")); return false; }
        var farm = spec.farm(into.factoryId);
        if (farm == null) return false;
        if (!dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.merge(into, from, farm)) return false;
        persistStore(m);
        p.sendMessage(msg("§aMerged — §f" + farm.display + " §ais now §6★" + (into.mergeStars + 1)
                + "§a; a slot was freed."));
        return true;
    }

    @Override
    public boolean selectRecipe(Machine m, Player p, int slot, String recipeId) {
        var spec = factorySpec(m);
        var s = factorySlot(m, slot);
        if (spec == null || s == null || !s.isBuilt()) return false;
        var farm = spec.farm(s.factoryId);
        if (farm == null || farm.recipes.isEmpty()) return false;
        if (farm.recipes.stream().noneMatch(r -> r.id.equals(recipeId))) return false;
        if (s.selectedRecipes == null) s.selectedRecipes = new java.util.ArrayList<>();
        int cap = (farm.stackMode == dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec.StackMode.MULTI)
                ? dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.unitCount(s) : 1;
        if (s.selectedRecipes.remove(recipeId)) {
            // toggled off
        } else {
            if (cap <= 1) s.selectedRecipes.clear();        // single-recipe machine: replace
            s.selectedRecipes.add(recipeId);
            while (s.selectedRecipes.size() > cap) s.selectedRecipes.remove(0);  // trim oldest over cap
        }
        persistStore(m);
        var active = dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.activeRecipes(farm, s);
        p.sendMessage(msg("§aNow crafting: §f" + (active.isEmpty() ? "auto"
                : active.stream().map(r -> r.display).reduce((a, b) -> a + "§7, §f" + b).orElse("auto"))));
        return true;
    }

    @Override
    public int craftVanilla(Machine m, Player p, int slot, org.bukkit.Material result, boolean all) {
        var spec = factorySpec(m);
        var s = factorySlot(m, slot);
        if (spec == null || s == null || !s.isBuilt() || result == null) return 0;
        var farm = spec.farm(s.factoryId);
        if (farm == null || !farm.craftsVanilla) return 0;
        long cap = spec.vaultCapForTier(m.factory().tier());
        boolean[] spilled = { false };
        java.util.function.Predicate<org.bukkit.inventory.ItemStack> overflow = over -> {
            spilled[0] = true;
            java.util.Map<Integer, org.bukkit.inventory.ItemStack> left = p.getInventory().addItem(over);
            if (!left.isEmpty()) {                                  // inventory full too → drop the rest, stop here
                for (org.bukkit.inventory.ItemStack ls : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), ls);
                return false;
            }
            return true;
        };
        int n = dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.craftFromVault(
                m.factory(), cap, result, all ? Integer.MAX_VALUE : 1, overflow);
        if (n > 0) {
            persistStore(m);
            String dest = spilled[0] ? " §7(vault full → §finventory§7)" : " §7→ vault.";
            p.sendMessage(msg("§aCrafted §f" + n + "x §a" + dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.pretty(result.name()) + dest));
        } else {
            p.sendMessage(msg("§cNot enough materials in the vault to craft that."));
        }
        return n;
    }

    private boolean affordsMmo(Player p, dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData d,
                               java.util.List<dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec.MmoCost> mmo) {
        for (var c : mmo)
            if (dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.mmoAvailable(p, d, c.type, c.id) < c.amount) return false;
        return true;
    }
    private String missingUpgrade(Player p, dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData d,
                                  dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec.Upgrade up) {
        StringBuilder sb = new StringBuilder(missingBlocks(p, d, up.blocks));
        for (var c : up.mmoBlocks) {
            long have = dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.mmoAvailable(p, d, c.type, c.id);
            if (have < c.amount) { if (sb.length() > 0) sb.append(", "); sb.append(c.amount - have).append("x ").append(c.id).append(" (MMO)"); }
        }
        return sb.toString();
    }

    private double rollRichness(dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec.Farm farm) {
        return dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.rollRichness(farm);
    }

    private String upgradeLockText(dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec spec,
                                   dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData d,
                                   dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData.Slot s,
                                   dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec.Upgrade up) {
        StringBuilder sb = new StringBuilder();
        for (var e : up.requiresProduced.entrySet()) {
            long have = s.producedLog.getOrDefault(e.getKey(), 0L);
            if (have < e.getValue()) { if (sb.length() > 0) sb.append(", "); sb.append("produce ").append(e.getValue() - have).append(" more ").append(dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.pretty(e.getKey())); }
        }
        for (String b : up.requiresBuilding) {
            if (dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.countOf(d, b) <= 0) {
                var bf = spec.farm(b); if (sb.length() > 0) sb.append(", "); sb.append("build a ").append(bf != null ? bf.display : b);
            }
        }
        return sb.toString();
    }

    @Override
    public boolean repairFarm(Machine m, Player p, int slot) {
        var spec = factorySpec(m);
        var s = factorySlot(m, slot);
        if (spec == null || s == null || !s.isBuilt()) return false;
        var farm = spec.farm(s.factoryId);
        if (farm == null) return false;
        if (!s.broken) { p.sendMessage(msg("§eThis machine isn't broken.")); return false; }
        var d = m.factory();
        if (!farm.hazardRepair.isEmpty() && !affordsBlocks(p, d, farm.hazardRepair)) {
            p.sendMessage(msg("§cRepair needs: §f" + missingBlocks(p, d, farm.hazardRepair)));
            return false;
        }
        consumeBlocks(p, d, farm.hazardRepair);
        s.broken = false;
        s.lastProduce = System.currentTimeMillis();   // don't bank the broken downtime
        persistStore(m);
        p.sendMessage(msg("§aRepaired the §f" + farm.display + "§a — back online."));
        return true;
    }

    @Override
    public boolean upgradeDistrict(Machine m, Player p) {
        var spec = factorySpec(m);
        if (spec == null) return false;
        if (!m.hasFactory()) m.setFactory(new dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData());
        var d = m.factory();
        if (!spec.canUpgrade(d.tier())) { p.sendMessage(msg("§eThis district is at its maximum tier.")); return false; }
        // reuse the trading-hall block-cost helpers via the factory manager's affords/consume? upgrade is blocks only.
        if (!affordsBlocks(p, d, spec.upgrade())) {
            p.sendMessage(msg("§cUpgrade needs: §f" + missingBlocks(p, d, spec.upgrade())));
            return false;
        }
        consumeBlocks(p, d, spec.upgrade());
        d.setTier(d.tier() + 1);
        d.ensureSlots(spec.slotsForTier(d.tier()));
        persistStore(m);
        p.sendMessage(msg("§aDistrict upgraded to §fTier " + d.tier() + "§a — §f"
                + spec.slotsForTier(d.tier()) + "§a slots, vault cap §f" + spec.vaultCapForTier(d.tier()) + "§a."));
        return true;
    }

    // block-only cost helpers for the district upgrade (inventory + vault)
    private boolean affordsBlocks(Player p, dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData d, java.util.List<ItemStack> recipe) {
        for (ItemStack req : recipe) {
            if (req == null) continue;
            if (dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.available(p, d, req) < req.getAmount()) return false;
        }
        return true;
    }
    private String missingBlocks(Player p, dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData d, java.util.List<ItemStack> recipe) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack req : recipe) {
            if (req == null) continue;
            long have = dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.available(p, d, req);
            if (have < req.getAmount()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(req.getAmount() - have).append("x ").append(dev.servereer.machineconstruct.factorydistrict.FactoryDistrictManager.pretty(req.getType().name()));
            }
        }
        return sb.toString();
    }
    private void consumeBlocks(Player p, dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData d, java.util.List<ItemStack> recipe) {
        for (ItemStack req : recipe) {
            if (req == null) continue;
            int need = req.getAmount();
            need -= removeFromPlayerInv(p, req, need);
            if (need > 0) d.removeFromVault(req, need);
        }
    }

    @Override
    public long depositVault(Machine m, Player p, ItemStack stack) {
        var spec = factorySpec(m);
        if (spec == null || stack == null || stack.getType().isAir()) return 0;
        if (!m.hasFactory()) m.setFactory(new dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData());
        var d = m.factory();
        long amount = stack.getAmount();
        long cap = spec.vaultCapForTier(d.tier());
        if (cap > 0) {
            long room = cap - d.vaultMass();
            if (room <= 0) { p.sendMessage(msg("§cThe vault is full.")); return 0; }
            amount = Math.min(amount, room);
        }
        d.addToVault(stack, amount);
        persistStore(m);
        return amount;
    }

    @Override
    public long withdrawVault(Machine m, Player p, ItemStack template, boolean all) {
        if (factorySpec(m) == null || template == null || !m.hasFactory()) return 0;
        var d = m.factory();
        long have = d.vaultCount(template);
        if (have <= 0) return 0;
        int max = template.getMaxStackSize();
        long want = all ? have : Math.min(have, max);
        long given = 0;
        while (want > 0) {
            int n = (int) Math.min(max, want);
            ItemStack give = template.clone();
            give.setAmount(n);
            int leftAmt = 0;
            for (ItemStack lo : p.getInventory().addItem(give).values()) leftAmt += lo.getAmount();
            given += n - leftAmt;
            want -= n;
            if (leftAmt > 0) break;
        }
        if (given > 0) { d.removeFromVault(template, given); persistStore(m); }
        else p.sendMessage(msg("§eYour inventory is full."));
        return given;
    }

    @Override
    public void persistFactory(Machine m) { persistStore(m); }

    /** Remove up to {@code need} of a template from the player's storage; returns removed count. */
    private int removeFromPlayerInv(Player p, ItemStack like, int need) {
        int removed = 0;
        ItemStack[] contents = p.getInventory().getStorageContents();
        ItemStack key = like.clone(); key.setAmount(1);
        for (int i = 0; i < contents.length && removed < need; i++) {
            ItemStack it = contents[i];
            if (it == null || !it.isSimilar(key)) continue;
            int take = Math.min(it.getAmount(), need - removed);
            it.setAmount(it.getAmount() - take);
            removed += take;
            if (it.getAmount() <= 0) contents[i] = null;
        }
        p.getInventory().setStorageContents(contents);
        return removed;
    }

    // --- TradingHallActions (called by the trading-hall menu) ---------------

    private dev.servereer.machineconstruct.tradinghall.TradingHallSpec hallSpec(Machine m) {
        MachineType t = registry == null ? null : registry.getMachineType(m.typeId());
        return (t == null) ? null : t.tradingHall();
    }

    private dev.servereer.machineconstruct.tradinghall.TradingHallData.Slot hallSlot(Machine m, int slot) {
        var spec = hallSpec(m);
        if (spec == null) return null;
        if (!m.hasTradingHall()) m.setTradingHall(new dev.servereer.machineconstruct.tradinghall.TradingHallData());
        var d = m.tradingHall();
        d.ensureSlots(spec.slotsForTier(d.tier()));
        if (slot < 0 || slot >= spec.slotsForTier(d.tier())) return null;
        return d.slot(slot);
    }

    /** Shift-right-click a wild villager near an owned Trading Hall → capture that one villager into its roster. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW, ignoreCancelled = true)
    public void onCaptureVillager(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;           // fire once (not the off-hand pass)
        if (!(e.getRightClicked() instanceof org.bukkit.entity.Villager v)) return;   // WanderingTrader excluded (separate class)
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;                                                  // shift-right-click only; plain click still trades
        Machine hall = nearestCaptureHall(p, v.getLocation());
        if (hall == null) return;                                                     // not near your hall → let vanilla trade proceed
        e.setCancelled(true);                                                         // suppress the vanilla trade UI
        if (!p.hasPermission("machineconstruct.use")) { p.sendMessage(msg("§cYou don't have permission to use machines.")); return; }
        if (!p.hasPermission("machineconstruct.admin") && !canBuildAt(p, v.getLocation())) {   // your own claim only — can't grab from spawn/others' claims
            p.sendMessage(msg("§cYou can only capture villagers inside your own claim.")); return;
        }
        MachineType type = registry == null ? null : registry.getMachineType(hall.typeId());
        var spec = type == null ? null : type.tradingHall();
        if (spec == null) return;
        if (!hall.hasTradingHall()) hall.setTradingHall(new dev.servereer.machineconstruct.tradinghall.TradingHallData());
        var d = hall.tradingHall();
        if (spec.rosterCap() > 0 && d.blankVillagers() >= spec.rosterCap()) {
            p.sendMessage(msg("§cThat trading hall's roster is full (§f" + spec.rosterCap() + "§c).")); return;
        }
        if (dev.servereer.machineconstruct.tradinghall.TradingHallManager.captureOne(d, spec, v)) {
            persistStore(hall);
            p.sendMessage(msg("§a✦ Captured a villager §7→ §f" + d.blankVillagers() + "§a blank villager(s) in the roster."));
            try {
                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_CELEBRATE, 0.7f, 1.2f);
                v.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, v.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.05);
            } catch (Throwable ignored) { }
        }
    }

    /** The nearest Trading Hall the player may capture into (owned, same world, villager within the hall's capture radius). */
    private Machine nearestCaptureHall(Player p, Location loc) {
        if (loc.getWorld() == null) return null;
        boolean admin = p.hasPermission("machineconstruct.admin");
        Machine best = null; double bestSq = Double.MAX_VALUE;
        for (Machine m : byAnchor.values()) {
            MachineType t = registry == null ? null : registry.getMachineType(m.typeId());
            if (t == null || !t.isTradingHall()) continue;
            if (!admin && m.owner() != null && !m.owner().equals(p.getUniqueId())) continue;
            Location a = m.anchor();
            if (a.getWorld() == null || !a.getWorld().equals(loc.getWorld())) continue;
            int r = t.tradingHall().captureRadius();
            double dSq = a.clone().add(0.5, 0.5, 0.5).distanceSquared(loc);
            if (dSq <= (double) r * r && dSq < bestSq) { bestSq = dSq; best = m; }
        }
        return best;
    }

    @Override
    public boolean buildStation(Machine m, Player p, int slot, String professionId) {
        var spec = hallSpec(m);
        var s = hallSlot(m, slot);
        if (spec == null || s == null || s.isBuilt()) return false;
        var prof = spec.profession(professionId);
        if (prof == null) return false;
        if (!dev.servereer.machineconstruct.tradinghall.TradingHallManager.affords(p, m.tradingHall(), prof.station)) {
            String missing = dev.servereer.machineconstruct.tradinghall.TradingHallManager.missingText(p, m.tradingHall(), prof.station);
            p.sendMessage(msg("§cMissing for the " + prof.display + " station: §f" + missing));
            return false;
        }
        dev.servereer.machineconstruct.tradinghall.TradingHallManager.consume(p, m.tradingHall(), prof.station);
        s.stationProfessionId = prof.id;
        persistStore(m);
        p.sendMessage(msg("§aBuilt the §f" + prof.display + "§a station."));
        return true;
    }

    @Override
    public boolean insertVillager(Machine m, Player p, int slot) {
        var s = hallSlot(m, slot);
        if (s == null || !s.isBuilt() || s.hasTrader()) return false;
        if (m.tradingHall().blankVillagers() <= 0) { p.sendMessage(msg("§cNo captured villagers — shift-right-click a wild villager near this hall first.")); return false; }
        var spec = hallSpec(m);
        var prof = spec == null ? null : spec.profession(s.stationProfessionId);
        m.tradingHall().addBlankVillagers(-1);
        var t = new dev.servereer.machineconstruct.tradinghall.TradingHallData.Trader();
        t.professionId = s.stationProfessionId;
        if (prof != null) dev.servereer.machineconstruct.tradinghall.TradingHallManager.rollTrades(prof, t);
        s.trader = t;
        persistStore(m);
        p.sendMessage(msg("§aInserted a villager — it's open for trade."));
        return true;
    }

    @Override
    public boolean rerollOffer(Machine m, Player p, int slot, int offerIndex) {
        var spec = hallSpec(m);
        var s = hallSlot(m, slot);
        if (spec == null || s == null || !s.hasTrader()) return false;
        var prof = spec.profession(s.trader.professionId);
        if (prof == null) return false;
        boolean ok = dev.servereer.machineconstruct.tradinghall.TradingHallManager.rerollOffer(prof, s.trader, offerIndex);
        if (ok) persistStore(m);
        return ok;
    }

    @Override
    public int executeTrade(Machine m, Player p, int slot, int offerIndex, boolean bulk) {
        var spec = hallSpec(m);
        var s = hallSlot(m, slot);
        if (spec == null || s == null || !s.hasTrader()) return 0;
        var prof = spec.profession(s.trader.professionId);
        if (prof == null) return 0;
        var d = m.tradingHall();
        int done;
        if (isHallOwner(m, p)) {                                              // owner: vault-first, output toggle
            long cap = spec.vaultCapForTier(d.tier());
            done = dev.servereer.machineconstruct.tradinghall.TradingHallManager
                    .trade(prof, d, s.trader, offerIndex, p, bulk, cap);
            if (done == 0) p.sendMessage(msg("§eCan't trade — out of stock or not enough materials (vault + inventory)."));
        } else {                                                              // customer: pay from inv → owner till
            if (!d.open()) { p.sendMessage(msg("§cThis shop is closed.")); return 0; }
            done = dev.servereer.machineconstruct.tradinghall.TradingHallManager
                    .customerTrade(prof, d, s.trader, offerIndex, p, bulk);
            if (done == 0) p.sendMessage(msg("§eCan't trade — out of stock, can't afford it, or your inventory is full."));
        }
        if (done > 0) persistStore(m);
        return done;
    }

    /** True if {@code p} owns this hall (or the hall predates ownership — null owner = full access). */
    private boolean isHallOwner(Machine m, Player p) {
        return m.owner() == null || m.owner().equals(p.getUniqueId());
    }

    @Override
    public boolean upgradeStall(Machine m, Player p) {
        var spec = hallSpec(m);
        if (spec == null) return false;
        if (!m.hasTradingHall()) m.setTradingHall(new dev.servereer.machineconstruct.tradinghall.TradingHallData());
        var d = m.tradingHall();
        if (!spec.canUpgrade(d.tier())) { p.sendMessage(msg("§eThis stall is at its maximum tier.")); return false; }
        if (!dev.servereer.machineconstruct.tradinghall.TradingHallManager.affords(p, d, spec.upgrade())) {
            String missing = dev.servereer.machineconstruct.tradinghall.TradingHallManager.missingText(p, d, spec.upgrade());
            p.sendMessage(msg("§cUpgrade needs: §f" + missing));
            return false;
        }
        dev.servereer.machineconstruct.tradinghall.TradingHallManager.consume(p, d, spec.upgrade());
        d.setTier(d.tier() + 1);
        d.ensureSlots(spec.slotsForTier(d.tier()));
        persistStore(m);
        p.sendMessage(msg("§aStall upgraded to §fTier " + d.tier() + "§a — §f"
                + spec.slotsForTier(d.tier()) + "§a slots, vault cap §f" + spec.vaultCapForTier(d.tier()) + "§a."));
        return true;
    }

    @Override
    public boolean resetStall(Machine m, Player p, int slot) {
        var spec = hallSpec(m);
        var s = hallSlot(m, slot);
        if (spec == null || s == null || !s.hasTrader()) return false;
        var prof = spec.profession(s.trader.professionId);
        if (prof == null) return false;
        // Re-roll WHICH trades are offered, but carry the per-slot usage across so a reset can't be used
        // as a free restock — stock only refills on the timed restock (restock-seconds), not by re-rolling.
        int[] prevUses = new int[s.trader.offers.size()];
        for (int i = 0; i < prevUses.length; i++) prevUses[i] = s.trader.offers.get(i).uses;
        dev.servereer.machineconstruct.tradinghall.TradingHallManager.rollTrades(prof, s.trader);
        for (int i = 0; i < s.trader.offers.size() && i < prevUses.length; i++) {
            var o = s.trader.offers.get(i);
            o.uses = Math.min(Math.max(0, prevUses[i]), o.maxUses);
        }
        persistStore(m);
        p.sendMessage(msg("§aStall re-rolled §7— trades changed, stock/usage kept."));
        return true;
    }

    @Override
    public boolean toggleAuto(Machine m, Player p, int slot, int offerIndex) {
        var s = hallSlot(m, slot);
        if (s == null || !s.hasTrader()) return false;
        if (offerIndex < 0 || offerIndex >= s.trader.offers.size()) return false;
        var o = s.trader.offers.get(offerIndex);
        o.autoTrade = !o.autoTrade;
        persistStore(m);
        p.sendMessage(msg(o.autoTrade
                ? "§aAuto-trade §lON§r§a for this trade — runs from the vault."
                : "§eAuto-trade §lOFF§r§e for this trade."));
        return o.autoTrade;
    }

    @Override
    public boolean upgradeStock(Machine m, Player p, int slot) {
        var spec = hallSpec(m);
        var s = hallSlot(m, slot);
        if (spec == null || s == null || !s.hasTrader()) return false;
        if (!spec.canStockUpgrade()) { p.sendMessage(msg("§eStock upgrades aren't configured for this hall.")); return false; }
        var d = m.tradingHall();
        if (!dev.servereer.machineconstruct.tradinghall.TradingHallManager.affords(p, d, spec.stockUpgrade())) {
            String missing = dev.servereer.machineconstruct.tradinghall.TradingHallManager.missingText(p, d, spec.stockUpgrade());
            p.sendMessage(msg("§cStock upgrade needs: §f" + missing));
            return false;
        }
        dev.servereer.machineconstruct.tradinghall.TradingHallManager.consume(p, d, spec.stockUpgrade());
        s.trader.stockLevel++;
        persistStore(m);
        p.sendMessage(msg("§aStock upgraded — this shop now stocks §f×" + (1 + s.trader.stockLevel) + "§a per trade."));
        return true;
    }

    @Override
    public boolean toggleOutput(Machine m, Player p) {
        if (hallSpec(m) == null) return false;
        if (!m.hasTradingHall()) m.setTradingHall(new dev.servereer.machineconstruct.tradinghall.TradingHallData());
        var d = m.tradingHall();
        d.setOutputToVault(!d.outputToVault());
        persistStore(m);
        p.sendMessage(msg(d.outputToVault()
                ? "§aTrade results now go to the §fVault§a."
                : "§aTrade results now go to your §finventory§a (overflow → vault)."));
        return d.outputToVault();
    }

    @Override
    public boolean toggleShopfront(Machine m, Player p) {
        if (hallSpec(m) == null) return false;
        if (!isHallOwner(m, p)) { p.sendMessage(msg("§cOnly the owner can open this shop.")); return false; }
        if (!m.hasTradingHall()) m.setTradingHall(new dev.servereer.machineconstruct.tradinghall.TradingHallData());
        var d = m.tradingHall();
        d.setOpen(!d.open());
        persistStore(m);
        p.sendMessage(msg(d.open()
                ? "§aShopfront §lOPEN§r§a — anyone may now trade here (payments go to your till)."
                : "§eShopfront §lCLOSED§r§e — only you can trade here now."));
        return d.open();
    }

    @Override
    public long collectTill(Machine m, Player p, org.bukkit.inventory.ItemStack template, boolean all) {
        if (hallSpec(m) == null || template == null || !m.hasTradingHall()) return 0;
        if (!isHallOwner(m, p)) { p.sendMessage(msg("§cOnly the owner can collect earnings.")); return 0; }
        var d = m.tradingHall();
        long have = d.tillCount(template);
        if (have <= 0) return 0;
        int max = template.getMaxStackSize();
        long want = all ? have : Math.min(have, max);
        long given = 0;
        while (want > 0) {
            int n = (int) Math.min(max, want);
            org.bukkit.inventory.ItemStack give = template.clone();
            give.setAmount(n);
            int leftAmt = 0;
            for (org.bukkit.inventory.ItemStack lo : p.getInventory().addItem(give).values()) leftAmt += lo.getAmount();
            given += n - leftAmt;
            want -= n;
            if (leftAmt > 0) break;   // inventory full
        }
        if (given > 0) { d.removeFromTill(template, given); persistStore(m); }
        else p.sendMessage(msg("§eYour inventory is full."));
        return given;
    }

    @Override
    public long sweepTillToVault(Machine m, Player p) {
        var spec = hallSpec(m);
        if (spec == null || !m.hasTradingHall()) return 0;
        if (!isHallOwner(m, p)) { p.sendMessage(msg("§cOnly the owner can move earnings.")); return 0; }
        var d = m.tradingHall();
        long cap = spec.vaultCapForTier(d.tier());
        long moved = 0;
        for (org.bukkit.inventory.ItemStack tmpl : new ArrayList<>(d.till().keySet())) {
            long have = d.tillCount(tmpl);
            if (have <= 0) continue;
            long room = cap <= 0 ? have : Math.max(0, cap - d.vaultMass());
            if (room <= 0) break;                 // vault full
            long take = Math.min(have, room);
            d.removeFromTill(tmpl, take);
            d.addToVault(tmpl, take);
            moved += take;
        }
        if (moved > 0) { persistStore(m); p.sendMessage(msg("§aSwept §f" + moved + "§a earnings into the vault.")); }
        else p.sendMessage(msg("§eNothing moved — the vault is full or the till is empty."));
        return moved;
    }

    @Override
    public long deposit(Machine m, Player p, org.bukkit.inventory.ItemStack stack) {
        var spec = hallSpec(m);
        if (spec == null || stack == null || stack.getType().isAir()) return 0;
        if (!m.hasTradingHall()) m.setTradingHall(new dev.servereer.machineconstruct.tradinghall.TradingHallData());
        var d = m.tradingHall();
        long amount = stack.getAmount();
        long cap = spec.vaultCapForTier(d.tier());
        if (cap > 0) {
            long room = cap - d.vaultMass();
            if (room <= 0) { p.sendMessage(msg("§cThe vault is full.")); return 0; }
            amount = Math.min(amount, room);
        }
        d.addToVault(stack, amount);
        persistStore(m);
        return amount;
    }

    @Override
    public long withdraw(Machine m, Player p, org.bukkit.inventory.ItemStack template, boolean all) {
        if (hallSpec(m) == null || template == null || !m.hasTradingHall()) return 0;
        var d = m.tradingHall();
        long have = d.vaultCount(template);
        if (have <= 0) return 0;
        int max = template.getMaxStackSize();
        long want = all ? have : Math.min(have, max);
        long given = 0;
        while (want > 0) {
            int n = (int) Math.min(max, want);
            org.bukkit.inventory.ItemStack give = template.clone();
            give.setAmount(1);   // normalize to template (amount 1) then set n
            give.setAmount(n);
            int leftAmt = 0;
            for (org.bukkit.inventory.ItemStack lo : p.getInventory().addItem(give).values()) leftAmt += lo.getAmount();
            given += n - leftAmt;
            want -= n;
            if (leftAmt > 0) break;   // inventory full
        }
        if (given > 0) { d.removeFromVault(template, given); persistStore(m); }
        else p.sendMessage(msg("§eYour inventory is full."));
        return given;
    }

    @Override
    public boolean removeVillager(Machine m, Player p, int slot) {
        var s = hallSlot(m, slot);
        if (s == null || !s.hasTrader()) return false;
        s.trader = null;
        m.tradingHall().addBlankVillagers(1);
        persistStore(m);
        p.sendMessage(msg("§aReturned the villager to the roster."));
        return true;
    }

    /** Apply per-type vault rules: VOID discards, SELL auto-sells to the owner (even offline). */
    private void applyVaultRules(Machine m, MachineType type) {
        QuarryData d = m.quarry();
        if (d.rules().isEmpty()) return;
        PriceService prices = type.pricing();
        EconomyBridge econ = economy();
        org.bukkit.OfflinePlayer owner = m.owner() == null ? null : plugin.getServer().getOfflinePlayer(m.owner());
        double money = 0;
        for (ItemStack tmpl : new ArrayList<>(d.vault().keySet())) {
            QuarryData.Rule r = d.ruleFor(tmpl);
            if (r == QuarryData.Rule.KEEP) continue;
            long n = d.vault().getOrDefault(tmpl, 0L);
            if (r == QuarryData.Rule.VOID) { d.vault().remove(tmpl); continue; }
            if (prices != null && econ.available() && owner != null) {     // SELL
                double each = prices.price(tmpl);
                if (each > 0) { money += each * n; d.vault().remove(tmpl); }
            }
        }
        if (money > 0 && owner != null) econ.deposit(owner, money * QuarrySkill.sellPayoutMult(d));
    }

    /** Drop a quarry's tools + vault on teardown. */
    private void dropQuarry(World w, Location drop, QuarryData d) {
        if (d.pickaxe() != null) w.dropItemNaturally(drop, d.pickaxe());
        if (d.rod() != null) w.dropItemNaturally(drop, d.rod());
        for (ItemStack it : d.pickRack()) if (it != null) w.dropItemNaturally(drop, it);
        for (ItemStack it : d.rodRack()) if (it != null) w.dropItemNaturally(drop, it);
        for (java.util.Map.Entry<ItemStack, Long> e : d.vault().entrySet()) {
            long n = e.getValue();
            ItemStack tmpl = e.getKey();
            int max = Math.max(1, tmpl.getMaxStackSize());
            while (n > 0) {
                int amt = (int) Math.min(n, max);
                ItemStack s = tmpl.clone();
                s.setAmount(amt);
                w.dropItemNaturally(drop, s);
                n -= amt;
            }
        }
    }

    /** Drop a collector's vault as item stacks on teardown (fallback path only). */
    private void dropCollectorVault(World w, Location drop, dev.servereer.machineconstruct.collector.ChunkCollectorData d) {
        for (java.util.Map.Entry<ItemStack, Long> e : d.vault().entrySet()) {
            long n = e.getValue();
            ItemStack tmpl = e.getKey();
            int max = Math.max(1, tmpl.getMaxStackSize());
            while (n > 0) {
                int amt = (int) Math.min(n, max);
                ItemStack s = tmpl.clone();
                s.setAmount(amt);
                w.dropItemNaturally(drop, s);
                n -= amt;
            }
        }
    }

    @Override
    public void persist(Machine m) { persistStore(m); }

    // --- shared vault item movement (the grinder pool, the collector vault, … all use these) ---

    /** Move up to {@code amount} of one template out of a vault map into the player's inventory. Returns moved. */
    private static long vaultWithdraw(Map<ItemStack, Long> source, Player p, ItemStack template, long amount) {
        if (source == null || template == null) return 0;
        long have = source.getOrDefault(template, 0L);
        long take = Math.min(Math.max(0, amount), have);
        int max = Math.max(1, template.getMaxStackSize());
        long moved = 0;
        boolean full = false;
        while (take > 0 && !full) {
            int amt = (int) Math.min(take, max);
            ItemStack give = template.clone();
            give.setAmount(amt);
            Map<Integer, ItemStack> left = p.getInventory().addItem(give);
            int leftover = 0;
            for (ItemStack ls : left.values()) leftover += ls.getAmount();
            int placed = amt - leftover;
            moved += placed;
            take -= placed;
            if (leftover > 0) full = true;
        }
        long now = have - moved;
        if (now <= 0) source.remove(template); else source.put(template, now);
        return moved;
    }

    /** Withdraw everything that fits from a vault map into the player's inventory. Returns total moved. */
    private static long vaultWithdrawAll(Map<ItemStack, Long> source, Player p) {
        if (source == null) return 0;
        long total = 0;
        for (ItemStack tmpl : new ArrayList<>(source.keySet())) {
            total += vaultWithdraw(source, p, tmpl, Long.MAX_VALUE);
            if (source.containsKey(tmpl)) break;   // some remained → inventory full, stop
        }
        return total;
    }

    /** Sell a whole vault map at the given prices: remove sold items, deposit, fire the sale event.
     *  Returns {@code [money, itemsSold]}; the caller does the per-machine log + persist. */
    private double[] sellVaultMap(Map<ItemStack, Long> source, PriceService prices, Player p) {
        double total = 0;
        long sold = 0;
        Map<Material, double[]> breakdown = new java.util.HashMap<>();   // material -> [qty, money] for the sales rollup
        for (ItemStack tmpl : new ArrayList<>(source.keySet())) {
            long n = source.getOrDefault(tmpl, 0L);
            double each = prices.price(tmpl);
            if (each <= 0) continue;                          // unsellable → leave it
            total += each * n;
            sold += n;
            double[] a = breakdown.computeIfAbsent(tmpl.getType(), k -> new double[2]);
            a[0] += n; a[1] += each * n;
            source.remove(tmpl);
        }
        if (total > 0) {
            economy().deposit(p, total);
            final long soldF = sold; final double totalF = total;
            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new dev.servereer.machineconstruct.api.events.GrinderSellEvent(p, totalF, soldF));
            fireMachineItemSell(p.getUniqueId(), p.getName(), false, breakdown);
        }
        return new double[]{ total, sold };
    }

    /** Emit a per-material {@link dev.servereer.machineconstruct.api.events.MachineItemSellEvent} for the
     *  sales rollup. {@code breakdown} is material → [qty, money]; JDK-typed maps for cross-plugin listeners. */
    private void fireMachineItemSell(java.util.UUID id, String name, boolean auto, Map<Material, double[]> breakdown) {
        if (id == null || breakdown.isEmpty()) return;
        Map<String, Long> qty = new java.util.HashMap<>();
        Map<String, Double> money = new java.util.HashMap<>();
        for (Map.Entry<Material, double[]> e : breakdown.entrySet()) {
            long q = (long) e.getValue()[0];
            if (q <= 0) continue;
            qty.put(e.getKey().name(), q);
            money.put(e.getKey().name(), e.getValue()[1]);
        }
        if (qty.isEmpty()) return;
        org.bukkit.Bukkit.getPluginManager().callEvent(
                new dev.servereer.machineconstruct.api.events.MachineItemSellEvent(id, name, auto, qty, money));
    }

    @Override
    public long[] collectAll(Machine m) {
        if (!m.hasGrinder()) return new long[]{0, 0};
        long[] got = GrinderManager.collectAll(m.grinder());
        if (got[0] > 0 || got[1] > 0)
            m.grinder().logEvent("Collected " + got[0] + " items, " + got[1] + " XP");
        return got;
    }

    @Override
    public long withdrawPool(Machine m, Player p) {
        if (!m.hasGrinder()) return 0;
        long withdrawn = vaultWithdrawAll(m.grinder().pool(), p);   // shared vault movement
        if (withdrawn > 0) m.grinder().logEvent("Withdrew " + withdrawn + " items (" + p.getName() + ")");
        return withdrawn;
    }

    @Override
    public long withdrawPoolItem(Machine m, Player p, ItemStack template, long amount) {
        if (!m.hasGrinder() || template == null) return 0;
        long moved = vaultWithdraw(m.grinder().pool(), p, template, amount);   // shared vault movement
        if (moved > 0) persistStore(m);
        return moved;
    }

    @Override
    public long claimXp(Machine m, Player p, long amount) {
        if (!m.hasGrinder()) return 0;
        GrinderData d = m.grinder();
        long xp = d.storedExp();
        if (xp <= 0 || amount <= 0) return 0;
        int give = (int) Math.min(Integer.MAX_VALUE, Math.min(amount, xp));
        p.giveExp(give, true);   // applyMending: repair worn/held gear first (like real XP orbs), remainder to the bar
        d.setStoredExp(xp - give);
        d.logEvent("Claimed " + give + " XP (" + p.getName() + ")");
        return give;
    }

    @Override
    public double sellAll(Machine m, Player p) {
        if (m.hasQuarry()) return sellQuarryVault(m, p);      // shared by GrinderActions + QuarryActions
        if (!m.hasGrinder()) return 0;
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        if (type == null) return 0;
        PriceService prices = type.pricing();
        if (prices == null || !economy().available()) return -1;   // nothing to pay with
        double[] r = sellVaultMap(m.grinder().pool(), prices, p);   // shared vault sell
        if (r[0] > 0) m.grinder().logEvent("Sold " + (long) r[1] + " items for " + fmt(r[0]) + " (" + p.getName() + ")");
        return r[0];
    }

    @Override
    public void buySpawner(Machine m, Player p, String type) {
        MachineType mt = registry == null ? null : registry.getMachineType(m.typeId());
        if (mt == null || !mt.isGrinder()) return;
        GrinderSpec spec = mt.grinder();
        String t = type.toUpperCase(java.util.Locale.ROOT);
        GrinderSpec.CatalogEntry entry = spec.catalog().get(t);
        if (entry == null) { p.sendMessage(smsg(mt, "not_for_sale", "§cThat spawner isn't for sale.")); return; }
        if (entry.permission != null && !p.hasPermission(entry.permission)) {
            p.sendMessage(smsg(mt, "no_access_spawner", "§cYou don't have access to that spawner.")); return;
        }
        if (!m.hasGrinder()) m.setGrinder(new GrinderData());
        GrinderData d = m.grinder();
        // Check there is room for one before charging (don't take money for nothing).
        long cap = spec.stackCap(m.tier());
        boolean hasRoom = d.slotsUsed() < spec.slots(m.tier());
        if (!hasRoom) {
            for (InstalledSpawner s : d.spawners()) if (s.type().equalsIgnoreCase(t) && s.stackSize() < cap) { hasRoom = true; break; }
        }
        if (!hasRoom) { p.sendMessage(smsg(mt, "full", "§cGrinder is full — upgrade it for more slots.")); return; }
        EconomyBridge econ = economy();
        if (!econ.available()) { p.sendMessage(smsg(mt, "no_economy_buy", "§cNo economy available to buy.")); return; }
        if (!econ.has(p, entry.price)) { p.sendMessage(smsg(mt, "cant_afford", "§cYou need §f" + fmt(entry.price) + "§c to buy that.",
                dev.servereer.machineconstruct.gui.MenuSkin.vars("price", fmt(entry.price)))); return; }
        if (!econ.withdraw(p, entry.price)) { p.sendMessage(smsg(mt, "payment_failed", "§cPayment failed.")); return; }

        d.install(spec, m.tier(), t, 1, System.currentTimeMillis());
        d.logEvent("Bought " + LootTable.pretty(t) + " spawner for " + fmt(entry.price) + " (" + p.getName() + ")");
        persistStore(m);
        p.sendMessage(smsg(mt, "bought", "§aBought §f" + LootTable.pretty(t) + "§a spawner.",
                dev.servereer.machineconstruct.gui.MenuSkin.vars("mob", LootTable.pretty(t))));
    }

    private static String fmt(double d) {
        return (d == Math.floor(d)) ? Long.toString((long) d) : String.format("%.2f", d);
    }

    @Override
    public void insertHeld(Machine m, Player p) {
        ItemStack cursor = p.getItemOnCursor();
        String t = spawnerType(cursor);
        MachineType mt = registry == null ? null : registry.getMachineType(m.typeId());
        if (t == null) { p.sendMessage(smsg(mt, "hold_spawner", "§7Hold a spawner with a mob type on your cursor, then click Insert.")); return; }
        if (mt == null || !mt.isGrinder()) return;
        GrinderSpec spec = mt.grinder();
        LootTable loot = mt.loot();
        if (loot == null || !loot.has(t)) { p.sendMessage(smsg(mt, "no_loot_table", "§cNo loot table for §f" + LootTable.pretty(t) + "§c.",
                dev.servereer.machineconstruct.gui.MenuSkin.vars("mob", LootTable.pretty(t)))); return; }
        if (!m.hasGrinder()) m.setGrinder(new GrinderData());
        GrinderData d = m.grinder();
        long added = d.install(spec, m.tier(), t, 1, System.currentTimeMillis());
        if (added <= 0) { p.sendMessage(smsg(mt, "full", "§cGrinder is full — upgrade it for more slots.")); return; }
        if (cursor.getAmount() <= 1) p.setItemOnCursor(null);
        else { cursor.setAmount(cursor.getAmount() - 1); p.setItemOnCursor(cursor); }
        d.logEvent("Inserted " + LootTable.pretty(t) + " spawner (" + p.getName() + ")");
        persistStore(m);
        p.sendMessage(smsg(mt, "inserted", "§aInserted §f" + LootTable.pretty(t) + "§a spawner.",
                dev.servereer.machineconstruct.gui.MenuSkin.vars("mob", LootTable.pretty(t))));
    }

    @Override
    public void destackSlot(Machine m, Player p, int slotIndex, long amount) {
        if (!m.hasGrinder()) return;
        GrinderData d = m.grinder();
        if (slotIndex < 0 || slotIndex >= d.spawners().size()) return;
        InstalledSpawner s = d.spawners().get(slotIndex);
        String type = s.type();
        long take = Math.max(1, Math.min(amount, s.stackSize()));
        long remaining = take;
        while (remaining > 0) {
            int amt = (int) Math.min(remaining, 64);
            ItemStack give = makeSpawnerItem(type, amt);
            Map<Integer, ItemStack> left = p.getInventory().addItem(give);
            for (ItemStack ls : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), ls);
            remaining -= amt;
        }
        if (s.stackSize() - take <= 0) d.spawners().remove(slotIndex);
        else s.setStackSize(s.stackSize() - take);
        d.logEvent("Removed " + take + " " + LootTable.pretty(type) + " spawner (" + p.getName() + ")");
        persistStore(m);
        p.sendMessage(smsg(typeOfMachine(m), "removed", "§eRemoved §f" + take + "× " + LootTable.pretty(type) + "§e spawner.",
                dev.servereer.machineconstruct.gui.MenuSkin.vars("count", take, "mob", LootTable.pretty(type))));
    }

    // --- grinder input/output chest links ----------------------------------

    @Override
    public void beginChestSelection(Machine m, Player p, ChestLink.Type type) {
        pendingChestSelect.put(p.getUniqueId(), new Object[]{ m, type });
        p.closeInventory();
        p.sendMessage(smsg(typeOfMachine(m), type == ChestLink.Type.OUTPUT ? "link_prompt_out" : "link_prompt_in",
                "§dRight-click a chest within §f" + ioReach(m.tier()) + "§d blocks to link it as "
                + (type == ChestLink.Type.OUTPUT ? "§aOUTPUT" : "§bINPUT") + "§d. §7(do something else to cancel)",
                dev.servereer.machineconstruct.gui.MenuSkin.vars("reach", ioReach(m.tier()))));
    }

    @Override
    public void removeChestLink(Machine m, int index) {
        List<ChestLink> links = linksOf(m);
        if (links != null && index >= 0 && index < links.size()) { links.remove(index); persistStore(m); }
    }

    @Override
    public void promptLinkRate(Machine m, Player p, int index) {
        List<ChestLink> links = linksOf(m);
        if (links == null || index < 0 || index >= links.size()) return;   // rate applies to OUTPUT (push) and INPUT (pull) alike
        pendingChestRate.put(p.getUniqueId(), new Object[]{ m, index });
        p.closeInventory();
        p.sendMessage(smsg(typeOfMachine(m), "rate_prompt", "§dType the max §fitems/second§d for this link, or §ffull§d for unlimited. §7(type §fcancel§7 to abort)"));
    }

    @Override
    public void toggleLinkFilter(Machine m, int index, Material mat) {
        if (mat == null) return;
        List<ChestLink> links = linksOf(m);
        if (links != null && index >= 0 && index < links.size()) { links.get(index).toggleFilter(mat); persistStore(m); }
    }

    @Override
    public void clearLinkFilter(Machine m, int index) {
        List<ChestLink> links = linksOf(m);
        if (links != null && index >= 0 && index < links.size()) { links.get(index).clearFilter(); persistStore(m); }
    }

    private boolean isFactoryMachine(Machine m) {
        MachineType t = registry == null ? null : registry.getMachineType(m.typeId());
        return t != null && t.isFactory();
    }

    /** The I/O link list for whichever subsystem this machine is (factory district or grinder); never null. */
    private List<ChestLink> linksOf(Machine m) {
        if (isCollectorMachine(m)) {
            if (!m.hasChunkCollector()) m.setChunkCollector(new dev.servereer.machineconstruct.collector.ChunkCollectorData());
            return m.chunkCollector().links();
        }
        if (isFactoryMachine(m)) {
            if (!m.hasFactory()) m.setFactory(new dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData());
            return m.factory().links();
        }
        if (!m.hasGrinder()) m.setGrinder(new GrinderData());
        return m.grinder().links();
    }

    private void reopenFactoryChests(Player p, Machine m) { factoryMenus.openChestsView(p, m); }

    /** Step 2 of linking: the player's next chest right-click while a selection is pending. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW)
    public void onGrinderChestSelect(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Object[] sel = pendingChestSelect.remove(e.getPlayer().getUniqueId());
        if (sel == null) return;
        Player p = e.getPlayer();
        Machine m = (Machine) sel[0];
        ChestLink.Type type = (ChestLink.Type) sel[1];
        boolean factory = isFactoryMachine(m);
        boolean collector = isCollectorMachine(m);
        Runnable reopen = collector ? () -> reopenCollectorChests(p, m)
                : (factory ? () -> reopenFactoryChests(p, m) : () -> reopenChests(p, m));
        String what = collector ? "collector" : (factory ? "district" : "grinder");
        e.setCancelled(true);                                   // don't open the chest
        Block b = e.getClickedBlock();
        Location loc = b.getLocation();
        MachineType lmt = typeOfMachine(m);
        if (!(b.getState() instanceof org.bukkit.block.Container)) { p.sendMessage(smsg(lmt, "link_not_container", "§cThat isn't a container.")); reopen.run(); return; }
        if (byAnchor.containsKey(key(loc))) { p.sendMessage(smsg(lmt, "link_is_machine", "§cThat block is a machine — it can't be a link.")); reopen.run(); return; }
        Location anchor = m.anchor();
        int reach = ioReach(m.tier());
        if (anchor.getWorld() == null || loc.getWorld() == null || !anchor.getWorld().getUID().equals(loc.getWorld().getUID())
                || anchor.distanceSquared(loc) > (double) reach * reach + 2) {
            p.sendMessage(smsg(lmt, "link_too_far", "§cToo far — link must be within §f" + reach + "§c blocks of the " + what + ".",
                    dev.servereer.machineconstruct.gui.MenuSkin.vars("reach", reach))); reopen.run(); return;
        }
        // Grief gate (checked once, here — the runtime sweep stays cheap): you can only link a chest you
        // may build at, so a machine can't be pointed at someone else's claimed container to drain/flood it.
        if (!canBuildAt(p, loc)) { p.sendMessage(smsg(lmt, "link_no_build", "§cYou can't link a chest you don't have build access to there.")); reopen.run(); return; }
        List<ChestLink> links = linksOf(m);
        for (ChestLink l : links) if (l.at(loc)) { p.sendMessage(smsg(lmt, "link_exists", "§eThat chest is already linked.")); reopen.run(); return; }
        // OUTPUT links start rate-limited (10/sec) so a full vault/pool can't instantly flood the chest
        // before the player sets a rate or filter; INPUT defaults to full (vault cap already bounds it).
        long defRate = (type == ChestLink.Type.OUTPUT) ? DEFAULT_OUTPUT_RATE : 0;
        links.add(new ChestLink(type, loc, new java.util.HashSet<>(), defRate));
        String logLine = (type == ChestLink.Type.OUTPUT ? "Linked OUTPUT" : "Linked INPUT") + " chest @ "
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + " (" + p.getName() + ")";
        if (collector) m.chunkCollector().logEvent(logLine);
        else if (!factory) m.grinder().logEvent(logLine);
        persistStore(m);
        p.sendMessage(smsg(lmt, type == ChestLink.Type.OUTPUT ? "linked_out" : "linked_in",
                "§aLinked " + (type == ChestLink.Type.OUTPUT ? "§aOUTPUT" : "§bINPUT") + "§a chest."));
        reopen.run();
    }

    /** Parse a chat number for a pending auto-sell rate prompt. */
    @EventHandler(ignoreCancelled = true)
    public void onGrinderAutoSellRate(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        Object[] pr = pendingAutoSellRate.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Machine m = (Machine) pr[0];
        Material mat = (Material) pr[1];
        boolean factory = isFactoryMachine(m);
        boolean collector = isCollectorMachine(m);
        Runnable reopen = collector ? () -> reopenCollectorAutoSell(p, m)
                : (factory ? () -> reopenFactoryAutoSell(p, m) : () -> reopenAutoSell(p, m));
        String txt = e.getMessage().trim();
        if (txt.equalsIgnoreCase("cancel")) { plugin.getServer().getScheduler().runTask(plugin, reopen); return; }
        long rate;
        if (txt.equalsIgnoreCase("all") || txt.equalsIgnoreCase("full")) rate = 0;
        else { try { rate = Math.max(0, Long.parseLong(txt)); } catch (NumberFormatException ex) {
            plugin.getServer().getScheduler().runTask(plugin, () -> { p.sendMessage(msg("§cEnter a number or 'all'.")); reopen.run(); }); return; } }
        final long r = rate;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            autoSellStateOf(m).setAutoSellItem(mat, r, System.currentTimeMillis()); persistStore(m);
            p.sendMessage(msg("§aAuto-sell " + mat.name().toLowerCase() + " → " + (r <= 0 ? "§fall" : "§f" + r + "/sec") + "§a."));
            reopen.run();
        });
    }

    /** Step 2 of rate config: parse the chat number for a pending OUTPUT link. */
    @EventHandler(ignoreCancelled = true)
    public void onGrinderRatePrompt(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        Object[] pr = pendingChestRate.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Machine m = (Machine) pr[0];
        int index = (Integer) pr[1];
        boolean factory = isFactoryMachine(m);
        boolean collector = isCollectorMachine(m);
        Runnable reopen = collector ? () -> reopenCollectorChests(p, m)
                : (factory ? () -> reopenFactoryChests(p, m) : () -> reopenChests(p, m));
        String txt = e.getMessage().trim();
        if (txt.equalsIgnoreCase("cancel")) { plugin.getServer().getScheduler().runTask(plugin, reopen); return; }
        long rate;
        if (txt.equalsIgnoreCase("full")) rate = 0;
        else { try { rate = Math.max(0, Long.parseLong(txt)); } catch (NumberFormatException ex) {
            plugin.getServer().getScheduler().runTask(plugin, () -> { p.sendMessage(msg("§cEnter a number or 'full'.")); reopen.run(); }); return; } }
        final long r = rate;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<ChestLink> links = linksOf(m);
            if (index >= 0 && index < links.size()) { links.get(index).setMaxPerSec(r); persistStore(m); }
            p.sendMessage(smsg(typeOfMachine(m), r <= 0 ? "rate_set_full" : "rate_set",
                    "§aOutput rate → " + (r <= 0 ? "§ffull (everything that fits)" : "§f" + r + "/sec") + "§a.",
                    dev.servereer.machineconstruct.gui.MenuSkin.vars("rate", r)));
            reopen.run();
        });
    }

    private void reopenChests(Player p, Machine m) {
        grinderMenus.openTo(p, m, dev.servereer.machineconstruct.gui.GrinderMenuHolder.View.CHESTS);
    }

    private void reopenCollectorChests(Player p, Machine m) {
        collectorMenus.openTo(p, m, dev.servereer.machineconstruct.gui.ChunkCollectorMenuHolder.View.CHESTS);
    }

    private void reopenCollectorAutoSell(Player p, Machine m) {
        collectorMenus.openTo(p, m, dev.servereer.machineconstruct.gui.ChunkCollectorMenuHolder.View.AUTOSELL);
    }

    // --- auto-sell ----------------------------------------------------------

    @Override
    public void toggleAutoSell(Machine m) {
        var st = autoSellStateOf(m);
        st.setAutoSellEnabled(!st.autoSellEnabled());
        if (st.autoSellEnabled() && st.autoSellLastPayout() == 0) st.setAutoSellLastPayout(System.currentTimeMillis());
        persistStore(m);
    }

    @Override
    public void toggleAutoSellItem(Machine m, Material mat) {
        if (mat == null) return;
        var st = autoSellStateOf(m);
        if (st.isAutoSellItem(mat)) st.removeAutoSellItem(mat);
        else st.setAutoSellItem(mat, 0, System.currentTimeMillis());   // default: sell all of it
        persistStore(m);
    }

    @Override
    public void clearAutoSellItems(Machine m) {
        autoSellStateOf(m).clearAutoSellItems(); persistStore(m);
    }

    @Override
    public void promptAutoSellItemRate(Machine m, Player p, Material mat) {
        if (mat == null) return;
        pendingAutoSellRate.put(p.getUniqueId(), new Object[]{ m, mat });
        p.closeInventory();
        p.sendMessage(msg("§dType the max §fitems/second§d for §f" + mat.name().toLowerCase() + "§d, or §fall§d for everything. §7(type §fcancel§7 to abort)"));
    }

    private void reopenAutoSell(Player p, Machine m) {
        grinderMenus.openTo(p, m, dev.servereer.machineconstruct.gui.GrinderMenuHolder.View.AUTOSELL);
    }

    /** Auto-sell matching pool items at the configured rate; accumulate money, pay out + log once per hour. */
    private boolean autoSell(GrinderData d, MachineType type, Machine m, long now) {
        return runAutoSell(d, type == null ? null : type.pricing(), m, now);
    }

    /**
     * Shared per-item auto-sell over an {@link dev.servereer.machineconstruct.grinder.econ.AutoSellState}
     * source — the grinder pool or the district vault. Sells matching items at each rule's rate, accumulates
     * money, and pays out (one deposit + one trade-log {@code GrinderSellEvent}) once per hour.
     */
    private boolean runAutoSell(dev.servereer.machineconstruct.grinder.econ.AutoSellState st, PriceService prices, Machine m, long now) {
        if (st == null || !st.autoSellEnabled()) return false;
        EconomyBridge econ = economy();
        if (prices == null || !econ.available()) return false;
        boolean changed = false;
        Map<Material, double[]> breakdown = new java.util.HashMap<>();   // this sweep's per-material [qty, money]
        for (ItemStack tmpl : new ArrayList<>(st.autoSellSource().keySet())) {
            Material mat = tmpl.getType();
            if (!st.isAutoSellItem(mat)) continue;                     // only the items the player chose
            long have = st.autoSellSource().getOrDefault(tmpl, 0L);
            if (have <= 0) continue;
            double each = prices.price(tmpl);
            if (each <= 0) continue;                                   // unsellable → keep
            long allowance = st.autoSellItemAllowance(mat, now);       // per-item rate
            long want = st.autoSellItemFull(mat) ? have : Math.min(have, allowance);
            st.advanceAutoSellFlow(mat, now, st.autoSellItemFull(mat) ? 0 : want);  // carry leftover fraction (rate 1/sec)
            if (want <= 0) continue;
            long left = have - want;
            if (left <= 0) st.autoSellSource().remove(tmpl); else st.autoSellSource().put(tmpl, left);
            st.addAutoSellAcc(each * want, want);
            double[] a = breakdown.computeIfAbsent(mat, k -> new double[2]);
            a[0] += want; a[1] += each * want;
            changed = true;
        }
        // Record item volume as it sells (money payout stays hourly, below) — attributed to the owner.
        if (!breakdown.isEmpty() && m.owner() != null) fireMachineItemSell(m.owner(), null, true, breakdown);
        // hourly payout (one deposit + one trade-log entry per hour → no over-transactioning)
        if (st.autoSellAccMoney() > 0 && m.owner() != null && now - st.autoSellLastPayout() >= 3_600_000L) {
            double money = st.autoSellAccMoney();
            long items = st.autoSellAccItems();
            org.bukkit.OfflinePlayer owner = org.bukkit.Bukkit.getOfflinePlayer(m.owner());
            if (econ.deposit(owner, money)) {
                String oname = owner.getName() != null ? owner.getName() : m.owner().toString();
                if (st instanceof GrinderData gd) gd.logEvent("Auto-sold " + items + " items for " + fmt(money) + " (hourly payout)");
                else if (st instanceof dev.servereer.machineconstruct.collector.ChunkCollectorData cd) cd.logEvent("Auto-sold " + items + " items for " + fmt(money) + " (hourly payout)");
                org.bukkit.Bukkit.getPluginManager().callEvent(
                        new dev.servereer.machineconstruct.api.events.GrinderSellEvent(m.owner(), oname, money, items, true));
                st.resetAutoSellAcc(now);
                changed = true;
            }
        }
        return changed;
    }

    /** The auto-sell state for whichever subsystem this machine is (factory district or grinder); never null. */
    private dev.servereer.machineconstruct.grinder.econ.AutoSellState autoSellStateOf(Machine m) {
        if (isCollectorMachine(m)) {
            if (!m.hasChunkCollector()) m.setChunkCollector(new dev.servereer.machineconstruct.collector.ChunkCollectorData());
            return m.chunkCollector();
        }
        if (isFactoryMachine(m)) {
            if (!m.hasFactory()) m.setFactory(new dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData());
            return m.factory();
        }
        if (!m.hasGrinder()) m.setGrinder(new GrinderData());
        return m.grinder();
    }

    private PriceService factoryPricesFallback;

    /** The Factory District's auto-sell prices: its configured {@code economy:} prices, else a shop-only fallback. */
    private PriceService factoryPrices(MachineType type) {
        if (type != null && type.pricing() != null) return type.pricing();
        if (factoryPricesFallback == null) factoryPricesFallback = PriceService.shopOnly();
        return factoryPricesFallback;
    }

    private void reopenFactoryAutoSell(Player p, Machine m) { factoryMenus.openAutoSellView(p, m); }

    /**
     * The mob type of a spawner item. Prefers the vanilla block-state spawned type; if that's
     * unset (e.g. a player-mined spawner from EconomyShopGUI / SmartSpawner, which store the
     * type in NBT + the display name rather than the block state), falls back to parsing the
     * display name ("Zombie Spawner" → ZOMBIE, "Iron Golem Spawner" → IRON_GOLEM). Null if unknown.
     */
    private static String spawnerType(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof CreatureSpawner cs) {
            EntityType et = cs.getSpawnedType();
            if (et != null) return et.name();
        }
        return spawnerTypeFromName(displayNamePlain(meta));
    }

    /** Plain text of an item's display name (Adventure first, legacy fallback), or null. */
    private static String displayNamePlain(ItemMeta meta) {
        if (meta == null || !meta.hasDisplayName()) return null;
        try {
            net.kyori.adventure.text.Component c = meta.displayName();
            if (c != null) return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        } catch (Throwable ignored) {
            try { return org.bukkit.ChatColor.stripColor(meta.getDisplayName()); } catch (Throwable ignored2) { }
        }
        return null;
    }

    /** Best-effort "Zombie Spawner" / "Iron Golem Spawner" → EntityType name; null if no match. */
    private static String spawnerTypeFromName(String name) {
        if (name == null || name.isBlank()) return null;
        String s = name.replaceAll("(?i)spawner", " ")   // drop the word "spawner"
                       .replaceAll("[^A-Za-z ]", " ")      // drop colours / symbols / punctuation
                       .trim().replaceAll("\\s+", "_")
                       .toUpperCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return null;
        try { return EntityType.valueOf(s).name(); } catch (IllegalArgumentException ignored) { }
        return null;
    }

    /** A vanilla spawner item pre-set to the given mob type. */
    private static ItemStack makeSpawnerItem(String type, int amount) {
        ItemStack item = new ItemStack(Material.SPAWNER, Math.max(1, amount));
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof CreatureSpawner cs) {
                cs.setSpawnedType(EntityType.valueOf(type));
                bsm.setBlockState(cs);
                item.setItemMeta(bsm);
            }
        } catch (Throwable ignored) { }
        return item;
    }

    @Override
    public void upgrade(Machine m, Player p) {
        upgradeMachine(m, p);
        grinderMenus.refresh(m);
    }

    private static int storedCount(ItemStack[] out) {
        int n = 0;
        for (ItemStack s : out) if (s != null && !s.getType().isAir()) n += s.getAmount();
        return n;
    }

    /** True if the machine has burn left or a fuel item it could ignite. */
    private boolean hasFuelAvailable(Machine m, MachineType type) {
        if (m.fuelRemaining() > 0) return true;
        for (ItemStack f : m.fuel()) if (f != null && type.burnTicks(f.getType()) > 0) return true;
        return false;
    }

    /**
     * Spend one tick-step of burn, igniting a fuel item if the buffer ran dry.
     * Returns false if there's no burn left and nothing to ignite.
     */
    private boolean burnTick(Machine m, MachineType type) {
        if (m.fuelRemaining() <= 0) {
            int ticks = igniteOneFuel(m, type);
            if (ticks <= 0) return false;
            m.setFuelRemaining(ticks);
            m.setFuelMax(ticks);
            persistStore(m);   // a fuel item was consumed
        }
        m.setFuelRemaining(m.fuelRemaining() - processTicks);
        return true;
    }

    /** Consume one fuel item from the fuel buffer; returns its burn ticks, or 0 if none. */
    private int igniteOneFuel(Machine m, MachineType type) {
        ItemStack[] fuel = m.fuel();
        for (int i = 0; i < fuel.length; i++) {
            ItemStack f = fuel[i];
            if (f == null) continue;
            int ticks = type.burnTicks(f.getType());
            if (ticks > 0) {
                if (f.getAmount() <= 1) fuel[i] = null;
                else f.setAmount(f.getAmount() - 1);
                return ticks;
            }
        }
        return 0;
    }

    /**
     * Deliver one cycle's output (consuming inputs if any). If the machine can
     * immediately run again it stays WORKING with a fresh progress bar — so a
     * generator's rate is exact, not stalled a tick between cycles. Otherwise it
     * settles to BLOCKED (no room / at capacity) or IDLE.
     */
    private void produce(Machine m, MachineType type, Recipe r, ItemStack[] in, ItemStack[] out) {
        if (!hasInputs(in, r.inputs())) {        // inputs vanished
            m.setActive(null);
            m.setState(MachineState.IDLE);
            menus.refresh(m);
            return;
        }
        List<ItemStack> outs = outputsFor(type, r, m.tier());
        if (!fits(out, outs) || atCapacity(m, type)) {   // nowhere to put it / full
            if (m.state() != MachineState.BLOCKED) { m.setState(MachineState.BLOCKED); menus.refresh(m); }
            return;
        }
        consume(in, r.inputs());
        deposit(out, outs);
        if (canRun(m, type, r)) {                // keep running (generator loop / chained craft)
            m.setProgress(0);
            m.setState(MachineState.WORKING);
        } else {
            m.setActive(null);
            m.setProgress(0);
            m.setState(MachineState.IDLE);
        }
        persistStore(m);    // items changed → save (crash-safe)
        menus.refresh(m);
    }

    private boolean hasInputs(ItemStack[] store, List<ItemStack> need) {
        for (ItemStack req : need) {
            int have = 0;
            for (ItemStack s : store) if (s != null && s.isSimilar(req)) have += s.getAmount();
            if (have < req.getAmount()) return false;
        }
        return true;
    }

    /** Remove the required inputs from the input array (across slots). */
    private void consume(ItemStack[] store, List<ItemStack> need) {
        for (ItemStack req : need) {
            int left = req.getAmount();
            for (int i = 0; i < store.length && left > 0; i++) {
                ItemStack s = store[i];
                if (s != null && s.isSimilar(req)) {
                    int take = Math.min(s.getAmount(), left);
                    left -= take;
                    if (s.getAmount() - take <= 0) store[i] = null;
                    else s.setAmount(s.getAmount() - take);
                }
            }
        }
    }

    /** True if {@code outputs} fit in the output array (pure simulation). */
    private boolean fits(ItemStack[] store, List<ItemStack> outputs) {
        ItemStack[] sim = new ItemStack[store.length];
        for (int i = 0; i < store.length; i++) sim[i] = (store[i] == null) ? null : store[i].clone();
        return deposit(sim, outputs);
    }

    /** Add outputs into the array (merge then fill). Returns false (and leaves partial) if it overflows. */
    private boolean deposit(ItemStack[] store, List<ItemStack> outputs) {
        for (ItemStack out : outputs) {
            int remaining = out.getAmount();
            int max = out.getMaxStackSize();
            for (int i = 0; i < store.length && remaining > 0; i++) {
                ItemStack s = store[i];
                if (s != null && s.isSimilar(out) && s.getAmount() < max) {
                    int add = Math.min(max - s.getAmount(), remaining);
                    s.setAmount(s.getAmount() + add);
                    remaining -= add;
                }
            }
            for (int i = 0; i < store.length && remaining > 0; i++) {
                if (store[i] == null || store[i].getType().isAir()) {
                    int add = Math.min(max, remaining);
                    ItemStack slot = out.clone();
                    slot.setAmount(add);
                    store[i] = slot;
                    remaining -= add;
                }
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    // --- placement / teardown ----------------------------------------------

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY && e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;   // a button press (ButtonManager) — not a machine click
        Player player = e.getPlayer();

        // 1) Holding a placer → build a machine.
        String type = Placer.typeOf(placerKey, e.getItem());
        if (type != null) {
            e.setCancelled(true);   // never let the head place as a vanilla block
            Block target = e.getClickedBlock().getRelative(e.getBlockFace());
            if (!target.getType().isAir()) {
                player.sendMessage(msg("§cNo space there."));
                return;
            }
            if (!player.hasPermission("machineconstruct.place")) {
                player.sendMessage(msg("§cYou don't have permission to place machines."));
                return;
            }
            if (!canBuild(player, target, e.getClickedBlock(), e.getItem(), e.getHand())) {
                player.sendMessage(msg("§cYou can't build here."));   // GriefPrevention / WorldGuard / etc.
                return;
            }
            if (!withinPlacementLimit(player, type)) {
                player.sendMessage(msg("§cYou can only have §f" + placementLimit(type) + "§c of that machine."));
                return;
            }
            place(player, type, target, e.getItem());
            if (player.getGameMode() != GameMode.CREATIVE) {
                ItemStack hand = e.getItem();
                if (hand != null) hand.setAmount(hand.getAmount() - 1);
            }
            player.sendMessage(msg("§aBuilt §f" + type + "§a machine."));
            return;
        }

        // 2) Right-clicking an existing machine chest → open its sealed GUI.
        //    The vanilla chest never opens (items live in the machine, not the chest).
        Machine m = byAnchor.get(key(e.getClickedBlock().getLocation()));
        if (m != null) {
            // Respect a protection plugin's container/use denial (GP/WG ran at lower priority).
            if (e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
            e.setCancelled(true);
            if (!player.hasPermission("machineconstruct.use")) {
                player.sendMessage(msg("§cYou don't have permission to use machines."));
                return;
            }
            MachineType mt = registry == null ? null : registry.getMachineType(m.typeId());
            if (mt == null) return;
            // Private economy machines (grinder/quarry/chunk-collector/factory) hold the owner's loot pool,
            // vault and auto-sell config. Only the machine owner, an admin, or someone the protection plugin
            // trusts on the claim (GriefPrevention claim owner / trusted) may open them. Trading halls are
            // public shopfronts and jukeboxes are shared, so those stay open to everyone.
            if ((mt.isGrinder() || mt.isQuarry() || mt.isChunkCollector() || mt.isFactory())
                    && !canOpenMachine(player, e.getClickedBlock(), m)) {
                player.sendMessage(msg("§cYou don't have access to this machine."));
                return;
            }
            if (mt.isGrinder()) {
                // Holding a typed spawner (and not sneaking) → install it straight onto the grinder.
                String st2 = spawnerType(e.getItem());
                if (st2 != null && !player.isSneaking()) {
                    if (!m.hasGrinder()) m.setGrinder(new GrinderData());
                    LootTable lt = mt.loot();
                    if (lt == null || !lt.has(st2)) { player.sendMessage(smsg(mt, "no_loot_table", "§cThat spawner has no loot table here.",
                            dev.servereer.machineconstruct.gui.MenuSkin.vars("mob", LootTable.pretty(st2)))); return; }
                    long added = m.grinder().install(mt.grinder(), m.tier(), st2, 1, System.currentTimeMillis());
                    if (added <= 0) { player.sendMessage(smsg(mt, "full", "§cGrinder is full — upgrade it for more slots.")); return; }
                    ItemStack hand = e.getItem();
                    if (player.getGameMode() != GameMode.CREATIVE && hand != null) hand.setAmount(hand.getAmount() - 1);
                    m.grinder().logEvent("Installed " + LootTable.pretty(st2) + " spawner (" + player.getName() + ")");
                    persistStore(m);
                    grinderMenus.refresh(m);
                    player.sendMessage(smsg(mt, "installed", "§aInstalled §f" + LootTable.pretty(st2) + "§a spawner into the grinder.",
                            dev.servereer.machineconstruct.gui.MenuSkin.vars("mob", LootTable.pretty(st2))));
                    return;
                }
                openGrinder(player, m, mt); return;
            }
            if (mt.isQuarry()) { if (!m.hasQuarry()) m.setQuarry(new QuarryData()); quarryMenus.open(player, m); return; }
            if (mt.isTradingHall()) { if (!m.hasTradingHall()) m.setTradingHall(new dev.servereer.machineconstruct.tradinghall.TradingHallData()); tradingHallMenus.open(player, m); return; }
            if (mt.isFactory()) { if (!m.hasFactory()) m.setFactory(new dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData()); factoryMenus.open(player, m); return; }
            if (mt.isChunkCollector()) { if (!m.hasChunkCollector()) m.setChunkCollector(new dev.servereer.machineconstruct.collector.ChunkCollectorData()); collectorMenus.open(player, m); return; }
            if (mt.isPanel()) {   // display-only: admins get the text/theme editor, everyone else just refreshes it
                if (player.hasPermission("machineconstruct.admin")) panelEditor.open(player, m); else panelResolve(m, false);
                return;
            }
            if (mt.isJukebox()) {
                if (!m.hasJukebox()) m.setJukebox(new dev.servereer.machineconstruct.jukebox.JukeboxData());
                jukeboxMenus.open(player, m);
                return;
            }
            if (mt.isGacha()) {   // no player GUI: the lever/dial is the interface. A parked capsule opens for its puller; admins get the loading menu.
                // a crane in play: any click sends the claw down (claw.drop_on_click)
                if (mt.gacha().isClaw() && claw.busy(m)) {
                    if (mt.gacha().claw().dropOnClick()) claw.drop(player, m);
                    return;
                }
                if (gacha.tryOpen(player, m, mt)) return;
                if (player.hasPermission("machineconstruct.admin") && !player.isSneaking()) gachaMenus.open(player, m);
                else gacha.hint(player, m, mt);
                return;
            }
            if (mt.guiFor(m.tier()) != null) menus.open(player, m, mt, mt.guiFor(m.tier()));
        }
    }

    /**
     * Whether {@code player} may build at {@code target}, honouring any protection
     * plugin (GriefPrevention, WorldGuard, Towny, …): we fire a probe
     * {@link BlockPlaceEvent} — those plugins veto it exactly as for a real place —
     * and respect the verdict. No hard dependency on any one of them.
     */
    private boolean canBuild(Player player, Block target, Block against, ItemStack hand, org.bukkit.inventory.EquipmentSlot slot) {
        ItemStack inHand = (hand != null) ? hand : new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.event.block.BlockPlaceEvent probe = new org.bukkit.event.block.BlockPlaceEvent(
                target, target.getState(), against, inHand, player, true,
                slot != null ? slot : org.bukkit.inventory.EquipmentSlot.HAND);
        plugin.getServer().getPluginManager().callEvent(probe);
        return !probe.isCancelled() && probe.canBuild();
    }

    /** Optionally forbid placing spawner blocks in the world (s5: spawners must go into a grinder). */
    @EventHandler(ignoreCancelled = true)
    public void onSpawnerPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
        if (!plugin.getConfig().getBoolean("block-spawner-placement", false)) return;
        if (e.getPlayer().hasPermission("machineconstruct.spawnerplace.bypass")) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(smsg(anyGrinderType(), "spawner_place_blocked", "§cSpawners can't be placed here — put them in a §dDimensional Grinder§c instead."));
    }

    private boolean breakProbe;   // re-entrancy guard: ignore our own grief-probe BlockBreakEvent in onBreak

    // ignoreCancelled: if GriefPrevention/WorldGuard already vetoed the break, we don't tear down.
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (breakProbe) return;   // this is our own grief probe (see canBreakAt) — don't act on it
        Block block = e.getBlock();
        // Identify by the PDC tag, not the block type — anchors can be chest/barrel/…
        BlockState st = block.getState();
        if (st instanceof TileState tile) {
            String tag = tile.getPersistentDataContainer().get(machineKey, PersistentDataType.STRING);
            if (tag == null) return;
            String[] parts = tag.split("\\|");   // id|type|owner
            String type = parts.length > 1 ? parts[1] : "?";
            UUID owner = parts.length > 2 ? tryUuid(parts[2]) : null;
            if (!breakAllowed(e.getPlayer(), owner)) { e.setCancelled(true); return; }
            if (refuseUnsealableBreak(e, e.getPlayer(), type, byAnchor.get(key(block.getLocation())))) return;
            Machine m = byAnchor.remove(key(block.getLocation()));
            backupSnapshot(m);       // safety net: capture the machine's data before teardown
            World w = block.getWorld();
            Location drop = block.getLocation().add(0.5, 0.5, 0.5);
            ItemStack placer = teardownToPlacer(m, type, w, drop);
            e.setDropItems(false);   // no chest drop (items are sealed in the machine)
            w.dropItemNaturally(drop, placer);
            e.getPlayer().sendMessage(msg("§eRemoved §f" + type + "§e machine."));
            return;
        }
        // Non-TileState anchor broken directly (e.g. a creative player smashing the BARRIER) → tear down + drop.
        Machine m = byAnchor.get(key(block.getLocation()));
        if (m == null) return;
        if (!breakAllowed(e.getPlayer(), m.owner())) { e.setCancelled(true); return; }
        if (refuseUnsealableBreak(e, e.getPlayer(), m.typeId(), m)) return;
        backupSnapshot(m);       // safety net before teardown
        byAnchor.remove(key(block.getLocation()));
        World w = block.getWorld();
        Location drop = block.getLocation().add(0.5, 0.5, 0.5);
        ItemStack placer = teardownToPlacer(m, m.typeId(), w, drop);
        externalStore.remove(key(block.getLocation()));
        externalStore.flush();
        e.setDropItems(false);
        w.dropItemNaturally(drop, placer);
        e.getPlayer().sendMessage(msg("§eRemoved §f" + m.typeId() + "§e machine."));
    }

    /** Break permission + ownership check (messages the player on denial). */
    private boolean breakAllowed(Player player, UUID owner) {
        if (!player.hasPermission("machineconstruct.break")) {
            player.sendMessage(msg("§cYou don't have permission to break machines.")); return false;
        }
        if (owner != null && !owner.equals(player.getUniqueId()) && !player.hasPermission("machineconstruct.admin")) {
            player.sendMessage(msg("§cThis machine isn't yours.")); return false;
        }
        return true;
    }

    /**
     * May {@code player} open this private machine's GUI? The machine owner and admins always may; anyone
     * else may only if the protection plugin trusts them on the claim (GriefPrevention: claim owner or a
     * trusted player). If there's no claim there — or GriefPrevention isn't installed — only the owner/admin
     * qualifies, so a non-owner can't reach into someone else's machine.
     */
    private boolean canOpenMachine(Player p, Block anchor, Machine m) {
        if (p.hasPermission("machineconstruct.admin")) return true;
        if (m.owner() == null || m.owner().equals(p.getUniqueId())) return true;
        return Boolean.TRUE.equals(griefTrust(p, anchor.getLocation()));   // non-owner: needs claim trust
    }

    // Soft GriefPrevention integration (no compile dependency): TRUE = trusted on the claim here,
    // FALSE = there is a claim but the player isn't trusted, null = no claim / GP absent / unknown.
    private volatile boolean gpResolved;
    private volatile Object gpDataStore;
    private java.lang.reflect.Method mGetClaimAt, mCheckPerm, mAllowContainers;
    private java.lang.reflect.Method mCheckPermUuid, mGetOwnerId;   // offline (owner-UUID) build check
    private Object permInventory, permBuild;

    private Boolean griefTrust(Player p, Location loc) {
        if (plugin.getServer().getPluginManager().getPlugin("GriefPrevention") == null) return null;
        try {
            if (!gpResolved) resolveGp();
            if (gpDataStore == null || mGetClaimAt == null) return null;
            Object claim = mGetClaimAt.invoke(gpDataStore, loc, true, null);
            if (claim == null) return null;   // no claim here → non-owner can't open
            if (mCheckPerm != null && permInventory != null) return mCheckPerm.invoke(claim, p, permInventory, null) == null;
            if (mAllowContainers != null) return mAllowContainers.invoke(claim, p) == null;
            return null;
        } catch (Throwable t) { return null; }
    }

    /**
     * Offline analogue of {@link #griefTrust}: may the owner (identified by UUID, no online Player needed)
     * build at {@code loc}? TRUE = yes (owner or trusted on the claim), FALSE = a claim exists and the owner
     * isn't trusted, null = no claim / GP unresolved (caller treats null as "allow"). Lets the chunk collector
     * keep respecting claims while the owner is offline instead of pausing.
     */
    private Boolean griefBuildTrustOffline(UUID ownerId, Location loc) {
        try {
            if (!gpResolved) resolveGp();
            if (gpDataStore == null || mGetClaimAt == null) return null;
            Object claim = mGetClaimAt.invoke(gpDataStore, loc, true, null);
            if (claim == null) return null;   // unclaimed → allow
            if (mCheckPermUuid != null && permBuild != null)
                return mCheckPermUuid.invoke(claim, ownerId, permBuild, null) == null;
            if (mGetOwnerId != null)          // fallback: only the owner's own claims
                return ownerId.equals(mGetOwnerId.invoke(claim));
            return null;
        } catch (Throwable t) { return null; }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void resolveGp() {
        try {
            Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object gp = gpClass.getField("instance").get(null);
            gpDataStore = gpClass.getField("dataStore").get(gp);
            Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim");
            mGetClaimAt = gpDataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, claimClass);
            try {   // modern GP: Claim.checkPermission(Player, ClaimPermission.Inventory, Event) -> Supplier<String> (null = allowed)
                Class<?> permClass = Class.forName("me.ryanhamshire.GriefPrevention.ClaimPermission");
                permInventory = Enum.valueOf((Class) permClass, "Inventory");
                mCheckPerm = claimClass.getMethod("checkPermission", Player.class, permClass, org.bukkit.event.Event.class);
            } catch (Throwable older) {   // older GP: Claim.allowContainers(Player) -> String (null = allowed)
                try { mAllowContainers = claimClass.getMethod("allowContainers", Player.class); } catch (Throwable ignore) { }
            }
            try {   // offline (owner-UUID) build check — lets the chunk collector respect claims while the owner is offline
                Class<?> permClass = Class.forName("me.ryanhamshire.GriefPrevention.ClaimPermission");
                permBuild = Enum.valueOf((Class) permClass, "Build");
                mCheckPermUuid = claimClass.getMethod("checkPermission", UUID.class, permClass, org.bukkit.event.Event.class);
            } catch (Throwable noUuidApi) {   // fallback: only the owner's own claims (Claim.getOwnerID())
                try { mGetOwnerId = claimClass.getMethod("getOwnerID"); } catch (Throwable ignore) { }
            }
        } catch (Throwable t) {
            gpDataStore = null;
            plugin.getLogger().info("[machine-access] GriefPrevention present but API not resolved — machines fall back to owner-only.");
        }
        gpResolved = true;
    }

    /**
     * Serialized machine-state ceiling (chars) we'll pack into a placer head's PDC. Beyond this the item's NBT
     * exceeds Minecraft's per-item/packet limits and crash-kicks any client it's transmitted to (the "break the
     * machine → get disconnected → placer comes back empty" bug). Player-initiated breaks are refused above this
     * (see {@link #unsafeToSeal}); non-cancellable teardowns (AoE/orphan) scatter the contents instead.
     */
    private static final int MAX_SEAL_CHARS = 30_000;

    /**
     * If this machine's contents can't be safely sealed into a portable placer head, a short human reason
     * (shown to the player); otherwise null. Catches the two things players weaponize — books (huge, unique,
     * non-stacking NBT) and nested machines — plus a generic total-size backstop for anything else.
     */
    private String unsafeToSeal(Machine m) {
        if (m == null) return null;
        for (ItemStack it : m.inputs())  { String r = sealOffender(it); if (r != null) return r; }
        for (ItemStack it : m.outputs()) { String r = sealOffender(it); if (r != null) return r; }
        for (ItemStack it : m.fuel())    { String r = sealOffender(it); if (r != null) return r; }
        for (ItemStack it : m.upgrade()) { String r = sealOffender(it); if (r != null) return r; }
        if (m.hasGrinder())        for (ItemStack it : m.grinder().pool().keySet())        { String r = sealOffender(it); if (r != null) return r; }
        if (m.hasQuarry())         for (ItemStack it : m.quarry().vault().keySet())         { String r = sealOffender(it); if (r != null) return r; }
        if (m.hasChunkCollector()) for (ItemStack it : m.chunkCollector().vault().keySet()) { String r = sealOffender(it); if (r != null) return r; }
        try {
            int chars = MachineStore.serialize(m).length();
            if (m.hasGrinder()) chars += dev.servereer.machineconstruct.grinder.GrinderStore.serialize(m.grinder()).length();
            if (m.hasQuarry()) chars += dev.servereer.machineconstruct.quarry.QuarryStore.serialize(m.quarry()).length();
            if (m.hasChunkCollector()) chars += dev.servereer.machineconstruct.collector.ChunkCollectorStore.serialize(m.chunkCollector()).length();
            if (chars > MAX_SEAL_CHARS) return "it holds too much data to pack into a portable head";
        } catch (Throwable ignored) { }
        return null;
    }

    /** The reason a single item can't ride a placer head (book / nested machine), or null if it's fine. */
    private String sealOffender(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        Material t = it.getType();
        if (t == Material.ENCHANTED_BOOK || t == Material.WRITTEN_BOOK || t == Material.WRITABLE_BOOK)
            return "it contains books";
        if (it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(placerKey, PersistentDataType.STRING))
            return "it contains another machine";
        return null;
    }

    /** Cancel a player-initiated break when the machine can't be sealed; messages the player + returns true. */
    private boolean refuseUnsealableBreak(org.bukkit.event.Cancellable e, Player p, String type, Machine m) {
        String reason = unsafeToSeal(m);
        if (reason == null) return false;
        e.setCancelled(true);
        p.sendMessage(msg("§cCan't remove this §f" + type + "§c — " + reason
                + ". §7Empty it first (Collect All / Sell), then break it."));
        return true;
    }

    /** Unregister displays + close menus, then build the state-carrying placer (fallback: scatter + plain placer). */
    private ItemStack teardownToPlacer(Machine m, String type, World w, Location drop) {
        ItemStack placer = makePlacer(type);   // fallback: a plain placer
        if (m == null) return placer;
        { MachineType tt = typeOfMachine(m); if (tt != null && tt.isPanel()) panelIndexDirty = true; }
        for (PacketDisplay d : m.displays()) tracker.unregister(d);
        claw.forget(m); gacha.forget(m);
        menus.closeFor(m);
        grinderMenus.closeFor(m);
        quarryMenus.closeFor(m);
        collectorMenus.closeFor(m);
        jukeboxMenus.closeFor(m);
        if (m.hasJukebox()) { m.jukebox().setPlaying(false); musicPlayer.stop("jukebox:" + key(m.anchor())); }
        try {
            // Non-cancellable teardown (AoE break/orphan recovery): if the contents can't safely ride a placer
            // head, scatter them as real stacks rather than mint a client-crashing item.
            if (unsafeToSeal(m) != null) throw new IllegalStateException("contents unsealable — scattering instead of crash-head");
            // Lossless: the whole machine (tier + items + behaviour state) rides the placer.
            placer = makePlacerWithState(type, m);
        } catch (Throwable t) {
            plugin.getLogger().warning("[MachineConstruct] couldn't capture state into placer @ "
                    + key(drop) + " — scattering contents: " + t.getMessage());
            for (ItemStack it : m.inputs()) if (it != null && !it.getType().isAir()) w.dropItemNaturally(drop, it);
            for (ItemStack it : m.outputs()) if (it != null && !it.getType().isAir()) w.dropItemNaturally(drop, it);
            for (ItemStack it : m.fuel()) if (it != null && !it.getType().isAir()) w.dropItemNaturally(drop, it);
            for (ItemStack it : m.upgrade()) if (it != null && !it.getType().isAir()) w.dropItemNaturally(drop, it);
            if (m.hasGrinder()) dropPool(w, drop, m.grinder());
            if (m.hasQuarry()) dropQuarry(w, drop, m.quarry());
            if (m.hasChunkCollector()) dropCollectorVault(w, drop, m.chunkCollector());
            placer = makePlacer(type);
        }
        return placer;
    }

    /**
     * Survival break for non-TileState anchors (BARRIER): sneak + left-click. Barriers are
     * unbreakable in survival (no BlockBreakEvent), so we detect the punch ourselves, run the
     * same permission + grief checks, and tear the machine down.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onLeftClickBreak(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getState() instanceof TileState) return;        // TileState anchors break via BlockBreakEvent
        Machine m = byAnchor.get(key(b.getLocation()));
        if (m == null) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (!p.isSneaking()) { p.sendMessage(msg("§7Sneak + left-click to remove this machine.")); return; }
        if (!breakAllowed(p, m.owner())) return;
        if (!canBreakAt(p, b)) { p.sendMessage(msg("§cYou can't break here.")); return; }   // grief-plugin veto
        if (refuseUnsealableBreak(e, p, m.typeId(), m)) return;   // event already cancelled above; just message + bail
        removeMachineExternal(m, b, p);
    }

    /** Grief probe: fire a BlockBreakEvent at the block and respect any protection plugin's veto. */
    private boolean canBreakAt(Player p, Block b) {
        breakProbe = true;
        try {
            org.bukkit.event.block.BlockBreakEvent probe = new org.bukkit.event.block.BlockBreakEvent(b, p);
            plugin.getServer().getPluginManager().callEvent(probe);
            return !probe.isCancelled();
        } finally {
            breakProbe = false;
        }
    }

    /** Tear down a non-TileState-anchored machine: drop the state-carrying placer, clear the anchor + flatfile. */
    private void removeMachineExternal(Machine m, Block block, Player player) {
        backupSnapshot(m);       // safety net before tearing down a barrier-anchored machine
        byAnchor.remove(key(block.getLocation()));
        World w = block.getWorld();
        Location drop = block.getLocation().add(0.5, 0.5, 0.5);
        String type = m.typeId();
        ItemStack placer = teardownToPlacer(m, type, w, drop);
        externalStore.remove(key(block.getLocation()));
        externalStore.flush();
        block.setType(Material.AIR);
        w.dropItemNaturally(drop, placer);
        player.sendMessage(msg("§eRemoved §f" + type + "§e machine."));
    }

    // --- chunk-load re-registration (chest tag = source of truth) ----------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        scanChunk(e.getChunk());
    }

    private void scanLoadedChunks() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) scanChunk(c);
        }
    }

    private void scanChunk(Chunk c) {
        for (BlockState st : c.getTileEntities()) {
            if (!(st instanceof TileState tile)) continue;
            String tag = tile.getPersistentDataContainer().get(machineKey, PersistentDataType.STRING);
            if (tag == null) continue;
            String k = key(st.getLocation());
            if (byAnchor.containsKey(k)) continue;
            String[] parts = tag.split("\\|");
            UUID id = parts.length > 0 ? tryUuid(parts[0]) : UUID.randomUUID();
            String type = parts.length > 1 ? parts[1] : "?";
            UUID owner = parts.length > 2 ? tryUuid(parts[2]) : null;
            Machine scanned = new Machine(id != null ? id : UUID.randomUUID(), type, st.getLocation(), owner);
            if (parts.length > 3) { Integer f = tryInt(parts[3]); if (f != null) scanned.setFacing(f); }
            register(scanned);
        }
        // External (non-TileState anchor, e.g. BARRIER) machines recorded in the flatfile.
        for (String lk : externalStore.keys()) {
            if (byAnchor.containsKey(lk)) continue;
            String w = externalStore.get(lk, "world");
            if (w == null || !w.equals(c.getWorld().getName())) continue;
            Integer x = tryInt(externalStore.get(lk, "x")), y = tryInt(externalStore.get(lk, "y")), z = tryInt(externalStore.get(lk, "z"));
            if (x == null || y == null || z == null) continue;
            if ((x >> 4) != c.getX() || (z >> 4) != c.getZ()) continue;
            String tag = externalStore.get(lk, "tag");
            if (tag == null) continue;
            String[] tp = tag.split("\\|");
            UUID id = tp.length > 0 ? tryUuid(tp[0]) : UUID.randomUUID();
            String type = tp.length > 1 ? tp[1] : "?";
            UUID owner = tp.length > 2 ? tryUuid(tp[2]) : null;
            Machine ext = new Machine(id != null ? id : UUID.randomUUID(), type, new Location(c.getWorld(), x, y, z), owner);
            if (tp.length > 3) { Integer f = tryInt(tp[3]); if (f != null) ext.setFacing(f); }
            register(ext);
        }
    }

    private static Integer tryInt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    // --- core register / place ---------------------------------------------

    private void place(Player player, String type, Block target, ItemStack placer) {
        MachineType mt = registry == null ? null : registry.getMachineType(type);
        Material anchor = (mt == null) ? Material.CHEST : mt.anchorMaterial();
        target.setType(anchor);
        if (target.getBlockData() instanceof Chest chest) {
            chest.setType(Chest.Type.SINGLE);   // never pair into a double chest
            target.setBlockData(chest);
        }
        UUID id = UUID.randomUUID();
        // facing: player → snap the model's yaw (45° steps, so diagonals work) so its authored front (−Z) points at the placer.
        int facing = 0;
        if (mt != null && mt.facesPlacer()) {
            double dx = player.getLocation().getX() - (target.getX() + 0.5);
            double dz = player.getLocation().getZ() - (target.getZ() + 0.5);
            // authored front is −Z; yaw θ (about +Y, [Y,X,Z] euler) sends −Z to (−sinθ, 0, −cosθ)
            double want = Math.toDegrees(Math.atan2(-dx, -dz));
            facing = ((int) Math.round(want / 45.0) * 45 % 360 + 360) % 360;
            facing = (facing + plugin.getConfig().getInt("facing-yaw-offset", 0) + 360) % 360;   // config escape hatch if a server ever finds it turned
        }
        String tagStr = id + "|" + type + "|" + player.getUniqueId() + "|" + facing;
        BlockState st = target.getState();
        if (st instanceof TileState tile) {
            tile.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, tagStr);
            copyPlacerState(placer, tile);   // restore carried state before register() rehydrates
            tile.update();
        } else {
            // Anchor has no block-entity (e.g. BARRIER) → record identity + carried state in the flatfile.
            String lk = key(target.getLocation());
            externalStore.set(lk, "world", target.getWorld().getName());
            externalStore.set(lk, "x", String.valueOf(target.getX()));
            externalStore.set(lk, "y", String.valueOf(target.getY()));
            externalStore.set(lk, "z", String.valueOf(target.getZ()));
            externalStore.set(lk, "tag", tagStr);
            copyPlacerStateExternal(placer, lk);   // restore carried state before register() rehydrates
            externalStore.flush();
        }
        Machine placed = new Machine(id, type, target.getLocation(), player.getUniqueId());
        placed.setFacing(facing);
        register(placed);
    }

    private void register(Machine m) {
        byAnchor.put(key(m.anchor()), m);
        faulted.remove(m.id());   // fresh registration → give it another chance
        initAndLoadStore(m);
        renderMachine(m);
        MachineType rt = typeOfMachine(m);
        if (rt != null && rt.isPanel()) panelIndexDirty = true;
    }

    // --- panels.yml (Dev's Diary Panels Studio) -------------------------------

    private void flushPanelIndex() {
        if (!panelIndexDirty) return;
        panelIndexDirty = false;
        panelIndex.write(byAnchor.values(), this::typeOfMachine);
    }

    /** {@code /mc panels apply}: read panels.yml and apply each known id's theme / color / facing / texts. */
    public String applyPanelIndex() {
        Map<UUID, Machine> byId = new HashMap<>();
        for (Machine m : byAnchor.values()) byId.put(m.id(), m);
        int applied = 0, unknown = 0;
        for (PanelIndex.Entry e : panelIndex.read()) {
            UUID id = tryUuid(e.id());
            Machine m = id == null ? null : byId.get(id);
            MachineType t = m == null ? null : typeOfMachine(m);
            if (m == null || t == null || !t.isPanel()) { unknown++; continue; }
            if (m.panel() == null) m.setPanel(new PanelData());
            PanelData pd = m.panel();
            pd.setTheme(t.panelThemeModels().containsKey(e.theme()) ? e.theme() : null);
            pd.setColor(e.color().matches("#[0-9a-fA-F]{6}") ? e.color() : null);
            pd.setState(t.panelStates().containsKey(e.state()) ? e.state() : null);
            pd.texts().clear();
            for (Map.Entry<String, List<String>> te : e.texts().entrySet()) pd.setLines(te.getKey(), te.getValue());
            pd.layouts().clear();
            for (Map.Entry<String, PanelData.TextLayout> le : e.layouts().entrySet()) pd.setLayout(le.getKey(), le.getValue());
            pd.pages().clear();
            if (t.panelPaged()) { pd.pages().addAll(e.pages()); pd.setPage(e.page() == null ? 0 : Math.max(0, e.page() - 1)); }
            boolean rerender = false;
            if (e.facing() != null) {
                int f = ((e.facing() % 360) + 360) % 360;
                if (f != m.facing()) { m.setFacing(f); rewriteFacingTag(m); rerender = true; }
            }
            persistStore(m);
            if (rerender) renderMachine(m); else { renderMachine(m); }   // theme may have changed the model → always rebuild
            applied++;
        }
        panelIndexDirty = true;
        return "§aApplied §f" + applied + "§a panel(s) from panels.yml" + (unknown > 0 ? " §7(" + unknown + " unknown id(s) skipped)" : "") + "§a.";
    }

    /** Facing lives in the identity tag (id|type|owner|yaw) — rewrite it after a facing change. */
    private void rewriteFacingTag(Machine m) {
        String tag = m.id() + "|" + m.typeId() + "|" + (m.owner() == null ? "" : m.owner()) + "|" + m.facing();
        BlockState st = m.anchor().getBlock().getState();
        if (st instanceof TileState tile) {
            tile.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, tag);
            tile.update();
        } else {
            externalStore.set(key(m.anchor()), "tag", tag);
        }
    }

    /** Placed panels as the engine sees them, for chat/listing. */
    public String debugButtons(Player p) { return buttons.debug(p); }

    public String listPanels() {
        StringBuilder sb = new StringBuilder("§7Placed panels:");
        int n = 0;
        for (Machine m : byAnchor.values()) {
            MachineType t = typeOfMachine(m);
            if (t == null || !t.isPanel()) continue;
            Location a = m.anchor();
            sb.append("\n §f").append(m.id().toString(), 0, 8).append(" §7").append(m.typeId())
              .append(" §8@ §f").append(a.getBlockX()).append(' ').append(a.getBlockY()).append(' ').append(a.getBlockZ())
              .append(" §8facing §f").append(m.facing())
              .append(" §8theme §f").append(m.panel() == null || m.panel().theme() == null ? t.panelTheme() : m.panel().theme())
              .append(t.panelStates().isEmpty() ? "" : " §8state §f" + panelState(m, t));
            n++;
        }
        if (n == 0) sb.append(" §8none");
        return sb.toString();
    }

    /**
     * Size the machine's I/O + fuel buffers from the largest tier layout, then load saved state — RESILIENTLY.
     * A corrupt/unreadable blob QUARANTINES the machine (faulted) with its saved blob PRESERVED, instead of
     * silently loading empty and letting a later save overwrite the good data (the "full reset" bug). A clean
     * load snapshots the machine into the backup history.
     */
    private void initAndLoadStore(Machine m) {
        MachineType type = registry == null ? null : registry.getMachineType(m.typeId());
        int in = type == null ? 0 : type.maxSlots(GuiLayout::inputCount);
        int out = type == null ? 0 : type.maxSlots(GuiLayout::outputCount);
        int fuelSlots = type == null ? 0 : type.maxSlots(GuiLayout::fuelCount);
        int upgSlots = type == null ? 0 : type.maxSlots(GuiLayout::upgradeCount);
        m.initStore(in, out, fuelSlots, upgSlots);

        BlockState st = m.anchor().getBlock().getState();
        boolean tile = st instanceof TileState;
        org.bukkit.persistence.PersistentDataContainer pdc = tile ? ((TileState) st).getPersistentDataContainer() : null;
        String lk = tile ? null : key(m.anchor());

        java.util.List<String> failed = new java.util.ArrayList<>();
        Throwable[] firstErr = { null };

        try { MachineStore.load(tile ? pdc.get(itemsKey, PersistentDataType.STRING) : externalStore.get(lk, "items"), m); }
        catch (Throwable t) { failed.add("items"); firstErr[0] = t; }

        if (type != null && type.isGrinder()) {
            try { m.setGrinder(dev.servereer.machineconstruct.grinder.GrinderStore.load(blob(tile, pdc, lk, grinderKey, "grinder"))); }
            catch (Throwable t) { m.setGrinder(dev.servereer.machineconstruct.grinder.GrinderStore.load(null)); failed.add("grinder"); if (firstErr[0] == null) firstErr[0] = t; }
        }
        if (type != null && type.isQuarry()) {
            try { m.setQuarry(dev.servereer.machineconstruct.quarry.QuarryStore.load(blob(tile, pdc, lk, quarryKey, "quarry"))); }
            catch (Throwable t) { m.setQuarry(dev.servereer.machineconstruct.quarry.QuarryStore.load(null)); failed.add("quarry"); if (firstErr[0] == null) firstErr[0] = t; }
        }
        if (type != null && type.isTradingHall()) {
            try { m.setTradingHall(dev.servereer.machineconstruct.tradinghall.TradingHallStore.load(blob(tile, pdc, lk, tradingHallKey, "tradinghall"))); }
            catch (Throwable t) { m.setTradingHall(dev.servereer.machineconstruct.tradinghall.TradingHallStore.load(null)); failed.add("tradinghall"); if (firstErr[0] == null) firstErr[0] = t; }
            m.tradingHall().ensureSlots(type.tradingHall().slotsForTier(m.tradingHall().tier()));
        }
        if (type != null && type.isFactory()) {
            try { m.setFactory(dev.servereer.machineconstruct.factorydistrict.FactoryDistrictStore.load(blob(tile, pdc, lk, factoryKey, "factory"))); }
            catch (Throwable t) { m.setFactory(dev.servereer.machineconstruct.factorydistrict.FactoryDistrictStore.load(null)); failed.add("factory"); if (firstErr[0] == null) firstErr[0] = t; }
            m.factory().ensureSlots(type.factory().slotsForTier(m.factory().tier()));
        }
        if (type != null && type.isChunkCollector()) {
            try { m.setChunkCollector(dev.servereer.machineconstruct.collector.ChunkCollectorStore.load(blob(tile, pdc, lk, collectorKey, "collector"))); }
            catch (Throwable t) { m.setChunkCollector(dev.servereer.machineconstruct.collector.ChunkCollectorStore.load(null)); failed.add("collector"); if (firstErr[0] == null) firstErr[0] = t; }
        }
        if (type != null && type.isJukebox()) {
            try { m.setJukebox(dev.servereer.machineconstruct.jukebox.JukeboxStore.load(blob(tile, pdc, lk, jukeboxKey, "jukebox"))); }
            catch (Throwable t) { m.setJukebox(dev.servereer.machineconstruct.jukebox.JukeboxStore.load(null)); failed.add("jukebox"); if (firstErr[0] == null) firstErr[0] = t; }
        }

        if (!failed.isEmpty()) {
            faultLoad(m, String.join(", ", failed), firstErr[0]);
        } else if (backups != null && backups.entry(m.id().toString()) == null) {
            // First time we've seen this machine → take one initial snapshot. Repeated chunk reloads do
            // NOT re-serialize (the periodic task handles ongoing changes), keeping the hot load path cheap.
            // Fully isolated: a backup hiccup must never disturb a machine loading or working.
            try { backups.record(m, currentBlobs(m)); } catch (Throwable ignored) { }
        }
    }

    /** Read a behavior's serialized blob from either the TileState PDC or the external flatfile. */
    private String blob(boolean tile, org.bukkit.persistence.PersistentDataContainer pdc, String lk, NamespacedKey key, String field) {
        return tile ? pdc.get(key, PersistentDataType.STRING) : externalStore.get(lk, field);
    }

    /** Quarantine a machine whose saved data failed to load — its blob is PRESERVED (persist is blocked). */
    private void faultLoad(Machine m, String parts, Throwable t) {
        if (faulted.add(m.id())) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "[MachineConstruct] QUARANTINED '" + m.typeId() + "' @ " + key(m.anchor())
                    + " (owner " + m.owner() + ") — corrupt saved data [" + parts + "]. Its blob is PRESERVED (NOT overwritten). "
                    + "Look at it and run /mc restore to roll back to a healthy backup, or fix the cause and /mc reload.", t);
            String note = "§c[MachineConstruct] §f" + m.typeId() + "§c machine @ §f" + key(m.anchor())
                    + "§c quarantined (corrupt data) — its data is safe. §e/mc restore §7while looking at it to recover.";
            for (Player p : plugin.getServer().getOnlinePlayers())
                if (p.hasPermission("machineconstruct.reload")) p.sendMessage(note);
        }
    }

    /** Serialize the machine's I/O + fuel buffers to the anchor's PDC (saved with the chunk). */
    private void persistStore(Machine m) {
        // A quarantined machine must NEVER write: its live data may be empty/partial from a failed load,
        // and overwriting the good saved blob is exactly the "reset" bug we're fixing. Preserve the blob.
        if (faulted.contains(m.id())) return;
        backupDirty.add(m.id());   // state changed → include it in the next incremental backup snapshot
        try {
            Block block = m.anchor().getBlock();
            BlockState st = block.getState();
            if (!(st instanceof TileState tile)) { persistStoreExternal(m); return; }   // BARRIER etc. → flatfile
            tile.getPersistentDataContainer().set(itemsKey, PersistentDataType.STRING,
                    MachineStore.serialize(m));
            if (m.hasGrinder()) {
                tile.getPersistentDataContainer().set(grinderKey, PersistentDataType.STRING,
                        dev.servereer.machineconstruct.grinder.GrinderStore.serialize(m.grinder()));
            }
            if (m.hasQuarry()) {
                tile.getPersistentDataContainer().set(quarryKey, PersistentDataType.STRING,
                        dev.servereer.machineconstruct.quarry.QuarryStore.serialize(m.quarry()));
            }
            if (m.hasTradingHall()) {
                tile.getPersistentDataContainer().set(tradingHallKey, PersistentDataType.STRING,
                        dev.servereer.machineconstruct.tradinghall.TradingHallStore.serialize(m.tradingHall()));
            }
            if (m.hasFactory()) {
                tile.getPersistentDataContainer().set(factoryKey, PersistentDataType.STRING,
                        dev.servereer.machineconstruct.factorydistrict.FactoryDistrictStore.serialize(m.factory()));
            }
            if (m.hasChunkCollector()) {
                tile.getPersistentDataContainer().set(collectorKey, PersistentDataType.STRING,
                        dev.servereer.machineconstruct.collector.ChunkCollectorStore.serialize(m.chunkCollector()));
            }
            if (m.hasJukebox()) {
                tile.getPersistentDataContainer().set(jukeboxKey, PersistentDataType.STRING,
                        dev.servereer.machineconstruct.jukebox.JukeboxStore.serialize(m.jukebox()));
            }
            tile.update();
        } catch (Throwable t) {
            plugin.getLogger().warning("[MachineConstruct] could not persist machine @ "
                    + key(m.anchor()) + ": " + t.getMessage());
        }
    }

    /** Serialize a non-TileState-anchored machine's state into the external flatfile (dirty → batched flush). */
    private void persistStoreExternal(Machine m) {
        if (faulted.contains(m.id())) return;   // quarantined → never overwrite the preserved blob
        try {
            Location a = m.anchor();
            String lk = key(a);
            if (externalStore.get(lk, "tag") == null) {   // ensure identity present (e.g. first persist after load)
                externalStore.set(lk, "world", a.getWorld() == null ? "" : a.getWorld().getName());
                externalStore.set(lk, "x", String.valueOf(a.getBlockX()));
                externalStore.set(lk, "y", String.valueOf(a.getBlockY()));
                externalStore.set(lk, "z", String.valueOf(a.getBlockZ()));
                externalStore.set(lk, "tag", m.id() + "|" + m.typeId() + "|" + (m.owner() == null ? "" : m.owner()));
            }
            externalStore.set(lk, "items", MachineStore.serialize(m));
            if (m.hasGrinder()) externalStore.set(lk, "grinder",
                    dev.servereer.machineconstruct.grinder.GrinderStore.serialize(m.grinder()));
            if (m.hasQuarry()) externalStore.set(lk, "quarry",
                    dev.servereer.machineconstruct.quarry.QuarryStore.serialize(m.quarry()));
            if (m.hasTradingHall()) externalStore.set(lk, "tradinghall",
                    dev.servereer.machineconstruct.tradinghall.TradingHallStore.serialize(m.tradingHall()));
            if (m.hasFactory()) externalStore.set(lk, "factory",
                    dev.servereer.machineconstruct.factorydistrict.FactoryDistrictStore.serialize(m.factory()));
            if (m.hasChunkCollector()) externalStore.set(lk, "collector",
                    dev.servereer.machineconstruct.collector.ChunkCollectorStore.serialize(m.chunkCollector()));
            if (m.hasJukebox()) externalStore.set(lk, "jukebox",
                    dev.servereer.machineconstruct.jukebox.JukeboxStore.serialize(m.jukebox()));
        } catch (Throwable t) {
            plugin.getLogger().warning("[MachineConstruct] could not persist external machine @ "
                    + key(m.anchor()) + ": " + t.getMessage());
        }
    }

    /** (Re)build a machine's displays from the current model registry. */
    private void renderMachine(Machine m) {
        // Despawn any existing leaves first (reload / re-render).
        for (PacketDisplay d : m.displays()) tracker.unregister(d);
        m.setRendered(null);

        Location anchor = m.anchor();
        List<PacketDisplay> leaves = new ArrayList<>();
        Model model = tierModel(m);
        if (model != null) {
            RenderedModel r = model.render(anchor.clone(), m.facing());
            m.setRendered(r);
            leaves.addAll(r.leaves());
            applyPanelLayouts(m);    // per-placed-panel text layout overrides (shift / size / align / wrap)
            panelResolve(m, true);   // live text: first resolve on render, so a panel never shows raw %placeholders%
        } else {
            // Unknown type → debug fallback: a copper block above the chest.
            leaves.add(PacketDisplay.block(anchor.clone().add(0.0, 1.0, 0.0),
                    Material.COPPER_BLOCK.createBlockData(), 1.0f));
        }
        m.setDisplays(leaves);
        // blank + pin the cue-driven parts before they spawn; never let a capsule hiccup (a bad series
        // file, a missing crate) stop the model from reaching viewers — it did, and the machine then
        // stayed invisible until a /mc reload.
        { MachineType gt = typeOfMachine(m);
          if (gt != null && gt.isGacha()) {
              try { claw.forget(m); cues.forget(m); gacha.onRender(m, gt); }
              catch (Throwable ex) { plugin.getLogger().warning("[MachineConstruct] capsule render hook failed for " + m.typeId() + ": " + ex); }
          } }
        for (PacketDisplay d : leaves) tracker.register(d);
        buttons.forget(m);
        m.buttons().clear();
        for (PacketDisplay d : leaves) if (d.button() != null) m.buttons().add(d);
    }

    // --- physical buttons -----------------------------------------------------

    /** The current state name of a panel (its own, else the type's default). */
    private String panelState(Machine m, MachineType t) {
        String s = m.panel() != null ? m.panel().state() : null;
        if (s != null && t.panelStates().containsKey(s)) return s;
        return t.panelDefaultState();
    }

    private void onButtonPress(Machine m, Player p, PacketDisplay d, String action) {
        MachineType t = typeOfMachine(m);
        if (t == null || action == null) return;
        String a = action.trim();
        if (a.startsWith("state:")) {
            if (!t.isPanel() || t.panelStates().isEmpty()) return;
            List<String> names = new ArrayList<>(t.panelStates().keySet());
            String cur = panelState(m, t);
            int i = Math.max(0, names.indexOf(cur));
            String next;
            if (a.equals("state:next")) next = names.get((i + 1) % names.size());
            else if (a.equals("state:prev")) next = names.get((i - 1 + names.size()) % names.size());
            else if (a.startsWith("state:set:")) { next = a.substring("state:set:".length()).trim(); if (!t.panelStates().containsKey(next)) return; }
            else return;
            if (m.panel() == null) m.setPanel(new PanelData());
            m.panel().setState(next);
            persistStore(m); panelIndexDirty = true;
            List<PacketDisplay> changed = panelResolveCollect(m);
            fadeIn(changed);
        } else if (a.startsWith("page:")) {
            if (!t.isPanel() || !t.panelPaged()) return;
            PanelData pd = m.panel() == null ? new PanelData() : m.panel();
            int count = (!pd.pages().isEmpty() ? pd.pages() : t.panelPages()).size();
            if (count == 0) return;
            int cur = Math.min(Math.max(0, pd.page()), count - 1), next = cur;
            if (a.equals("page:next")) next = Math.min(count - 1, cur + 1);        // clamps — a last page has no next
            else if (a.equals("page:prev")) next = Math.max(0, cur - 1);
            else if (a.equals("page:first")) next = 0;
            else if (a.equals("page:last")) next = count - 1;
            else if (a.startsWith("page:set:")) { try { next = Math.min(count - 1, Math.max(0, Integer.parseInt(a.substring(9).trim()) - 1)); } catch (NumberFormatException ex) { return; } }
            else return;
            if (next == cur) return;
            if (m.panel() == null) m.setPanel(pd);
            pd.setPage(next);
            persistStore(m); panelIndexDirty = true;
            fadeIn(panelResolveCollect(m));
        } else if (a.startsWith("gacha:")) {
            if (!t.isGacha()) return;
            String sub = a.substring(6).trim();
            int n = sub.equals("pull") ? 1 : sub.startsWith("pull:") ? Math.max(1, Integer.parseInt(sub.substring(5).trim())) : 0;
            // on a crane the same button starts the play and then drops the claw
            if (n > 0 && t.gacha().isClaw()) claw.start(p, m, t);
            else if (n > 0) gacha.pull(p, m, t, n);
            else if (sub.equals("menu")) gachaMenus.open(p, m);
        } else if (a.startsWith("claw:")) {
            if (t.gacha() != null && t.gacha().isClaw()) {
                if (a.endsWith(":drop")) claw.drop(p, m); else claw.start(p, m, t);
            }
        } else if (a.startsWith("cue:")) {   // any machine: fire one of its authored cues
            dev.servereer.machineconstruct.model.anim.Cue c = t.cues().get(a.substring(4).trim());
            if (c != null && !cues.playing(m)) cues.play(m, c, Map.of("player", p.getName()), p, null);
        } else if (a.equals("refresh")) {
            if (t.isPanel()) panelResolve(m, false);
        } else if (a.startsWith("command:")) {
            p.performCommand(a.substring("command:".length()).trim().replace("{player}", p.getName()));
        } else if (a.startsWith("console:")) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), a.substring("console:".length()).trim().replace("{player}", p.getName()));
        } else if (a.startsWith("message:")) {
            p.sendMessage(dev.servereer.machineconstruct.gui.MenuSkin.mini(a.substring("message:".length()).trim()));
        }
    }

    /** Rebuild every placed machine from the (reloaded) registry — used by /mc reload. */
    public void reRenderAll() {
        menus.closeAll();   // layouts/store sizes may have changed; reopen fresh
        grinderMenus.closeAll();
        quarryMenus.closeAll();
        faulted.clear();    // give every machine a clean slate after a reload
        for (Machine m : byAnchor.values()) {
            initAndLoadStore(m);
            renderMachine(m);
        }
    }

    private Model modelFor(String typeId) {
        if (registry == null) return null;
        var type = registry.getMachineType(typeId);
        if (type == null) return null;
        return registry.getModel(type.modelId());
    }

    /** The model for a machine at its current tier: a tier override if defined, else the base model. */
    private Model tierModel(Machine m) {
        if (registry == null) return null;
        MachineType type = registry.getMachineType(m.typeId());
        if (type == null) return null;
        if (!type.panelThemeModels().isEmpty()) {   // themed machines (panels, capsule machines): the placed one's block theme
            String name = m.panel() != null && m.panel().theme() != null ? m.panel().theme() : type.panelTheme();
            String mid = type.panelThemeModels().get(name);
            if (mid == null) mid = type.panelThemeModels().get(type.panelTheme());
            Model themed = mid == null ? null : registry.getModel(mid);
            if (themed != null) return themed;
        }
        Model override = type.modelOverride(m.tier());
        return override != null ? override : registry.getModel(type.modelId());
    }

    public void clearAll() {
        for (Machine m : byAnchor.values()) {
            for (PacketDisplay d : m.displays()) tracker.unregister(d);
        }
        byAnchor.clear();
    }

    /** Flush every machine's state to PDC — called on clean shutdown so nothing's lost. */
    public void persistAll() {
        for (Machine m : byAnchor.values()) persistStore(m);
        externalStore.flush();
        snapshotAllAndFlush();   // one last backup snapshot on shutdown (synchronous)
        if (backups != null) backups.shutdown();   // stop the background writer thread
    }

    // --- machine state backups (reset recovery) -----------------------------

    /**
     * Build the backup retention policy from config. Tiered by default so a machine can be rolled back by
     * hours OR days, not just the ~80 minutes the old fixed 8-version cap allowed. All keys are optional —
     * the defaults (keep every snapshot for 2h, then hourly to 24h, then daily to 14d, capped at 64) apply
     * even on servers whose config.yml predates them, since {@code saveResource} won't rewrite a live file.
     */
    private MachineBackupStore.Retention backupRetention() {
        var cfg = plugin.getConfig();
        long recentMs = 60_000L * Math.max(0, cfg.getInt("backups.retention.recent-window-minutes", 120));
        long hourlyMs = 3_600_000L * Math.max(1, cfg.getInt("backups.retention.hourly-window-hours", 24));
        long maxAgeMs = 86_400_000L * Math.max(1, cfg.getInt("backups.retention.daily-window-days", 14));
        int maxSnaps = Math.max(4, cfg.getInt("backups.retention.max-snapshots", 64));
        // Back-compat: an explicitly-set (larger) legacy max-versions still raises the hard cap floor.
        maxSnaps = Math.max(maxSnaps, cfg.getInt("backups.max-versions", 0));
        return new MachineBackupStore.Retention(recentMs, hourlyMs, maxAgeMs, maxSnaps);
    }

    /** Timer path: snapshot only machines that CHANGED since last time, then write off the main thread. */
    private void snapshotDirtyAndFlush() {
        if (backups == null || backupDirty.isEmpty()) return;
        for (UUID id : new ArrayList<>(backupDirty)) {
            backupDirty.remove(id);
            Machine m = machineById(id);
            if (m == null || faulted.contains(m.id())) continue;   // gone/quarantined → skip (never snapshot empty)
            try { backups.record(m, currentBlobs(m)); } catch (Throwable ignored) { }
        }
        backups.flushAsync();
    }

    /** Shutdown path: snapshot everything healthy + write synchronously so nothing's lost on stop. */
    private void snapshotAllAndFlush() {
        if (backups == null) return;
        for (Machine m : byAnchor.values()) {
            if (faulted.contains(m.id())) continue;   // never snapshot a quarantined (possibly-empty) machine
            try { backups.record(m, currentBlobs(m)); } catch (Throwable ignored) { }
        }
        backups.flushSync();
    }

    /** Snapshot ONE machine right now (on break/teardown, so a removed machine stays recoverable). */
    private void backupSnapshot(Machine m) {
        if (backups == null || m == null || faulted.contains(m.id())) return;
        try { backups.record(m, currentBlobs(m)); backups.flushAsync(); } catch (Throwable ignored) { }
    }

    /** A loaded machine by its id, or null. */
    private Machine machineById(UUID id) {
        for (Machine m : byAnchor.values()) if (m.id().equals(id)) return m;
        return null;
    }

    /** Short admin-facing id = first 8 hex of the machine UUID. */
    public String shortId(UUID id) { return id == null ? "?" : id.toString().substring(0, 8); }

    private AdminMenu adminMenu;
    /** Open the /mc admin machine browser + restore GUI. */
    public void openAdminMenu(Player p) {
        if (adminMenu == null) {
            adminMenu = new AdminMenu(this);
            plugin.getServer().getPluginManager().registerEvents(adminMenu, plugin);
        }
        adminMenu.open(p);
    }

    /** The machine's full serialized state, keyed the same way persistStore stores it. */
    private java.util.Map<String, String> currentBlobs(Machine m) {
        java.util.Map<String, String> b = new java.util.LinkedHashMap<>();
        try { b.put("items", MachineStore.serialize(m)); } catch (Throwable ignored) { }
        if (m.hasGrinder()) try { b.put("grinder", dev.servereer.machineconstruct.grinder.GrinderStore.serialize(m.grinder())); } catch (Throwable ignored) { }
        if (m.hasQuarry()) try { b.put("quarry", dev.servereer.machineconstruct.quarry.QuarryStore.serialize(m.quarry())); } catch (Throwable ignored) { }
        if (m.hasTradingHall()) try { b.put("tradinghall", dev.servereer.machineconstruct.tradinghall.TradingHallStore.serialize(m.tradingHall())); } catch (Throwable ignored) { }
        if (m.hasFactory()) try { b.put("factory", dev.servereer.machineconstruct.factorydistrict.FactoryDistrictStore.serialize(m.factory())); } catch (Throwable ignored) { }
        if (m.hasChunkCollector()) try { b.put("collector", dev.servereer.machineconstruct.collector.ChunkCollectorStore.serialize(m.chunkCollector())); } catch (Throwable ignored) { }
        if (m.hasJukebox()) try { b.put("jukebox", dev.servereer.machineconstruct.jukebox.JukeboxStore.serialize(m.jukebox())); } catch (Throwable ignored) { }
        return b;
    }

    /** Write a set of blobs back into the machine's anchor storage, then re-load + re-render it. */
    private boolean restoreBlobs(Machine m, java.util.Map<String, String> blobs) {
        try {
            BlockState st = m.anchor().getBlock().getState();
            if (st instanceof TileState tile) {
                var pdc = tile.getPersistentDataContainer();
                putOrRemove(pdc, itemsKey, blobs.get("items"));
                putOrRemove(pdc, grinderKey, blobs.get("grinder"));
                putOrRemove(pdc, quarryKey, blobs.get("quarry"));
                putOrRemove(pdc, tradingHallKey, blobs.get("tradinghall"));
                putOrRemove(pdc, factoryKey, blobs.get("factory"));
                putOrRemove(pdc, collectorKey, blobs.get("collector"));
                putOrRemove(pdc, jukeboxKey, blobs.get("jukebox"));
                tile.update();
            } else {
                String lk = key(m.anchor());
                for (String field : new String[]{"items", "grinder", "quarry", "tradinghall", "factory", "collector", "jukebox"})
                    externalStore.set(lk, field, blobs.get(field));
                externalStore.flush();
            }
            faulted.remove(m.id());     // give it a clean slate
            initAndLoadStore(m);        // re-hydrate from the restored blob
            renderMachine(m);           // re-render (despawns old leaves first)
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[MachineConstruct] restore failed @ " + key(m.anchor()), t);
            return false;
        }
    }

    private static void putOrRemove(org.bukkit.persistence.PersistentDataContainer pdc, NamespacedKey key, String val) {
        if (val == null) pdc.remove(key); else pdc.set(key, PersistentDataType.STRING, val);
    }

    /** The placed machine a player is looking at (anchor within 6 blocks), or null. */
    public Machine lookingAt(Player p) {
        Block b = p.getTargetBlockExact(6);
        return b == null ? null : byAnchor.get(key(b.getLocation()));
    }

    // ---- command-facing backup API ----

    public String backupNow() {
        if (backups == null) return "§cBackups are unavailable.";
        int n = 0;
        for (Machine m : byAnchor.values()) { if (faulted.contains(m.id())) continue; try { backups.record(m, currentBlobs(m)); n++; } catch (Throwable ignored) { } }
        backups.flushAsync();
        return "§aSnapshotted §f" + n + "§a loaded machine(s). §7Tracking §f" + backups.machineCount()
                + "§7 machine(s), §f" + backups.totalVersions() + "§7 total snapshots.";
    }

    public String backupSummary() {
        if (backups == null) return "§cBackups are unavailable.";
        return "§7MachineConstruct backups: §f" + backups.machineCount() + "§7 machine(s) tracked, §f"
                + backups.totalVersions() + "§7 total snapshots. §8(rolling history; /mc restore while looking at a machine)";
    }

    public String backupInfoLookedAt(Player p) {
        Machine m = lookingAt(p);
        if (m == null) return "§cLook at a placed machine's anchor block first.";
        MachineBackupStore.Entry e = backups == null ? null : backups.entry(m.id().toString());
        if (e == null || e.versions.isEmpty()) return "§7No snapshots yet for §f" + m.typeId() + "§7.";
        int total = e.versions.size();
        StringBuilder sb = new StringBuilder("§e" + m.typeId() + " §7#§f" + shortId(m.id()) + "§7 — §f" + total + "§7 snapshot(s)"
                + (faulted.contains(m.id()) ? " §c[QUARANTINED — data preserved]" : "")
                + " §8(spanning " + ago(e.versions.get(0).ts) + ")§7:");
        int show = Math.min(total, 10);
        for (int k = total - 1; k >= total - show; k--) {
            MachineBackupStore.Version v = e.versions.get(k);
            sb.append("\n§7 #§f").append(k + 1).append(" §8· §f").append(ago(v.ts)).append(" ago §8· §7").append(v.size()).append(" bytes");
        }
        if (total > show) {
            sb.append("\n§8 …").append(total - show).append(" older — oldest §7#§f1 §8(").append(ago(e.versions.get(0).ts)).append(" ago)");
        }
        sb.append("\n§7Run §e/mc restore§7 (newest) or §e/mc restore ").append(shortId(m.id())).append(" <#>§7 for a specific snapshot §8(1=oldest…" + total + "=newest).");
        return sb.toString();
    }

    public String restoreLookedAt(Player p) {
        Machine m = lookingAt(p);
        if (m == null) return "§cLook at a placed machine's anchor block first, or use §f/mc restore <id>§c.";
        MachineBackupStore.Version v = backups == null ? null : backups.latest(m.id().toString());
        if (v == null) return "§cNo backup snapshots exist for §f" + m.typeId() + "§c yet — nothing to restore.";
        boolean ok = restoreBlobs(m, v.blobs);
        return ok ? "§aRestored §f" + m.typeId() + " §7#" + shortId(m.id()) + "§a from a snapshot taken §f" + ago(v.ts) + "§a ago §7(" + v.size() + " bytes)."
                  : "§cRestore failed — see console.";
    }

    /** /mc restore &lt;idPrefix&gt; [version] — restore by id (no line-of-sight); default newest, else 1-based version. */
    public String restoreById(String prefix, Integer version) {
        if (backups == null) return "§cBackups are unavailable.";
        java.util.Map.Entry<String, MachineBackupStore.Entry> hit = backups.byPrefix(prefix);
        if (hit == null) return "§cNo single machine matches id §f" + prefix + "§c — unknown, or ambiguous (use more characters, or /mc admin).";
        MachineBackupStore.Entry e = hit.getValue();
        if (e.versions.isEmpty()) return "§cThat machine has no snapshots.";
        int idx = version == null ? e.versions.size() - 1 : version - 1;
        if (idx < 0 || idx >= e.versions.size()) return "§cVersion out of range (1–" + e.versions.size() + ").";
        MachineBackupStore.Version v = e.versions.get(idx);
        UUID mid = tryUuid(hit.getKey());
        Machine m = mid == null ? null : machineById(mid);
        if (m == null) m = loadMachineAt(e);   // not loaded → try to load its chunk
        if (m == null) return "§eThat machine isn't loaded and its chunk couldn't be reached. §7Go to §f"
                + e.world + " " + e.x + " " + e.y + " " + e.z + "§7, then retry.";
        boolean ok = restoreBlobs(m, v.blobs);
        return ok ? "§aRestored §f" + e.type + " §7#" + (mid == null ? "?" : shortId(mid)) + "§a from snapshot §f#" + (idx + 1)
                + "§a (" + ago(v.ts) + " ago, " + v.size() + " bytes)."
                  : "§cRestore failed — see console.";
    }

    /** Force-load the chunk at a backup entry's recorded location and return the (re-registered) machine, or null. */
    private Machine loadMachineAt(MachineBackupStore.Entry e) {
        try {
            if (e.world == null) return null;
            World w = plugin.getServer().getWorld(e.world);
            if (w == null) return null;
            w.getChunkAt(e.x >> 4, e.z >> 4);   // synchronous load
            scanChunk(w.getChunkAt(e.x >> 4, e.z >> 4));   // re-register any tagged machine in it
            return byAnchor.get(w.getName() + ":" + e.x + ":" + e.y + ":" + e.z);
        } catch (Throwable t) { return null; }
    }

    // ---- admin GUI data + actions ----
    /** All backed-up machines (id + entry) for the admin browser, newest-changed first. */
    public java.util.List<java.util.Map.Entry<String, MachineBackupStore.Entry>> backupEntries() {
        if (backups == null) return java.util.List.of();
        java.util.List<java.util.Map.Entry<String, MachineBackupStore.Entry>> l = backups.all();
        l.sort((a, b) -> {
            long ta = a.getValue().latest() == null ? 0 : a.getValue().latest().ts;
            long tb = b.getValue().latest() == null ? 0 : b.getValue().latest().ts;
            return Long.compare(tb, ta);
        });
        return l;
    }
    public MachineBackupStore.Entry backupEntry(String id) { return backups == null ? null : backups.entry(id); }
    public boolean isQuarantined(UUID id) { return faulted.contains(id); }
    public static String agoText(long ts) { return ago(ts); }

    /** Admin GUI: restore a specific version of a machine by full id; loads the chunk if needed. Returns a status line. */
    public String restoreVersion(String fullId, int idx) {
        if (backups == null) return "§cBackups unavailable.";
        MachineBackupStore.Entry e = backups.entry(fullId);
        if (e == null || idx < 0 || idx >= e.versions.size()) return "§cSnapshot not found.";
        MachineBackupStore.Version v = e.versions.get(idx);
        UUID mid = tryUuid(fullId);
        Machine m = mid == null ? null : machineById(mid);
        if (m == null) m = loadMachineAt(e);
        if (m == null) return "§eCouldn't load that machine's chunk — go to §f" + e.world + " " + e.x + " " + e.y + " " + e.z + "§7.";
        boolean ok = restoreBlobs(m, v.blobs);
        return ok ? "§aRestored §f" + e.type + " §7#" + (mid == null ? "?" : shortId(mid)) + "§a to snapshot §f#" + (idx + 1) + "§a (" + ago(v.ts) + " ago)."
                  : "§cRestore failed — see console.";
    }

    private static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    // --- helpers ------------------------------------------------------------

    private static String key(Location l) {
        return l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    /** Whether any player is within render distance of the machine — gates animation work. */
    private boolean hasViewerNear(Machine m) {
        Location a = m.anchor();
        World w = a.getWorld();
        if (w == null) return false;
        double rdSq = (double) tracker.renderDistance() * tracker.renderDistance();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(a) <= rdSq) return true;   // short-circuit on the first
        }
        return false;
    }

    /** First non-empty stack in a store array (for the live {@code <input>}/{@code <output>} binding). */
    private static ItemStack firstNonEmpty(ItemStack[] arr) {
        for (ItemStack s : arr) if (s != null && !s.getType().isAir()) return s;
        return null;
    }

    private static UUID tryUuid(String s) {
        try { return UUID.fromString(s); } catch (Exception ignored) { return null; }
    }

    /** A chat line that a machine's {@code skin.messages.<key>} may override (MiniMessage, with
     *  {@code {placeholders}} filled and the skin's prefix); otherwise the legacy default. */
    private Component smsg(MachineType mt, String key, String legacyDefault, Map<String, String> vars) {
        dev.servereer.machineconstruct.gui.MenuSkin sk = mt == null ? null : mt.skin();
        String over = sk == null ? null : sk.msg(key, null, vars);
        if (over == null) return msg(legacyDefault);
        return dev.servereer.machineconstruct.gui.MenuSkin.mini(sk.prefix("<gold>MachineConstruct <dark_gray>» ") + over);
    }

    private Component smsg(MachineType mt, String key, String legacyDefault) { return smsg(mt, key, legacyDefault, null); }

    private MachineType typeOfMachine(Machine m) { return (m == null || registry == null) ? null : registry.getMachineType(m.typeId()); }

    /** The first grinder-kind machine type (for messages sent with no machine in hand). */
    private MachineType anyGrinderType() {
        if (registry == null) return null;
        for (Machine m : byAnchor.values()) { MachineType t = registry.getMachineType(m.typeId()); if (t != null && t.isGrinder()) return t; }
        return null;
    }

    private Component msg(String legacy) {
        return Component.text("MachineConstruct ", NamedTextColor.GOLD)
                .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                .append(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(legacy));
    }
}
