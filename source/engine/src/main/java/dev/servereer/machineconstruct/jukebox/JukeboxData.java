package dev.servereer.machineconstruct.jukebox;

/**
 * Per-machine Jukebox state, attached to a {@link dev.servereer.machineconstruct.machine.Machine} and
 * persisted to the anchor PDC (see {@link JukeboxStore}). Holds the loaded track, playback flags, and
 * whether a physical disc is inside (so it can be ejected back to the player).
 */
public final class JukeboxData {

    private String trackId;      // loaded single track id, or null
    private String playlistName; // loaded playlist name, or null (mutually exclusive with trackId)
    private boolean playing;
    private boolean paused;
    private boolean loop;
    private boolean hasDisc;     // a physical custom disc is inserted (vs. admin-loaded)
    private double volume = 1.0; // scales the spec distance (0..1)
    private double speed = 1.0;  // playback speed (0.5..2.0; >1 = faster + higher pitch)
    private boolean locked;      // admin lock: normal players can't change the song while set

    public String trackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; if (trackId != null) this.playlistName = null; }
    public boolean hasTrack() { return trackId != null && !trackId.isEmpty(); }

    public String playlistName() { return playlistName; }
    public void setPlaylistName(String name) { this.playlistName = name; if (name != null) this.trackId = null; }
    public boolean hasPlaylist() { return playlistName != null && !playlistName.isEmpty(); }

    /** True if there's something to play (single track or a playlist). */
    public boolean hasContent() { return hasTrack() || hasPlaylist(); }

    public boolean playing() { return playing; }
    public void setPlaying(boolean playing) { this.playing = playing; }

    public boolean paused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }

    public boolean loop() { return loop; }
    public void setLoop(boolean loop) { this.loop = loop; }

    public boolean hasDisc() { return hasDisc; }
    public void setHasDisc(boolean hasDisc) { this.hasDisc = hasDisc; }

    public double volume() { return volume; }
    public void setVolume(double volume) { this.volume = Math.max(0.1, Math.min(1.0, volume)); }

    public double speed() { return speed; }
    public void setSpeed(double speed) { this.speed = Math.max(0.5, Math.min(2.0, speed)); }

    public boolean locked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
}
