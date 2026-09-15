package dev.servereer.machineconstruct.music;

import dev.servereer.machineconstruct.audio.TrackIngest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /music} — the player/admin surface over the {@link TrackLibrary}, {@link PlaylistLibrary} and
 * {@link MusicPlayer}: download from a URL (single video OR a whole playlist, id defaulting to the video
 * title), list/remove tracks, manage playlists, play (locational / radio / personal) with pause/resume/
 * skip/stop, and mint discs.
 */
public final class MusicCommand implements CommandExecutor, TabCompleter {

    private static final String BRAND = "§x§d§4§a§f§3§7Music §8» ";

    private final JavaPlugin plugin;
    private final TrackIngest ingest;
    private final TrackLibrary library;
    private final PlaylistLibrary playlists;
    private final MusicPlayer player;
    private final DiscItem discItem;

    public MusicCommand(JavaPlugin plugin, TrackIngest ingest, TrackLibrary library, PlaylistLibrary playlists,
                        MusicPlayer player, DiscItem discItem) {
        this.plugin = plugin;
        this.ingest = ingest;
        this.library = library;
        this.playlists = playlists;
        this.player = player;
        this.discItem = discItem;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        switch (args[0].toLowerCase()) {
            case "list" -> doList(sender, args);
            case "scan" -> { if (admin(sender)) { library.load(); playlists.load(); sender.sendMessage(BRAND + "§arescanned — §f" + library.size() + "§a track(s), §f" + playlists.size() + "§a playlist(s)."); } }
            case "login", "cookies", "auth" -> { if (admin(sender)) doLogin(sender); }
            case "download", "dl" -> doDownload(sender, args);
            case "remove", "delete" -> doRemove(sender, args);
            case "play" -> doPlay(sender, args, Target.LOCATIONAL);
            case "radio" -> { if (admin(sender)) doPlay(sender, args, Target.RADIO); }
            case "personal", "me" -> doPlay(sender, args, Target.PERSONAL);
            case "pause" -> doPause(sender);
            case "resume" -> doResume(sender);
            case "skip", "next" -> doSkip(sender);
            case "stop" -> doStop(sender, args);
            case "playlist", "pl" -> doPlaylist(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private enum Target { LOCATIONAL, RADIO, PERSONAL }

    // --- list ---------------------------------------------------------------

    private void doLogin(CommandSender sender) {
        net.kyori.adventure.text.minimessage.MiniMessage mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        boolean has = ingest.cookiesFile() != null;
        sender.sendMessage(mm.deserialize("<gradient:#f9d423:#ff4e50>♪ YouTube login</gradient> <dark_gray>— status: " + (has ? "<green>cookies detected ✔" : "<red>no cookies yet ✘")));
        sender.sendMessage(mm.deserialize("<gray>YouTube blocks downloads from servers until you add a login cookie. It's 3 clicks:"));
        sender.sendMessage(mm.deserialize("<white>1) <gray>Install <click:open_url:'https://chromewebstore.google.com/detail/get-cookiestxt-locally/cclelndahbckbenkjhflpdbgdldlbecc'><aqua><u>Get cookies.txt LOCALLY</u></aqua></click> <dark_gray>(Chrome)<gray> or <click:open_url:'https://addons.mozilla.org/en-US/firefox/addon/cookies-txt/'><aqua><u>cookies.txt</u></aqua></click> <dark_gray>(Firefox)"));
        sender.sendMessage(mm.deserialize("<white>2) <gray>Open <click:open_url:'https://www.youtube.com'><aqua><u>YouTube</u></aqua></click> <gray>logged in <dark_gray>(use a throwaway Google account)<gray>, click the extension → <white>Export</white> → saves <white>cookies.txt</white>"));
        sender.sendMessage(mm.deserialize("<white>3) <gray>Upload <white>cookies.txt</white> into <white>plugins/MachineConstruct/</white> <dark_gray>(panel file manager or SFTP)<gray> — it's picked up automatically."));
        sender.sendMessage(mm.deserialize("<dark_gray>Then retry your download. Re-check with <white>/music login</white>."));
    }

    private void doList(CommandSender sender, String[] args) {
        if (args.length > 1 && (args[1].equalsIgnoreCase("playlists") || args[1].equalsIgnoreCase("pl"))) {
            List<String> names = playlists.names();
            if (names.isEmpty()) { sender.sendMessage(BRAND + "§7no playlists yet — §f/music playlist create <name>"); return; }
            sender.sendMessage(BRAND + "§7playlists (§f" + names.size() + "§7):");
            for (String n : names) sender.sendMessage("§8• §f" + n + " §7(" + playlists.get(n).size() + " tracks)");
            return;
        }
        List<TrackLibrary.Track> all = library.all();
        if (all.isEmpty()) { sender.sendMessage(BRAND + "§7no saved tracks yet — §f/music download <url>"); return; }
        sender.sendMessage(BRAND + "§7saved tracks (§f" + all.size() + "§7):");
        for (TrackLibrary.Track t : all) sender.sendMessage("§8• §f" + t.id + " §7" + t.title + dur(t.durationMs));
    }

    // --- download (single video OR playlist) --------------------------------

    private void doDownload(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 2) { sender.sendMessage(BRAND + "§7usage: §f/music download <url> [id] [title…]"); return; }
        if (!ingest.available()) { sender.sendMessage(BRAND + "§cffmpeg/yt-dlp not found — set music.bin-dir in config."); return; }
        String url = args[1];

        if (TrackIngest.isPlaylistUrl(url)) {
            String plNameArg = args.length > 2 ? args[2] : null;
            sender.sendMessage(BRAND + "§7reading playlist… (this may take a moment)");
            ingest.enumeratePlaylist(url, (plTitle, entries, err) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (entries == null || entries.isEmpty()) { sender.sendMessage(BRAND + "§ccouldn't read playlist: §f" + err + botHint(err)); return; }
                String name = PlaylistLibrary.sanitize(plNameArg != null ? plNameArg : (plTitle != null ? plTitle : "playlist"));
                sender.sendMessage(BRAND + "§adownloading §f" + entries.size() + "§a tracks into playlist §f" + name + "§a…");
                downloadSequential(sender, entries, 0, new ArrayList<>(), new java.util.HashSet<>(), name);
            }));
            return;
        }

        // single video
        if (args.length >= 3) {
            String id = TrackLibrary.sanitize(args[2]);
            String title = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : id;
            startSingleDownload(sender, url, id, title);
        } else {
            // no id given → derive from the video title
            sender.sendMessage(BRAND + "§7fetching title…");
            ingest.fetchTitle(url, (title, err) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (title == null) { sender.sendMessage(BRAND + "§ccouldn't read title: §f" + err + " §7— give an id: §f/music download <url> <id>"); return; }
                startSingleDownload(sender, url, TrackLibrary.sanitize(title), title);
            }));
        }
    }

    private void startSingleDownload(CommandSender sender, String url, String id, String title) {
        sender.sendMessage(BRAND + "§7downloading §f" + id + "§7…");
        library.downloadUrl(url, id, title, sender.getName(), (ok, err) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ok
                        ? BRAND + "§asaved §f" + id + "§a — §f/music play " + id
                        : BRAND + "§cdownload failed: §f" + err + botHint(err))));
    }

    /** Download playlist entries one at a time (avoids spawning N yt-dlp at once), then save the playlist. */
    private void downloadSequential(CommandSender sender, List<TrackIngest.Entry> entries, int i,
                                    List<String> doneIds, java.util.Set<String> used, String plName) {
        if (i >= entries.size()) {
            playlists.set(plName, doneIds);
            sender.sendMessage(BRAND + "§aplaylist §f" + plName + "§a ready — §f" + doneIds.size() + "§a tracks. §7Play: §f/music play pl:" + plName);
            return;
        }
        TrackIngest.Entry e = entries.get(i);
        String base = TrackLibrary.sanitize(e.title);
        String id = base;
        int n = 2;
        while (used.contains(id)) id = base + "-" + (n++);   // unique within this import
        used.add(id);
        final String fid = id;
        library.downloadUrl(e.url, fid, e.title, sender.getName(), (ok, err) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (ok) { doneIds.add(fid); sender.sendMessage(BRAND + "§8[" + (i + 1) + "/" + entries.size() + "] §asaved §f" + fid); }
                    else sender.sendMessage(BRAND + "§8[" + (i + 1) + "/" + entries.size() + "] §cskipped §f" + fid + " §8(" + err + ")");
                    downloadSequential(sender, entries, i + 1, doneIds, used, plName);
                }));
    }

    // --- remove -------------------------------------------------------------

    private void doRemove(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 2) { sender.sendMessage(BRAND + "§7usage: §f/music remove <id>"); return; }
        String id = TrackLibrary.sanitize(args[1]);
        if (library.remove(id)) { playlists.purgeTrack(id); sender.sendMessage(BRAND + "§aremoved §f" + id + "§a."); }
        else sender.sendMessage(BRAND + "§cno track '§f" + args[1] + "§c'.");
    }

    // --- playback -----------------------------------------------------------

    private void doPlay(CommandSender sender, String[] args, Target target) {
        boolean needsPlayer = target != Target.RADIO;
        if (needsPlayer && !(sender instanceof Player)) { sender.sendMessage(BRAND + "§cplayer only."); return; }
        if (args.length < 2) { sender.sendMessage(BRAND + "§7usage: §f/music " + args[0].toLowerCase() + " <id|pl:name> [loop]"); return; }
        if (!player.available()) { sender.sendMessage(BRAND + "§cSimple Voice Chat isn't active — can't play audio."); return; }
        List<String> queue = resolveQueue(sender, args[1]);
        if (queue == null) return;
        boolean loop = flag(args, "loop");
        switch (target) {
            case LOCATIONAL -> {
                Player p = (Player) sender;
                float dist = (float) plugin.getConfig().getDouble("music.command-distance", 48.0);
                boolean shuffle = args[1].toLowerCase().startsWith("pl:");   // shuffle when playing a playlist
                player.playLocational("cmd:" + p.getUniqueId(), queue, p.getLocation(), dist, loop, shuffle, 1.0, null,
                        ok -> p.sendMessage(ok ? BRAND + "§aplaying §f" + args[1] + "§a here." : BRAND + "§ccouldn't play that."));
            }
            case PERSONAL -> {
                Player p = (Player) sender;
                player.playPersonal(p, queue, loop, ok -> p.sendMessage(ok ? BRAND + "§aplaying §f" + args[1] + "§a just for you." : BRAND + "§ccouldn't play that."));
            }
            case RADIO -> player.startRadio(queue, loop, ok -> sender.sendMessage(ok ? BRAND + "§abroadcasting §f" + args[1] + "§a to everyone." : BRAND + "§ccouldn't start radio."));
        }
    }

    /** Resolve an id or {@code pl:<name>} into a play queue; sends an error and returns null on failure. */
    private List<String> resolveQueue(CommandSender sender, String arg) {
        if (arg.regionMatches(true, 0, "pl:", 0, 3)) {
            String name = arg.substring(3);
            List<String> ids = playlists.get(name);
            if (ids == null) { sender.sendMessage(BRAND + "§cno playlist '§f" + name + "§c'. §7Try §f/music list playlists"); return null; }
            List<String> live = new ArrayList<>();
            for (String id : ids) if (library.get(id) != null) live.add(id);
            if (live.isEmpty()) { sender.sendMessage(BRAND + "§cplaylist '§f" + name + "§c' has no playable tracks."); return null; }
            return live;
        }
        if (library.get(arg) == null) { sender.sendMessage(BRAND + "§cno track '§f" + arg + "§c'. §7Try §f/music list"); return null; }
        return List.of(TrackLibrary.sanitize(arg));
    }

    // --- transport (player's own streams) -----------------------------------

    private void doPause(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(BRAND + "§cplayer only."); return; }
        boolean any = player.pause("cmd:" + p.getUniqueId()) | player.pause("personal:" + p.getUniqueId());
        p.sendMessage(any ? BRAND + "§7paused." : BRAND + "§7nothing playing.");
    }

    private void doResume(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(BRAND + "§cplayer only."); return; }
        boolean any = player.resume("cmd:" + p.getUniqueId()) | player.resume("personal:" + p.getUniqueId());
        p.sendMessage(any ? BRAND + "§aresumed." : BRAND + "§7nothing paused.");
    }

    private void doSkip(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(BRAND + "§cplayer only."); return; }
        boolean any = player.skip("cmd:" + p.getUniqueId()) | player.skip("personal:" + p.getUniqueId());
        p.sendMessage(any ? BRAND + "§7skipped." : BRAND + "§7nothing to skip.");
    }

    private void doStop(CommandSender sender, String[] args) {
        String what = args.length > 1 ? args[1].toLowerCase() : "";
        if (what.equals("all")) {
            if (!admin(sender)) return;
            player.stopAll();
            sender.sendMessage(BRAND + "§7stopped ALL music.");
            return;
        }
        if (what.equals("radio")) {
            if (!admin(sender)) return;
            player.stop(MusicPlayer.RADIO_KEY);
            sender.sendMessage(BRAND + "§7radio stopped.");
            return;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage(BRAND + "§7use §f/music stop radio§7 or §f/music stop all§7 from console."); return; }
        player.stop("cmd:" + p.getUniqueId());
        player.stop("personal:" + p.getUniqueId());
        p.sendMessage(BRAND + "§7stopped your music.");
    }

    // --- playlist management ------------------------------------------------

    private void doPlaylist(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(BRAND + "§7/music playlist §fcreate|delete|add|remove|list|play §7<name> [args]"); return; }
        String sub = args[1].toLowerCase();
        if (sub.equals("list")) {
            if (args.length < 3) { doList(sender, new String[]{ "list", "playlists" }); return; }
            List<String> ids = playlists.get(args[2]);
            if (ids == null) { sender.sendMessage(BRAND + "§cno playlist '§f" + args[2] + "§c'."); return; }
            sender.sendMessage(BRAND + "§7playlist §f" + PlaylistLibrary.sanitize(args[2]) + "§7 (" + ids.size() + "):");
            for (String id : ids) { TrackLibrary.Track t = library.get(id); sender.sendMessage("§8• §f" + id + (t == null ? " §c(missing)" : " §7" + t.title)); }
            return;
        }
        if (sub.equals("play")) {
            if (args.length < 3) { sender.sendMessage(BRAND + "§7usage: §f/music playlist play <name> [radio|me] [loop]"); return; }
            String dest = args.length > 3 ? args[3].toLowerCase() : "here";
            Target target = dest.equals("radio") ? Target.RADIO : (dest.equals("me") || dest.equals("personal")) ? Target.PERSONAL : Target.LOCATIONAL;
            if (target == Target.RADIO && !admin(sender)) return;
            doPlay(sender, new String[]{ args[0], "pl:" + args[2], flag(args, "loop") ? "loop" : "" }, target);
            return;
        }
        if (!admin(sender)) return;   // create/delete/add/remove are admin
        switch (sub) {
            case "create" -> { if (args.length < 3) { sender.sendMessage(BRAND + "§7usage: §f/music playlist create <name>"); return; }
                sender.sendMessage(BRAND + "§acreated playlist §f" + playlists.create(args[2]) + "§a."); }
            case "delete" -> { if (args.length < 3) { sender.sendMessage(BRAND + "§7usage: §f/music playlist delete <name>"); return; }
                sender.sendMessage(playlists.delete(args[2]) ? BRAND + "§adeleted §f" + args[2] + "§a." : BRAND + "§cno playlist '§f" + args[2] + "§c'."); }
            case "add" -> { if (args.length < 4) { sender.sendMessage(BRAND + "§7usage: §f/music playlist add <name> <trackId>"); return; }
                String id = TrackLibrary.sanitize(args[3]);
                if (library.get(id) == null) { sender.sendMessage(BRAND + "§cno track '§f" + args[3] + "§c'."); return; }
                sender.sendMessage(playlists.add(args[2], id) ? BRAND + "§aadded §f" + id + "§a to §f" + PlaylistLibrary.sanitize(args[2]) : BRAND + "§7already in the playlist."); }
            case "remove" -> { if (args.length < 4) { sender.sendMessage(BRAND + "§7usage: §f/music playlist remove <name> <trackId>"); return; }
                sender.sendMessage(playlists.removeTrack(args[2], TrackLibrary.sanitize(args[3])) ? BRAND + "§aremoved from §f" + PlaylistLibrary.sanitize(args[2]) : BRAND + "§cnot in that playlist."); }
            default -> sender.sendMessage(BRAND + "§7/music playlist §fcreate|delete|add|remove|list|play");
        }
    }

    // --- helpers ------------------------------------------------------------

    private static String dur(long ms) {
        return ms > 0 ? " §8(" + (ms / 60000) + ":" + String.format("%02d", (ms / 1000) % 60) + ")" : "";
    }

    private static boolean flag(String[] args, String f) {
        for (String a : args) if (a.equalsIgnoreCase(f)) return true;
        return false;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("machineconstruct.music.admin")) return true;
        sender.sendMessage(BRAND + "§cno permission.");
        return false;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(BRAND + "§7play: §flist§7, §fplay <id|pl:name>§7, §fradio§7, §fpersonal§7, §fpause§7, §fresume§7, §fskip§7, §fstop [radio|all]");
        sender.sendMessage(BRAND + "§7manage: §fdownload <url> [id]§7, §flogin§7, §fremove <id>§7, §fplaylist …§7, §fscan");
    }

    /** Append a hint to run /music login when a failure looks like YouTube's bot/cookie wall. */
    private static String botHint(String err) {
        if (err == null) return "";
        String l = err.toLowerCase();
        return (l.contains("sign in") || l.contains("bot") || l.contains("cookie")) ? " §7— run §f/music login" : "";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("list", "play", "radio", "personal", "pause", "resume", "skip", "stop", "download", "login", "remove", "playlist", "scan"));
            subs.removeIf(s -> !s.startsWith(args[0].toLowerCase()));
            return subs;
        }
        String s = args[0].toLowerCase();
        if (args.length == 2) {
            if (s.equals("play") || s.equals("radio") || s.equals("personal") || s.equals("me")) {
                List<String> out = new ArrayList<>();
                for (TrackLibrary.Track t : library.all()) if (t.id.startsWith(args[1].toLowerCase())) out.add(t.id);
                for (String n : playlists.names()) if (("pl:" + n).startsWith(args[1].toLowerCase())) out.add("pl:" + n);
                return out;
            }
            if (s.equals("remove")) {
                List<String> out = new ArrayList<>();
                for (TrackLibrary.Track t : library.all()) if (t.id.startsWith(args[1].toLowerCase())) out.add(t.id);
                return out;
            }
            if (s.equals("stop")) return List.of("radio", "all");
            if (s.equals("playlist") || s.equals("pl")) return List.of("create", "delete", "add", "remove", "list", "play");
        }
        if (args.length == 3 && (s.equals("playlist") || s.equals("pl"))) {
            List<String> out = new ArrayList<>();
            for (String n : playlists.names()) if (n.startsWith(args[2].toLowerCase())) out.add(n);
            return out;
        }
        return List.of();
    }

    public void register(JavaPlugin owner) {
        if (owner.getCommand("music") != null) {
            owner.getCommand("music").setExecutor(this);
            owner.getCommand("music").setTabCompleter(this);
        }
    }
}
