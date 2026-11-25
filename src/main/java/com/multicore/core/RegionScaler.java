package com.multicore.core;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many region threads each world gets, and how finely it is cut into regions, so that it keeps up with 20 TPS.
 * <p>
 * A world starts without region threads, ticked on the dimension thread it shares with other light worlds (see
 * {@link ThreadGroups}). Every {@link Settings#windowTicks} ticks each world's average tick time is compared with {@link Settings#targetMs}
 * (40 ms by default, leaving the rest of the 50 ms tick for everything else):
 * <ul>
 * <li><b>Too slow on its shared thread:</b> split over two region threads.</li>
 * <li><b>Too slow, and it has more regions than threads:</b> more threads (half as many again, at least one more), up to
 * {@link Settings#maxThreads} (default: every logical core).</li>
 * <li><b>Too slow, and every region already has its own thread:</b> smaller squares (half the size, down to
 * {@link Settings#minShift}): groups of machines with a gap between them then become separate regions.</li>
 * <li><b>Well under {@link Settings#relaxMs}:</b> one thread fewer, back down to none (only its shared thread), then bigger
 * squares again (fewer pauses for things that reach across a region border), up to the configured size.</li>
 * </ul>
 * One region (one connected group of machines and mobs) always runs on one thread: that is what keeps it correct. A
 * single huge factory with no gaps in it cannot be split however many cores there are.
 * <p>
 * Pure bookkeeping, unit tested. {@link #record} and {@link #endTick} on the main thread; the getters from any thread.
 */
public final class RegionScaler {
    public record Settings(boolean enabled, double targetMs, double relaxMs, int minThreads, int maxThreads, int minShift,
                           int maxShift, int windowTicks) {
        public static final Settings DEFAULT = new Settings(true, 40.0, 15.0, 1, 0, 2, 3, 100);

        int threadLimit() {
            return this.maxThreads > 0 ? this.maxThreads : Math.max(2, Runtime.getRuntime().availableProcessors());
        }
    }

    static final class World {
        volatile int threads;
        volatile int shift;
        double sumMs;
        int ticks;
        int maxRegions;
        double lastAvgMs = Double.NaN;
        String last = "";
    }

    private static final RegionScaler INSTANCE = new RegionScaler();

    private volatile Settings settings = Settings.DEFAULT;
    private final Map<String, World> worlds = new ConcurrentHashMap<>();
    private int windowTick;

    RegionScaler() {
    }

    public static RegionScaler get() {
        return INSTANCE;
    }

    /** Settings a test set, which a config reload in the middle of it must not replace. */
    private volatile Settings override;

    public void configure(Settings s) {
        this.base = s;
        if (this.override == null) {
            this.apply(s);
        }
    }

    private volatile Settings base = Settings.DEFAULT;

    private void apply(Settings s) {
        this.settings = s;
        this.worlds.clear();
        this.windowTick = 0;
    }

    /** For tests: use {@code s} until {@link #clearOverride}, whatever the config says meanwhile. */
    public void override(Settings s) {
        this.override = s;
        this.apply(s);
    }

    public void clearOverride() {
        this.override = null;
        this.apply(this.base);
    }

    public Settings settings() {
        return this.settings;
    }

    private World world(String dim) {
        return this.worlds.computeIfAbsent(dim, d -> {
            World w = new World();
            Settings s = this.settings;
            w.threads = Math.min(s.threadLimit(), Math.max(1, s.minThreads()));
            w.shift = s.maxShift();
            return w;
        });
    }

    /** Region threads {@code dim} may use for one loop this tick. */
    public int threads(String dim) {
        Settings s = this.settings;
        if (!s.enabled()) return s.threadLimit();
        World w = this.worlds.get(dim);
        return w == null ? Math.min(s.threadLimit(), Math.max(1, s.minThreads())) : w.threads;
    }

    /** Square size of {@code dim} as a power of two in chunks. */
    public int shift(String dim) {
        Settings s = this.settings;
        if (!s.enabled()) return s.maxShift();
        World w = this.worlds.get(dim);
        return w == null ? s.maxShift() : w.shift;
    }

    /** Main thread, once per world per tick: how long it took and how many regions its biggest split found (1 = none). */
    public void record(String dim, double tickMs, int regions) {
        if (!this.settings.enabled()) return;
        World w = this.world(dim);
        w.sumMs += tickMs;
        w.ticks++;
        w.maxRegions = Math.max(w.maxRegions, regions);
    }

    /** Main thread, end of every server tick. Returns a message when something changed. */
    public String endTick() {
        Settings s = this.settings;
        if (!s.enabled() || ++this.windowTick < s.windowTicks()) return null;
        this.windowTick = 0;
        StringBuilder changed = new StringBuilder();
        int limit = s.threadLimit();
        for (Map.Entry<String, World> e : this.worlds.entrySet()) {
            World w = e.getValue();
            if (w.ticks == 0) continue;
            double avg = w.sumMs / w.ticks;
            w.lastAvgMs = avg;
            String what = null;
            if (avg > s.targetMs()) {
                if (w.threads == 1 && limit > 1) {
                    // Not split yet, so its regions are not known: try two threads (the governor keeps the split only if faster).
                    w.threads = 2;
                    what = String.format(Locale.ROOT, "%.1f ms/tick on its shared thread: splitting it over 2 threads", avg);
                } else if (w.maxRegions > w.threads && w.threads < limit) {
                    int before = w.threads;
                    w.threads = Math.min(limit, Math.max(w.threads + 1, Math.min(w.maxRegions, w.threads + w.threads / 2)));
                    what = String.format(Locale.ROOT, "%.1f ms/tick, %d regions: %d -> %d threads", avg, w.maxRegions, before, w.threads);
                } else if (w.maxRegions <= w.threads && w.shift > s.minShift() && w.maxRegions > 0) {
                    w.shift--;
                    what = String.format(Locale.ROOT, "%.1f ms/tick, %d regions on %d threads: squares down to %d chunks",
                            avg, w.maxRegions, w.threads, 1 << w.shift);
                }
            } else if (avg < s.relaxMs()) {
                if (w.threads > Math.max(1, s.minThreads())) {
                    w.threads--;
                    what = String.format(Locale.ROOT, "%.1f ms/tick: down to %d threads", avg, w.threads);
                } else if (w.shift < s.maxShift()) {
                    w.shift++;
                    what = String.format(Locale.ROOT, "%.1f ms/tick: squares back up to %d chunks", avg, 1 << w.shift);
                }
            }
            if (what != null) {
                w.last = what;
                changed.append(changed.isEmpty() ? "" : "; ").append(e.getKey()).append(' ').append(what);
            }
            w.sumMs = 0;
            w.ticks = 0;
            w.maxRegions = 0;
        }
        return changed.isEmpty() ? null : changed.toString();
    }

    /** One line per world for /multicore status. */
    public Map<String, String> describe() {
        Map<String, String> out = new TreeMap<>();
        this.worlds.forEach((dim, w) -> out.put(dim, String.format(Locale.ROOT, "%d region threads, %d-chunk squares (%.1f ms/tick)%s",
                w.threads, 1 << w.shift, w.lastAvgMs, w.last.isEmpty() ? "" : "; last change: " + w.last)));
        return out;
    }
}
