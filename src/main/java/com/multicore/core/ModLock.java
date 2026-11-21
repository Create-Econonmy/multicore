package com.multicore.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock shared by all code from mods that are not known to be thread-safe.
 * <p>
 * Holding it guarantees that no other dimension thread is running such code at the same moment, which is what those
 * mods assume (they were written for a single server thread). Vanilla code and thread-safe mods do not take it, so
 * they keep running in parallel.
 * <p>
 * While waiting for the lock a dimension thread keeps running its own chunk tasks; the holder may be waiting on
 * one of them.
 */
public final class ModLock {
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final int SPIN_TRIES = 200;
    private static final Map<String, LockSource> MOD_SOURCES = new ConcurrentHashMap<>();
    /** Only written and read by the thread holding the lock at depth 1. */
    private static long holdStart;
    private static LockSource holder;

    private ModLock() {
    }

    public static void acquire(LockSource source) {
        source.acquisitions.increment();
        Stats.MOD_LOCK_ACQUIRES.increment();
        if (!LOCK.tryLock()) {
            long start = System.nanoTime();
            // Most holds are a single event listener or block callback and last well under a microsecond, so spin
            // briefly before paying for a park/unpark.
            boolean acquired = false;
            for (int i = 0; i < SPIN_TRIES; i++) {
                Thread.onSpinWait();
                if (!LOCK.isLocked() && LOCK.tryLock()) {
                    acquired = true;
                    break;
                }
            }
            if (!acquired) {
                LevelThread self = TickThreads.currentLevelThread();
                // Lock order is "mod lock, then a region split's write lock": whoever pauses the region threads holds the
                // mod lock first. So a region thread lets go of its read lock while it waits here, and takes it back
                // once it has the mod lock (nobody can hold the write lock then).
                int reads = RegionTicker.releaseReadsForWait();
                try {
                    while (!LOCK.tryLock(100, TimeUnit.MICROSECONDS)) {
                        if (self != null) {
                            self.pollOwnTasks();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOCK.lock();
                } finally {
                    RegionTicker.reacquireReads(reads);
                }
            }
            long waited = System.nanoTime() - start;
            source.contended.increment();
            source.waitNanos.add(waited);
            Stats.MOD_LOCK_WAIT_NANOS.add(waited);
            if (Thread.currentThread() instanceof LevelThread lt) {
                lt.lockWaitNanos += waited;
            }
        }
        if (LOCK.getHoldCount() == 1) {
            holdStart = System.nanoTime();
            holder = source;
        }
    }

    public static void release() {
        if (LOCK.getHoldCount() == 1 && holder != null) {
            holder.holdNanos.add(System.nanoTime() - holdStart);
            holder = null;
        }
        LOCK.unlock();
    }

    /** Runs {@code call} holding the lock for {@code source}, or without it when {@code source} is null. */
    public static void runLocked(@javax.annotation.Nullable LockSource source, Runnable call) {
        if (source == null) {
            call.run();
            return;
        }
        acquire(source);
        try {
            call.run();
        } finally {
            release();
        }
    }

    public static boolean heldByCurrentThread() {
        return LOCK.isHeldByCurrentThread();
    }

    /**
     * Fully releases the lock if this thread holds it, for the duration of a wait on another dimension's thread (which
     * may itself be waiting for this lock). Returns the hold count for {@link #restore}. Like a vanilla sync chunk load,
     * other code may run while this thread waits.
     */
    public static int suspend() {
        int count = LOCK.getHoldCount();
        for (int i = 0; i < count; i++) {
            release();
        }
        return count;
    }

    public static void restore(int count) {
        for (int i = 0; i < count; i++) {
            acquire(RESUMED);
        }
    }

    private static final LockSource RESUMED = LockSource.of("resumed after cross-dimension wait");

    /**
     * Takes the lock if code of {@code modId} is running on a dimension thread and that mod is not thread-safe.
     * Pair with {@link #exit(boolean)} in a finally block. Called from mixins and from bytecode added by
     * {@code MethodLockTransformer}.
     */
    public static boolean enterForMod(String modId) {
        if (ContentPolicy.lockingActive() && !ContentPolicy.isSafeNamespace(modId)) {
            acquire(MOD_SOURCES.computeIfAbsent(modId, id -> LockSource.of("code " + id)));
            return true;
        }
        return false;
    }

    /**
     * For a mod's own worker thread that runs game code while the server ticks (createthreadedtrains ticks all of
     * Create's trains that way): takes the lock so that code never overlaps mod code on the dimension threads, which
     * is what the mod assumed with a single server thread. The server thread and dimension threads are left alone.
     */
    public static boolean enterFromWorker(String what) {
        Thread current = Thread.currentThread();
        if (current instanceof LevelThread || current == TickThreads.serverThread() || !ParallelTicker.get().isActive()) {
            return false;
        }
        acquire(MOD_SOURCES.computeIfAbsent("worker " + what, LockSource::of));
        return true;
    }

    /**
     * For vanilla state shared by all dimensions (maps, saved data, recipe caches): takes the lock on a dimension
     * thread during the parallel phase regardless of config, so the state is only ever touched by one dimension thread
     * at a time. Taking this lock before any monitor on the same state keeps the lock order fixed (mod lock first),
     * which rules out deadlocks between the two.
     */
    public static boolean enterSharedState(LockSource source) {
        if (Thread.currentThread() instanceof LevelThread && TickThreads.inParallelPhase()) {
            acquire(source);
            return true;
        }
        return false;
    }

    public static void exit(boolean entered) {
        if (entered) {
            release();
        }
    }
}
