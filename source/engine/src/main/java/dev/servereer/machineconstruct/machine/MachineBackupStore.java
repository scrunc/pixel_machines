package dev.servereer.machineconstruct.machine;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rolling, file-backed history of every machine's serialized state — the safety net for the
 * "machine got reset" data-loss class, and the source for the admin restore GUI.
 *
 * <p><b>v2 (2026-07):</b> stored as <b>one small file per machine</b> under {@code backups/machines/<id>.yml}
 * (was a single multi-MB file), and <b>flushed off the main thread</b>. Serializing + writing the whole
 * history on the main-thread tick every N minutes was freezing the server and disconnecting players; now the
 * expensive disk write happens on a background writer thread and only <i>changed</i> machines are rewritten.
 *
 * <p>For each machine id we keep the last {@code maxVersions} distinct snapshots (a timestamp + the same
 * serialized blobs the engine stores in PDC). Consecutive identical snapshots are de-duped. Nothing here
 * throws into the engine — a bad file is skipped, a bad write is retried next flush.
 */
public final class MachineBackupStore {

    public static final class Version {
        public final long ts;
        public final Map<String, String> blobs;   // field -> serialized blob
        public Version(long ts, Map<String, String> blobs) { this.ts = ts; this.blobs = blobs; }
        public int size() { int n = 0; for (String v : blobs.values()) if (v != null) n += v.length(); return n; }
    }

    public static final class Entry {
        public String type, owner, world;
        public int x, y, z;
        public final List<Version> versions = new ArrayList<>();   // oldest → newest
        public Version latest() { return versions.isEmpty() ? null : versions.get(versions.size() - 1); }
        public int size() { Version v = latest(); return v == null ? 0 : v.size(); }
    }

    /**
     * Tiered (grandfather-father-son) retention. Rather than "keep the last N snapshots" — which at a
     * 10-minute interval only spans ~N×10 minutes — we keep <b>every</b> snapshot inside the recent
     * window, then thin to roughly <b>one per hour</b>, then <b>one per day</b>, out to a maximum age.
     * That lets an admin roll a machine back by minutes, hours, OR days while the per-machine file stays
     * bounded (a few dozen snapshots, not hundreds).
     */
    public static final class Retention {
        final long recentWindowMs;   // younger than this: keep every snapshot (full interval granularity)
        final long hourlyWindowMs;   // up to this age: keep ~1 per hour
        final long maxAgeMs;         // up to this age: keep ~1 per day; older than this is dropped
        final int maxSnapshots;      // absolute per-machine cap (safety bound on file size)

        public Retention(long recentWindowMs, long hourlyWindowMs, long maxAgeMs, int maxSnapshots) {
            this.recentWindowMs = Math.max(0, recentWindowMs);
            this.hourlyWindowMs = Math.max(this.recentWindowMs, hourlyWindowMs);
            this.maxAgeMs = Math.max(this.hourlyWindowMs, maxAgeMs);
            this.maxSnapshots = Math.max(4, maxSnapshots);
        }

        /** The legacy "keep last N at the interval" behaviour, if anyone still wants it. */
        public static Retention lastN(int n) {
            long span = Math.max(1, n) * 24L * 3_600_000L;   // effectively no age horizon
            return new Retention(span, span, span, Math.max(4, n));
        }
    }

    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    /**
     * Per-machine blob-size ceiling (chars). A grief-flooded machine can serialize to multiple MB; folding
     * that into the history file makes {@code saveToString()} throw, which returns null and silently skips the
     * write — killing snapshots for <i>every</i> machine in the batch. Skip recording the oversized one instead.
     */
    private static final int MAX_BLOB_CHARS = 512_000;

