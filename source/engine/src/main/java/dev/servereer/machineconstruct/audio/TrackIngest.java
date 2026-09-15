package dev.servereer.machineconstruct.audio;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * On-box media ingest for the music library — the audio-only, capture-to-file subset of VideoWall's
 * {@code live.LiveSource}. Instead of streaming raw PCM to a socket for live SVC, it transcodes to a
 * compact <b>Opus-in-Ogg</b> file on disk that can be replayed offline forever (and bound to discs).
 *
 * <p><b>Networking is done by yt-dlp, never ffmpeg.</b> The bundled static ffmpeg is glibc-static and
 * segfaults doing its own DNS ({@code getaddrinfo}) — so for stream URLs we run
 * {@code yt-dlp -o - <url> | ffmpeg -i pipe:0 …}: yt-dlp fetches and muxes to stdout, ffmpeg only
 * transcodes from the pipe.
 */
public final class TrackIngest {

    /** Completion callback, invoked on a background thread. */
    public interface Callback {
        void done(boolean ok, String error);
    }

    /** One entry of a YouTube playlist. */
    public static final class Entry {
        public final String url;
        public final String title;
        public Entry(String url, String title) { this.url = url; this.title = title; }
    }

    /** Playlist-enumeration callback (background thread); {@code entries} null on failure. */
    public interface PlaylistCallback {
        void done(String playlistTitle, java.util.List<Entry> entries, String error);
    }

    /** Title-probe callback (background thread); {@code title} null on failure. */
    public interface TitleCallback {
        void done(String title, String error);
    }

    private final JavaPlugin plugin;

    public TrackIngest(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private File binDir() {
        String cfg = plugin.getConfig().getString("music.bin-dir");
        return (cfg != null && !cfg.isBlank()) ? new File(cfg) : new File(plugin.getDataFolder(), "bin");
    }

    private File resolve(String configKey, String defaultName) {
        String cfg = plugin.getConfig().getString(configKey);
        if (cfg != null && !cfg.isBlank()) return new File(cfg);
        File f = new File(binDir(), defaultName);
        if (!f.isFile()) {
            File exe = new File(binDir(), defaultName + ".exe");   // Windows dev boxes
            if (exe.isFile()) return exe;
        }
        return f;
    }

    private File ffmpegBin() { return resolve("music.ffmpeg-path", "ffmpeg"); }
    private File ytdlpBin()  { return resolve("music.ytdlp-path", "yt-dlp"); }

    /**
     * The YouTube cookies file to use, or null. Honours {@code music.cookies-file} when set; otherwise
     * auto-detects a {@code cookies.txt} dropped into the plugin folder or bin dir (zero-config).
     */
    public File cookiesFile() {
        String cookies = plugin.getConfig().getString("music.cookies-file", "");
        if (cookies != null && !cookies.isBlank()) {
            File cf = new File(cookies);
            if (!cf.isAbsolute()) cf = new File(plugin.getDataFolder(), cookies);
            return cf.isFile() ? cf : null;
        }
        for (File f : new File[]{ new File(plugin.getDataFolder(), "cookies.txt"), new File(binDir(), "cookies.txt") })
            if (f.isFile()) return f;
        return null;
    }

    /**
     * Append YouTube auth options so downloads survive the "Sign in to confirm you're not a bot" wall on
     * server IPs: a {@code --cookies <file>} (Netscape cookies.txt exported from a logged-in browser) and
     * any {@code music.ytdlp-extra-args} (e.g. {@code --extractor-args youtube:player_client=...}).
     */
    private void addYtdlpAuth(List<String> args) {
        File cf = cookiesFile();
        if (cf != null) { args.add("--cookies"); args.add(cf.getAbsolutePath()); }
        for (String extra : plugin.getConfig().getStringList("music.ytdlp-extra-args")) {
            if (extra != null && !extra.isBlank()) {
                for (String tok : extra.trim().split("\\s+")) if (!tok.isEmpty()) args.add(tok);
            }
        }
    }

    private File cacheDir() {
        File c = new File(plugin.getDataFolder(), "cache");
        c.mkdirs();
        return c;
    }

    /** True if ffmpeg is present (ingest/decode need it). */
    public boolean available() {
        return ffmpegBin().isFile();
    }

    /** True if a URL looks like a YouTube (or generic) playlist we should expand. */
    public static boolean isPlaylistUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("/playlist?") || u.contains("list=");
    }

