package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Inventory holder marking a Virtual Factory District window (ADR 0013) — the identity the
 * sealed click-handling keys off, plus the machine + current view + page + slot index.
 */
public final class FactoryDistrictMenuHolder implements InventoryHolder {

    public enum View { MAIN, CATEGORIES, BUILD, FACTORY, VAULT, RECIPES, VANILLA, CHESTS, CHEST_FILTER, AUTOSELL }

    private final Machine machine;
    private View view;
    private int page;
    private int slotIndex = -1;   // CATEGORIES/BUILD/FACTORY view: which district slot
    private int linkIndex = -1;   // CHEST_FILTER view: which output link is being edited
    private String categoryId;    // BUILD view: the category being browsed
    private Inventory inventory;

    public FactoryDistrictMenuHolder(Machine machine, View view, int page) {
        this.machine = machine;
        this.view = view;
        this.page = page;
    }

    public Machine machine() { return machine; }
    public View view() { return view; }
    public int page() { return page; }
    public int slotIndex() { return slotIndex; }
    public void setSlotIndex(int i) { this.slotIndex = i; }
    public int linkIndex() { return linkIndex; }
    public void setLinkIndex(int i) { this.linkIndex = i; }
    public String categoryId() { return categoryId; }
    public void setCategoryId(String id) { this.categoryId = id; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
