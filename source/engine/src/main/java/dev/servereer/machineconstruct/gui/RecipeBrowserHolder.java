package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as a recipe-browser window and binds it to the machine it
 * was opened from (so the Back button can return to that machine's menu) and the
 * type whose recipes it lists, plus the current page. View-only — the click
 * handler treats every slot but the nav buttons as inert.
 */
public final class RecipeBrowserHolder implements InventoryHolder {

    private final Machine machine;
    private final MachineType type;
    private final int page;
    private Inventory inventory;

    public RecipeBrowserHolder(Machine machine, MachineType type, int page) {
        this.machine = machine;
        this.type = type;
        this.page = page;
    }

    public Machine machine() { return machine; }
    public MachineType type() { return type; }
    public int page() { return page; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
