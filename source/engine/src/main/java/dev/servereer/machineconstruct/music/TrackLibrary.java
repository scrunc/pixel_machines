package dev.servereer.machineconstruct.music;

import dev.servereer.machineconstruct.audio.MusicAudio;
import dev.servereer.machineconstruct.audio.TrackIngest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The persistent registry of saved music tracks — the store that lets music be "saved for future
 * playing and disc making". Each track is a compact Opus/Ogg file under {@code music/tracks/<id>.ogg}
 * plus a metadata row in {@code music/tracks.yml}. Decoded PCM ({@link MusicAudio.Clip}) is LRU-cached so
 * repeated plays don't re-decode.
 */
public final class TrackLibrary {

    /** Immutable metadata for one saved track. */
    public static final class Track {
        public final String id;
        public final String title;
        public final String sourceUrl;   // "" for file-drop
        public final long durationMs;    // 0 if unknown
        public final String addedBy;
        public final long addedAt;

        Track(String id, String title, String sourceUrl, long durationMs, String addedBy, long addedAt) {
            this.id = id;
            this.title = title;
            this.sourceUrl = sourceUrl;
            this.durationMs = durationMs;
            this.addedBy = addedBy;
            this.addedAt = addedAt;
        }
    }

    private static final int CACHE_MAX = 8;

    private final JavaPlugin plugin;
    private final TrackIngest ingest;
    private final MusicAudio audio;
    private final File dir;         // music/tracks
    private final File indexFile;   // music/tracks.yml

