package com.multicore.core;

import com.multicore.MultiCore;

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
    /** A ReentrantLock that can say who holds it (for the "waiting too long" report). */
    private static final class OwnedLock extends ReentrantLock {
        Thread holder() {
            return this.getOwner();
        }
    }

    private static final OwnedLock LOCK = new OwnedLock();
    private static final long REPORT_WAIT_NANOS = 10_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_REPORT = new java.util.concurrent.atomic.AtomicLong();
    private static final int SPIN_TRIES = 200;
    private static final Map<String, LockSource> MOD_SOURCES = new ConcurrentHashMap<>();
    /** Only written and read by the thread holding the lock at depth 1. */
    private static long holdStart;
    private static LockSource holder;

    private ModLock() {
    }

    public static void acquire(LockSource source) {
        acquire(source, false);
    }

    private static final long MONITOR_CHECK_NANOS = 100_000_000L;
    private static final java.util.concurrent.atomic.AtomicBoolean GAVE_UP_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();

    /** Does the current thread hold a Java monitor (a mod's synchronized block)? Slow: only after a long wait. */
    private static boolean holdsMonitor() {
        java.lang.management.ThreadInfo[] info = java.lang.management.ManagementFactory.getThreadMXBean()
                .getThreadInfo(new long[] {Thread.currentThread().threadId()}, true, false);
        return info.length > 0 && info[0] != null && info[0].getLockedMonitors().length > 0;
    }

    /**
     * @param mayGiveUp for the extra locks MultiCore adds around shared state (the static guard, vanilla shared data):
     *                  after 100 ms of waiting, a thread inside a mod's own synchronized block gives up and goes on without
     *                  the lock, because the holder may be waiting for that same block (a deadlock no wait can end).
     * @return true if the lock is held now
     */
    static boolean acquire(LockSource source, boolean mayGiveUp) {
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
                // Waiting for the mod lock counts as stopped for a world pause (whoever pauses holds this lock).
                if (self != null) self.safe++;
                try {
                    boolean reported = false;
                    boolean checkedMonitors = false;
                    while (!LOCK.tryLock(100, TimeUnit.MICROSECONDS)) {
                        if (self != null) {
                            self.pollOwnTasks();
                        }
                        if (mayGiveUp && !checkedMonitors && System.nanoTime() - start > MONITOR_CHECK_NANOS) {
                            checkedMonitors = true;
                            if (holdsMonitor()) {
                                Stats.MOD_LOCK_GIVE_UPS.increment();
                                if (GAVE_UP_LOGGED.compareAndSet(false, true)) {
                                    Thread owner = LOCK.holder();
                                    MultiCore.LOGGER.warn("[MultiCore] {} waited 100 ms for the mod lock ({}) inside a mod's own synchronized "
                                            + "code while {} holds it; going on without it to avoid a deadlock. Please report this log.",
                                            Thread.currentThread().getName(), source, owner == null ? "nobody" : owner.getName(),
                                            new Throwable("where it happened"));
                                }
                                return false;
                            }
                        }
                        if (!reported && System.nanoTime() - start > REPORT_WAIT_NANOS) {
                            reported = true;
                            reportLongWait(source);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOCK.lock();
                } finally {
                    // Holding the mod lock now, so nobody holds a world pause: no need to look for one.
                    if (self != null) self.safe--;
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
        return true;
    }

    /** Someone has waited 10 s for the lock: say who holds it, what for, and where it is (once a minute at most). */
    private static void reportLongWait(LockSource wanted) {
        long now = System.nanoTime();
        long last = LAST_REPORT.get();
        if (now - last < 60_000_000_000L && last != 0 || !LAST_REPORT.compareAndSet(last, now)) return;
        Thread owner = LOCK.holder();
        LockSource what = holder;
        StringBuilder sb = new StringBuilder();
        if (owner != null) for (StackTraceElement e : owner.getStackTrace()) sb.append("\n\tat ").append(e);
        com.multicore.MultiCore.LOGGER.warn("[MultiCore] {} has waited 10 s for the mod lock ({}). It is held by {} ({}) for {}:{}",
                Thread.currentThread().getName(), wanted, owner == null ? "nobody" : owner.getName(),
                owner == null ? "-" : owner.getState(), what, sb);
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
        // A world pause this thread holds ends first (it keeps one hold of the lock): the wait may need the others.
        if (Thread.currentThread() instanceof LevelThread self) {
            WorldPause.end(self);
        }
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
        if (current instanceof LevelThread || current == TickThreads.serverThread()) {
            return false;
        }
        // Always, also while the dimensions tick on the server thread: from now on the server thread takes the lock
        // for what the worker shares with it (see ContentPolicy.lockingActive), so the two never overlap.
        workerSeen = true;
        acquire(MOD_SOURCES.computeIfAbsent("worker " + what, LockSource::of));
        return true;
    }

    /** A mod's worker thread has run game code under the lock (createthreadedtrains). */
    private static volatile boolean workerSeen;

    /** Must the server thread take the lock too? Only once a mod's worker thread runs game code beside it. */
    static boolean serverThreadLocks() {
        return workerSeen && Thread.currentThread() == TickThreads.serverThread();
    }

    /**
     * For vanilla state shared by all dimensions (maps, saved data, recipe caches): takes the lock on a dimension
     * thread during the parallel phase regardless of config, so the state is only ever touched by one dimension thread
     * at a time. Taking this lock before any monitor on the same state keeps the lock order fixed (mod lock first),
     * which rules out deadlocks between the two.
     */
    public static boolean enterSharedState(LockSource source) {
        if ((Thread.currentThread() instanceof LevelThread && TickThreads.inParallelPhase()) || serverThreadLocks()) {
            return acquire(source, true);
        }
        return false;
    }

    private static final Map<String, LockSource> SHARED_SOURCES = new ConcurrentHashMap<>();

    /**
     * {@link #enterSharedState} by name, for bytecode added by {@code MethodLockTransformer.wrapAllMethods}: a class of a
     * thread-safe mod (Create's global logistics manager) whose state every dimension and region thread shares.
     */
    public static boolean enterShared(String name) {
        return enterSharedState(SHARED_SOURCES.computeIfAbsent(name, n -> LockSource.of("shared " + n)));
    }

    /**
     * {@link #enterShared}, and pause every other dimension and region thread for the rest of the item (see
     * {@link WorldPause}): Create's logistics reach vaults and chests anywhere through references they keep.
     */
    public static boolean enterSharedPausing(String name) {
        boolean held = enterShared(name);
        WorldPause.begin();
        return held;
    }

    public static void exit(boolean entered) {
        if (entered) {
            release();
        }
    }
}
