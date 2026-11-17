package com.multicore.universal.core;

/**
 * Base of MultiCore's dimension threads. Holds the per-thread {@link ModLock} bookkeeping as plain fields: woven mod
 * code reads and writes them on every call, so they must be cheaper than a ThreadLocal. Only the thread itself
 * touches them.
 * <p>
 * Deliberately free of Minecraft types: woven mod classes refer to this class (through ModLock), sometimes before the
 * game has bootstrapped.
 */
public abstract class TickThread extends Thread {
    /** How many woven calls deep this thread is while holding the mod lock (0 = not holding it). */
    int lockDepth;
    /** How many mod static initializers this thread is running; it never waits for the lock inside one. */
    int staticInitDepth;
    /** How many lock waits are nested on this thread (a wait runs chunk tasks, which may wait again). */
    int waitNesting;
    /** This thread's copies of other mods' scoped static flags during the current tick (see {@link StaticOverlay}). */
    Object[] overlay;

    protected TickThread(ThreadGroup group, String name) {
        super(group, null, name);
    }

    /** Runs one pending task of this thread's own level, if any. Called while waiting for the mod lock. */
    public abstract boolean pollOwnTasks();
}
