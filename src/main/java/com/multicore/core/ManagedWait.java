package com.multicore.core;

import com.multicore.MultiCore;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Every place where a dimension thread waits for something must keep running its own level's chunk tasks,
 * otherwise two dimension threads waiting on each other would deadlock.
 */
public final class ManagedWait {
    private static final long WARN_AFTER_NANOS = 10_000_000_000L;

    private ManagedWait() {
    }

    public static void until(BooleanSupplier done, String what) {
        LevelThread self = TickThreads.currentLevelThread();
        long start = System.nanoTime();
        long nextWarn = start + WARN_AFTER_NANOS;
        while (!done.getAsBoolean()) {
            if (self == null || !self.pollOwnTasks()) {
                LockSupport.parkNanos(50_000L);
            }
            long now = System.nanoTime();
            if (now > nextWarn) {
                nextWarn = now + WARN_AFTER_NANOS;
                MultiCore.LOGGER.warn("[MultiCore] {} has been waiting {} s for {}", Thread.currentThread().getName(),
                        (now - start) / 1_000_000_000L, what, new Throwable("waiting here"));
            }
        }
    }
}
