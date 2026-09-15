package dev.servereer.machineconstruct.music;

import dev.servereer.machineconstruct.audio.AudioTrack;
import dev.servereer.machineconstruct.audio.VoiceChatAudio;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns all active music playback via a small <b>session</b> model — a session is a queue of track ids
 * with a position, its Simple Voice Chat handle(s), and an auto-advance timer. This unifies single
 * tracks and playlists and gives clean <b>stop</b>, <b>pause/resume</b> (resume from a saved frame
 * offset), and playlist auto-advance across all three modes: <b>locational</b> (jukebox, one channel),
 * <b>radio</b> (server-wide, one static channel per player), and <b>personal</b> (one player).
 *
 * <p>Decoding runs off the main thread (ffmpeg); playback starts on the main thread. SVC {@code onStopped}
 * callbacks and the advance timer both run on the main thread. If SVC is absent, {@link #available()} is
 * false and play calls are no-ops.
 */
public final class MusicPlayer implements Listener {

    private static final int MS_PER_FRAME = 20;   // 960 samples @ 48 kHz
    public static final String RADIO_KEY = "radio";

    private enum Mode { LOCATIONAL, PERSONAL, RADIO }

    private final class Session {
        final String key;
        final Mode mode;
        // target
        World world; double x, y, z; float distance;   // LOCATIONAL
        UUID listener;                                  // PERSONAL
        // content
        List<String> queue;
        int index;
        boolean loopQueue;
        boolean shuffle;             // play the queue in a random order (re-shuffled each loop)
        int[] order;                 // shuffled index order (shuffle only); order[orderPos] == index
        int orderPos;
        Runnable onEnd;              // whole queue finished (non-loop)
        // runtime
        AudioTrack track;            // current decoded track
        double startSample;          // source-sample offset this segment starts from (seek/pause anchor)
        double speed = 1.0;          // playback speed (1.0 = normal; >1 faster + higher pitch)
        long startedAt;              // millis this segment's handles began
        boolean paused;
        int epoch;                   // bumped on stop/advance/pause to void stale async starts
        VoiceChatAudio.Handle single;                   // LOCATIONAL / PERSONAL
        final Map<UUID, VoiceChatAudio.Handle> radio = new HashMap<>();
        BukkitTask advanceTask;

        Session(String key, Mode mode) { this.key = key; this.mode = mode; }

        String currentTrackId() { return (queue != null && index < queue.size()) ? queue.get(index) : null; }
    }

    private final JavaPlugin plugin;
    private final TrackLibrary library;
    private final VoiceChatAudio audio;   // null if voicechat absent

    private final Map<String, Session> sessions = new HashMap<>();

    public MusicPlayer(JavaPlugin plugin, TrackLibrary library, VoiceChatAudio audio) {
        this.plugin = plugin;
        this.library = library;
        this.audio = audio;
    }

    public boolean available() { return audio != null && audio.available(); }

    // --- public query -------------------------------------------------------

    public boolean isActive(String key) { Session s = sessions.get(key); return s != null; }
    public boolean isPlaying(String key) { Session s = sessions.get(key); return s != null && !s.paused; }
    public boolean isPaused(String key) { Session s = sessions.get(key); return s != null && s.paused; }
    public boolean radioActive() { return sessions.containsKey(RADIO_KEY); }

    /** Update a locational session's reach live (no restart). Returns false if no such active session. */
    public boolean setLocationalDistance(String key, float distance) {
        Session s = sessions.get(key);
        if (s == null || s.mode != Mode.LOCATIONAL) return false;
        s.distance = distance;                       // future segments (playlist advance) use it too
        if (s.single != null) s.single.setDistance(distance);
        return true;
    }

    /** Update a session's queue-loop flag live (no restart). Returns false if no such active session. */
    public boolean setLoopQueue(String key, boolean loop) {
        Session s = sessions.get(key);
        if (s == null) return false;
        s.loopQueue = loop;
        return true;
    }

    public String currentTrackId(String key) { Session s = sessions.get(key); return s == null ? null : s.currentTrackId(); }
    public int queueSize(String key) { Session s = sessions.get(key); return s == null || s.queue == null ? 0 : s.queue.size(); }
    public int queueIndex(String key) { Session s = sessions.get(key); return s == null ? 0 : s.index; }
    public boolean loopQueue(String key) { Session s = sessions.get(key); return s != null && s.loopQueue; }

    /** Current playback speed of a session (1.0 if none). */
    public double speed(String key) { Session s = sessions.get(key); return s == null ? 1.0 : s.speed; }
    /** Current position in the playing track, in milliseconds (0 if none). */
    public long positionMs(String key) {
        Session s = sessions.get(key);
        if (s == null || s.track == null) return 0;
        return (long) (currentSample(s) * 1000.0 / AudioTrack.SAMPLE_RATE);
    }
    /** Length of the playing track, in milliseconds (0 if none). */
    public long trackLengthMs(String key) {
        Session s = sessions.get(key);
        return s == null || s.track == null ? 0 : s.track.durationMs();
    }

    // --- start --------------------------------------------------------------

    public void playLocational(String key, List<String> queue, Location loc, float distance,
                               boolean loopQueue, boolean shuffle, double speed, Runnable onEnd, Consumer<Boolean> result) {
        if (!available() || loc.getWorld() == null || queue == null || queue.isEmpty()) { result.accept(false); return; }
        Session s = new Session(key, Mode.LOCATIONAL);
        s.world = loc.getWorld(); s.x = loc.getX(); s.y = loc.getY(); s.z = loc.getZ(); s.distance = distance;
        s.queue = new ArrayList<>(queue); s.loopQueue = loopQueue; s.shuffle = shuffle; s.speed = clampSpeed(speed); s.onEnd = onEnd;
        begin(s, result);
    }

    public void playPersonal(Player player, List<String> queue, boolean loopQueue, Consumer<Boolean> result) {
        if (!available() || queue == null || queue.isEmpty()) { result.accept(false); return; }
        Session s = new Session("personal:" + player.getUniqueId(), Mode.PERSONAL);
        s.listener = player.getUniqueId();
        s.queue = new ArrayList<>(queue); s.loopQueue = loopQueue;
        begin(s, result);
    }

    public void startRadio(List<String> queue, boolean loopQueue, Consumer<Boolean> result) {
        if (!available() || queue == null || queue.isEmpty()) { result.accept(false); return; }
        Session s = new Session(RADIO_KEY, Mode.RADIO);
        s.queue = new ArrayList<>(queue); s.loopQueue = loopQueue;
        begin(s, result);
    }

    /** Register a fresh session (replacing any existing one under the same key) and start its first track. */
    private void begin(Session s, Consumer<Boolean> result) {
        stop(s.key);
        if (s.shuffle && s.queue.size() > 1) reshuffle(s);   // start on a random track
        sessions.put(s.key, s);
        startCurrent(s, result);
    }

    private static double clampSpeed(double v) { return Math.max(0.5, Math.min(2.0, v)); }

    /** Build a fresh random play order and point the session at its first entry. */
    private void reshuffle(Session s) {
        int n = s.queue.size();
        int[] o = new int[n];
        for (int i = 0; i < n; i++) o[i] = i;
        java.util.Random r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = n - 1; i > 0; i--) { int j = r.nextInt(i + 1); int t = o[i]; o[i] = o[j]; o[j] = t; }
        s.order = o; s.orderPos = 0; s.index = o[0];
    }

    /** Decode the current queue track and start a playback segment; schedules the advance timer. */
    private void startCurrent(Session s, Consumer<Boolean> result) {
        String id = s.currentTrackId();
        if (id == null) { endSession(s); if (result != null) result.accept(false); return; }
        final int epoch = s.epoch;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            AudioTrack track = library.loadTrack(id);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (sessions.get(s.key) != s || s.epoch != epoch) { if (result != null) result.accept(false); return; }
                if (track == null) {   // bad track — skip to the next one
                    if (advanceIndex(s)) { startCurrent(s, result); }
                    else { endSession(s); if (result != null) result.accept(false); }
                    return;
                }
                s.track = track;
                s.startSample = 0;
                startSegment(s);
                if (result != null) result.accept(true);
            });
        });
    }

    /** Open SVC handle(s) for the current segment (from {@code startSample}, at {@code speed}) and schedule auto-advance. */
    private void startSegment(Session s) {
        s.startedAt = System.currentTimeMillis();
        s.paused = false;
        switch (s.mode) {
            case LOCATIONAL -> s.single = audio.startLocational(s.world, s.x, s.y, s.z, s.distance,
                    supplierFrom(s.track, s.startSample, s.speed), null);
            case PERSONAL -> {
                Player p = plugin.getServer().getPlayer(s.listener);
                if (p != null) s.single = audio.startStatic(p, supplierFrom(s.track, s.startSample, s.speed), null);
            }
            case RADIO -> {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    VoiceChatAudio.Handle h = audio.startStatic(p, supplierFrom(s.track, s.startSample, s.speed), null);
                    if (h != null) s.radio.put(p.getUniqueId(), h);
                }
            }
        }
        scheduleAdvance(s);
    }

    /** A per-handle supplier from {@code startSample}, advancing {@code speed} source samples per output sample. */
    private static Supplier<short[]> supplierFrom(AudioTrack track, double startSample, double speed) {
        double[] pos = { startSample };
        double step = speed <= 0 ? 1.0 : speed;
        return () -> {
            if (pos[0] >= track.totalSamples()) return null;
            short[] out = new short[AudioTrack.FRAME_SAMPLES];
            track.fill(out, pos[0], step);
            pos[0] += AudioTrack.FRAME_SAMPLES * step;
            return out;
        };
    }

    /** Current source-sample position of the playing (or paused) segment. */
    private static double currentSample(Session s) {
        if (s.track == null) return 0;
        if (s.paused) return s.startSample;
        double elapsedSec = (System.currentTimeMillis() - s.startedAt) / 1000.0;
        return s.startSample + elapsedSec * AudioTrack.SAMPLE_RATE * s.speed;
    }

    /** Fire the advance timer at the current track's remaining wall-clock duration (speed-adjusted, + a gap). */
    private void scheduleAdvance(Session s) {
        cancelAdvance(s);
        double remainingSamples = Math.max(0, s.track.totalSamples() - s.startSample);
        long remainingMs = (long) (remainingSamples / (AudioTrack.SAMPLE_RATE * Math.max(0.1, s.speed)) * 1000.0);
        long ticks = Math.max(1, (remainingMs + 200) / 50);   // +200 ms so the tail isn't clipped
        final int epoch = s.epoch;
        s.advanceTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(s.key) != s || s.epoch != epoch || s.paused) return;
            stopHandles(s);
            if (advanceIndex(s)) startCurrent(s, null);
            else endSession(s);
        }, ticks);
    }

    /** Restart the current segment from {@code newStartSample} (used by seek + speed change). */
    private void reseat(Session s, double newStartSample) {
        if (s.track == null) return;
        double clamped = Math.max(0, Math.min(newStartSample, s.track.totalSamples()));
        boolean wasPaused = s.paused;
        s.epoch++;                 // void any pending advance/decode
        cancelAdvance(s);
        stopHandles(s);
        s.startSample = clamped;
        if (!wasPaused) startSegment(s);   // reopen handles + reschedule at the new position/speed
        else s.paused = true;              // stay paused; resume() will start from startSample
    }

    /** Seek the playing track by {@code deltaMs} (± source time). Returns false if nothing is loaded. */
    public boolean seek(String key, long deltaMs) {
        Session s = sessions.get(key);
        if (s == null || s.track == null) return false;
        double cur = currentSample(s);
        double delta = deltaMs / 1000.0 * AudioTrack.SAMPLE_RATE;   // seek in source time
        reseat(s, cur + delta);
        return true;
    }

    /** Set playback speed live (clamped 0.5..2.0), preserving the current position. Returns false if none. */
    public boolean setSpeed(String key, double speed) {
        Session s = sessions.get(key);
        if (s == null) return false;
        double cur = currentSample(s);
        s.speed = clampSpeed(speed);
        reseat(s, cur);   // rebuild the segment supplier at the new speed from where we are
        return true;
    }

    /** Move to the next track (shuffled order if enabled), wrapping if looping. False when a non-looping queue is done. */
    private boolean advanceIndex(Session s) {
        s.epoch++;   // void any pending timer/decode from the previous track
        if (s.shuffle && s.queue.size() > 1) {
            if (s.order == null || s.order.length != s.queue.size()) { reshuffle(s); return true; }
            s.orderPos++;
            if (s.orderPos < s.order.length) { s.index = s.order[s.orderPos]; return true; }
            if (s.loopQueue) { reshuffle(s); return true; }   // fresh shuffle for the next pass
            return false;
        }
        s.index++;
        if (s.index < s.queue.size()) return true;
        if (s.loopQueue) { s.index = 0; return true; }
        return false;
    }

    // --- stop / pause / resume ---------------------------------------------

    public void stop(String key) {
        Session s = sessions.remove(key);
        if (s == null) return;
        s.epoch++;
        cancelAdvance(s);
        stopHandles(s);
    }

    /** Stop every active session (plugin disable / global kill). */
    public void stopAll() {
        for (String key : new ArrayList<>(sessions.keySet())) stop(key);
    }

    public boolean pause(String key) {
        Session s = sessions.get(key);
        if (s == null || s.paused) return false;
        s.epoch++;                         // void the pending advance timer
        cancelAdvance(s);
        s.startSample = currentSample(s);  // capture position before flipping paused
        s.paused = true;
        stopHandles(s);
        return true;
    }

    public boolean resume(String key) {
        Session s = sessions.get(key);
        if (s == null || !s.paused || s.track == null) return false;
        if (s.startSample >= s.track.totalSamples()) {   // paused right at the end → advance instead
            if (advanceIndex(s)) { startCurrent(s, null); return true; }
            endSession(s); return false;
        }
        startSegment(s);
        return true;
    }

    /** Skip to the next track. With shuffle it picks another random track (reshuffling once the run is used up). */
    public boolean skip(String key) {
        Session s = sessions.get(key);
        if (s == null || s.queue == null || s.queue.isEmpty()) return false;
        boolean shuffle = s.shuffle && s.queue.size() > 1;
        boolean hasNext = shuffle || (s.index + 1) < s.queue.size() || s.loopQueue;
        if (!hasNext) return false;   // last track, not looping → keep it playing rather than stop
        boolean wasPaused = s.paused;
        cancelAdvance(s);
        stopHandles(s);
        if (shuffle) {
            s.epoch++;
            if (s.order == null || s.order.length != s.queue.size() || s.orderPos + 1 >= s.order.length) reshuffle(s);
            else { s.orderPos++; s.index = s.order[s.orderPos]; }
        } else {
            advanceIndex(s);
        }
        if (wasPaused) startCurrentPaused(s);   // stay paused at the start of the next track
        else startCurrent(s, null);
        return true;
    }

    private void startCurrentPaused(Session s) {
        String id = s.currentTrackId();
        if (id == null) { endSession(s); return; }
        final int epoch = s.epoch;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            AudioTrack track = library.loadTrack(id);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (sessions.get(s.key) != s || s.epoch != epoch) return;
                if (track == null) { if (advanceIndex(s)) startCurrentPaused(s); else endSession(s); return; }
                s.track = track; s.startSample = 0; s.paused = true;
            });
        });
    }

    // --- lifecycle ----------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Session s = sessions.get(RADIO_KEY);
        if (s == null || s.paused || s.track == null) return;
        double pos = currentSample(s);
        VoiceChatAudio.Handle h = audio.startStatic(e.getPlayer(), supplierFrom(s.track, pos, s.speed), null);
        if (h != null) s.radio.put(e.getPlayer().getUniqueId(), h);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uid = e.getPlayer().getUniqueId();
        Session radio = sessions.get(RADIO_KEY);
        if (radio != null) { VoiceChatAudio.Handle h = radio.radio.remove(uid); if (h != null) h.stop(); }
        stop("personal:" + uid);   // their private stream ends with them
    }

    private void endSession(Session s) {
        cancelAdvance(s);
        stopHandles(s);
        sessions.remove(s.key);
        if (s.onEnd != null) plugin.getServer().getScheduler().runTask(plugin, s.onEnd);
    }

    private void stopHandles(Session s) {
        if (s.single != null) { s.single.stop(); s.single = null; }
        for (VoiceChatAudio.Handle h : s.radio.values()) h.stop();
        s.radio.clear();
    }

    private void cancelAdvance(Session s) {
        if (s.advanceTask != null) { s.advanceTask.cancel(); s.advanceTask = null; }
    }
}
