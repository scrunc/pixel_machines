package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Holder for an Interdimensional Quarry window — machine, current screen, page. */
public final class QuarryMenuHolder implements InventoryHolder {

    public enum View { MAIN, VAULT, SKILLS }
    public enum Sort { QTY, VALUE, NAME }

    private final Machine machine;
    private View view;
    private int page;
    private int tab;                 // vault category tab (0=All,1=Ore,2=Gem,3=Block,4=Misc)
    private Sort sort = Sort.QTY;
    private boolean sellArmed;       // Sell-All double-confirm
    private boolean prestigeArmed;   // Prestige double-confirm
    private Inventory inventory;

    public QuarryMenuHolder(Machine machine, View view) {
        this.machine = machine;
        this.view = view;
    }

    public Machine machine() { return machine; }
    public View view() { return view; }
    public void setView(View view) { this.view = view; this.sellArmed = false; this.prestigeArmed = false; }
    public int page() { return page; }
    public void setPage(int page) { this.page = Math.max(0, page); }
    public int tab() { return tab; }
    public void setTab(int tab) { this.tab = tab; this.page = 0; }
    public Sort sort() { return sort; }
    public void setSort(Sort sort) { this.sort = sort; }
    public boolean sellArmed() { return sellArmed; }
    public void setSellArmed(boolean armed) { this.sellArmed = armed; }
    public boolean prestigeArmed() { return prestigeArmed; }
    public void setPrestigeArmed(boolean armed) { this.prestigeArmed = armed; }

    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}
