package com.multicore.universal.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Who takes the {@link ModLock} and how long others wait for it because of that. /multicore locks shows the top
 * entries; they are the mods worth checking and adding to threadSafeMods.
 */
public final class LockSource {
    private static final Map<String, LockSource> ALL = new ConcurrentHashMap<>();

    final String name;
    final LongAdder acquisitions = new LongAdder();
    final LongAdder contended = new LongAdder();
    final LongAdder waitNanos = new LongAdder();
    final LongAdder holdNanos = new LongAdder();

    private LockSource(String name) {
        this.name = name;
    }

    public static LockSource of(String name) {
        return ALL.computeIfAbsent(name, LockSource::new);
    }

    public String name() {
        return this.name;
    }

    public static void reset() {
        ALL.values().forEach(s -> {
            s.acquisitions.reset();
            s.contended.reset();
            s.waitNanos.reset();
            s.holdNanos.reset();
        });
    }

    /** Sources sorted by how much time they held the lock (time other threads may have been blocked). */
    public static List<String> report(int limit) {
        List<LockSource> sources = new ArrayList<>(ALL.values());
        sources.removeIf(s -> s.acquisitions.sum() == 0);
        sources.sort(Comparator.comparingLong((LockSource s) -> s.holdNanos.sum()).reversed());
        List<String> lines = new ArrayList<>();
        for (LockSource s : sources.subList(0, Math.min(limit, sources.size()))) {
            lines.add(String.format(java.util.Locale.ROOT, "%-48s held %8.1f ms, %9d times, waited %8.1f ms (%d contended)",
                    s.name, s.holdNanos.sum() / 1e6, s.acquisitions.sum(), s.waitNanos.sum() / 1e6, s.contended.sum()));
        }
        return lines;
    }
}
