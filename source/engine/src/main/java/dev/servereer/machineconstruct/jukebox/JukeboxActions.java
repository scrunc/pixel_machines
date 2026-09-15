package dev.servereer.machineconstruct.jukebox;

import dev.servereer.machineconstruct.machine.Machine;
import org.bukkit.entity.Player;

/**
 * Callbacks the Jukebox menu fires into the engine ({@code MachineManager} implements this). Every
 * mutation persists the machine's state and drives Simple Voice Chat playback deterministically.
 */
public interface JukeboxActions {

    /** Load a library track into the jukebox (admin browse pick; not a physical disc). */
    void jukeboxSetTrack(Machine m, String trackId);

    /** Load a playlist into the jukebox (auto-advances through its tracks). */
    void jukeboxSetPlaylist(Machine m, String playlistName);

    /** Start playing the loaded track/playlist locationally. */
    void jukeboxPlay(Machine m);

    /** Stop playback. */
    void jukeboxStop(Machine m);

    /** Pause playback (keeps position). */
    void jukeboxPause(Machine m);

    /** Resume paused playback. */
    void jukeboxResume(Machine m);

    /** Skip to the next track (playlist only). */
    void jukeboxSkip(Machine m);

    /** The track id currently playing from this jukebox's live session, or null. */
    String jukeboxCurrentTrackId(Machine m);

    /** Playback position of the live session in ms (0 if not playing). */
    long jukeboxPositionMs(Machine m);

    /** Length of the currently-playing track in ms (0 if not playing). */
    long jukeboxLengthMs(Machine m);

    /** Download a single track from {@code url} and append it to {@code playlist}; {@code onDone} runs on the main thread. */
    void jukeboxAddUrlToPlaylist(org.bukkit.entity.Player p, String playlist, String url, Runnable onDone);

    /** Toggle looping. */
    void jukeboxToggleLoop(Machine m);

    /** Adjust volume (scales locational reach); {@code delta} in the range ±1. */
    void jukeboxAdjustVolume(Machine m, double delta);

    /** Seek the current track by {@code deltaMs} milliseconds (± to skip forward/back within the song). */
    void jukeboxSeek(Machine m, long deltaMs);

    /** Adjust playback speed by {@code delta} (clamped 0.5..2.0; higher = faster + higher pitch). */
    void jukeboxAdjustSpeed(Machine m, double delta);

    /** Toggle the admin lock — while locked, non-admins can't change the song. */
    void jukeboxToggleLock(Machine m);

    /** Eject/clear: stop and unload the loaded track/playlist. */
    void jukeboxEject(Player p, Machine m);
}
