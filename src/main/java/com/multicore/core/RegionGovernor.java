package com.multicore.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps region threading only where it pays off.
 * <p>
 * A dimension whose entities or block entities are mostly mod code (Create, Sable...) gains nothing from region threads:
 * every item takes the one mod lock, so the region threads queue on it and pause each other for cross-region access,
 * and the split ends up several times slower than one thread (seen live: South 30 ms/tick on one thread, 148 ms split).
 * <p>
 * Per dimension and per loop (entities, block entities) this times both ways, per item, and splits only while splitting
 * is clearly faster. It starts with the single thread as the baseline, tries the split, and if the split loses it stays
 * on one thread for a back-off period (doubling each time, up to {@link #MAX_BACKOFF_NANOS}) before trying again. While
 * the split wins it still re-checks the single thread now and then, since the world changes.
 */
public final class RegionGovernor {
    /** Runs measured per phase before deciding. */
    private static final int SAMPLE_RUNS = 40;
    private static final int EARLY_RUNS = 5;
    /** The split must take at most this fraction of the single-thread time per item to be kept. */
    private static final double KEEP_RATIO = 0.85;
    private static final long FIRST_BACKOFF_NANOS = 60_000_000_000L;
    private static final long MAX_BACKOFF_NANOS = 15 * 60_000_000_000L;
    /** While the split wins, re-measure the single thread this often. */
    private static final long RECHECK_NANOS = 5 * 60_000_000_000L;

    private static volatile boolean enabled = true;
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private RegionGovernor() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        STATES.clear();
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void reset() {
        STATES.clear();
    }

    enum Phase {
        MEASURE_SERIAL, MEASURE_SPLIT, SPLIT, SERIAL
    }

    static final class State {
        Phase phase = Phase.MEASURE_SERIAL;
        int runs;
        long nanos;
        long items;
        double serialPerItem = Double.NaN;
        double splitPerItem = Double.NaN;
        long until;
        long backoff = FIRST_BACKOFF_NANOS;
        int fallbacks;
    }

    private static String key(String dim, boolean entities) {
        return dim + (entities ? " entities" : " block entities");
    }

    /** May this loop of {@code dim} be split this tick? Only called on the dimension's own thread. */
    static boolean allowSplit(String dim, boolean entities) {
        if (!enabled) {
            return true;
        }
        State s = STATES.computeIfAbsent(key(dim, entities), k -> new State());
        long now = System.nanoTime();
        switch (s.phase) {
            case SERIAL -> {
                if (now - s.until >= 0) {
                    start(s, Phase.MEASURE_SPLIT);
                    return true;
                }
                return false;
            }
            case SPLIT -> {
                if (now - s.until >= 0) {
                    start(s, Phase.MEASURE_SERIAL);
                    return false;
                }
                return true;
            }
            case MEASURE_SPLIT -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** After the loop ran: {@code split} says how it ran, {@code nanos} its wall time for {@code count} items. */
    static void record(String dim, boolean entities, boolean split, long nanos, int count) {
        if (!enabled || count <= 0) {
            return;
        }
        State s = STATES.get(key(dim, entities));
        if (s == null) {
            return;
        }
        boolean measuring = (s.phase == Phase.MEASURE_SERIAL && !split) || (s.phase == Phase.MEASURE_SPLIT && split);
        if (!measuring) {
            return;
        }
        s.runs++;
        s.nanos += nanos;
        s.items += count;
        if (s.runs < SAMPLE_RUNS) {
            // A split twice as slow as one thread after a few runs is not worth the rest of the sample (players feel it).
            if (s.phase == Phase.MEASURE_SPLIT && s.runs >= EARLY_RUNS && !Double.isNaN(s.serialPerItem)
                    && (double) s.nanos / s.items > s.serialPerItem * 2) {
                s.splitPerItem = (double) s.nanos / s.items;
                fallBack(s, System.nanoTime());
            }
            return;
        }
        double perItem = (double) s.nanos / s.items;
        long now = System.nanoTime();
        if (s.phase == Phase.MEASURE_SERIAL) {
            s.serialPerItem = perItem;
            if (Double.isNaN(s.splitPerItem)) {
                start(s, Phase.MEASURE_SPLIT);
            } else if (s.splitPerItem <= perItem * KEEP_RATIO) {
                s.phase = Phase.SPLIT;
                s.until = now + RECHECK_NANOS;
            } else {
                fallBack(s, now);
            }
        } else {
            s.splitPerItem = perItem;
            if (perItem <= s.serialPerItem * KEEP_RATIO) {
                s.phase = Phase.SPLIT;
                s.until = now + RECHECK_NANOS;
                s.backoff = FIRST_BACKOFF_NANOS;
            } else {
                fallBack(s, now);
            }
        }
    }

    private static void fallBack(State s, long now) {
        s.phase = Phase.SERIAL;
        s.until = now + s.backoff;
        s.backoff = Math.min(MAX_BACKOFF_NANOS, s.backoff * 2);
        s.fallbacks++;
        Stats.REGION_FALLBACKS.increment();
    }

    private static void start(State s, Phase phase) {
        s.phase = phase;
        s.runs = 0;
        s.nanos = 0;
        s.items = 0;
    }

    /** One line per loop for /multicore status. */
    public static Map<String, String> describe() {
        Map<String, String> out = new java.util.TreeMap<>();
        long now = System.nanoTime();
        STATES.forEach((k, s) -> {
            String what = switch (s.phase) {
                case SPLIT -> "split (faster)";
                case SERIAL -> "one thread, split was slower; retry in " + Math.max(0, (s.until - now) / 1_000_000_000L) + " s";
                case MEASURE_SPLIT -> "measuring split";
                case MEASURE_SERIAL -> "measuring one thread";
            };
            out.put(k, what + String.format(" (one thread %s us/item, split %s us/item, %d fallbacks)",
                    fmt(s.serialPerItem), fmt(s.splitPerItem), s.fallbacks));
        });
        return out;
    }

    private static String fmt(double nanosPerItem) {
        return Double.isNaN(nanosPerItem) ? "?" : String.format("%.2f", nanosPerItem / 1000.0);
    }
}
