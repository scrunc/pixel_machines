package dev.servereer.machineconstruct.audio;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.function.Supplier;

/**
 * How the music system reaches a speaker. The implementation is the shared <b>PixelAudio</b> plugin
 * ({@link PixelAudioMusic}); this engine no longer carries an audio core of its own.
 *
 * <p>This interface exists so that nothing outside {@link PixelAudioMusic} ever names a
 * {@code dev.servereer.pixelaudio} type. Those classes live in PixelAudio's own jar and classloader, so
 * a server without that plugin would fail to load any class that mentions them — and the jukebox is not
 * allowed to take the rest of the engine down with it. {@link MusicPlayerless} is what the music system
 * gets instead: every call refuses, and tracks still download and save, they just play silently.
 *
 * <p>Keeping the SVC connection over in PixelAudio buys two things beyond the deleted duplication:
 * Simple Voice Chat hands its server API to a plugin exactly once at startup, so a PlugMan-reload of
 * THIS plugin no longer costs us audio for the rest of the boot; and one Opus/SVC bridge is fixed in one
 * place for every consumer (the jukebox bass-quality fix had to be made twice).
 */
public interface MusicAudio {

    int SAMPLE_RATE = 48_000;
    int FRAME_SAMPLES = 960;   // 20 ms @ 48 kHz — one voice-chat frame

    /** True once the core can actually play (SVC present and its server started). */
    boolean available();

    /** A name for status lines: the core behind this, or why there is none. */
    String describe();

    /**
     * Decode a file to PCM the core can play. Opus/Ogg must be turned into a WAV first
     * ({@link TrackIngest#decodeToWav}); {@code .mp3} and WAV load directly. Never null — throws on failure.
     */
    Clip load(File file) throws Exception;

    /**
      * The SHARED ffmpeg, or null when this core has none. One ~220 MB binary in
      * {@code plugins/PixelAudio/bin/} serves every plugin that ingests audio, instead of a copy per plugin;
      * {@link TrackIngest} falls back to its own {@code bin/} when this returns null.
      */
    File ffmpeg();

    /** The SHARED yt-dlp, or null when this core has none. */
    File ytdlp();

    /** Decode through the shared ffmpeg. Throws when this core has none — callers fall back to their own. */
    File decodeToWav(File src, File cache) throws Exception;

    /** A stream at a point in the world, audible within {@code distance} blocks. Null if it cannot start. */
    Handle startLocational(World world, double x, double y, double z, float distance,
                           Supplier<short[]> supplier, Runnable onStopped);

    /** A stream only {@code listener} hears, wherever they stand (radio, personal). Null if it cannot start. */
    Handle startStatic(Player listener, Supplier<short[]> supplier, Runnable onStopped);

    /** A running stream. */
    interface Handle {
        void stop();
        boolean playing();
        /** Change a locational stream's audible radius live. No-op on a per-player stream. */
        void setDistance(float distance);
    }

    /** A decoded track: 48 kHz mono 16-bit, read by the frame. */
    interface Clip {
        int totalSamples();
        long durationMs();
        /** True once {@code frameIndex} (20 ms units) has run past the end of a non-looping track. */
        boolean exhausted(long frameIndex);
        /**
         * Fill one frame starting at {@code startSample}, advancing {@code step} source samples per output
         * sample — {@code step} is the playback speed, so it pitch-shifts like vinyl. False at the end.
         */
        boolean fill(short[] out, double startSample, double step);
    }

    /** The core when PixelAudio is not installed: nothing plays, and nothing explodes. */
    final class MusicPlayerless implements MusicAudio {
        private final String why;
        public MusicPlayerless(String why) { this.why = why; }
        @Override public boolean available() { return false; }
        @Override public String describe() { return why; }
        @Override public Clip load(File file) throws Exception { throw new IllegalStateException(why); }
        @Override public File ffmpeg() { return null; }
        @Override public File ytdlp() { return null; }
        @Override public File decodeToWav(File src, File cache) throws Exception { throw new IllegalStateException(why); }
        @Override public Handle startLocational(World w, double x, double y, double z, float d,
                                                Supplier<short[]> s, Runnable r) { return null; }
        @Override public Handle startStatic(Player l, Supplier<short[]> s, Runnable r) { return null; }
    }
}
