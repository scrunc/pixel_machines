package dev.servereer.machineconstruct.gui;

import dev.servereer.machineconstruct.api.MachineConstructAPI;
import dev.servereer.machineconstruct.jukebox.JukeboxActions;
import dev.servereer.machineconstruct.jukebox.JukeboxData;
import dev.servereer.machineconstruct.jukebox.JukeboxSpec;
import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import dev.servereer.machineconstruct.music.PlaylistLibrary;
import dev.servereer.machineconstruct.music.TrackLibrary;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Jukebox menu — a fully code-rendered, sealed GUI (no authored {@link GuiLayout}) with the same
 * un-glitchable discipline as {@link ChunkCollectorMenus}. Views: MAIN (now-playing + transport/craft),
 * BROWSE (tracks OR playlists picker), and PL_EDIT (build a playlist: add from URL via chat prompt, or
 * add from the library track list). Playlist editing is gated behind {@code machineconstruct.music.admin}.
 */
public final class JukeboxMenus implements Listener {

    private static final int SIZE = 54;

    // MAIN slots
    private static final int NOW_PLAYING = 4;
    private static final int B_BROWSE = 10, B_PLAY = 12, B_STOP = 13, B_SKIP = 14, B_LOOP = 16;
    // seek row: −10m −1m −10s [position] +10s +1m +10m
    private static final int SK_M10M = 19, SK_M1M = 20, SK_M10S = 21, POS = 22, SK_P10S = 23, SK_P1M = 24, SK_P10M = 25;
    // speed + volume row
    private static final int B_VOLDOWN = 28, B_SPEEDDOWN = 30, SPEED_DISP = 31, B_SPEEDUP = 32, B_VOLUP = 34;
    // bottom bar
    private static final int B_LOCK = 48, B_EJECT = 50;
    // BROWSE / PL_EDIT shared grid
    private static final int GRID_START = 9, GRID_END = 45;   // 36 per page
    private static final int PER_PAGE = GRID_END - GRID_START;
    private static final int NAV_PREV = 45, NAV_NEXT = 53;
    // BROWSE bottom bar
    private static final int BR_TOGGLE = 47, BR_BACK = 49, BR_NEW = 51, BR_PAGE = 51;   // BR_PAGE only shown in add-mode (BR_NEW only in playlists mode)
    // PL_EDIT bottom bar
    private static final int E_ADDURL = 46, E_BACK = 49, E_ADDLIB = 47, E_PLAY = 51;

    private static final String ADMIN = "machineconstruct.music.admin";

    private final Plugin plugin;
    private final MachineConstructAPI registry;
    private final JukeboxActions actions;
    private final TrackLibrary library;
    private final PlaylistLibrary playlists;

    private final Map<UUID, List<Inventory>> open = new HashMap<>();

    /** A pending chat prompt (New-Playlist name, or Add-URL to a playlist). */
    private enum Kind { NEW_NAME, ADD_URL }
    private record Pending(Machine machine, Kind kind, String playlist) {}
    private final Map<UUID, Pending> prompts = new HashMap<>();

    public JukeboxMenus(Plugin plugin, MachineConstructAPI registry, JukeboxActions actions,
                        TrackLibrary library, PlaylistLibrary playlists) {
        this.plugin = plugin;
        this.registry = registry;
        this.actions = actions;
        this.library = library;
        this.playlists = playlists;
    }

    public void open(Player player, Machine machine) {
        openView(player, new JukeboxMenuHolder(machine, JukeboxMenuHolder.View.MAIN));
    }

    /** Create + render a fresh window for the holder's current view and show it. */
    private void openView(Player player, JukeboxMenuHolder h) {
        Inventory inv = Bukkit.createInventory(h, SIZE, title(h));
        h.setInventory(inv);
        List<Inventory> list = open.computeIfAbsent(h.machine().id(), k -> new ArrayList<>());
        list.removeIf(i -> i.getViewers().isEmpty());
        list.add(inv);
        player.openInventory(inv);   // open first so getViewers() is populated for the admin-only lock button
        render(inv, h);
    }

    public void refresh(Machine machine) {
        List<Inventory> list = open.get(machine.id());
        if (list == null) return;
        list.removeIf(inv -> inv.getViewers().isEmpty());
        if (list.isEmpty()) { open.remove(machine.id()); return; }
        for (Inventory inv : list) if (inv.getHolder() instanceof JukeboxMenuHolder h) render(inv, h);
    }

