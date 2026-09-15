package dev.servereer.machineconstruct.machine;

import dev.servereer.machineconstruct.core.PacketDisplay;
import dev.servereer.machineconstruct.model.RenderedModel;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A placed machine instance, anchored to a real chest block (§3 of DESIGN.md).
 *
 * <p>P3: identity, type, anchor, owner, and the rendered model — a list of
 * {@link PacketDisplay} leaves flattened from the type's model (or a single
 * debug block for unknown types). State (inventory, progress, tier) arrives in P5.
 */
public final class Machine {

    private final UUID id;
    private final String typeId;
    private final Location anchor;   // the chest block location
    private final UUID owner;
    private final List<PacketDisplay> displays = new ArrayList<>();
    private final double phase = ThreadLocalRandom.current().nextDouble(0.0, 10.0); // desync identical machines
    private RenderedModel rendered;  // null for the debug-fallback (unknown type)

    // P5d processing state (drives the working/blocked animation triggers).
    private MachineState state = MachineState.IDLE;
    private Recipe active;            // recipe being processed, or null
    private int progress;            // ticks elapsed on the active recipe

    // P6 sealed item store (sized from the type's GUI layout; persisted to PDC).
    private ItemStack[] inputs = new ItemStack[0];
    private ItemStack[] outputs = new ItemStack[0];
    private ItemStack[] fuel = new ItemStack[0];
    private ItemStack[] upgrade = new ItemStack[0];   // upgrade-item slot(s)
    private int tier = 1;                             // P13 current tier (>= 1)

    // P7 fuel burn (transient — items in the fuel slot are persisted, the live burn is not).
    private int fuelRemaining;   // ticks of burn left
    private int fuelMax;         // ticks the current fuel item provided (for the burn bar)
    private double level;        // P12 fill level 0..1 (stored ÷ capacity) for the fill driver

    // Dimensional Grinder state (null unless this is a grinder-kind machine).
    private dev.servereer.machineconstruct.grinder.GrinderData grinder;

    // Interdimensional Quarry state (null unless this is a quarry-kind machine).
    private dev.servereer.machineconstruct.quarry.QuarryData quarry;

    // Virtual Trading Hall state (null unless this is a tradinghall-kind machine).
    private dev.servereer.machineconstruct.tradinghall.TradingHallData tradingHall;

    // Virtual Factory District state (null unless this is a factory-kind machine).
    private dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData factory;

    // Chunk Collector state (null unless this is a chunk-collector-kind machine).
    private dev.servereer.machineconstruct.collector.ChunkCollectorData chunkCollector;

    // Jukebox state (null unless this is a jukebox-kind machine).
    private dev.servereer.machineconstruct.jukebox.JukeboxData jukebox;

    public Machine(UUID id, String typeId, Location anchor, UUID owner) {
        this.id = id;
        this.typeId = typeId;
        this.anchor = anchor.clone();
        this.owner = owner;
    }

    // Yaw (degrees, 45° steps) the model is turned by around the anchor's column — set at placement
    // for types with `facing: player`, persisted in the identity tag. 0 = authored orientation.
    private int facing;

    private PanelData panel;   // panel-kind machines: admin text overrides + theme (null = defaults)
    private final List<PacketDisplay> buttons = new ArrayList<>();   // the button parts among displays (ButtonManager ray-casts these)
    public List<PacketDisplay> buttons() { return buttons; }
    public PanelData panel() { return panel; }
    public void setPanel(PanelData p) { this.panel = p; }

    public int facing() { return facing; }
    public void setFacing(int deg) { this.facing = ((deg % 360) + 360) % 360; }

    public UUID id() { return id; }
    public String typeId() { return typeId; }
    public Location anchor() { return anchor.clone(); }
    public UUID owner() { return owner; }
    public double phase() { return phase; }

    public List<PacketDisplay> displays() { return displays; }
    public void setDisplays(List<PacketDisplay> list) {
        displays.clear();
        displays.addAll(list);
    }

