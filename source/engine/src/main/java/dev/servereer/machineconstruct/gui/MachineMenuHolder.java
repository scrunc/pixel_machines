package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as a MachineConstruct menu and binds it to its
 * {@link Machine} + {@link GuiLayout}. The click/drag handlers identify our
 * windows purely by {@code getHolder() instanceof MachineMenuHolder} — no title
 * string matching, no ambiguity, so foreign inventories are never touched.
 */
public final class MachineMenuHolder implements InventoryHolder {

    private final Machine machine;
    private final MachineType type;
    private final GuiLayout layout;
    private Inventory inventory;

    public MachineMenuHolder(Machine machine, MachineType type, GuiLayout layout) {
        this.machine = machine;
        this.type = type;
        this.layout = layout;
    }

    public Machine machine() { return machine; }
    public MachineType type() { return type; }
    public GuiLayout layout() { return layout; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
