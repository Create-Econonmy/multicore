package com.multicore.core;

import com.multicore.MultiCore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nullable;

/**
 * Stop-the-world for the tick threads. One dimension or region thread asks for it; every other dimension and region
 * thread stops at its next safe point (between two block entity or entity ticks, or wherever it already waits) and
 * stays there until the asking thread has finished its current item. Then that item runs as if the server had a
 * single thread, whatever dimension or region it reaches into: a Create stock ticker pulling from a vault in another
 * dimension cannot race that vault's own belts.
 * <p>
 * Paused threads keep running their own chunk tasks, so the asking thread can still load chunks anywhere.
 * <p>
 * Lock order: the mod lock first, then the pause. Whoever pauses holds the mod lock for the whole pause, which also
 * makes pauses one at a time. A thread that waits for the mod lock counts as stopped.
 * <p>
 * Cost when nobody pauses: one volatile read per item.
 */
public final class WorldPause {
    private static final long WAIT_LIMIT_NANOS = 2_000_000_000L;
    private static final LockSource SOURCE = LockSource.of("world pause");
    private static final AtomicBoolean TIMEOUT_LOGGED = new AtomicBoolean();

    /** The thread holding the pause, or null. */
    @Nullable
    private static volatile LevelThread holder;
    /** Every tick thread that ever ran (dimension and region threads). Idle ones count as stopped. */
    private static final Set<LevelThread> THREADS = ConcurrentHashMap.newKeySet();
    private static volatile boolean enabled = true;

    private WorldPause() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    static void register(LevelThread thread) {
        THREADS.add(thread);
    }

    static void unregister(LevelThread thread) {
        THREADS.remove(thread);
    }

    /** Is some other thread holding the pause right now? */
    static boolean heldByOther(Thread self) {
        LevelThread h = holder;
        return h != null && h != self && h.isAlive();
    }

    public static boolean heldByCurrentThread() {
        return holder == Thread.currentThread();
    }

    /**
     * Pause every other tick thread for the rest of the current item. Does nothing outside the parallel phase (the
     * server thread is alone then) or when this thread already holds the pause.
     */
    public static void begin() {
        if (!enabled || !(Thread.currentThread() instanceof LevelThread self) || !TickThreads.inParallelPhase()) {
            return;
        }
        if (holder == self) {
            return;
        }
        if (self.safe > 0) {
            // Not ticking: the thread is waiting, done with its tick, or stopped for someone else's pause, and only runs
            // chunk tasks (a mod's chunk-load listener reaching into another dimension). A pause asked for here would
            // have no item to end with and would stop the world for good (found 2026-11-25: Simulated Coasters'
            // chunk-load hook on a dimension thread that had finished ticking).
            Stats.WORLD_PAUSES_SKIPPED.increment();
            return;
        }
        long start = System.nanoTime();
        boolean lost;
        do {
            ModLock.acquire(SOURCE);
            // Nobody else holds the pause now: every holder keeps the mod lock until it lets go.
            holder = self;
            self.pauseModLock = true;
            Stats.WORLD_PAUSES.increment();
            lost = false;
            waiting:
            for (LevelThread other : THREADS) {
                if (other == self) continue;
                while (other.safe <= 0 && other.isAlive()) {
                    if (System.nanoTime() - start > WAIT_LIMIT_NANOS) {
                        timedOut(self, other);
                        break;
                    }
                    if (!self.pollOwnTasks()) {
                        LockSupport.parkNanos(20_000L);
                    }
                    if (holder != self) {
                        // One of this thread's own chunk tasks waited (enterSafe / ModLock.suspend), which ends the pause
                        // and lets go of the mod lock. Another thread may have started its own pause since, and the two
                        // would wait for each other until both timed out (soak 2026-11-26: the_nether and overworld
                        // region 3 both inside this loop). Take the pause again from the start.
                        lost = true;
                        break waiting;
                    }
                }
            }
        } while (lost);
        Stats.WORLD_PAUSE_WAIT_NANOS.add(System.nanoTime() - start);
    }

