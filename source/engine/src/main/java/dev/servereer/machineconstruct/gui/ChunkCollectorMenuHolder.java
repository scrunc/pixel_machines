package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Holder identifying a Chunk Collector window — the machine, which view is open, and
 * the current page. The collector GUI is rendered entirely in code (no authored
 * {@link GuiLayout}), so this is the only state the click handler needs.
 */
public final class ChunkCollectorMenuHolder implements InventoryHolder {

    /** Which screen of the collector is open. */
    public enum View { MAIN, LOG, CHESTS, CHEST_FILTER, AUTOSELL }
    /** Vault-browser sort order. */
    public enum Sort { QTY, VALUE, NAME }

    private final Machine machine;
    private View view;
    private int page;
    private int tab;               // vault category tab (0=All,1=Ores,2=Gems,3=Blocks,4=Misc)
    private Sort sort = Sort.QTY;
    private boolean sellArmed;     // Sell-All double-confirm
    private int linkIndex = -1;    // selected chest-link index (CHESTS / CHEST_FILTER views)
    private Inventory inventory;

    public ChunkCollectorMenuHolder(Machine machine, View view, int page) {
        this.machine = machine;
        this.view = view;
        this.page = page;
    }

    public Machine machine() { return machine; }
    public View view() { return view; }
    public void setView(View view) { this.view = view; this.sellArmed = false; }
    public int page() { return page; }
    public void setPage(int page) { this.page = Math.max(0, page); }
    public int tab() { return tab; }
    public void setTab(int tab) { this.tab = tab; this.page = 0; }
    public Sort sort() { return sort; }
    public void setSort(Sort sort) { this.sort = sort; }
    public boolean sellArmed() { return sellArmed; }
    public void setSellArmed(boolean armed) { this.sellArmed = armed; }
    public int linkIndex() { return linkIndex; }
    public void setLinkIndex(int i) { this.linkIndex = i; }

    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