    /** Fetch just the video title (yt-dlp), for deriving a default track id. Runs async. */
    public void fetchTitle(String url, TitleCallback cb) {
        Thread t = new Thread(() -> {
            try {
                File yt = ytdlpBin();
                if (!yt.isFile()) { cb.done(null, "yt-dlp binary missing at " + yt.getPath()); return; }
                ensureExecutable(yt);
                List<String> a = new ArrayList<>(List.of(yt.getAbsolutePath(),
                        "--no-playlist", "--skip-download", "--print", "%(title)s", "--no-warnings"));
                addYtdlpAuth(a);
                a.add(url);
                ProcessBuilder pb = new ProcessBuilder(a);
                applyTempEnv(pb);
                Process p = pb.start();
                String out = readAll(p.getInputStream());
                String err = drain(p.getErrorStream());
                int code = p.waitFor();
                String title = out.isBlank() ? null : out.trim().lines().findFirst().orElse("").trim();
                if (code != 0 || title == null || title.isEmpty()) cb.done(null, err.isBlank() ? "no title" : err);
                else cb.done(title, null);
            } catch (Throwable e) {
                cb.done(null, String.valueOf(e.getMessage()));
            }
        }, "mc-title-probe");
        t.setDaemon(true);
        t.start();
    }

    /** Enumerate a YouTube playlist's entries (id + title) without downloading. Runs async. */
    public void enumeratePlaylist(String url, PlaylistCallback cb) {
        Thread t = new Thread(() -> {
            try {
                File yt = ytdlpBin();
                if (!yt.isFile()) { cb.done(null, null, "yt-dlp binary missing at " + yt.getPath()); return; }
                ensureExecutable(yt);
                List<String> a = new ArrayList<>(List.of(yt.getAbsolutePath(),
                        "--flat-playlist", "--print", "%(playlist_title)s\t%(id)s\t%(title)s", "--no-warnings"));
                addYtdlpAuth(a);
                a.add(url);
                ProcessBuilder pb = new ProcessBuilder(a);
                applyTempEnv(pb);
                Process p = pb.start();
                String out = readAll(p.getInputStream());
                String err = drain(p.getErrorStream());
                int code = p.waitFor();
                java.util.List<Entry> entries = new ArrayList<>();
                String playlistTitle = null;
                for (String line : out.split("\n")) {
                    String s = line.trim();
                    if (s.isEmpty()) continue;
                    String[] parts = s.split("\t", 3);
                    if (parts.length < 3) continue;
                    if (playlistTitle == null && !parts[0].isBlank() && !parts[0].equals("NA")) playlistTitle = parts[0].trim();
                    String id = parts[1].trim();
                    if (id.isBlank() || id.equals("NA")) continue;
                    entries.add(new Entry("https://www.youtube.com/watch?v=" + id, parts[2].trim()));
                }
                if (code != 0 && entries.isEmpty()) cb.done(null, null, err.isBlank() ? "enumerate failed" : err);
                else cb.done(playlistTitle, entries, null);
            } catch (Throwable e) {
                cb.done(null, null, String.valueOf(e.getMessage()));
            }
        }, "mc-playlist-enum");
        t.setDaemon(true);
        t.start();
    }

