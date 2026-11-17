package com.multicore.universal.core;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock shared by all code from mods that are not known to be thread-safe.
 * <p>
 * The coremod weaves {@link #enter(int)} / {@link #exit()} around every method of every installed mod, so whenever a
 * dimension thread runs such code it holds this lock, and no other dimension thread runs mod code at the same moment.
 * That is what those mods assume: they were written for a single server thread. Vanilla code and thread-safe mods do
 * not take it and keep running in parallel.
 * <p>
 * The lock is held at most once per thread; nesting is counted in a per-thread depth, which for dimension threads is a
 * plain field ({@link TickThread#lockDepth}), so the nested calls that make up most mod code cost a field increment.
 * While waiting for the lock a dimension thread keeps running its own chunk tasks; the holder may be waiting on one.
 */
public final class ModLock {
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final int SPIN_TRIES = 200;
    /** Hold times are measured for one acquisition in 16 (System.nanoTime is not free). */
    private static final int TIMING_MASK = 15;
    /** One acquisition in 4096 records where it came from (a stack walk). */
    private static final int HOTSPOT_MASK = 4095;
    private static final ThreadLocal<int[]> OTHER_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final LockSource RESUMED = LockSource.of("resumed after cross-dimension wait");
    /** Only touched by the thread holding the lock. */
    private static long holdStart;
    private static LockSource timedHolder;
    private static int acquisitionCounter;

    /** Nested lock waits beyond this depth stop running chunk tasks, so waits cannot recurse without bound. */
    private static final int MAX_POLLING_WAITS = 4;

    static {
        // Load everything the lock touches now. The slow path must never load a class: class loading runs other
        // mods' transformers, which may themselves be woven, and would re-enter the lock while waiting for it.
        ModPolicy.count();
        Stats.MOD_LOCK_ACQUIRES.sum();
        TimeUnit.MICROSECONDS.toNanos(1);
    }

    private ModLock() {
    }

    // ---- Called by woven mod code ----

    private static final java.lang.invoke.MethodHandle ENTER;
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.lang.invoke.MethodHandle> ENTER_BY_MOD =
            new java.util.concurrent.ConcurrentHashMap<>();

    static {
        try {
            ENTER = java.lang.invoke.MethodHandles.lookup().findStatic(ModLock.class, "enter",
                    java.lang.invoke.MethodType.methodType(void.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Bootstrap for the woven {@code invokedynamic enter()V} call: binds it to {@link #enter(int)} with the mod index
     * from the bootstrap argument (kept out of the method's constants, see the coremod's LockWeaver).
     */
    public static java.lang.invoke.CallSite bootstrapEnter(java.lang.invoke.MethodHandles.Lookup lookup, String name,
                                                           java.lang.invoke.MethodType type, int mod) {
        return new java.lang.invoke.ConstantCallSite(ENTER_BY_MOD.computeIfAbsent(mod,
                m -> java.lang.invoke.MethodHandles.insertArguments(ENTER, 0, m)));
    }

    /** Start of a woven method of mod number {@code mod} (see {@link ModPolicy}). */
    public static void enter(int mod) {
        Thread t = Thread.currentThread();
        if (t instanceof TickThread tt) {
            int depth = tt.lockDepth;
            if (depth > 0) {
                tt.lockDepth = depth + 1;
            } else if (LockState.parallel && LockState.serialize && tt.staticInitDepth == 0 && !ModPolicy.isSafe(mod)) {
                lock(ModPolicy.source(mod));
                tt.lockDepth = 1;
            }
        } else if (t == LockState.serverThread) {
            int depth = LockState.mainDepth;
            if (depth > 0) {
                LockState.mainDepth = depth + 1;
            } else if (LockState.parallel && LockState.serialize && LockState.mainStaticInitDepth == 0 && !LockState.mainBorrowed
                    && !ModPolicy.isSafe(mod)) {
                lock(ModPolicy.source(mod));
                LockState.mainDepth = 1;
            }
        }
    }

    /** Start of a woven mod static initializer. */
    public static void enterStaticInit() {
        Thread t = Thread.currentThread();
        if (t instanceof TickThread tt) {
            tt.staticInitDepth++;
        } else if (t == LockState.serverThread) {
            LockState.mainStaticInitDepth++;
        }
    }

    /** End of a woven mod static initializer (every return and exception). */
    public static void exitStaticInit() {
        Thread t = Thread.currentThread();
        if (t instanceof TickThread tt) {
            if (tt.staticInitDepth > 0) {
                tt.staticInitDepth--;
            }
        } else if (t == LockState.serverThread && LockState.mainStaticInitDepth > 0) {
            LockState.mainStaticInitDepth--;
        }
    }

    /**
     * End of a woven method (every return and every exception). Undoes exactly what the matching {@link #enter} did:
     * if the lock was held when that method started, enter counted one level, which is removed here; if it was not
     * held and enter did not take it, the depth is back at zero here as well.
     */
    public static void exit() {
        Thread t = Thread.currentThread();
        if (t instanceof TickThread tt) {
            int depth = tt.lockDepth;
            if (depth > 1) {
                tt.lockDepth = depth - 1;
            } else if (depth == 1) {
                tt.lockDepth = 0;
                unlock();
            }
        } else if (t == LockState.serverThread) {
            int depth = LockState.mainDepth;
            if (depth > 1) {
                LockState.mainDepth = depth - 1;
            } else if (depth == 1) {
                LockState.mainDepth = 0;
                unlock();
            }
        }
    }

    // ---- Explicit use (vanilla shared state, cross-dimension waits, tests) ----

    /** Takes the lock unconditionally (re-entrant). Pair with {@link #release()}. */
    public static void acquire(LockSource source) {
        Thread t = Thread.currentThread();
        int depth = depth(t);
        if (depth == 0) {
            lock(source);
        }
        setDepth(t, depth + 1);
    }

    public static void release() {
        Thread t = Thread.currentThread();
        int depth = depth(t);
        if (depth <= 0) {
            throw new IllegalMonitorStateException("MultiCore mod lock released by a thread that does not hold it");
        }
        setDepth(t, depth - 1);
        if (depth == 1) {
            unlock();
        }
    }

    public static boolean heldByCurrentThread() {
        return depth(Thread.currentThread()) > 0;
    }

    /**
     * Fully releases the lock if this thread holds it, for the duration of a wait on another dimension's thread (which
     * may itself be waiting for this lock). Returns the depth for {@link #restore}. Like a vanilla sync chunk load,
     * other code may run while this thread waits.
     */
    public static int suspend() {
        Thread t = Thread.currentThread();
        int depth = depth(t);
        if (depth > 0) {
            setDepth(t, 0);
            unlock();
        }
        return depth;
    }

    public static void restore(int depth) {
        if (depth > 0) {
            Thread t = Thread.currentThread();
            lock(RESUMED);
            setDepth(t, depth);
        }
    }

    /**
     * For vanilla state shared by all dimensions (maps, saved data, recipe caches): takes the lock on a dimension
     * thread during the parallel phase regardless of config, so the state is only ever touched by one dimension thread
     * at a time. Taking this lock before any monitor on the same state keeps the lock order fixed (mod lock first),
     * which rules out deadlocks between the two.
     */
    public static boolean enterSharedState(LockSource source) {
        if (Thread.currentThread() instanceof TickThread && LockState.parallel) {
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

    /**
     * Safety net at the end of a dimension thread's tick: the depth must be back at zero. If something escaped the
     * woven try/finally (a StackOverflowError inside enter, for example) the lock is released here so the other
     * dimensions are not blocked forever. Returns the depth that was left over.
     */
    static int resetCurrentThread() {
        Thread t = Thread.currentThread();
        if (t instanceof TickThread tt) {
            tt.staticInitDepth = 0;
            tt.waitNesting = 0;
            StaticOverlay.clear(tt);
        }
        int depth = depth(t);
        if (depth > 0) {
            setDepth(t, 0);
            unlock();
        }
        return depth;
    }

    // ---- internals ----

    private static int depth(Thread t) {
        if (t instanceof TickThread tt) {
            return tt.lockDepth;
        }
        if (t == LockState.serverThread) {
            return LockState.mainDepth;
        }
        return OTHER_DEPTH.get()[0];
    }

    private static void setDepth(Thread t, int depth) {
        if (t instanceof TickThread tt) {
            tt.lockDepth = depth;
        } else if (t == LockState.serverThread) {
            LockState.mainDepth = depth;
        } else {
            OTHER_DEPTH.get()[0] = depth;
        }
    }

    private static void lock(LockSource source) {
        source.acquisitions.increment();
        Stats.MOD_LOCK_ACQUIRES.increment();
        if (!LOCK.tryLock()) {
            long start = System.nanoTime();
            // Most holds are one mod call and last microseconds at most, so spin briefly before parking.
            boolean acquired = false;
            for (int i = 0; i < SPIN_TRIES; i++) {
                Thread.onSpinWait();
                if (!LOCK.isLocked() && LOCK.tryLock()) {
                    acquired = true;
                    break;
                }
            }
            if (!acquired) {
                lockWhilePolling();
            }
            long waited = System.nanoTime() - start;
            source.contended.increment();
            source.waitNanos.add(waited);
            Stats.MOD_LOCK_WAIT_NANOS.add(waited);
        }
        owner = Thread.currentThread();
        ownerSource = source;
        if ((acquisitionCounter & HOTSPOT_MASK) == 0) {
            Hotspots.sample();
        }
        if ((++acquisitionCounter & TIMING_MASK) == 0) {
            holdStart = System.nanoTime();
            timedHolder = source;
        }
    }

    private static void unlock() {
        LockSource timed = timedHolder;
        if (timed != null) {
            timed.holdNanos.add((System.nanoTime() - holdStart) * (TIMING_MASK + 1));
            timedHolder = null;
        }
        owner = null;
        LOCK.unlock();
    }

    /** The thread holding the lock, or null. */
    private static volatile Thread owner;
    /** What made the current holder take the lock (for leak reports). */
    private static volatile LockSource ownerSource;

    /**
     * Called by a dimension thread right after its level ticked: it must not hold the lock any more. If it does, some
     * code entered without leaving (a bug, reported in the log); the lock is released so the other dimensions can
     * finish their tick instead of waiting forever. Returns the leaked depth (0 normally).
     */
    static int releaseLeakedLock() {
        Thread t = Thread.currentThread();
        int depth = depth(t);
        if (depth > 0) {
            setDepth(t, 0);
            unlock();
        }
        return depth;
    }

    static String ownerSourceName() {
        LockSource source = ownerSource;
        return source == null ? "?" : source.name();
    }

    /**
     * Main thread only: runs work that {@code blocked} (a dimension thread) is waiting for. If {@code blocked} holds
     * the lock, mod code in that work runs without it: the holder is parked until the work is done and no other
     * thread can take the lock meanwhile, while waiting for it here would deadlock.
     */
    public static void runOnBehalfOf(Thread blocked, Runnable work) {
        if (owner != blocked || Thread.currentThread() != LockState.serverThread) {
            work.run();
            return;
        }
        LockState.mainBorrowed = true;
        try {
            work.run();
        } finally {
            LockState.mainBorrowed = false;
        }
    }

    /**
     * The slow wait. A dimension thread keeps running its own level's chunk tasks meanwhile (the holder may be waiting
     * for one of them). Those tasks may run mod code that waits again; past {@link #MAX_POLLING_WAITS} nested waits the
     * innermost one just blocks, so the nesting stays bounded.
     */
    private static void lockWhilePolling() {
        TickThread self = Thread.currentThread() instanceof TickThread tt ? tt : null;
        boolean poll = self != null && self.waitNesting < MAX_POLLING_WAITS;
        if (self != null) {
            self.waitNesting++;
        }
        try {
            while (!LOCK.tryLock(100, TimeUnit.MICROSECONDS)) {
                if (poll) {
                    self.pollOwnTasks();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOCK.lock();
        } finally {
            if (self != null) {
                self.waitNesting--;
            }
        }
    }
}
