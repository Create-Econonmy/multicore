package com.multicore.core;

import java.util.concurrent.atomic.LongAdder;

/** Counters shown by /multicore status. */
public final class Stats {
    public static final LongAdder DEFERRED = new LongAdder();
    public static final LongAdder REGION_SPLITS = new LongAdder();
    /** Last parallel phase: the work it did as one thread would have had to do it (ns), for auto-adjust and tests. */
    public static volatile long lastSumOfLevelsNanos;
    /** Main thread, last tick (ns): LevelTickEvent.Pre + time sync; waiting while the dimension threads tick (running the
     * serial dimensions' chunk tasks); deferred work; serial dimensions; LevelTickEvent.Post. Written by the main thread. */
    public static volatile long mainPreNanos, mainWaitNanos, mainDeferredNanos, mainSerialLevelsNanos, mainPostNanos;
    /** Smoothed (about the last 5 seconds) of the same, in ms, for /multicore status. */
    public static volatile double avgPreMs, avgWaitMs, avgDeferredMs, avgSerialLevelsMs, avgPostMs, avgServerTickMs;
    public static final LongAdder REGION_DEFERRED = new LongAdder();
    public static final LongAdder REGION_EXCLUSIVE = new LongAdder();
    public static final LongAdder REGION_EXCLUSIVE_TIMEOUTS = new LongAdder();
    public static final LongAdder REGION_FALLBACKS = new LongAdder();
    public static final LongAdder CROSS_LEVEL_READS = new LongAdder();
    public static final LongAdder CROSS_LEVEL_LOADS = new LongAdder();
    public static final LongAdder MOD_LOCK_ACQUIRES = new LongAdder();
    public static final LongAdder MOD_LOCK_WAIT_NANOS = new LongAdder();

    /** Main-thread only. */
    static volatile long lastPhaseNanos;

    public static long lastPhaseNanos() {
        return lastPhaseNanos;
    }
    static long lastSerialNanos;
    static long phases;
    static double avgPhaseMillis;
    static double avgSumOfLevelsMillis;

    private Stats() {
    }

    /** Running totals, for measuring a window (benchmarks). Main-thread only. */
    static long totalPhaseNanos;
    static long totalSumOfLevelsNanos;

    public static long totalPhaseNanos() {
        return totalPhaseNanos;
    }

    public static long totalSumOfLevelsNanos() {
        return totalSumOfLevelsNanos;
    }

    static void recordPhase(long phaseNanos, long sumOfLevelNanos) {
        phases++;
        totalPhaseNanos += phaseNanos;
        totalSumOfLevelsNanos += sumOfLevelNanos;
        lastPhaseNanos = phaseNanos;
        lastSumOfLevelsNanos = sumOfLevelNanos;
        avgPhaseMillis = avgPhaseMillis * 0.95 + phaseNanos / 1_000_000.0 * 0.05;
        avgSumOfLevelsMillis = avgSumOfLevelsMillis * 0.95 + sumOfLevelNanos / 1_000_000.0 * 0.05;
    }

    public static double avgPhaseMillis() {
        return avgPhaseMillis;
    }

    /** How long the same work would take on one thread. The ratio to avgPhaseMillis is the speedup. */
    public static double avgSumOfLevelsMillis() {
        return avgSumOfLevelsMillis;
    }

    public static long phases() {
        return phases;
    }

    public static double lastSerialMillis() {
        return lastSerialNanos / 1_000_000.0;
    }

    /** Main thread only, once per tick. */
    static void recordMainThread(long pre, long wait, long deferred, long serialLevels, long post, long averageTickNanos) {
        mainPreNanos = pre;
        mainWaitNanos = wait;
        mainDeferredNanos = deferred;
        mainSerialLevelsNanos = serialLevels;
        mainPostNanos = post;
        double a = 0.01; // about 100 ticks
        avgPreMs += (pre / 1e6 - avgPreMs) * a;
        avgWaitMs += (wait / 1e6 - avgWaitMs) * a;
        avgDeferredMs += (deferred / 1e6 - avgDeferredMs) * a;
        avgSerialLevelsMs += (serialLevels / 1e6 - avgSerialLevelsMs) * a;
        avgPostMs += (post / 1e6 - avgPostMs) * a;
        avgServerTickMs = averageTickNanos / 1e6;
    }
}
