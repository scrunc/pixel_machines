package dev.servereer.machineconstruct.machine;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flatfile persistence for machines whose anchor block has no block-entity — e.g. a
 * {@code BARRIER}, which can't hold the per-block PDC the rest of the engine relies on.
 *
 * <p>Mirrors what the anchor's PDC would hold: per location, the identity {@code tag}
 * ({@code id|type|owner}), the block coords, and the same serialized state strings
 * (items / grinder / quarry / tradinghall / factory / collector). Records are kept in
 * memory and flushed in batches (dirty flag → {@link #flush()}) to bound disk I/O — the
 * engine flushes periodically, on teardown, and on shutdown. Stored as a list of maps so
 * world names / coords never collide with YAML's '.' path separator.
 */
public final class ExternalMachineStore {

    private final File file;
    private final Map<String, Map<String, String>> records = new LinkedHashMap<>();   // locKey → field → value
    private boolean dirty;

    public ExternalMachineStore(File dataFolder) {
        this.file = new File(dataFolder, "external_machines.yml");
        load();
    }

    private void load() {
        records.clear();
        if (!file.isFile()) return;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            for (Map<?, ?> raw : y.getMapList("machines")) {
                Object loc = raw.get("loc");
                if (loc == null) continue;
                Map<String, String> rec = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    rec.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                records.put(String.valueOf(loc), rec);
            }
        } catch (Throwable ignored) {
            // corrupt file → start empty rather than crash
        }
    }

    public Set<String> keys() { return new HashSet<>(records.keySet()); }
    public boolean has(String loc) { return records.containsKey(loc); }

    public String get(String loc, String field) {
        Map<String, String> r = records.get(loc);
        return r == null ? null : r.get(field);
    }

    public void set(String loc, String field, String value) {
        Map<String, String> r = records.computeIfAbsent(loc, k -> new LinkedHashMap<>());
        if (value == null) r.remove(field); else r.put(field, value);
        dirty = true;
    }

    public void remove(String loc) {
        if (records.remove(loc) != null) dirty = true;
    }

    /** Write to disk only if something changed since the last flush. */
    public void flush() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, String>> list = new ArrayList<>(records.size());
        for (Map.Entry<String, Map<String, String>> e : records.entrySet()) {
            Map<String, String> rec = new LinkedHashMap<>(e.getValue());
            rec.put("loc", e.getKey());
            list.add(rec);
        }
        y.set("machines", list);
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) {
            // disk error → keep dirty, retry next flush
        }
    }
}
