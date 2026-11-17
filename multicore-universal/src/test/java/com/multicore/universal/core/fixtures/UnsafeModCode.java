package com.multicore.universal.core.fixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Typical thread-unsafe mod code, woven by the tests like the coremod weaves a real mod: shared static collections,
 * a plain counter, a synchronized method, lambdas, exceptions thrown through woven frames and recursion.
 */
public final class UnsafeModCode {
    public static final Map<Integer, Integer> COUNTS = new HashMap<>();
    public static final List<Integer> LOG = new ArrayList<>();
    public static int plainCounter;
    private static int syncCounter;

    private UnsafeModCode() {
    }

    public static void count(int key) {
        COUNTS.merge(key, 1, Integer::sum);
        plainCounter++;
        if ((plainCounter & 63) == 0) {
            LOG.add(key);
            if (LOG.size() > 128) {
                LOG.subList(0, 64).clear();
            }
        }
    }

    public static int nested(int depth) {
        if (depth == 0) {
            count(-1);
            return 0;
        }
        return 1 + nested(depth - 1);
    }

    public static synchronized void syncIncrement() {
        syncCounter++;
        count(-2);
    }

    public static int syncCounter() {
        return syncCounter;
    }

    public static void throwing(int key) {
        count(key);
        throw new IllegalStateException("thrown through a woven method");
    }

    public static int catches(int key) {
        try {
            throwing(key);
            return -1;
        } catch (IllegalStateException e) {
            return key;
        }
    }

    public static IntSupplier lambda(int key) {
        return () -> {
            count(key);
            return key;
        };
    }

    /** Holds the lock (when woven) until {@code release} opens. */
    public static void park(java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch release)
            throws InterruptedException {
        entered.countDown();
        release.await();
    }

    /** Trivial: left unwrapped. */
    public static int trivialGetter() {
        return plainCounter;
    }

    public static void reset() {
        COUNTS.clear();
        LOG.clear();
        plainCounter = 0;
        syncCounter = 0;
    }
}
