package dev.servereer.machineconstruct.model.anim;

import dev.servereer.machineconstruct.core.DisplayContent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A content (skin) selector — the {@code swap} channel of DESIGN.md §10. Unlike
 * an {@link AnimationFunction} (which outputs a transform delta), a swap picks
 * <b>which</b> {@link DisplayContent} a part shows this frame, from a list of
 * resolved frames. Re-sent as metadata each tick (cheap: same entity, only the
 * content index changes), so frames must share the base part's display type.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code CYCLE} — step through frames over {@code period} seconds (a
 *       flipbook; align {@code period} to a spin's period for an
 *       "at end of animation" switch).</li>
 *   <li>{@code RANDOM} — pick a random frame every {@code interval} seconds.</li>
 *   <li>{@code HOLD} — show {@code frames[0]} while the trigger is active
 *       (state-driven skin switch: a different look per idle/working/…).</li>
 * </ul>
 * Per-instance desync is free: the driver {@code clock} already carries each
 * machine's phase offset, so identical machines cycle/roll out of lockstep.
 */
public final class ContentSwap {

    public enum Mode {
        CYCLE, RANDOM, HOLD;

        public static Mode from(String s) {
            if (s == null) return CYCLE;
            return switch (s.toLowerCase()) {
                case "random" -> RANDOM;
                case "hold", "state" -> HOLD;
                default -> CYCLE;   // cycle | sequence | flipbook
            };
        }
    }

    private final Mode mode;
    private final double period;     // CYCLE: seconds for a full loop
    private final double interval;   // RANDOM: seconds per pick
    private final List<DisplayContent> frames;
    private final long seed;         // per-swap salt so sibling swaps differ

    public ContentSwap(Mode mode, double period, double interval, List<DisplayContent> frames) {
        this.mode = mode;
        this.period = period;
        this.interval = interval;
        this.frames = frames;
        this.seed = ThreadLocalRandom.current().nextLong();
    }

    /** The content to show this frame, or {@code null} if there are no frames. */
    public DisplayContent pick(DriverContext ctx) {
        int n = frames.size();
        if (n == 0) return null;
        return switch (mode) {
            case HOLD -> frames.get(0);
            case CYCLE -> {
                double frameDur = (period <= 0 ? 1.0 : period) / n;
                int idx = (int) Math.floor(ctx.clock / frameDur);
                yield frames.get(((idx % n) + n) % n);
            }
            case RANDOM -> {
                double iv = interval <= 0 ? 0.5 : interval;
                long bucket = (long) Math.floor(ctx.clock / iv);
                int idx = (int) Math.floorMod(mix(bucket ^ seed), n);
                yield frames.get(idx);
            }
        };
    }

    /** SplitMix64 finalizer — spreads adjacent buckets so picks don't streak. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