    private final Map<String, Track> tracks = new LinkedHashMap<>();
    // Access-ordered LRU of decoded tracks.
    private final Map<String, MusicAudio.Clip> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, MusicAudio.Clip> e) { return size() > CACHE_MAX; }
    };

    public TrackLibrary(JavaPlugin plugin, TrackIngest ingest, MusicAudio audio) {
        this.plugin = plugin;
        this.ingest = ingest;
        this.audio = audio;
        File music = new File(plugin.getDataFolder(), "music");
        this.dir = new File(music, "tracks");
        this.dir.mkdirs();
        this.indexFile = new File(music, "tracks.yml");
    }

    public File tracksDir() { return dir; }

    /** The Ogg file backing a track (may not exist yet). */
    public File fileFor(String id) { return new File(dir, id + ".ogg"); }

    // --- index --------------------------------------------------------------

    /** Load the index from disk, then scan for dropped files + config-listed tracks. */
    public synchronized void load() {
        tracks.clear();
        if (indexFile.isFile()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(indexFile);
            ConfigurationSection root = y.getConfigurationSection("tracks");
            if (root != null) for (String id : root.getKeys(false)) {
                ConfigurationSection t = root.getConfigurationSection(id);
                if (t == null) continue;
                tracks.put(id, new Track(id,
                        t.getString("title", id),
                        t.getString("source", ""),
                        t.getLong("duration_ms", 0),
                        t.getString("added_by", ""),
                        t.getLong("added_at", 0)));
            }
        }
        scanDroppedFiles();
        scanConfigTracks();
    }

    private synchronized void saveIndex() {
        YamlConfiguration y = new YamlConfiguration();
        for (Track t : tracks.values()) {
            String base = "tracks." + t.id;
            y.set(base + ".title", t.title);
            y.set(base + ".source", t.sourceUrl);
            y.set(base + ".duration_ms", t.durationMs);
            y.set(base + ".added_by", t.addedBy);
            y.set(base + ".added_at", t.addedAt);
        }
        try {
            indexFile.getParentFile().mkdirs();
            y.save(indexFile);
        } catch (Exception e) {
            plugin.getLogger().warning("[MachineConstruct] could not save music index: " + e.getMessage());
        }
    }

    // --- lookups ------------------------------------------------------------

    public synchronized Track get(String id) { return tracks.get(sanitize(id)); }
    public synchronized boolean has(String id) { return tracks.containsKey(sanitize(id)); }
    public synchronized List<Track> all() { return new ArrayList<>(tracks.values()); }
    public synchronized int size() { return tracks.size(); }

    /** Register a ready-on-disk track (its .ogg already exists) and persist the index. */
    public synchronized void register(String id, String title, String sourceUrl, long durationMs, String addedBy) {
        id = sanitize(id);
        tracks.put(id, new Track(id, title == null || title.isBlank() ? id : title,
                sourceUrl == null ? "" : sourceUrl, Math.max(0, durationMs),
                addedBy == null ? "" : addedBy, System.currentTimeMillis()));
        cache.remove(id);
        saveIndex();
    }

    public synchronized boolean remove(String id) {
        id = sanitize(id);
        Track t = tracks.remove(id);
        if (t == null) return false;
        cache.remove(id);
        //noinspection ResultOfMethodCallIgnored
        fileFor(id).delete();
        saveIndex();
        return true;
    }

    // --- decode (blocking; call off the main thread) ------------------------

    /**
     * Decode a track to PCM (cached). Blocking — decodes Opus→WAV via ffmpeg on a cache miss, so call
     * from an async task. Returns null if the file is missing or decoding fails.
     */
    public MusicAudio.Clip loadTrack(String id) {
        id = sanitize(id);
        synchronized (this) {
            MusicAudio.Clip cached = cache.get(id);
            if (cached != null) return cached;
        }
        File ogg = fileFor(id);
        if (!ogg.isFile()) return null;
        File wav = null;
        try {
            wav = ingest.decodeToWav(ogg);
            MusicAudio.Clip track = audio.load(wav);
            synchronized (this) { cache.put(id, track); }
            return track;
        } catch (Throwable t) {
            plugin.getLogger().warning("[MachineConstruct] failed to decode track '" + id + "': " + t.getMessage());
            return null;
        } finally {
            if (wav != null) //noinspection ResultOfMethodCallIgnored
                wav.delete();
        }
    }

    // --- ingest helpers (async) --------------------------------------------

    /** Download a URL into the library under {@code id}; {@code cb} fires on completion (background thread). */
    public void downloadUrl(String url, String id, String title, String addedBy, TrackIngest.Callback cb) {
        String sid = sanitize(id);
        File dest = fileFor(sid);
        ingest.captureUrl(url, dest, (ok, err) -> {
            if (ok) register(sid, title, url, probeDuration(dest), addedBy);
            cb.done(ok, err);
        });
    }

    /** Download a URL, deriving the id from the video title. {@code idCb} gets the new id, or null on failure (background thread). */
    public void downloadUrlAutoId(String url, String addedBy, java.util.function.Consumer<String> idCb) {
        ingest.fetchTitle(url, (title, err) -> {
            String id = sanitize(title != null ? title : ("track" + System.currentTimeMillis()));
            String t = title != null ? title : id;
            downloadUrl(url, id, t, addedBy, (ok, e) -> idCb.accept(ok ? id : null));
        });
    }

    /** Normalise a local audio file into the library under {@code id}; {@code cb} fires on completion. */
    public void addFile(File src, String id, String title, String addedBy, TrackIngest.Callback cb) {
        String sid = sanitize(id);
        File dest = fileFor(sid);
        ingest.transcodeFile(src, dest, (ok, err) -> {
            if (ok) register(sid, title, "", probeDuration(dest), addedBy);
            cb.done(ok, err);
        });
    }

    /** Best-effort duration probe by decoding (cheap enough once; also warms nothing). Returns 0 on failure. */
    private long probeDuration(File ogg) {
        File wav = null;
        try {
            wav = ingest.decodeToWav(ogg);
            return audio.load(wav).durationMs();
        } catch (Throwable t) {
            return 0;
        } finally {
            if (wav != null) //noinspection ResultOfMethodCallIgnored
                wav.delete();
        }
    }

    // --- auto-discovery -----------------------------------------------------

    /** Index any {@code <id>.ogg} present on disk but missing from the index (e.g. hand-copied). */
    private void scanDroppedFiles() {
        File[] oggs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".ogg"));
        if (oggs == null) return;
        boolean changed = false;
        for (File f : oggs) {
            String id = sanitize(f.getName().replaceFirst("(?i)\\.ogg$", ""));
            if (tracks.containsKey(id)) continue;
            tracks.put(id, new Track(id, id, "", 0, "", f.lastModified()));
            changed = true;
            plugin.getLogger().info("[MachineConstruct] indexed dropped track '" + id + "'.");
        }
        // Non-ogg drops (mp3/wav/…) are transcoded then indexed asynchronously.
        File[] others = dir.listFiles((d, n) -> {
            String l = n.toLowerCase(Locale.ROOT);
            return !l.endsWith(".ogg") && (l.endsWith(".mp3") || l.endsWith(".wav") || l.endsWith(".flac")
                    || l.endsWith(".m4a") || l.endsWith(".opus") || l.endsWith(".webm") || l.endsWith(".aac"));
        });
        if (others != null) for (File src : others) {
            String id = sanitize(src.getName().replaceFirst("\\.[^.]+$", ""));
            if (tracks.containsKey(id) || fileFor(id).isFile()) continue;
            addFile(src, id, id, "", (ok, err) -> {
                if (ok) plugin.getLogger().info("[MachineConstruct] imported dropped file '" + src.getName() + "' as '" + id + "'.");
                else plugin.getLogger().warning("[MachineConstruct] failed to import '" + src.getName() + "': " + err);
            });
        }
        if (changed) saveIndex();
    }

    /** Ingest tracks declared under {@code music.config-tracks:} (id → local path or URL). */
    private void scanConfigTracks() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("music.config-tracks");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            String sid = sanitize(id);
            if (tracks.containsKey(sid) || fileFor(sid).isFile()) continue;
            String value = sec.getString(id, "");
            if (value.isBlank()) continue;
            if (value.toLowerCase(Locale.ROOT).startsWith("http")) {
                downloadUrl(value, sid, sid, "config", (ok, err) -> {
                    if (ok) plugin.getLogger().info("[MachineConstruct] downloaded config track '" + sid + "'.");
                    else plugin.getLogger().warning("[MachineConstruct] config track '" + sid + "' failed: " + err);
                });
            } else {
                File src = new File(value);
                if (!src.isAbsolute()) src = new File(plugin.getDataFolder(), value);
                addFile(src, sid, sid, "config", (ok, err) -> {
                    if (ok) plugin.getLogger().info("[MachineConstruct] imported config track '" + sid + "'.");
                    else plugin.getLogger().warning("[MachineConstruct] config track '" + sid + "' failed: " + err);
                });
            }
        }
    }

    /** Lowercase, keep [a-z0-9_-], collapse everything else to '_'. */
    public static String sanitize(String id) {
        if (id == null) return "track";
        String s = id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return s.isEmpty() ? "track" : s;
    }
}
