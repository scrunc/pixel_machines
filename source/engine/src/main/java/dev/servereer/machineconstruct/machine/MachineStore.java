package dev.servereer.machineconstruct.machine;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Serializes a machine's input/output item arrays to/from a string for PDC
 * storage (DESIGN.md §14, §15). Uses Bukkit's {@link YamlConfiguration} (items
 * are {@code ConfigurationSerializable}) — version-stable and round-trips meta
 * (names, custom_model_data, head profiles). Written on every change, so a crash
 * or restart never loses items (the chest's PDC is saved with the chunk).
 */
public final class MachineStore {

    private MachineStore() {}

    public static String serialize(Machine m) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("in", new ArrayList<>(Arrays.asList(m.inputs())));     // null slots preserved in order
        y.set("out", new ArrayList<>(Arrays.asList(m.outputs())));
        y.set("fuel", new ArrayList<>(Arrays.asList(m.fuel())));
        y.set("up", new ArrayList<>(Arrays.asList(m.upgrade())));
        y.set("tier", m.tier());
        y.set("burn", m.fuelRemaining());     // live fuel burn — persisted so it survives restart
        y.set("burnMax", m.fuelMax());
        if (m.panel() != null && !m.panel().isEmpty()) m.panel().save(y.createSection("panel"));
        return y.saveToString();
    }

    /** Load the machine's full state (items, tier, fuel burn) from PDC; arrays must be pre-sized. */
    public static void load(String data, Machine m) {
        if (data == null || data.isEmpty()) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(data);                 // InvalidConfigurationException
            copyInto(y.getList("in"), m.inputs());  // deserialization of a now-invalid item
            copyInto(y.getList("out"), m.outputs());
            copyInto(y.getList("fuel"), m.fuel());
            copyInto(y.getList("up"), m.upgrade());
            m.setTier(Math.max(1, y.getInt("tier", 1)));
            m.setFuelRemaining(Math.max(0, y.getInt("burn", 0)));
            m.setFuelMax(Math.max(0, y.getInt("burnMax", 0)));
            if (y.isConfigurationSection("panel")) m.setPanel(PanelData.load(y.getConfigurationSection("panel")));
        } catch (Throwable t) {
            // Used to silently leave defaults (empty) → the engine then persisted empty over the good
            // blob (a "reset"). Signal it so the manager quarantines + preserves the saved blob instead.
            throw new IllegalStateException("corrupt machine store blob", t);
        }
    }

    private static void copyInto(List<?> src, ItemStack[] dst) {
        if (src == null) return;
        int n = Math.min(src.size(), dst.length);
        for (int i = 0; i < n; i++) {
            Object o = src.get(i);
            dst[i] = (o instanceof ItemStack is) ? is : null;
        }
    }
}
