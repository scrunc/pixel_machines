package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Inventory holder marking a Virtual Trading Hall window (ADR 0011) — the identity the
 * sealed click-handling keys off, plus the machine + current view + page.
 */
public final class TradingHallMenuHolder implements InventoryHolder {

    public enum View { MAIN, BUILD, TRADER, VAULT, TILL }

    private final Machine machine;
    private View view;
    private int page;
    private int slotIndex = -1; // for TRADER view: which hall slot is open
    private boolean customer = false; // true = a non-owner shopfront customer (trade-only view)
    private Inventory inventory;

    public TradingHallMenuHolder(Machine machine, View view, int page) {
        this.machine = machine;
        this.view = view;
        this.page = page;
    }

    public Machine machine() { return machine; }
    public View view() { return view; }
    public void setView(View v) { this.view = v; }
    public int page() { return page; }
    public void setPage(int p) { this.page = Math.max(0, p); }
    public int slotIndex() { return slotIndex; }
    public void setSlotIndex(int i) { this.slotIndex = i; }
    public boolean customer() { return customer; }
    public void setCustomer(boolean c) { this.customer = c; }

    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
