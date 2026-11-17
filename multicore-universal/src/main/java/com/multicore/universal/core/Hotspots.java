package com.multicore.universal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Which methods take the mod lock most often, from a sample of acquisitions (see ModLock). The method that took the
 * lock is the first frame below MultiCore's own; for mixin hooks merged into vanilla classes the name shows the mod
 * ("handler$...$modid$..."). /multicore locks lists the top ones: they are what to look at before marking a mod
 * thread-safe. No Minecraft types (loaded with ModLock).
 */
public final class Hotspots {
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance();

    private Hotspots() {
    }

    static void sample() {
        String site = WALKER.walk(frames -> frames
                .filter(f -> !f.getClassName().startsWith("com.multicore.universal.core.") && !f.getClassName().startsWith("java."))
                .findFirst()
                .map(f -> f.getClassName() + "." + f.getMethodName())
                .orElse("?"));
        COUNTS.computeIfAbsent(site, k -> new LongAdder()).increment();
    }

    public static void reset() {
        COUNTS.clear();
    }

    public static List<String> report(int limit) {
        List<Map.Entry<String, LongAdder>> entries = new ArrayList<>(COUNTS.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        long total = entries.stream().mapToLong(e -> e.getValue().sum()).sum();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, LongAdder> e : entries.subList(0, Math.min(limit, entries.size()))) {
            lines.add(String.format(Locale.ROOT, "%5.1f%%  %s", 100.0 * e.getValue().sum() / Math.max(1, total), e.getKey()));
        }
        return lines;
    }
}