    public void closeFor(Machine machine) {
        List<Inventory> list = open.remove(machine.id());
        if (list != null) for (Inventory inv : list)
            for (HumanEntity v : new ArrayList<>(inv.getViewers())) v.closeInventory();
    }

    private MachineType typeOf(Machine m) { return registry == null ? null : registry.getMachineType(m.typeId()); }

    // --- rendering ----------------------------------------------------------

    private void render(Inventory inv, JukeboxMenuHolder h) {
        inv.clear();
        switch (h.view()) {
            case BROWSE -> renderBrowse(inv, h);
            case PL_EDIT -> renderEdit(inv, h);
            default -> renderMain(inv, h);
        }
    }

    private void renderMain(Inventory inv, JukeboxMenuHolder h) {
        Machine m = h.machine();
        MachineType type = typeOf(m);
        JukeboxData d = m.jukebox();
        if (d == null || type == null || type.jukebox() == null) return;
        JukeboxSpec spec = type.jukebox();
        boolean admin = viewerIsAdmin(inv);
        boolean locked = d.locked();

        ItemStack edge = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, edge);

        boolean playing = d.playing() && !d.paused();
        boolean paused = d.paused();
        int reach = (int) (spec.distance(m.tier()) * d.volume());
        String state = playing ? "<green>▶ Playing" : paused ? "<gold>❚❚ Paused" : "<yellow>■ Stopped";

        // now playing
        List<String> npLore = new ArrayList<>();
        String npName; Material npIcon;
        if (d.hasPlaylist()) {
            String curId = actions.jukeboxCurrentTrackId(m);
            TrackLibrary.Track cur = curId == null ? null : library.get(curId);
            npName = "<aqua>▶ " + playlistDisplay(d.playlistName());
            npLore.add(state + " <dark_gray>· <gray>shuffle <green>on");
            if (cur != null) npLore.add("<gray>Now: <white>" + cur.title);
            else if (curId != null) npLore.add("<gray>Now: <white>" + curId);
            npLore.add("<gray>Tracks: <white>" + playlistCount(d.playlistName()) + " <dark_gray>· <gray>Loop: " + (d.loop() ? "<green>on" : "<red>off") + " <dark_gray>· <gray>Reach: <white>" + reach);
            npIcon = Material.CHEST_MINECART;
        } else if (d.hasTrack()) {
            TrackLibrary.Track t = library.get(d.trackId());
            npName = "<gold>♪ " + (t == null ? d.trackId() : t.title);
            npLore.add(state);
            npLore.add("<gray>Loop: " + (d.loop() ? "<green>on" : "<red>off") + " <dark_gray>· <gray>Reach: <white>" + reach);
            npIcon = Material.MUSIC_DISC_PIGSTEP;
        } else {
            npName = "<gray>— nothing loaded —";
            npLore.add("<gray>Click Browse to pick a category.");
            npIcon = Material.MUSIC_DISC_11;
        }
        if (locked) npLore.add("<red>🔒 Locked by an admin");
        inv.setItem(NOW_PLAYING, label(npIcon, npName, npLore));

        boolean hasContent = d.hasContent();
        inv.setItem(B_BROWSE, label(Material.CHEST, "<aqua>Browse library", List.of("<gray>Pick a category / track.", "<dark_gray>▶ Click")));
        if (playing) inv.setItem(B_PLAY, label(Material.HOPPER_MINECART, "<gold>❚❚ Pause", List.of("<dark_gray>▶ Click to pause")));
        else if (paused) inv.setItem(B_PLAY, label(Material.LIME_DYE, "<green>▶ Resume", List.of("<dark_gray>▶ Click to resume")));
        else inv.setItem(B_PLAY, label(hasContent ? Material.LIME_DYE : Material.GRAY_DYE, hasContent ? "<green>▶ Play" : "<dark_gray>Play",
                hasContent ? List.of("<dark_gray>▶ Click") : List.of("<dark_gray>Load something first")));
        inv.setItem(B_STOP, label(Material.REDSTONE_TORCH, "<red>■ Stop", List.of("<dark_gray>▶ Click to stop")));
        inv.setItem(B_SKIP, label(Material.ARROW, "<yellow>» Skip", List.of(d.hasPlaylist() ? "<gray>Shuffle to another track" : "<dark_gray>(playlist only)", "<dark_gray>▶ Click")));
        inv.setItem(B_LOOP, label(d.loop() ? Material.LIME_DYE : Material.GRAY_DYE, "<gold>Loop: " + (d.loop() ? "<green>ON" : "<red>OFF"), List.of("<dark_gray>▶ Click to toggle")));

