package com.multicore.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which dimensions share a dimension thread. One thread runs several dimensions (2 to {@code maxDimensionsPerThread},
 * default 6), one after another: light dimensions are packed together until the thread holds about
 * {@code threadTargetMs} of tick time, and a heavy dimension (a busy overworld) gets a thread of its own. Dimensions
 * listed together in {@code threadGroups} always share a thread. At most {@code maxThreads} threads (0 = the logical
 * cores minus two). Uses each dimension's recent tick time, so the grouping follows the load. Pure logic, unit tested.
 */
public final class ThreadGroups {
    private ThreadGroups() {
    }

    public record Settings(int maxThreads, int maxDimensionsPerThread, double threadTargetMs) {
        public static final Settings DEFAULT = new Settings(0, 6, 10.0);

        int threadLimit() {
            return this.maxThreads > 0 ? this.maxThreads : autoLimit();
        }
    }

    /** Thread limit when {@code maxThreads} is 0: the logical cores minus the main thread and one for the OS, at least 2. */
    public static int autoLimit() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    }

    /**
     * @param dims       the parallel dimensions this tick, in tick order
     * @param configured groups from the config (each a set of dimension ids that must share a thread)
     * @param costMs     recent tick time per dimension (missing = 1 ms)
     * @return the dimensions of each thread: every input dimension exactly once, in input order within a thread
     */
    public static List<List<String>> assign(List<String> dims, Collection<? extends Collection<String>> configured, Settings s,
                                            Map<String, Double> costMs) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < dims.size(); i++) order.put(dims.get(i), i);
        // 1. Units: configured groups (only their dimensions that are parallel now), then every other dimension alone.
        List<Set<String>> units = new ArrayList<>();
        // Configured groups get a thread to themselves: nothing else is packed onto it while the thread limit allows.
        Set<Set<String>> dedicated = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<String> placed = new LinkedHashSet<>();
        for (Collection<String> g : configured) {
            Set<String> unit = new LinkedHashSet<>();
            for (String d : g) if (order.containsKey(d) && !placed.contains(d)) unit.add(d);
            if (!unit.isEmpty()) {
                units.add(unit);
                dedicated.add(unit);
                placed.addAll(unit);
            }
        }
        for (String d : dims) {
            if (placed.add(d)) {
                Set<String> unit = new LinkedHashSet<>();
                unit.add(d);
                units.add(unit);
            }
        }
        // 2. Pack, heaviest first: into the fullest thread that still has room (count and time), else a new thread,
        // else (at the thread limit) the least loaded thread.
        units.sort(Comparator.comparingDouble((Set<String> u) -> cost(u, costMs)).reversed()
                .thenComparingInt(u -> order.get(u.iterator().next())));
        int perThread = Math.max(1, s.maxDimensionsPerThread());
        int limit = Math.max(1, s.threadLimit());
        List<Set<String>> threads = new ArrayList<>();
        List<Double> load = new ArrayList<>();
        List<Boolean> closed = new ArrayList<>(); // runs a configured group: nothing else joins it
        for (Set<String> u : units) {
            double c = cost(u, costMs);
            boolean own = dedicated.contains(u);
            int best = -1;
            for (int i = 0; i < threads.size() && !own; i++) {
                boolean fits = !closed.get(i) && threads.get(i).size() + u.size() <= perThread && load.get(i) + c <= s.threadTargetMs();
                if (fits && (best < 0 || load.get(i) > load.get(best))) best = i;
            }
            if (best < 0 && threads.size() < limit) {
                threads.add(new LinkedHashSet<>());
                load.add(0.0);
                closed.add(own);
                best = threads.size() - 1;
            }
            if (best < 0) { // at the thread limit: the least loaded thread, configured or not
                for (int i = 0; i < threads.size(); i++) {
                    boolean room = threads.get(i).size() + u.size() <= perThread;
                    if (best < 0 || (room && (threads.get(best).size() + u.size() > perThread || load.get(i) < load.get(best)))) best = i;
                }
            }
            threads.get(best).addAll(u);
            load.set(best, load.get(best) + c);
        }
        // 3. Stable output: dimensions in tick order inside a thread, threads by their first dimension.
        List<List<String>> out = new ArrayList<>();
        for (Set<String> t : threads) {
            List<String> l = new ArrayList<>(t);
            l.sort(Comparator.comparingInt(order::get));
            out.add(l);
        }
        out.sort(Comparator.comparingInt(l -> order.get(l.get(0))));
        return out;
    }

    private static double cost(Set<String> unit, Map<String, Double> costMs) {
        double c = 0;
        for (String d : unit) c += Math.max(0.05, costMs.getOrDefault(d, 1.0));
        return c;
    }

    /** Parses config entries like {@code "minecraft:the_nether, minecraft:the_end"} into groups. */
    public static List<Set<String>> parse(Collection<? extends String> entries) {
        List<Set<String>> groups = new ArrayList<>();
        for (String e : entries) {
            Set<String> g = new LinkedHashSet<>();
            for (String part : e.split("[,;+ ]+")) if (!part.isBlank()) g.add(part.trim());
            if (!g.isEmpty()) groups.add(g);
        }
        return groups;
    }

    /** For /multicore status: "minecraft:overworld | minecraft:the_nether + minecraft:the_end". */
    public static String describe(List<List<String>> groups) {
        List<String> parts = new ArrayList<>();
        for (List<String> g : groups) parts.add(String.join(" + ", g));
        return String.join(" | ", parts);
    }
}