    public RenderedModel rendered() { return rendered; }
    public void setRendered(RenderedModel rendered) { this.rendered = rendered; }

    // --- processing state ---------------------------------------------------

    public MachineState state() { return state; }
    public void setState(MachineState state) { this.state = state; }
    public Recipe active() { return active; }
    public void setActive(Recipe active) { this.active = active; }
    public int progress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public void addProgress(int ticks) { this.progress += ticks; }

    /** Processing progress 0..1 for progress-bound animations (0 when idle). */
    public double progress01() {
        if (active == null) return 0.0;
        return Math.min(1.0, (double) progress / active.timeTicks());
    }

    // --- sealed item store --------------------------------------------------

    /** Size the input/output/fuel/upgrade buffers from the type's GUI layout (call once at register). */
    public void initStore(int inputSize, int outputSize, int fuelSize, int upgradeSize) {
        if (inputs.length != inputSize) inputs = new ItemStack[Math.max(0, inputSize)];
        if (outputs.length != outputSize) outputs = new ItemStack[Math.max(0, outputSize)];
        if (fuel.length != fuelSize) fuel = new ItemStack[Math.max(0, fuelSize)];
        if (upgrade.length != upgradeSize) upgrade = new ItemStack[Math.max(0, upgradeSize)];
    }

    public ItemStack[] inputs() { return inputs; }
    public ItemStack[] outputs() { return outputs; }
    public ItemStack[] fuel() { return fuel; }
    public ItemStack[] upgrade() { return upgrade; }
    public int tier() { return tier; }
    public void setTier(int tier) { this.tier = Math.max(1, tier); }

    public int fuelRemaining() { return fuelRemaining; }
    public void setFuelRemaining(int t) { this.fuelRemaining = t; }
    public int fuelMax() { return fuelMax; }
    public void setFuelMax(int t) { this.fuelMax = t; }
    public double level() { return level; }
    public void setLevel(double level) { this.level = level; }

    // --- grinder ------------------------------------------------------------

    public dev.servereer.machineconstruct.grinder.GrinderData grinder() { return grinder; }
    public void setGrinder(dev.servereer.machineconstruct.grinder.GrinderData grinder) { this.grinder = grinder; }
    public boolean hasGrinder() { return grinder != null; }

    public dev.servereer.machineconstruct.quarry.QuarryData quarry() { return quarry; }
    public void setQuarry(dev.servereer.machineconstruct.quarry.QuarryData quarry) { this.quarry = quarry; }
    public boolean hasQuarry() { return quarry != null; }

    public dev.servereer.machineconstruct.tradinghall.TradingHallData tradingHall() { return tradingHall; }
    public void setTradingHall(dev.servereer.machineconstruct.tradinghall.TradingHallData t) { this.tradingHall = t; }
    public boolean hasTradingHall() { return tradingHall != null; }

    public dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData factory() { return factory; }
    public void setFactory(dev.servereer.machineconstruct.factorydistrict.FactoryDistrictData f) { this.factory = f; }
    public boolean hasFactory() { return factory != null; }

    public dev.servereer.machineconstruct.collector.ChunkCollectorData chunkCollector() { return chunkCollector; }
    public void setChunkCollector(dev.servereer.machineconstruct.collector.ChunkCollectorData c) { this.chunkCollector = c; }
    public boolean hasChunkCollector() { return chunkCollector != null; }

    public dev.servereer.machineconstruct.jukebox.JukeboxData jukebox() { return jukebox; }
    public void setJukebox(dev.servereer.machineconstruct.jukebox.JukeboxData j) { this.jukebox = j; }
    public boolean hasJukebox() { return jukebox != null; }

    /** Burn fraction 0..1 for the fuel bar. */
    public double fuel01() {
        if (fuelMax <= 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, (double) fuelRemaining / fuelMax));
    }
}
