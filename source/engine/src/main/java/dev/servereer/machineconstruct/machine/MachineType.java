package dev.servereer.machineconstruct.machine;

import dev.servereer.machineconstruct.gui.BrowserLayout;
import dev.servereer.machineconstruct.gui.GuiLayout;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * A machine type definition — what a placer builds (DESIGN.md §12). Id + model
 * id (the visual) + recipes (P5d) + GUI layout (P6) + fuel map (P7). Tiers and
 * placer head-skin are layered on later.
 */
public final class MachineType {

    private final String id;
    private final String modelId;
    private final List<Recipe> recipes;
    private final GuiLayout gui;
    private final Map<Material, Integer> fuel;   // fuel material → burn ticks; empty = runs without fuel
    private final Material anchorMaterial;       // the real block placed as the anchor (chest/barrel/…)
    private final BrowserLayout browser;         // recipe-browser sub-menu layout
    private final int capacity;                  // storage cap (0 = limited only by output slots); drives the fill level
    private final Tiers tiers;                   // upgrade ladder (null = no tiers)
    private final dev.servereer.machineconstruct.grinder.GrinderSpec grinder;   // null unless a grinder-kind machine
    private final dev.servereer.machineconstruct.grinder.LootTable loot;        // resolved loot table for the grinder
    private final dev.servereer.machineconstruct.grinder.econ.PriceService pricing;   // grinder/refinery sell prices
    private final dev.servereer.machineconstruct.quarry.QuarrySpec quarry;            // null unless a quarry-kind machine
    private final dev.servereer.machineconstruct.tradinghall.TradingHallSpec tradingHall; // null unless a tradinghall-kind machine
    private final dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec factory; // null unless a factory-kind machine
    private final dev.servereer.machineconstruct.collector.ChunkCollectorSpec chunkCollector; // null unless a chunk-collector-kind machine
    private final dev.servereer.machineconstruct.jukebox.JukeboxSpec jukebox;   // null unless a jukebox-kind machine
    private org.bukkit.inventory.ItemStack placerIcon;   // optional placer item prototype (null = default head)
    private dev.servereer.machineconstruct.gui.MenuSkin skin = dev.servereer.machineconstruct.gui.MenuSkin.EMPTY;   // code-rendered menu re-skin (skin:)
    private boolean facesPlacer;          // facing: player — the model turns toward whoever places it
    private int panelRefreshTicks;        // panel: { refresh } — live-text machine; 0 = not a panel
    private String panelTheme = "";                                         // panel.theme — default theme NAME ("" = first)
    private final Map<String, String> panelThemes = new java.util.LinkedHashMap<>();        // theme name → {theme} text colour (#hex)
    private final Map<String, String> panelThemeModels = new java.util.LinkedHashMap<>();   // theme name → model id (block palette)
    private final Map<String, Map<String, String>> panelStates = new java.util.LinkedHashMap<>();   // panel.states — name → {var: value} substituted as {var}
    private String panelDefaultState = "";
    private dev.servereer.machineconstruct.gacha.GachaSpec gacha;                         // gacha: — capsule machine (ADR 0045), null otherwise
    private java.util.Map<String, dev.servereer.machineconstruct.model.anim.Cue> cues = java.util.Map.of();   // cues: — one-shot scripted animations by name
    private boolean panelPaged;                                                            // panel.paged — info panels: pages of section texts, back/next buttons
    private final List<Map<String, List<String>>> panelPages = new java.util.ArrayList<>(); // panel.pages — the template's default pages (part → lines)
    private final Map<String, String> textVars = new java.util.LinkedHashMap<>();   // the file's string vars (palette, t_name…) for text written AFTER load

    public MachineType(String id, String modelId, List<Recipe> recipes, GuiLayout gui,
                       Map<Material, Integer> fuel, Material anchorMaterial, BrowserLayout browser,
                       int capacity, Tiers tiers,
                       dev.servereer.machineconstruct.grinder.GrinderSpec grinder,
                       dev.servereer.machineconstruct.grinder.LootTable loot,
                       dev.servereer.machineconstruct.grinder.econ.PriceService pricing,
                       dev.servereer.machineconstruct.quarry.QuarrySpec quarry,
                       dev.servereer.machineconstruct.tradinghall.TradingHallSpec tradingHall,
                       dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec factory,
                       dev.servereer.machineconstruct.collector.ChunkCollectorSpec chunkCollector,
                       dev.servereer.machineconstruct.jukebox.JukeboxSpec jukebox) {
        this.id = id;
        this.modelId = modelId;
        this.recipes = recipes;
        this.gui = gui;
        this.fuel = fuel;
        this.anchorMaterial = anchorMaterial;
        this.browser = browser;
        this.capacity = capacity;
        this.tiers = tiers;
        this.grinder = grinder;
        this.loot = loot;
        this.pricing = pricing;
        this.quarry = quarry;
        this.tradingHall = tradingHall;
        this.factory = factory;
        this.chunkCollector = chunkCollector;
        this.jukebox = jukebox;
    }

