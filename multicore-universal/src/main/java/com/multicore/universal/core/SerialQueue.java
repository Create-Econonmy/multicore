package com.multicore.universal.core;

import com.multicore.universal.MultiCore;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Work that a dimension thread is not allowed to do itself (moving entities between dimensions, running commands,
 * scheduled functions). It runs on the main thread at the end of the same tick, in the order it was queued.
 */
public final class SerialQueue {
    private static final ConcurrentLinkedQueue<Task> QUEUE = new ConcurrentLinkedQueue<>();

    private SerialQueue() {
    }

    public static void defer(String what, Runnable task) {
        QUEUE.add(new Task(what, task));
        Stats.DEFERRED.increment();
    }

    /** Main thread only. Tasks queued while running (by nested code) run in the same call. */
    static void runAll() {
        Task task;
        while ((task = QUEUE.poll()) != null) {
            try {
                task.task.run();
            } catch (Throwable t) {
                MultiCore.LOGGER.error("MultiCore deferred task '{}' failed", task.what, t);
            }
        }
    }

    static int size() {
        return QUEUE.size();
    }

    private record Task(String what, Runnable task) {
    }
}
