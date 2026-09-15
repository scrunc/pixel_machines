package dev.servereer.machineconstruct.audio;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bridges saved-track audio onto Simple Voice Chat channels — the music-system analogue of VideoWall's
 * {@code audio.VoiceChatAudio}, extended with <b>static</b> channels for global-radio and personal
 * playback (VideoWall only needed locational).
 *
 * <p>This class touches SVC API types, so it must only ever be loaded/used when the {@code voicechat}
 * plugin is actually installed — the engine's {@code MusicPlayer} guards that. The
 * {@link VoicechatServerApi} only becomes available once SVC fires its server-started event.
 */
public final class VoiceChatAudio {

    private final JavaPlugin plugin;
    private volatile VoicechatServerApi api;

    public VoiceChatAudio(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Register our SVC plugin hook. Call only when the voicechat plugin is present. */
    public void register() {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            plugin.getLogger().info("[MachineConstruct] Simple Voice Chat service unavailable — jukeboxes will play silently.");
            return;
        }
        service.registerPlugin(new Hook());
        plugin.getLogger().info("[MachineConstruct] Registered with Simple Voice Chat; music audio enabled.");
    }

    public boolean available() {
        return api != null;
    }

    /**
     * Start a locational audio stream at {@code (x,y,z)} fed by {@code supplier} (a 960-sample 20 ms
     * frame each call, or null to end). Heard by nearby players, fading with distance. Returns a handle
     * to stop it, or null if audio can't start.
     */
    public Handle startLocational(World world, double x, double y, double z, float distance,
                                  Supplier<short[]> supplier, Runnable onStopped) {
        VoicechatServerApi a = api;
        if (a == null) return null;
        ServerLevel level = a.fromServerLevel(world);
        LocationalAudioChannel channel = a.createLocationalAudioChannel(UUID.randomUUID(), level, a.createPosition(x, y, z));
        if (channel == null) return null;
        channel.setDistance(distance);
        // AUDIO (music) mode, not the deprecated no-arg createEncoder() which is VOIP/speech-tuned
        // and rolls off bass — jukeboxes play music, so preserve the full frequency range.
        AudioPlayer player = a.createAudioPlayer(channel, a.createEncoder(OpusEncoderMode.AUDIO), supplier);
        if (onStopped != null) player.setOnStopped(onStopped);
        player.startPlaying();
        return new Handle(player, channel);
    }

    /**
     * Start a static (non-locational) stream heard by exactly one {@code listener} regardless of where
     * they stand — used for global-radio (one per online player) and personal playback. Returns null if
     * the player isn't connected to voice chat or audio can't start.
     */
    public Handle startStatic(Player listener, Supplier<short[]> supplier, Runnable onStopped) {
        VoicechatServerApi a = api;
        if (a == null) return null;
        VoicechatConnection conn = a.getConnectionOf(listener.getUniqueId());
        if (conn == null) return null;   // player hasn't joined the voice session
        ServerLevel level = a.fromServerLevel(listener.getWorld());
        StaticAudioChannel channel = a.createStaticAudioChannel(UUID.randomUUID(), level, conn);
        if (channel == null) return null;
        AudioPlayer player = a.createAudioPlayer(channel, a.createEncoder(OpusEncoderMode.AUDIO), supplier);
        if (onStopped != null) player.setOnStopped(onStopped);
        player.startPlaying();
        return new Handle(player, null);
    }

    /** Opaque stop handle so callers needn't reference SVC types. */
    public static final class Handle {
        private final AudioPlayer player;
        private final LocationalAudioChannel channel;   // null for static (radio/personal) channels

        private Handle(AudioPlayer player, LocationalAudioChannel channel) {
            this.player = player;
            this.channel = channel;
        }

        public boolean playing() {
            try { return player.isPlaying(); } catch (Throwable t) { return false; }
        }

        /** Update the locational reach live (no restart). No-op for static channels. */
        public void setDistance(float distance) {
            try { if (channel != null) channel.setDistance(distance); } catch (Throwable ignored) { }
        }

        public void stop() {
            try {
                if (player.isPlaying()) player.stopPlaying();
            } catch (Throwable ignored) {
            }
        }
    }

    private final class Hook implements VoicechatPlugin {
        @Override
        public String getPluginId() {
            return "machineconstruct";
        }

        @Override
        public void initialize(VoicechatApi a) {
            // server API arrives via the started event below
        }

        @Override
        public void registerEvents(EventRegistration registration) {
            registration.registerEvent(VoicechatServerStartedEvent.class, e -> api = e.getVoicechat());
        }
    }
}
