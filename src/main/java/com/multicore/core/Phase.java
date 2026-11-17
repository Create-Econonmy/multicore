package com.multicore.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;

/**
 * One parallel phase: every parallel level ticks once, then all threads go idle.
 * <p>
 * Each thread reports "finished ticking" and "finished helping" once; the reports are idempotent per thread so the
 * main thread can also file them for a thread that died.
 */
final class Phase {
    private static final int TICKED = 1;
    private static final int HELPED = 2;

    private final BooleanSupplier hasTimeLeft;
    private final Thread mainThread;
    private final LevelThread[] threads;
    private final AtomicIntegerArray state;
    private final AtomicInteger ticking;
    private final AtomicInteger helping;
    private volatile boolean allTicked;
    final Map<ServerLevel, Throwable> errors = new ConcurrentHashMap<>();

    Phase(BooleanSupplier hasTimeLeft, Thread mainThread, LevelThread[] threads) {
        this.hasTimeLeft = hasTimeLeft;
        this.mainThread = mainThread;
        this.threads = threads;
        this.state = new AtomicIntegerArray(threads.length);
        this.ticking = new AtomicInteger(threads.length);
        this.helping = new AtomicInteger(threads.length);
        this.allTicked = threads.length == 0;
    }

    BooleanSupplier hasTimeLeft() {
        return this.hasTimeLeft;
    }

    void fail(ServerLevel level, Throwable t) {
        this.errors.putIfAbsent(level, t);
    }

    private int indexOf(LevelThread thread) {
        for (int i = 0; i < this.threads.length; i++) {
            if (this.threads[i] == thread) {
                return i;
            }
        }
        throw new IllegalArgumentException("Thread is not part of this phase: " + thread.getName());
    }

    void finishedTicking() {
        this.finishedTicking(this.indexOf((LevelThread) Thread.currentThread()));
    }

    void finishedHelping() {
        this.finishedHelping(this.indexOf((LevelThread) Thread.currentThread()));
    }

    private void finishedTicking(int index) {
        if (!this.set(index, TICKED)) {
            return;
        }
        if (this.ticking.decrementAndGet() == 0) {
            this.allTicked = true;
            for (LevelThread t : this.threads) {
                LockSupport.unpark(t);
            }
            LockSupport.unpark(this.mainThread);
        }
    }

    private void finishedHelping(int index) {
        this.finishedTicking(index);
        if (!this.set(index, HELPED)) {
            return;
        }
        if (this.helping.decrementAndGet() == 0) {
            LockSupport.unpark(this.mainThread);
        }
    }

    /** Sets a flag for thread {@code index}; false if it was already set. */
    private boolean set(int index, int flag) {
        while (true) {
            int old = this.state.get(index);
            if ((old & flag) != 0) {
                return false;
            }
            if (this.state.compareAndSet(index, old, old | flag)) {
                return true;
            }
        }
    }

    /**
     * Main thread: a dimension thread that died without reporting (it should not happen, but a dead thread must never
     * leave the server waiting forever) is counted as failed and finished.
     */
    void reapDeadThreads() {
        for (int i = 0; i < this.threads.length; i++) {
            LevelThread t = this.threads[i];
            if ((this.state.get(i) & HELPED) == 0 && !t.isAlive()) {
                this.fail(t.level(), new IllegalStateException("MultiCore thread " + t.getName() + " died during the tick"));
                this.finishedHelping(i);
            }
        }
    }

    boolean allTicked() {
        return this.allTicked;
    }

    /** True once every thread has stopped touching its level. Only then may the main thread take the levels back. */
    boolean done() {
        return this.helping.get() == 0;
    }

    int stillTicking() {
        return this.ticking.get();
    }

    LevelThread[] threads() {
        return this.threads;
    }

    boolean hasTicked(int index) {
        return (this.state.get(index) & TICKED) != 0;
    }
}