        // seek row
        inv.setItem(SK_M10M, seekBtn("−10 min"));
        inv.setItem(SK_M1M, seekBtn("−1 min"));
        inv.setItem(SK_M10S, seekBtn("−10 sec"));
        long pos = actions.jukeboxPositionMs(m), len = actions.jukeboxLengthMs(m);
        List<String> posLore = new ArrayList<>();
        if (len > 0) { posLore.add("<white>" + fmtDur(pos) + " <dark_gray>/ <gray>" + fmtDur(len)); posLore.add(progressBar(pos, len)); }
        else posLore.add("<dark_gray>not playing");
        inv.setItem(POS, label(Material.CLOCK, "<yellow>Position", posLore));
        inv.setItem(SK_P10S, seekBtn("+10 sec"));
        inv.setItem(SK_P1M, seekBtn("+1 min"));
        inv.setItem(SK_P10M, seekBtn("+10 min"));

        // speed + volume row
        inv.setItem(B_VOLDOWN, label(Material.RED_DYE, "<red>Volume −", List.of("<gray>Reach: <white>" + reach + "<gray> blocks")));
        inv.setItem(B_SPEEDDOWN, label(Material.PRISMARINE_SHARD, "<red>Speed −", List.of("<dark_gray>▶ slower")));
        inv.setItem(SPEED_DISP, label(Material.NOTE_BLOCK, "<gold>Speed: <white>" + fmtSpeed(d.speed()) + "×",
                List.of("<gray>How fast the track plays.", "<dark_gray>higher = faster + higher pitch")));
        inv.setItem(B_SPEEDUP, label(Material.PRISMARINE_CRYSTALS, "<green>Speed +", List.of("<dark_gray>▶ faster")));
        inv.setItem(B_VOLUP, label(Material.GREEN_DYE, "<green>Volume +", List.of("<gray>Reach: <white>" + reach + "<gray> blocks")));

