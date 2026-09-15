package dev.servereer.machineconstruct.api;

import dev.servereer.machineconstruct.machine.MachineType;
import dev.servereer.machineconstruct.model.Model;

import java.io.File;

/**
 * Public engine API — the "makes the system" contract that content plugins
 * (e.g. Foundry) call to register models and machine types (DESIGN.md §1B).
 * Fetched via the Bukkit {@code ServicesManager}.
 *
 * <p>P3 surface: model + machine-type registration/lookup. Recipes, items,
 * content-pack folders, placer-give, events, and {@code mintItem} are layered in
 * P5/P6.
 */
public interface MachineConstructAPI {

    /** Engine version string (matches the plugin version). */
    String version();

    /** Register a model so machine types can reference it by id. */
    void registerModel(Model model);

    /** Look up a registered model, or null. */
    Model getModel(String id);

    /** Register a machine type (id → model + behavior). */
    void registerMachineType(MachineType type);

    /** Look up a registered machine type, or null. */
    MachineType getMachineType(String id);

    /**
     * Register a content-pack folder. The engine scans {@code <folder>/machines/*.yml},
     * loads each (id + {@code model:} tree → model + machine type), and remembers
     * the folder so {@code /mc reload} re-reads it. This is how content (Foundry)
     * stays pure data — no machine logic in Java.
     */
    void registerContentPack(File folder);

    /**
     * Re-read the PixelProfiler price bridge ({@code prices.yml} + shops) right now, without a
     * server restart or a full {@code /mc reload}. Cheap: just rebuilds the price cache, no machine
     * re-render. PixelProfiler calls this from its own {@code /pixelprofiler reload} so a single
     * reload updates both plugins after a price/shop edit; safe to call even if the bridge is off.
     */
    void refreshPrices();
}