    private static void timedOut(LevelThread self, LevelThread other) {
        Stats.WORLD_PAUSE_TIMEOUTS.increment();
        if (TIMEOUT_LOGGED.compareAndSet(false, true)) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement e : other.getStackTrace()) sb.append("\n\tat ").append(e);
            MultiCore.LOGGER.warn("[MultiCore] {} could not pause {} within {} ms and went on without it. Please report this log. {} is at:{}",
                    self.getName(), other.getName(), WAIT_LIMIT_NANOS / 1_000_000L, other.getName(), sb, new Throwable("pause asked here"));
        }
    }

    /** The holder's item is over: let everyone go. */
    static void end(LevelThread self) {
        if (holder != self) return;
        holder = null;
        for (LevelThread t : THREADS) {
            if (t.safe > 0) LockSupport.unpark(t);
        }
        if (self.pauseModLock) {
            self.pauseModLock = false;
            ModLock.release();
        }
    }

    /** Before an item (block entity or entity tick) on a tick thread. */
    public static void beginItem() {
        if (Thread.currentThread() instanceof LevelThread t) {
            if (t.itemDepth++ == 0) {
                // A pause this thread asked for outside an item (a scheduled block tick...) ends here.
                end(t);
                stopIfPaused(t);
            }
        }
    }

    /** After an item (in a finally block). */
    public static void endItem() {
        if (Thread.currentThread() instanceof LevelThread t) {
            if (--t.itemDepth <= 0) {
                t.itemDepth = 0;
                end(t);
            }
        }
    }

    /**
     * The thread is about to wait (for a chunk, the mod lock...): it counts as stopped while it waits. A pause it holds
     * ends here: whatever it waits for may need the threads it stopped.
     */
    public static void enterSafe(LevelThread t) {
        end(t);
        t.safe++;
    }

    /** The wait is over: if someone is pausing, stay here until they are done. */
    public static void leaveSafe(LevelThread t) {
        t.safe--;
        stopIfPaused(t);
    }

    /** A safe point. If another thread holds the pause, stop here until it lets go. */
    static void stopIfPaused(LevelThread t) {
        // t.safe is volatile: written before holder is read, while the holder writes itself before reading t.safe.
        if (!heldByOther(t)) {
            return;
        }
        t.safe++;
        try {
            int reads = RegionTicker.releaseReadsForWait();
            try {
                long start = System.nanoTime();
                boolean warned = false;
                boolean checkedInit = false;
                while (heldByOther(t)) {
                    if (!t.pollOwnTasks()) {
                        LockSupport.parkNanos(t, 50_000L);
                    }
                    if (!checkedInit && System.nanoTime() - start > 100_000_000L) {
                        checkedInit = true;
                        // Inside a class initializer the holder may be waiting for this very class (see
                        // ModLock.insideClassInit): stopping here could never end. Go on, as for the mod lock.
                        if (ModLock.insideClassInit()) {
                            Stats.WORLD_PAUSE_TIMEOUTS.increment();
                            return;
                        }
                    }
                    if (!warned && System.nanoTime() - start > 10_000_000_000L) {
                        warned = true;
                        LevelThread h = holder;
                        if (h != null) {
                            StringBuilder sb = new StringBuilder();
                            for (StackTraceElement e : h.getStackTrace()) sb.append("\n\tat ").append(e);
                            MultiCore.LOGGER.warn("[MultiCore] {} has been stopped for 10 s by a world pause of {} ({}). Please report this log.{}",
                                    t.getName(), h.getName(), h.getState(), sb);
                        }
                    }
                }
            } finally {
                RegionTicker.reacquireReads(reads);
            }
        } finally {
            t.safe--;
        }
        // A new pause may have started just before this thread stopped counting as stopped: look again.
        if (heldByOther(t)) {
            stopIfPaused(t);
        }
    }
}