        // bottom: admin lock (only admins see it) + clear
        if (admin) inv.setItem(B_LOCK, label(Material.REDSTONE_LAMP, locked ? "<red>🔒 Locked" : "<green>🔓 Unlocked",
                List.of(locked ? "<gray>Only admins can change the song." : "<gray>Anyone can change the song.",
                        "<dark_gray>▶ Click to " + (locked ? "unlock" : "lock"))));
        inv.setItem(B_EJECT, label(Material.HOPPER, "<yellow>Clear", List.of("<gray>Stop and unload.", "<dark_gray>▶ Click")));
    }

    // --- MAIN helpers -------------------------------------------------------

    private boolean viewerIsAdmin(Inventory inv) {
        for (HumanEntity v : inv.getViewers()) return v.hasPermission(ADMIN);
        return false;
    }
    private boolean lockedFor(Player p, Machine m) {
        JukeboxData d = m.jukebox();
        return d != null && d.locked() && !p.hasPermission(ADMIN);
    }
    private boolean denyLocked(Player p, Machine m) {
        if (lockedFor(p, m)) { p.sendMessage(brand("§c🔒 This jukebox is locked by an admin.")); return true; }
        return false;
    }
    private String playlistDisplay(String name) {
        if (name == null) return "?";
        return PlaylistLibrary.UNLISTED.equals(name) ? "Unlisted" : name;
    }
    private int playlistCount(String name) {
        if (PlaylistLibrary.UNLISTED.equals(name)) return playlists == null ? 0 : playlists.unlisted(library).size();
        List<String> l = playlists == null ? null : playlists.get(name);
        return l == null ? 0 : l.size();
    }
    private ItemStack seekBtn(String label) {
        return label(Material.ARROW, "<yellow>" + label, List.of("<dark_gray>▶ jump within the song"));
    }
    private static String fmtSpeed(double s) {
        String v = String.format(java.util.Locale.ROOT, "%.2f", s);
        if (v.endsWith("0")) v = v.substring(0, v.length() - 1);   // 1.50 → 1.5, 1.00 → 1.0
        return v;
    }
    private static String progressBar(long pos, long len) {
        int seg = 10, done = len <= 0 ? 0 : (int) Math.round((double) pos / len * seg);
        done = Math.max(0, Math.min(seg, done));
        StringBuilder b = new StringBuilder("<green>");
        for (int i = 0; i < seg; i++) { if (i == done) b.append("<dark_gray>"); b.append('▮'); }
        return b.toString();
    }

    private void renderBrowse(Inventory inv, JukeboxMenuHolder h) {
        ItemStack edge = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, edge);
        boolean adding = h.addMode();

        List<String> rows = browseRows(h);
        int from = h.page() * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = from + i;
            if (idx >= rows.size()) { inv.setItem(GRID_START + i, null); continue; }
            String rid = rows.get(idx);
            if (adding) {                                       // track add-picker (playlist editing)
                TrackLibrary.Track t = library.get(rid);
                List<String> lore = new ArrayList<>();
                lore.add("<dark_gray>id: " + rid);
                if (t != null && t.durationMs > 0) lore.add("<dark_gray>" + fmtDur(t.durationMs));
                lore.add("<dark_gray>▶ Click to ADD to " + h.editPlaylist());
                inv.setItem(GRID_START + i, label(Material.MUSIC_DISC_11, "<gold>♪ " + (t == null ? rid : t.title), lore));
            } else if (PlaylistLibrary.UNLISTED.equals(rid)) {   // the default "Unlisted" category
                int n = playlists == null ? 0 : playlists.unlisted(library).size();
                inv.setItem(GRID_START + i, label(Material.JUKEBOX, "<gold>★ Unlisted",
                        List.of("<gray>" + n + " track(s) not in a playlist", "<dark_gray>▶ Click to open")));
            } else {                                            // a playlist category
                int n = playlists.get(rid) == null ? 0 : playlists.get(rid).size();
                inv.setItem(GRID_START + i, label(Material.CHEST_MINECART, "<aqua>▶ " + rid,
                        List.of("<gray>" + n + " tracks", "<dark_gray>▶ Click to open (play / edit)")));
            }
        }
        int pages = Math.max(1, (rows.size() + PER_PAGE - 1) / PER_PAGE);
        if (adding) {
            boolean listed = h.addFromListed();
            inv.setItem(BR_TOGGLE, label(listed ? Material.CHEST_MINECART : Material.MUSIC_DISC_11,
                    listed ? "<aqua>Source: In a playlist" : "<gold>Source: Unlisted tracks",
                    List.of(listed ? "<gray>Tracks already in some playlist." : "<gray>Tracks not in any playlist yet.",
                            "<dark_gray>▶ Click to switch source")));
            inv.setItem(BR_PAGE, label(Material.PAPER, "<gray>Page <white>" + (h.page() + 1) + "<gray>/<white>" + pages,
                    List.of("<dark_gray>" + rows.size() + " track(s) to add")));
            inv.setItem(BR_BACK, label(Material.LIME_STAINED_GLASS_PANE, "<green>✓ Done", List.of("<gray>Back to §f" + h.editPlaylist())));
        } else {
            inv.setItem(BR_BACK, label(Material.BARRIER, "<red>Back", List.of("<gray>Back to the jukebox")));
            inv.setItem(BR_NEW, label(Material.WRITABLE_BOOK, "<green>✚ New Category", List.of("<gray>Create a playlist, then add", "<gray>tracks by URL or from the library.", "<dark_gray>▶ Click (admin)")));
        }
        if (h.page() > 0) inv.setItem(NAV_PREV, label(Material.ARROW, "<yellow>◀ Previous", List.of()));
        if (from + PER_PAGE < rows.size()) inv.setItem(NAV_NEXT, label(Material.ARROW, "<yellow>Next ▶", List.of()));
    }

    /** The rows shown in BROWSE: filtered add-candidates when adding, else the categories (Unlisted + playlists). */
    private List<String> browseRows(JukeboxMenuHolder h) {
        if (h.addMode()) {
            java.util.Set<String> current = new java.util.HashSet<>();
            List<String> cur = playlists.get(h.editPlaylist());
            if (cur != null) current.addAll(cur);
            java.util.Set<String> listed = playlists.allTrackIds();
            List<String> out = new ArrayList<>();
            for (TrackLibrary.Track t : library.all()) {
                if (current.contains(t.id)) continue;                 // already in this playlist
                boolean inListed = listed.contains(t.id);
                if (h.addFromListed() == inListed) out.add(t.id);      // match the chosen source
            }
            return out;
        }
        return categoryRows();
    }

    /** The category list: the synthetic "Unlisted" bucket first, then every named playlist. */
    private List<String> categoryRows() {
        List<String> out = new ArrayList<>();
        out.add(PlaylistLibrary.UNLISTED);
        if (playlists != null) out.addAll(playlists.names());
        return out;
    }

    private void renderEdit(Inventory inv, JukeboxMenuHolder h) {
        ItemStack edge = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, edge);
        String name = h.editPlaylist();
        boolean unlisted = PlaylistLibrary.UNLISTED.equals(name);
        List<String> ids = unlisted ? (playlists == null ? new ArrayList<>() : playlists.unlisted(library))
                                     : (name == null ? null : playlists.get(name));
        if (ids == null) ids = new ArrayList<>();

        int from = h.page() * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = from + i;
            if (idx >= ids.size()) { inv.setItem(GRID_START + i, null); continue; }
            String id = ids.get(idx);
            TrackLibrary.Track t = library.get(id);
            inv.setItem(GRID_START + i, label(Material.MUSIC_DISC_11, "<gold>♪ " + (t == null ? id : t.title),
                    List.of("<dark_gray>id: " + id, "<dark_gray>▶ Click to play this track")));
        }
        inv.setItem(E_PLAY, label(Material.LIME_DYE, "<green>▶ Load & Play", List.of("<gray>Play this whole category (shuffled).", "<dark_gray>▶ Click")));
        if (!unlisted) {   // can't add tracks to the synthetic Unlisted bucket
            inv.setItem(E_ADDURL, label(Material.LEAD, "<aqua>✚ Add from URL", List.of("<gray>Paste a video URL in chat.", "<dark_gray>▶ Click (admin)")));
            inv.setItem(E_ADDLIB, label(Material.CHEST, "<aqua>✚ Add from Library", List.of("<gray>Pick saved tracks to add.", "<dark_gray>▶ Click (admin)")));
        }
        inv.setItem(E_BACK, label(Material.ARROW, "<yellow>◀ Back to categories", List.of("<gray>" + ids.size() + " tracks")));
        if (h.page() > 0) inv.setItem(NAV_PREV, label(Material.ARROW, "<yellow>◀ Previous", List.of()));
        if (from + PER_PAGE < ids.size()) inv.setItem(NAV_NEXT, label(Material.ARROW, "<yellow>Next ▶", List.of()));
    }

    // --- clicks -------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof JukeboxMenuHolder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) { e.setCancelled(true); return; }
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE) {
            if (e.getClick() == ClickType.DOUBLE_CLICK || e.isShiftClick()) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        Machine m = h.machine();
        try {
            switch (h.view()) {
                case BROWSE -> onBrowseClick(p, h, m, raw, e.getClick());
                case PL_EDIT -> onEditClick(p, h, m, raw);
                default -> onMainClick(p, h, m, raw);
            }
        } catch (Throwable t) {
            refresh(m);
            p.updateInventory();
        }
    }

    private void onMainClick(Player p, JukeboxMenuHolder h, Machine m, int raw) {
        JukeboxData d = m.jukebox();
        if (raw == B_BROWSE) { h.setView(JukeboxMenuHolder.View.BROWSE); h.setBrowseMode(JukeboxMenuHolder.BrowseMode.PLAYLISTS); h.setAddMode(false); h.setPage(0); openView(p, h); return; }
        if (raw == B_LOCK) { if (p.hasPermission(ADMIN)) { actions.jukeboxToggleLock(m); refresh(m); } return; }
        if (denyLocked(p, m)) return;   // song controls are gated while an admin has it locked
        switch (raw) {
            case B_PLAY -> {
                if (d != null && d.playing() && !d.paused()) actions.jukeboxPause(m);
                else if (d != null && d.paused()) actions.jukeboxResume(m);
                else actions.jukeboxPlay(m);
                refresh(m);
            }
            case B_STOP -> { actions.jukeboxStop(m); refresh(m); }
            case B_SKIP -> { actions.jukeboxSkip(m); plugin.getServer().getScheduler().runTaskLater(plugin, () -> refresh(m), 30L); refresh(m); }
            case B_LOOP -> { actions.jukeboxToggleLoop(m); refresh(m); }
            case B_VOLDOWN -> { actions.jukeboxAdjustVolume(m, -0.2); refresh(m); }
            case B_VOLUP -> { actions.jukeboxAdjustVolume(m, +0.2); refresh(m); }
            case B_SPEEDDOWN -> { actions.jukeboxAdjustSpeed(m, -0.25); refresh(m); }
            case B_SPEEDUP -> { actions.jukeboxAdjustSpeed(m, +0.25); refresh(m); }
            case SK_M10M -> { actions.jukeboxSeek(m, -600_000); refresh(m); }
            case SK_M1M  -> { actions.jukeboxSeek(m, -60_000); refresh(m); }
            case SK_M10S -> { actions.jukeboxSeek(m, -10_000); refresh(m); }
            case SK_P10S -> { actions.jukeboxSeek(m, 10_000); refresh(m); }
            case SK_P1M  -> { actions.jukeboxSeek(m, 60_000); refresh(m); }
            case SK_P10M -> { actions.jukeboxSeek(m, 600_000); refresh(m); }
            case B_EJECT -> { actions.jukeboxEject(p, m); refresh(m); }
            default -> { }
        }
    }

    private void onBrowseClick(Player p, JukeboxMenuHolder h, Machine m, int raw, ClickType click) {
        boolean adding = h.addMode();
        if (raw == NAV_PREV) { h.setPage(h.page() - 1); refresh(m); return; }
        if (raw == NAV_NEXT) { h.setPage(h.page() + 1); refresh(m); return; }
        if (raw == BR_BACK) {
            if (adding) { h.setAddMode(false); h.setView(JukeboxMenuHolder.View.PL_EDIT); h.setPage(0); openView(p, h); }
            else { h.setView(JukeboxMenuHolder.View.MAIN); openView(p, h); }
            return;
        }
        if (adding && raw == BR_TOGGLE) {   // switch add-source: unlisted ↔ in-a-playlist
            h.setAddFromListed(!h.addFromListed());
            refresh(m); return;
        }
        if (!adding && raw == BR_NEW) {
            if (!p.hasPermission(ADMIN)) { p.sendMessage(brand("§cno permission to make categories.")); return; }
            promptNewPlaylist(p, m);
            return;
        }
        if (raw >= GRID_START && raw < GRID_END) {
            List<String> rowIds = browseRows(h);
            int idx = h.page() * PER_PAGE + (raw - GRID_START);
            if (idx >= rowIds.size()) return;
            String rid = rowIds.get(idx);
            if (adding) {                     // add a library track to the editing playlist
                if (!p.hasPermission(ADMIN)) { p.sendMessage(brand("§cno permission.")); return; }
                boolean ok = playlists.add(h.editPlaylist(), rid);
                TrackLibrary.Track t = library.get(rid);
                p.sendMessage(brand(ok ? "§aadded §f" + (t == null ? rid : t.title) + "§a to §f" + h.editPlaylist() : "§7already in the playlist."));
                refresh(m);   // it leaves the candidate list → list shrinks live
                return;
            }
            // open the category (Unlisted or a playlist) → its track list
            h.setEditPlaylist(rid); h.setView(JukeboxMenuHolder.View.PL_EDIT); h.setPage(0); openView(p, h);
        }
    }

    private void onEditClick(Player p, JukeboxMenuHolder h, Machine m, int raw) {
        String name = h.editPlaylist();
        boolean unlisted = PlaylistLibrary.UNLISTED.equals(name);
        boolean admin = p.hasPermission(ADMIN);
        switch (raw) {
            // open to everyone: browse the category, play the whole list, or pick a single track
            case NAV_PREV -> { h.setPage(h.page() - 1); refresh(m); }
            case NAV_NEXT -> { h.setPage(h.page() + 1); refresh(m); }
            case E_BACK -> { h.setView(JukeboxMenuHolder.View.BROWSE); h.setBrowseMode(JukeboxMenuHolder.BrowseMode.PLAYLISTS); h.setPage(0); openView(p, h); }
            case E_PLAY -> {
                if (denyLocked(p, m)) return;
                actions.jukeboxSetPlaylist(m, name); actions.jukeboxPlay(m);
                h.setView(JukeboxMenuHolder.View.MAIN); openView(p, h);
            }
            // adding is admin-only + not available for Unlisted; NO remove here (use /music playlist remove)
            case E_ADDURL -> { if (unlisted) return; if (!admin) { p.sendMessage(brand("§cno permission to edit playlists.")); return; } promptAddUrl(p, m, name); }
            case E_ADDLIB -> { if (unlisted) return; if (!admin) { p.sendMessage(brand("§cno permission to edit playlists.")); return; }
                h.setAddMode(true); h.setAddFromListed(false); h.setView(JukeboxMenuHolder.View.BROWSE); h.setBrowseMode(JukeboxMenuHolder.BrowseMode.TRACKS); h.setPage(0); openView(p, h); }
            default -> {
                if (raw >= GRID_START && raw < GRID_END) {   // click a track → play just that track
                    if (denyLocked(p, m)) return;
                    List<String> ids = unlisted ? (playlists == null ? null : playlists.unlisted(library)) : playlists.get(name);
                    if (ids == null) return;
                    int idx = h.page() * PER_PAGE + (raw - GRID_START);
                    if (idx >= ids.size()) return;
                    actions.jukeboxSetTrack(m, ids.get(idx));
                    actions.jukeboxPlay(m);
                    h.setView(JukeboxMenuHolder.View.MAIN);
                    openView(p, h);
                }
            }
        }
    }

    // --- chat prompts -------------------------------------------------------

    private void promptNewPlaylist(Player p, Machine m) {
        prompts.put(p.getUniqueId(), new Pending(m, Kind.NEW_NAME, null));
        p.closeInventory();
        p.sendMessage(brand("§7Type a §fname§7 for the new playlist in chat §8(or 'cancel')§7."));
    }

    private void promptAddUrl(Player p, Machine m, String playlist) {
        prompts.put(p.getUniqueId(), new Pending(m, Kind.ADD_URL, playlist));
        p.closeInventory();
        p.sendMessage(brand("§7Paste a §fvideo URL§7 in chat to add to §f" + playlist + " §8(or 'cancel')§7."));
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Pending pending = prompts.remove(e.getPlayer().getUniqueId());
        if (pending == null) return;
        e.setCancelled(true);   // consume — don't broadcast the answer
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (text.isEmpty() || text.equalsIgnoreCase("cancel")) { p.sendMessage(brand("§7cancelled.")); return; }
            switch (pending.kind()) {
                case NEW_NAME -> {
                    String name = playlists.create(text);
                    p.sendMessage(brand("§acreated playlist §f" + name + "§a."));
                    JukeboxMenuHolder h = new JukeboxMenuHolder(pending.machine(), JukeboxMenuHolder.View.PL_EDIT);
                    h.setEditPlaylist(name);
                    openView(p, h);
                }
                case ADD_URL -> actions.jukeboxAddUrlToPlaylist(p, pending.playlist(), text, () -> {
                    JukeboxMenuHolder h = new JukeboxMenuHolder(pending.machine(), JukeboxMenuHolder.View.PL_EDIT);
                    h.setEditPlaylist(pending.playlist());
                    openView(p, h);
                });
            }
        });
    }

    // --- events -------------------------------------------------------------

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof JukeboxMenuHolder)) return;
        for (int raw : e.getRawSlots()) if (raw < SIZE) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof JukeboxMenuHolder h)) return;
        List<Inventory> list = open.get(h.machine().id());
        if (list != null) {
            list.remove(e.getInventory());
            if (list.isEmpty()) open.remove(h.machine().id());
        }
    }

    // --- helpers ------------------------------------------------------------

    private static String fmtDur(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private static String brand(String s) { return "§x§d§4§a§f§3§7Jukebox §8» " + s; }

    private Component title(JukeboxMenuHolder h) {
        String s = switch (h.view()) {
            case BROWSE -> h.addMode() ? "<dark_aqua>✦ Add to " + playlistDisplay(h.editPlaylist()) : "<dark_aqua>✦ Jukebox · Library";
            case PL_EDIT -> "<dark_aqua>✦ Category · " + playlistDisplay(h.editPlaylist());
            default -> "<dark_aqua>✦ Jukebox";
        };
        return mini(s);
    }

    private ItemStack pane(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) { meta.displayName(mini(" ")); it.setItemMeta(meta); }
        return it;
    }

    private ItemStack label(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(mini(name));
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String s : lore) lines.add(mini(s));
                meta.lore(lines);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component mini(String s) {
        return MiniMessage.miniMessage().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }
}