    /** The chunk-collector definition, or null if this isn't a chunk-collector-kind machine. */
    public dev.servereer.machineconstruct.collector.ChunkCollectorSpec chunkCollector() { return chunkCollector; }

    /** True if this machine is a Chunk Collector (vacuums dropped items in its chunk ring). */
    public boolean isChunkCollector() { return chunkCollector != null; }

    /** The jukebox definition, or null if this isn't a jukebox-kind machine. */
    public dev.servereer.machineconstruct.jukebox.JukeboxSpec jukebox() { return jukebox; }

    /** True if this machine is a Jukebox (plays library tracks + mints discs). */
    public boolean isJukebox() { return jukebox != null; }

    /** The trading-hall definition, or null if this isn't a tradinghall-kind machine. */
    public dev.servereer.machineconstruct.tradinghall.TradingHallSpec tradingHall() { return tradingHall; }

    /** True if this machine is a Virtual Trading Hall. */
    public boolean isTradingHall() { return tradingHall != null; }

    /** The factory-district definition, or null if this isn't a factory-kind machine. */
    public dev.servereer.machineconstruct.factorydistrict.FactoryDistrictSpec factory() { return factory; }

    /** True if this machine is a Virtual Factory District. */
    public boolean isFactory() { return factory != null; }

    /** Optional placer item prototype (configurable head/icon); null = default head. */
    public org.bukkit.inventory.ItemStack placerIcon() { return placerIcon; }
    public void setPlacerIcon(org.bukkit.inventory.ItemStack icon) { this.placerIcon = icon; }

    /** True if the model is rotated at placement to face the placing player ({@code facing: player}). */
    public boolean facesPlacer() { return facesPlacer; }
    public void setFacesPlacer(boolean b) { this.facesPlacer = b; }

    /** Ticks between live-text refreshes for a panel-kind machine; 0 = not a panel. */
    public int panelRefreshTicks() { return panelRefreshTicks; }
    public void setPanelRefreshTicks(int t) { this.panelRefreshTicks = Math.max(0, t); }
    /** True for a Panel: a display-only machine whose {@code %placeholders%} re-resolve on a timer; no GUI. */
    public boolean isPanel() { return panelRefreshTicks > 0; }
    /** The default theme name (the first declared one unless {@code panel.theme} names another). */
    public String panelTheme() {
        if (!panelTheme.isEmpty() && panelThemes.containsKey(panelTheme)) return panelTheme;
        return panelThemes.isEmpty() ? "" : panelThemes.keySet().iterator().next();
    }
    public void setPanelTheme(String name) { this.panelTheme = name == null ? "" : name.trim(); }
    /** Theme name → its {@code {theme}} text colour (#hex), in declaration order (the editor cycles these). */
    public Map<String, String> panelThemes() { return panelThemes; }
    /** Theme name → the id of the model pre-rendered with that theme's block palette. */
    public Map<String, String> panelThemeModels() { return panelThemeModels; }
    /** The machine file's string {@code vars:} (already self-resolved) — so text that arrives after load
     *  (a placed panel's custom lines from the editor / panels.yml) can still use {@code ${glow}} etc. */
    public Map<String, String> textVars() { return textVars; }
    public String expandTextVars(String s) {
        if (s == null || s.indexOf("${") < 0 || textVars.isEmpty()) return s;
        for (Map.Entry<String, String> e : textVars.entrySet()) s = s.replace("${" + e.getKey() + "}", e.getValue());
        return s;
    }

    /** Panel STATES (e.g. weekly / alltime): name → vars substituted into every text as {var}. Buttons switch them. */
    public Map<String, Map<String, String>> panelStates() { return panelStates; }
    public String panelDefaultState() {
        if (!panelDefaultState.isEmpty() && panelStates.containsKey(panelDefaultState)) return panelDefaultState;
        return panelStates.isEmpty() ? "" : panelStates.keySet().iterator().next();
    }
    public void setPanelDefaultState(String s) { this.panelDefaultState = s == null ? "" : s.trim(); }

