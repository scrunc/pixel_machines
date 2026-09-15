package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Holder identifying a Jukebox window — the machine, which view is open, and the page (BROWSE only).
 * The jukebox GUI is rendered entirely in code (no authored {@link GuiLayout}).
 */
public final class JukeboxMenuHolder implements InventoryHolder {

    /** MAIN = now-playing + controls; BROWSE = track/playlist picker; PL_EDIT = playlist contents editor. */
    public enum View { MAIN, BROWSE, PL_EDIT }
    /** What the BROWSE view lists. */
    public enum BrowseMode { TRACKS, PLAYLISTS }

    private final Machine machine;
    private View view;
    private BrowseMode browseMode = BrowseMode.PLAYLISTS;   // library opens straight into category (playlist) mode
    private int page;
    private String editPlaylist;   // playlist being edited (PL_EDIT / add-to-playlist BROWSE)
    private boolean addMode;        // BROWSE tracks are being ADDED to editPlaylist (not loaded)
    private boolean addFromListed;  // add-picker source: false = tracks in NO playlist, true = tracks already in a playlist
    private Inventory inventory;

    public JukeboxMenuHolder(Machine machine, View view) {
        this.machine = machine;
        this.view = view;
    }

    public Machine machine() { return machine; }
    public View view() { return view; }
    public void setView(View view) { this.view = view; }
    public BrowseMode browseMode() { return browseMode; }
    public void setBrowseMode(BrowseMode m) { this.browseMode = m; this.page = 0; }
    public int page() { return page; }
    public void setPage(int page) { this.page = Math.max(0, page); }
    public String editPlaylist() { return editPlaylist; }
    public void setEditPlaylist(String n) { this.editPlaylist = n; }
    public boolean addMode() { return addMode; }
    public void setAddMode(boolean b) { this.addMode = b; }
    public boolean addFromListed() { return addFromListed; }
    public void setAddFromListed(boolean b) { this.addFromListed = b; this.page = 0; }

    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