    /** Read a child's stdout fully. */
    private static String readAll(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException ignored) { }
        return sb.toString();
    }

    // --- URL capture --------------------------------------------------------

    /** Download {@code url} (yt-dlp) and transcode its audio to {@code dest} (.ogg). Runs async. */
    public void captureUrl(String url, File dest, Callback cb) {
        Thread t = new Thread(() -> {
            try {
                File ff = ffmpegBin();
                if (!ff.isFile()) { cb.done(false, "ffmpeg binary missing at " + ff.getPath()); return; }
                ensureExecutable(ff);
                if (needsYtdlp(url)) {
                    File yt = ytdlpBin();
                    if (!yt.isFile()) { cb.done(false, "yt-dlp binary missing at " + yt.getPath()); return; }
                    ensureExecutable(yt);
                    runPiped(yt, ff, url, dest, cb);
                } else {
                    runFfmpeg(ff, url, dest, cb);   // direct URL / local path
                }
            } catch (Throwable e) {
                cb.done(false, String.valueOf(e.getMessage()));
            }
        }, "mc-track-ingest");
        t.setDaemon(true);
        t.start();
    }

    /** Transcode a local audio file (mp3/wav/flac/…) to a normalised {@code dest} (.ogg). Runs async. */
    public void transcodeFile(File src, File dest, Callback cb) {
        Thread t = new Thread(() -> {
            try {
                File ff = ffmpegBin();
                if (!ff.isFile()) { cb.done(false, "ffmpeg binary missing at " + ff.getPath()); return; }
                ensureExecutable(ff);
                runFfmpeg(ff, src.getAbsolutePath(), dest, cb);
            } catch (Throwable e) {
                cb.done(false, String.valueOf(e.getMessage()));
            }
        }, "mc-track-transcode");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Decode a stored track file to a temp 48 kHz mono WAV for {@link AudioTrack#load}. Blocking — call
     * off the main thread. Returns the temp file (caller deletes it), or throws on failure.
     */
    public File decodeToWav(File src) throws IOException, InterruptedException {
        File ff = ffmpegBin();
        if (!ff.isFile()) throw new IOException("ffmpeg binary missing at " + ff.getPath());
        ensureExecutable(ff);
        File out = File.createTempFile("mc-track-", ".wav", cacheDir());
        List<String> args = new ArrayList<>();
        args.add(ff.getAbsolutePath());
        args.add("-hide_banner"); args.add("-loglevel"); args.add("error"); args.add("-y");
        args.add("-i"); args.add(src.getAbsolutePath());
        args.add("-ar"); args.add("48000"); args.add("-ac"); args.add("1");
        args.add("-f"); args.add("wav");
        args.add(out.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(args);
        applyTempEnv(pb);
        Process p = pb.start();
        String err = drain(p.getErrorStream());
        int code = p.waitFor();
        if (code != 0) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new IOException("ffmpeg decode failed (" + code + "): " + err);
        }
        return out;
    }

    // --- process orchestration ---------------------------------------------

    /** {@code yt-dlp -o - <url> | ffmpeg -i pipe:0 … <dest>} — ffmpeg does no networking. */
    private void runPiped(File ytdlpBin, File ffmpegBin, String url, File dest, Callback cb)
            throws IOException, InterruptedException {
        String binDir = ffmpegBin.getParentFile().getAbsolutePath();
        List<String> ytArgs = new ArrayList<>(List.of(
                ytdlpBin.getAbsolutePath(),
                "-o", "-",
                "-f", "bestaudio/best",
                // yt-dlp fetches HLS itself (DNS-safe); ffmpeg only ever reads the pipe.
                "--hls-prefer-native",
                "--ffmpeg-location", binDir,
                "--quiet", "--no-warnings", "--no-playlist", "--no-part"));
        addYtdlpAuth(ytArgs);
        ytArgs.add(url);   // --no-playlist: a watch?v=…&list=… URL downloads only the video
        ProcessBuilder yt = new ProcessBuilder(ytArgs);
        ProcessBuilder ff = new ProcessBuilder(ffmpegArgs("pipe:0", dest));
        applyTempEnv(yt);
        applyTempEnv(ff);
        List<Process> ps = ProcessBuilder.startPipeline(List.of(yt, ff));
        Process ytp = ps.get(0);
        Process ffp = ps.get(1);
        String ytErr = drain(ytp.getErrorStream());
        String ffErr = drain(ffp.getErrorStream());
        int code = ffp.waitFor();
        ytp.waitFor();
        if (code != 0 || !dest.isFile() || dest.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            String detail = !ytErr.isBlank() ? ytErr : ffErr;
            cb.done(false, "capture failed (" + code + "): " + detail);
            return;
        }
        cb.done(true, null);
    }

    /** ffmpeg reads {@code input} directly (local file or rtmp/rtsp). */
    private void runFfmpeg(File ffmpegBin, String input, File dest, Callback cb)
            throws IOException, InterruptedException {
        ProcessBuilder ff = new ProcessBuilder(ffmpegArgs(input, dest));
        applyTempEnv(ff);
        Process ffp = ff.start();
        String ffErr = drain(ffp.getErrorStream());
        int code = ffp.waitFor();
        if (code != 0 || !dest.isFile() || dest.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            cb.done(false, "transcode failed (" + code + "): " + ffErr);
            return;
        }
        cb.done(true, null);
    }

    /** ffmpeg args: read {@code inputSpec}, drop video, encode audio → Opus/Ogg at {@code dest}. */
    private List<String> ffmpegArgs(String inputSpec, File dest) {
        List<String> ff = new ArrayList<>();
        ff.add(ffmpegBin().getAbsolutePath());
        ff.add("-hide_banner");
        ff.add("-loglevel"); ff.add("warning");
        ff.add("-y");
        ff.add("-i"); ff.add(inputSpec);
        ff.add("-vn");                       // no video
        ff.add("-map"); ff.add("0:a:0?");    // first audio track (optional)
        ff.add("-ar"); ff.add("48000");
        ff.add("-ac"); ff.add("1");
        ff.add("-c:a"); ff.add("libopus");
        ff.add("-b:a"); ff.add(plugin.getConfig().getString("music.opus-bitrate", "96k"));
        ff.add("-f"); ff.add("ogg");
        ff.add(dest.getAbsolutePath());
        return ff;
    }

    private void applyTempEnv(ProcessBuilder pb) {
        String dir = cacheDir().getAbsolutePath();
        pb.environment().put("TMPDIR", dir);
        pb.environment().put("TMP", dir);
        pb.environment().put("TEMP", dir);
    }

    /** Read a child's stderr fully (keeps the pipe from blocking) and return the last lines for errors. */
    private String drain(InputStream stream) {
        StringBuilder sb = new StringBuilder();
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(stream))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() < 2000) sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
        return sb.toString().trim();
    }

    private static boolean needsYtdlp(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("rtmp") || u.startsWith("rtsp")) return false;
        return u.startsWith("http");   // all HTTP goes through yt-dlp (ffmpeg DNS segfault)
    }

    private static void ensureExecutable(File f) {
        f.setExecutable(true, false);
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(f.toPath(), perms);
        } catch (Exception ignored) {
        }
    }
}
