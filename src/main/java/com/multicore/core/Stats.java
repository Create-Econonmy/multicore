package com.multicore.core;

import java.util.concurrent.atomic.LongAdder;

/** Counters shown by /multicore status. */
public final class Stats {
    public static final LongAdder DEFERRED = new LongAdder();
    public static final LongAdder CROSS_LEVEL_READS = new LongAdder();
    public static final LongAdder CROSS_LEVEL_LOADS = new LongAdder();
    public static final LongAdder MOD_LOCK_ACQUIRES = new LongAdder();
    public static final LongAdder MOD_LOCK_WAIT_NANOS = new LongAdder();

    /** Main-thread only. */
    static long lastPhaseNanos;
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
}