    /** The capsule-machine definition, or null if this isn't a gacha-kind machine. */
    public dev.servereer.machineconstruct.gacha.GachaSpec gacha() { return gacha; }
    public boolean isGacha() { return gacha != null; }
    public void setGacha(dev.servereer.machineconstruct.gacha.GachaSpec g) { this.gacha = g; }
    /** Named cues (one-shot scripted animations) authored under {@code cues:}. */
    public java.util.Map<String, dev.servereer.machineconstruct.model.anim.Cue> cues() { return cues; }
    public void setCues(java.util.Map<String, dev.servereer.machineconstruct.model.anim.Cue> c) { this.cues = c == null ? java.util.Map.of() : c; }

    /** Paged (info) panel: texts come from PAGES — page N maps part names (sections) to lines; `page:next/prev` buttons turn them. */
    public boolean panelPaged() { return panelPaged; }
    public void setPanelPaged(boolean b) { this.panelPaged = b; }
    public List<Map<String, List<String>>> panelPages() { return panelPages; }

    /** The text colour for a theme name (falls back to the default theme, then teal). */
    public String panelThemeColor(String name) {
        String c = name == null ? null : panelThemes.get(name);
        if (c == null) c = panelThemes.get(panelTheme());
        return c == null ? "#35e0d0" : c;
    }

    /** The menu re-skin for code-rendered menus (never null; EMPTY = the built-in look). */
    public dev.servereer.machineconstruct.gui.MenuSkin skin() { return skin; }
    public void setSkin(dev.servereer.machineconstruct.gui.MenuSkin skin) { this.skin = skin == null ? dev.servereer.machineconstruct.gui.MenuSkin.EMPTY : skin; }

    /** The grinder/refinery price service (sell prices), or null. */
    public dev.servereer.machineconstruct.grinder.econ.PriceService pricing() { return pricing; }

    /** The quarry definition, or null if this isn't a quarry-kind machine. */
    public dev.servereer.machineconstruct.quarry.QuarrySpec quarry() { return quarry; }

    /** True if this machine is an Interdimensional Quarry. */
    public boolean isQuarry() { return quarry != null; }

    /** The grinder definition, or null if this isn't a grinder-kind machine. */
    public dev.servereer.machineconstruct.grinder.GrinderSpec grinder() { return grinder; }

    /** The resolved loot table (grinder-only), or null. */
    public dev.servereer.machineconstruct.grinder.LootTable loot() { return loot; }

    /** True if this machine is a Dimensional Grinder (uses accrual + the grinder menu). */
    public boolean isGrinder() { return grinder != null; }

    /** The upgrade ladder, or null if this machine has no tiers. */
    public Tiers tiers() {
        return tiers;
    }

    /** The model override for a tier (inline), or null to use the registry's base model. */
    public dev.servereer.machineconstruct.model.Model modelOverride(int tier) {
        return tiers == null ? null : tiers.modelOverride(tier);
    }

    /** The GUI for a tier — the tier override if any, else the base layout. */
    public GuiLayout guiFor(int tier) {
        if (tiers != null) {
            GuiLayout g = tiers.guiOverride(tier);
            if (g != null) return g;
        }
        return gui;
    }

    /** Max slot count of a kind across the base + all tier GUIs, so the store never shrinks. */
    public int maxSlots(java.util.function.ToIntFunction<GuiLayout> count) {
        int n = (gui == null) ? 0 : count.applyAsInt(gui);
        if (tiers != null) {
            for (int t = 1; t <= tiers.max(); t++) {
                GuiLayout g = tiers.guiOverride(t);
                if (g != null) n = Math.max(n, count.applyAsInt(g));
            }
        }
        return n;
    }

    /** Storage cap for generators (0 = unlimited / output-slot bound). */
    public int capacity() {
        return capacity;
    }

    public Material anchorMaterial() {
        return anchorMaterial;
    }

    public BrowserLayout browser() {
        return browser;
    }

    public String id() {
        return id;
    }

    public String modelId() {
        return modelId;
    }

    public List<Recipe> recipes() {
        return recipes;
    }

    public GuiLayout gui() {
        return gui;
    }

    /** True if this machine consumes fuel to run. */
    public boolean requiresFuel() {
        return fuel != null && !fuel.isEmpty();
    }

    /** Burn ticks the given material provides, or 0 if it isn't fuel for this machine. */
    public int burnTicks(Material mat) {
        Integer t = (fuel == null || mat == null) ? null : fuel.get(mat);
        return t == null ? 0 : t;
    }

    /** The materials accepted as fuel (for recipe-discovery display). */
    public java.util.Set<Material> fuelMaterials() {
        return fuel == null ? java.util.Set.of() : fuel.keySet();
    }
}