    private final File dir;                 // backups/machines/
    private final Retention policy;
    private final Map<String, Entry> byId = new LinkedHashMap<>();       // machineId -> history (main thread)
    private final java.util.Set<String> dirty = new java.util.HashSet<>();   // ids needing a disk write
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MachineConstruct-backup-writer"); t.setDaemon(true); return t;
    });

    public MachineBackupStore(File dataFolder, Retention policy) {
        this.dir = new File(new File(dataFolder, "backups"), "machines");
        this.policy = policy;
        retireLegacyFile(new File(dataFolder, "backups"));
        load();
    }

    /** The old single-file store (backups/machine_backups.yml) is set aside, not migrated — fresh start. */
    private void retireLegacyFile(File backupsDir) {
        try {
            File legacy = new File(backupsDir, "machine_backups.yml");
            if (legacy.isFile()) {
                File aside = new File(backupsDir, "machine_backups.yml.old-" + System.currentTimeMillis());
                if (!legacy.renameTo(aside)) { /* best-effort; leave it */ }
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ record / query

    /** Append a snapshot for a machine (de-duped against its latest); trims to the last {@code maxVersions}. */
    public synchronized void record(Machine m, Map<String, String> blobs) {
        if (m == null || blobs == null || blobs.isEmpty()) return;
        int total = 0;
        for (String v : blobs.values()) if (v != null) total += v.length();
        if (total > MAX_BLOB_CHARS) return;   // oversized (grief flood) → skip, so serialize() can't throw + silence the batch
        String id = m.id().toString();
        Entry e = byId.computeIfAbsent(id, k -> new Entry());
        e.type = m.typeId();
        e.owner = m.owner() == null ? null : m.owner().toString();
        if (m.anchor() != null && m.anchor().getWorld() != null) {
            e.world = m.anchor().getWorld().getName();
            e.x = m.anchor().getBlockX(); e.y = m.anchor().getBlockY(); e.z = m.anchor().getBlockZ();
        }
        Version last = e.latest();
        if (last != null && last.blobs.equals(blobs)) return;   // unchanged since last snapshot → skip
        e.versions.add(new Version(System.currentTimeMillis(), new LinkedHashMap<>(blobs)));
        prune(e);
        dirty.add(id);
    }

    /**
     * Tiered thinning (see {@link Retention}): keep every snapshot in the recent window, ~1/hour beyond
     * it, ~1/day beyond that, dropping anything past the max age — but <b>never</b> drop the newest, so an
     * idle machine that hasn't changed in weeks still keeps its current snapshot. Walks newest→oldest and
     * keeps a snapshot only when it's far enough (for its age tier) from the last one kept.
     */
    private void prune(Entry e) {
        List<Version> vs = e.versions;   // oldest → newest
        int n = vs.size();
        if (n <= 1) return;
        long now = System.currentTimeMillis();
        List<Version> keep = new ArrayList<>(n);
        Version newest = vs.get(n - 1);
        keep.add(newest);                 // always keep the newest, whatever its age
        long lastKeptTs = newest.ts;
        for (int i = n - 2; i >= 0; i--) {   // newest → oldest
            Version v = vs.get(i);
            long age = now - v.ts;
            if (age > policy.maxAgeMs) break;                       // past the horizon: this + all older go
            if (lastKeptTs - v.ts >= requiredStep(age)) { keep.add(v); lastKeptTs = v.ts; }
        }
        while (keep.size() > policy.maxSnapshots) keep.remove(keep.size() - 1);   // cap: drop the oldest kept
        if (keep.size() != n) {
            java.util.Collections.reverse(keep);   // back to oldest → newest
            vs.clear();
            vs.addAll(keep);
        }
    }

    /** Minimum spacing required from the newer kept snapshot, given a snapshot's age. */
    private long requiredStep(long age) {
        if (age <= policy.recentWindowMs) return 0L;        // recent: keep every snapshot
        if (age <= policy.hourlyWindowMs) return HOUR_MS;   // older: ~1 per hour
        return DAY_MS;                                        // oldest: ~1 per day
    }

    public synchronized Entry entry(String id) { return byId.get(id); }
    public synchronized Version latest(String id) { Entry e = byId.get(id); return e == null ? null : e.latest(); }
    public synchronized int machineCount() { return byId.size(); }
    public synchronized int totalVersions() { int n = 0; for (Entry e : byId.values()) n += e.versions.size(); return n; }

    /** The single entry whose id starts with {@code prefix}, or null if none / ambiguous. Full ids match too. */
    public synchronized Map.Entry<String, Entry> byPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return null;
        String p = prefix.toLowerCase();
        Map.Entry<String, Entry> hit = null;
        for (Map.Entry<String, Entry> en : byId.entrySet()) {
            if (en.getKey().toLowerCase().startsWith(p)) {
                if (hit != null) return null;   // ambiguous
                hit = en;
            }
        }
        return hit;
    }

    /** Snapshot of all entries (id → entry) for the admin browser. */
    public synchronized List<Map.Entry<String, Entry>> all() { return new ArrayList<>(byId.entrySet()); }

    // ------------------------------------------------------------------ persistence (off-thread write)

    /** Serialize the changed machines on the calling thread (fast), then write the files on the writer thread. */
    public synchronized void flushAsync() {
        if (dirty.isEmpty()) return;
        Map<String, String> batch = new ConcurrentHashMap<>();
        for (String id : dirty) {
            Entry e = byId.get(id);
            batch.put(id, e == null ? "" : serialize(e));   // "" = delete file
        }
        dirty.clear();
        writer.submit(() -> writeBatch(batch));
    }

    /** Same as {@link #flushAsync()} but writes on the calling thread — used on clean shutdown. */
    public synchronized void flushSync() {
        if (dirty.isEmpty()) return;
        Map<String, String> batch = new LinkedHashMap<>();
        for (String id : dirty) { Entry e = byId.get(id); batch.put(id, e == null ? "" : serialize(e)); }
        dirty.clear();
        writeBatch(batch);
    }

    /** Flush anything pending and stop the writer thread (called on plugin disable, after flushSync). */
    public void shutdown() {
        flushSync();
        writer.shutdown();
    }

    private String serialize(Entry e) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("type", e.type);
        y.set("owner", e.owner);
        y.set("world", e.world);
        y.set("x", e.x); y.set("y", e.y); y.set("z", e.z);
        List<Map<String, Object>> vs = new ArrayList<>(e.versions.size());
        for (Version v : e.versions) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("ts", v.ts);
            vm.putAll(v.blobs);
            vs.add(vm);
        }
        y.set("versions", vs);
        try { return y.saveToString(); } catch (Throwable t) { return null; }
    }

    private void writeBatch(Map<String, String> batch) {
        if (!dir.isDirectory()) dir.mkdirs();
        for (Map.Entry<String, String> en : batch.entrySet()) {
            File f = new File(dir, en.getKey() + ".yml");
            String data = en.getValue();
            try {
                if (data == null) continue;            // serialize failed → keep old file
                if (data.isEmpty()) { f.delete(); continue; }   // entry removed
                File tmp = new File(dir, en.getKey() + ".yml.tmp");
                Files.writeString(tmp.toPath(), data);
                try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (Throwable atomicUnsupported) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            } catch (Throwable ignored) { /* retry next flush */ }
        }
    }

    private void load() {
        byId.clear();
        if (!dir.isDirectory()) { dir.mkdirs(); return; }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            try {
                Entry e = parse(YamlConfiguration.loadConfiguration(f));
                if (e != null) byId.put(id, e);
            } catch (Throwable ignored) { /* skip a corrupt machine file */ }
        }
    }

    private Entry parse(ConfigurationSection s) {
        if (s == null) return null;
        Entry e = new Entry();
        e.type = s.getString("type");
        e.owner = s.getString("owner");
        e.world = s.getString("world");
        e.x = s.getInt("x"); e.y = s.getInt("y"); e.z = s.getInt("z");
        for (Map<?, ?> vm : s.getMapList("versions")) {
            long ts = 0;
            Map<String, String> blobs = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : vm.entrySet()) {
                String k = String.valueOf(en.getKey());
                if (en.getValue() == null) continue;
                if (k.equals("ts")) { try { ts = ((Number) en.getValue()).longValue(); } catch (Throwable ignored) { } }
                else blobs.put(k, String.valueOf(en.getValue()));
            }
            if (!blobs.isEmpty()) e.versions.add(new Version(ts, blobs));
        }
        return e;
    }
}
