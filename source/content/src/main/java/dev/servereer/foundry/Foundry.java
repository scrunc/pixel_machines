package dev.servereer.foundry;

import dev.servereer.machineconstruct.api.MachineConstructAPI;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Foundry — the default content pack for MachineConstruct. <b>Zero machine logic
 * in Java</b>: it ships YAML under {@code machines/} and just points the engine
 * at its data folder. Everything (model, transforms, animation, and later
 * recipes) lives in the YAML; {@code /mc reload} re-reads it live.
 */
public final class Foundry extends JavaPlugin {

    @Override
    public void onEnable() {
        RegisteredServiceProvider<MachineConstructAPI> rsp =
                getServer().getServicesManager().getRegistration(MachineConstructAPI.class);
        if (rsp == null) {
            getLogger().severe("MachineConstruct API not found — Foundry needs the engine. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MachineConstructAPI api = rsp.getProvider();

        // Copy bundled content to the data folder (only if absent, so edits stick),
        // then register the folder as a content pack for the engine to scan.
        saveResource("machines/crusher.yml", false);
        saveResource("machines/assembler.yml", false);
        saveResource("machines/oil_jack.yml", false);
        saveResource("machines/elixir_collector.yml", false);
        saveResource("machines/dimensional_grinder.yml", false);
        saveResource("machines/oil_refinery.yml", false);
        saveResource("machines/interdimensional_quarry.yml", false);
        saveResource("machines/virtual_trading_hall.yml", false);
        saveResource("machines/virtual_factory_district.yml", false);
        saveResource("machines/chunk_collector.yml", false);   // vacuum-hopper behaviour machine
        saveResource("machines/jukebox.yml", false);           // music-player behaviour machine (ADR 0024)
        saveResource("collector/item_prices.yml", false);      // chunk-collector sell prices
        saveResource("factory_district/builtin.yml", false);   // built-in farm catalog (merged with scans)
        saveResource("factory_district/farming.yml", false);   // farming branch — soil/livestock/processing (ADR 0017)
        saveResource("factory_district/housing.yml", false);   // housing subsystem — residences/civic/amenities/population (ADR 0018)
        saveResource("factory_district/lumber.yml", false);    // lumber branch — clearings/plantations/woodworking (ADR 0019)
        saveResource("factory_district/crafting.yml", false);  // crafting layer — recipe processors + vanilla bench (ADR 0021)
        saveResource("grinder/spawners_settings.yml", false);
        saveResource("grinder/item_prices.yml", false);
        api.registerContentPack(getDataFolder());

        getLogger().info("Foundry enabled — content pack registered on engine v" + api.version()
                + " (edit machines/*.yml then /mc reload).");
    }
}
