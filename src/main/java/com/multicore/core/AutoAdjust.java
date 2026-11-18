package com.multicore.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Adjusts MultiCore's regions (one per dimension) to the load, every {@link Settings#windowTicks} ticks:
 * <ul>
 * <li><b>Idle regions join the main thread.</b> A dimension with no players whose tick costs less than
 * {@link Settings#idleMs} ticks on the main server thread after the parallel phase instead of waking its own thread.
 * It gets its own thread back as soon as a player enters or its tick costs more than {@link Settings#busyMs}.</li>
 * <li><b>No gain under load, no threads.</b> When the server is busy (parallel phase over {@link Settings#loadMs}) but
 * the parallel tick is not at least {@link Settings#minSpeedup} times faster than the same work on one thread
 * (everything waiting on the shared mod lock), MultiCore ticks like vanilla for {@link Settings#retryTicks} and then
 * measures again. Each failed retry doubles the wait, up to {@link Settings#maxRetryTicks}.</li>
 * </ul>
 * Pure bookkeeping with no Minecraft types, so the decisions can be unit tested. Main thread only.
 */
public final class AutoAdjust {
    public record Settings(boolean enabled, double idleMs, double busyMs, double loadMs, double minSpeedup,
                           int windowTicks, int badWindows, long retryTicks, long maxRetryTicks) {
        public static final Settings DEFAULT = new Settings(true, 0.5, 2.0, 50.0, 1.05, 100, 6, 20L * 60 * 10, 20L * 60 * 60);
        public static final Settings OFF = new Settings(false, 0.5, 2.0, 50.0, 1.05, 100, 6, 20L * 60 * 10, 20L * 60 * 60);
    }

    static final class Region {
        double avgMs;
        boolean seen;
        int players;
        boolean idle;
    }

    private static final AutoAdjust INSTANCE = new AutoAdjust();

    private volatile Settings settings = Settings.DEFAULT;
    private final Map<String, Region> regions = new HashMap<>();
    private int windowTick;
    private double windowPhaseMs;
    private double windowWorkMs;
    private int windowPhases;
    private int slowWindows;
    private long singleThreadUntil = -1;
    private long nextRetryTicks = Settings.DEFAULT.retryTicks();
    private double lastSpeedup = Double.NaN;
    private String lastAction = "none yet";

    public static AutoAdjust get() {
        return INSTANCE;
    }

    public void configure(Settings s) {
        this.settings = s;
        this.nextRetryTicks = s.retryTicks();
        if (!s.enabled()) {
            this.reset();
        }
    }

    public Settings settings() {
        return this.settings;
    }

    public void reset() {
        this.regions.clear();
        this.windowTick = 0;
        this.windowPhaseMs = 0;
        this.windowWorkMs = 0;
        this.windowPhases = 0;
        this.slowWindows = 0;
        this.singleThreadUntil = -1;
        this.nextRetryTicks = this.settings.retryTicks();
        this.lastSpeedup = Double.NaN;
    }

    /** One dimension's tick this server tick: its time minus the time it spent waiting for the mod lock. */
    public void recordRegion(String dimension, double tickMs, int players) {
        Region r = this.regions.computeIfAbsent(dimension, k -> new Region());
        r.avgMs = r.seen ? r.avgMs * 0.9 + tickMs * 0.1 : tickMs;
        r.seen = true;
        r.players = players;
        if (r.idle && players > 0) {
            r.idle = false;
            this.lastAction = dimension + " got its own thread back (a player entered)";
        }
    }

    /** A parallel phase: its wall time and the work it did (sum of the dimensions' tick times minus lock waits). */
    public void recordParallelPhase(double wallMs, double workMs) {
        this.windowPhaseMs += wallMs;
        this.windowWorkMs += workMs;
        this.windowPhases++;
    }

    /** End of a server tick. Returns a message when a decision changed something, otherwise null. */
    public String endTick(long tick) {
        Settings s = this.settings;
        if (!s.enabled()) {
            return null;
        }
        if (this.singleThreadUntil >= 0 && tick >= this.singleThreadUntil) {
            this.singleThreadUntil = -1;
            this.slowWindows = 0;
            this.clearWindow();
            return this.note("retrying parallel ticking (" + this.retryText() + ")");
        }
        if (++this.windowTick < s.windowTicks()) {
            return null;
        }
        String msg = null;
        if (this.singleThreadUntil < 0) {
            msg = this.evaluateGain(s, tick);
            if (msg == null) {
                msg = this.evaluateIdle(s);
            }
        }
        this.clearWindow();
        return msg;
    }

    private String evaluateGain(Settings s, long tick) {
        if (this.windowPhases == 0) {
            return null;
        }
        double phase = this.windowPhaseMs / this.windowPhases;
        double work = this.windowWorkMs / this.windowPhases;
        double speedup = phase > 0 ? work / phase : 1.0;
        this.lastSpeedup = speedup;
        if (phase < s.loadMs()) {
            // A quiet server: losing a fraction of a millisecond to thread hand-offs does not matter.
            this.slowWindows = 0;
            return null;
        }
        if (speedup >= s.minSpeedup()) {
            this.slowWindows = 0;
            this.nextRetryTicks = s.retryTicks();
            return null;
        }
        if (++this.slowWindows < s.badWindows()) {
            return null;
        }
        this.slowWindows = 0;
        long wait = this.nextRetryTicks;
        this.singleThreadUntil = tick + wait;
        this.nextRetryTicks = Math.min(wait * 2, s.maxRetryTicks());
        for (Region r : this.regions.values()) {
            r.idle = false;
        }
        return this.note(String.format(Locale.ROOT,
                "under load (%.1f ms/tick) the parallel tick was only %.2fx faster; ticking on one thread for %s",
                phase, speedup, duration(wait)));
    }

    private String evaluateIdle(Settings s) {
        StringBuilder changed = new StringBuilder();
        for (Map.Entry<String, Region> e : this.regions.entrySet()) {
            Region r = e.getValue();
            if (!r.idle && r.players == 0 && r.avgMs < s.idleMs()) {
                r.idle = true;
                changed.append(changed.isEmpty() ? "" : "; ").append(e.getKey()).append(" is idle, ticking it on the main thread");
            } else if (r.idle && (r.players > 0 || r.avgMs > s.busyMs())) {
                r.idle = false;
                changed.append(changed.isEmpty() ? "" : "; ").append(e.getKey())
                        .append(String.format(Locale.ROOT, " is busy (%.2f ms), own thread again", r.avgMs));
            }
        }
        return changed.isEmpty() ? null : this.note(changed.toString());
    }

    private void clearWindow() {
        this.windowTick = 0;
        this.windowPhaseMs = 0;
        this.windowWorkMs = 0;
        this.windowPhases = 0;
    }

    private String note(String msg) {
        this.lastAction = msg;
        return msg;
    }

    private String retryText() {
        return "next wait if it still does not help: " + duration(this.nextRetryTicks);
    }

    static String duration(long ticks) {
        return ticks >= 1200 ? (ticks / 1200) + " min" : (ticks / 20) + " s";
    }

    /** True while every dimension should tick like vanilla because parallel ticking did not help under load. */
    public boolean singleThreadNow(long tick) {
        return this.settings.enabled() && this.singleThreadUntil >= 0 && tick < this.singleThreadUntil;
    }

    public boolean isIdle(String dimension) {
        if (!this.settings.enabled()) {
            return false;
        }
        Region r = this.regions.get(dimension);
        return r != null && r.idle;
    }

    public double averageMs(String dimension) {
        Region r = this.regions.get(dimension);
        return r == null ? Double.NaN : r.avgMs;
    }

    public double lastSpeedup() {
        return this.lastSpeedup;
    }

    public String lastAction() {
        return this.lastAction;
    }

    /** Ticks left on one thread, or 0. */
    public long singleThreadTicksLeft(long tick) {
        return this.singleThreadUntil < 0 ? 0 : Math.max(0, this.singleThreadUntil - tick);
    }
}
