package com.multicore.api;

import com.multicore.MultiCore;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;

/**
 * Folia-style scheduling for mods and scripts: run a task on the thread that owns a dimension, at the start of that
 * dimension's next tick. Inside the task it is safe to change anything in that dimension.
 * <p>
 * Works the same whether MultiCore is running dimensions in parallel or not (then the owner is the server thread).
 */
public final class LevelScheduler {
    private static final Map<ServerLevel, ConcurrentLinkedQueue<Runnable>> QUEUES = new ConcurrentHashMap<>();

    private LevelScheduler() {
    }

    public static void run(ServerLevel level, Runnable task) {
        QUEUES.computeIfAbsent(level, l -> new ConcurrentLinkedQueue<>()).add(task);
    }

    public static <T> CompletableFuture<T> supply(ServerLevel level, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        run(level, () -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Called at the start of {@code ServerLevel.tick}, on the level's owning thread. */
    public static void runPending(ServerLevel level) {
        ConcurrentLinkedQueue<Runnable> queue = QUEUES.get(level);
        if (queue == null) {
            return;
        }
        Runnable task;
        while ((task = queue.poll()) != null) {
            // Each task is one item: a safe point for world pauses before it, and a pause it asks for ends with it.
            com.multicore.core.WorldPause.beginItem();
            try {
                task.run();
            } catch (Throwable t) {
                MultiCore.LOGGER.error("[MultiCore] Scheduled task for {} failed", level.dimension().location(), t);
            } finally {
                com.multicore.core.WorldPause.endItem();
            }
        }
    }

    /** Called when a level is unloaded. */
    public static void forget(ServerLevel level) {
        QUEUES.remove(level);
    }
}
