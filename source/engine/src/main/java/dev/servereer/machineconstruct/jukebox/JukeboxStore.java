package dev.servereer.machineconstruct.jukebox;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Serializes {@link JukeboxData} to/from a YAML string for PDC storage (the jukebox analogue of
 * {@code ChunkCollectorStore}). Written on every mutation → crash-safe. Never throws.
 */
public final class JukeboxStore {

    private JukeboxStore() {}

    public static String serialize(JukeboxData d) {
        YamlConfiguration y = new YamlConfiguration();
        if (d.trackId() != null) y.set("track", d.trackId());
        if (d.playlistName() != null) y.set("playlist", d.playlistName());
        y.set("playing", d.playing());
        y.set("paused", d.paused());
        y.set("loop", d.loop());
        y.set("hasDisc", d.hasDisc());
        y.set("volume", d.volume());
        y.set("speed", d.speed());
        y.set("locked", d.locked());
        return y.saveToString();
    }

    public static JukeboxData load(String data) {
        JukeboxData d = new JukeboxData();
        if (data == null || data.isEmpty()) return d;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(data);
            String track = y.getString("track", null);
            if (track != null && !track.isEmpty()) d.setTrackId(track);
            String playlist = y.getString("playlist", null);
            if (playlist != null && !playlist.isEmpty()) d.setPlaylistName(playlist);
            d.setPlaying(y.getBoolean("playing", false));
            d.setPaused(y.getBoolean("paused", false));
            d.setLoop(y.getBoolean("loop", false));
            d.setHasDisc(y.getBoolean("hasDisc", false));
            d.setVolume(y.getDouble("volume", 1.0));
            d.setSpeed(y.getDouble("speed", 1.0));
            d.setLocked(y.getBoolean("locked", false));
        } catch (Throwable t) {
            throw new IllegalStateException("corrupt jukebox blob", t);   // quarantine + preserve, don't silently reset
        }
        return d;
    }
}
