package com.multicore.core;

import com.multicore.MultiCore;
import com.multicore.access.ServerChunkCacheAccessor;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * A dimension thread reading chunks of a level it does not own (another dimension).
 * <p>
 * Loaded chunks are returned directly (the chunk map it reads is copy-on-write in vanilla, so this is safe).
 * Chunks that still need loading are requested from the owning thread, while this thread keeps running its own
 * chunk tasks so the two threads can never deadlock.
 */
public final class CrossLevel {
    private static final Set<String> LOGGED_CALLERS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger LOG_BUDGET = new AtomicInteger(64);
    private static final AtomicLong SEEN = new AtomicLong();
    private static final long FULL_SCAN_ACCESSES = 256;
    private static final long SAMPLE_MASK = 1023;
    private static volatile boolean logAccess = true;

    private CrossLevel() {
    }

    public static void setLogging(boolean enabled) {
        logAccess = enabled;
    }

    @Nullable
    public static ChunkAccess getChunk(ServerChunkCache cache, int x, int z, ChunkStatus status, boolean load) {
        // A region thread reading its own dimension is not a cross-dimension read.
        boolean own = Thread.currentThread() instanceof RegionThread rt && rt.level() == cache.getLevel();
        if (!own) {
            Stats.CROSS_LEVEL_READS.increment();
            noteAccess("reads chunks of another dimension", cache.getLevel());
        }
        ChunkHolder holder = ((ServerChunkCacheAccessor) cache).multicore$getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder != null) {
            ChunkAccess chunk = holder.getChunkIfPresent(status);
            if (chunk != null) {
                return chunk;
            }
        }
        if (!load) {
            return null;
        }
        Stats.CROSS_LEVEL_LOADS.increment();
        CompletableFuture<ChunkResult<ChunkAccess>> future = cache.getChunkFuture(x, z, status, true);
        // The owning thread may be waiting for the mod lock this thread holds; let go of it while waiting.
        int suspended = ModLock.suspend();
        try {
            ManagedWait.until(future::isDone, "chunk " + x + ", " + z + " of " + cache.getLevel().dimension().location());
        } finally {
            ModLock.restore(suspended);
        }
        ChunkAccess chunk = future.join().orElse(null);
        if (chunk == null) {
            throw new IllegalStateException("Chunk not there when requested from another dimension thread: " + x + ", " + z);
        }
        return chunk;
    }

    @Nullable
    public static LevelChunk getChunkNow(ServerChunkCache cache, int x, int z) {
        if (!(Thread.currentThread() instanceof RegionThread)) {
            Stats.CROSS_LEVEL_READS.increment();
        }
        ChunkHolder holder = ((ServerChunkCacheAccessor) cache).multicore$getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder == null) {
            return null;
        }
        if (holder.currentlyLoading != null) {
            return holder.currentlyLoading;
        }
        return holder.getChunkIfPresent(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }

    /**
     * Logs, once per calling class, where cross-dimension access comes from. This is how new compatibility
     * problems are found: the log names the mod class responsible.
     */
    public static void noteAccess(String what, @Nullable net.minecraft.world.level.Level target) {
        if (!logAccess || LOG_BUDGET.get() <= 0) {
            return;
        }
        if (target != null && Thread.currentThread() instanceof RegionThread rt && rt.level() == target) {
            return; // a region thread in its own dimension
        }
        // Walking the stack costs microseconds. After the first few hundred accesses only look at a small sample, so a
        // mod that reads another dimension every tick does not slow the server down.
        long n = SEEN.getAndIncrement();
        if (n >= FULL_SCAN_ACCESSES && (n & SAMPLE_MASK) != 0) {
            return;
        }
        StackTraceElement caller = findCaller();
        String key = caller == null ? "unknown" : caller.getClassName() + "." + caller.getMethodName();
        if (LOGGED_CALLERS.add(key) && LOG_BUDGET.decrementAndGet() >= 0) {
            MultiCore.LOGGER.warn("[MultiCore] {} {} ({}) from thread {}", key, what,
                    target == null ? "?" : target.dimension().location(), Thread.currentThread().getName(),
                    new Throwable("where it happened"));
        }
    }

    @Nullable
    private static StackTraceElement findCaller() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String c = e.getClassName();
            String m = e.getMethodName();
            boolean mixinHandler = m.contains("$") && !m.startsWith("lambda$");
            if (mixinHandler && !m.contains("$multicore$")) {
                return e;
            }
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("net.minecraft.") || c.startsWith("net.neoforged.")
                    || c.startsWith("com.multicore.") || c.startsWith("com.mojang.") || c.startsWith("it.unimi.")) {
                continue;
            }
            return e;
        }
        return null;
    }
}
