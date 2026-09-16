package dev.servereer.machineconstruct.audio;

import dev.servereer.pixelaudio.AudioHandle;
import dev.servereer.pixelaudio.AudioMode;
import dev.servereer.pixelaudio.AudioTrack;
import dev.servereer.pixelaudio.PixelAudio;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.function.Supplier;

/**
 * {@link MusicAudio} on the shared PixelAudio plugin — the same core LivingNPC, VideoWall and the arcade
 * speak through, so one Simple Voice Chat connection serves them all.
 *
 * <p><b>Every {@code dev.servereer.pixelaudio} reference in this plugin lives in this one file.</b>
 * Nothing here may be touched before {@code isPluginEnabled("PixelAudio")} — see {@link #load}.
 */
public final class PixelAudioMusic implements MusicAudio {

    private final PixelAudio core;

    private PixelAudioMusic(PixelAudio core) { this.core = core; }

    /** Grab the service, or null if PixelAudio is enabled but has not published it yet. */
    public static MusicAudio load(JavaPlugin plugin) {
        PixelAudio core = plugin.getServer().getServicesManager().load(PixelAudio.class);
        return core == null ? null : new PixelAudioMusic(core);
    }

    @Override public boolean available() { return core.available(); }

    @Override public String describe() { return "PixelAudio" + (core.available() ? "" : " (waiting for Simple Voice Chat)"); }

    @Override
    public Clip load(File file) throws Exception {
        AudioTrack track = AudioTrack.load(file);
        return new Clip() {
            @Override public int totalSamples() { return track.totalSamples(); }
            @Override public long durationMs() { return track.durationMs(); }
            @Override public boolean exhausted(long frameIndex) { return track.exhausted(frameIndex); }
            @Override public boolean fill(short[] out, double startSample, double step) { return track.fill(out, startSample, step); }
        };
    }

    @Override
    public Handle startLocational(World world, double x, double y, double z, float distance,
                                  Supplier<short[]> supplier, Runnable onStopped) {
        // MUSIC, not SPEECH: the VOIP tuning rolls the bass off a jukebox.
        return wrap(core.startLocational(world, x, y, z, distance, AudioMode.MUSIC, supplier, onStopped));
    }

    @Override
    public Handle startStatic(Player listener, Supplier<short[]> supplier, Runnable onStopped) {
        return wrap(core.startStatic(listener, AudioMode.MUSIC, supplier, onStopped));
    }

    private static Handle wrap(AudioHandle h) {
        if (h == null) return null;
        return new Handle() {
            @Override public void stop() { h.stop(); }
            @Override public boolean playing() { return h.playing(); }
            @Override public void setDistance(float distance) { h.setDistance(distance); }
        };
    }
}
