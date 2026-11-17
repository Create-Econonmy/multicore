package com.multicore.universal.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Who is allowed to touch what, right now.
 * <p>
 * Outside the parallel phase the main server thread owns every level, exactly like vanilla.
 * During the parallel phase each parallel level is owned by its {@link LevelThread}.
 */
public final class TickThreads {
    /** The server's real levels (not mods' fake or wrapped ServerLevel copies), refreshed every tick. */
    private static volatile Set<ServerLevel> realLevels = Set.of();
    private static ServerLevel[] lastLevels;

    private TickThreads() {
    }

    static void setServerThread(@Nullable Thread thread) {
        LockState.serverThread = thread;
    }

    static void setParallelPhase(boolean value) {
        LockState.parallel = value;
    }

    /** Main thread only. */
    static void setRealLevels(ServerLevel[] levels) {
        if (levels == lastLevels) {
            return;
        }
        Set<ServerLevel> set = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(set, levels);
        realLevels = set;
        lastLevels = levels;
    }

    @Nullable
    public static Thread serverThread() {
        return LockState.serverThread;
    }

    /** True while dimension threads are ticking. */
    public static boolean inParallelPhase() {
        return LockState.parallel;
    }

    /** True on a MultiCore dimension thread. */
    public static boolean isLevelThread() {
        return Thread.currentThread() instanceof LevelThread;
    }

    @Nullable
    public static LevelThread currentLevelThread() {
        return Thread.currentThread() instanceof LevelThread t ? t : null;
    }

    /** The main server thread or a MultiCore dimension thread. */
    public static boolean isTickThread() {
        Thread t = Thread.currentThread();
        return t == LockState.serverThread || t instanceof LevelThread;
    }

    /** A level the server ticks, as opposed to a mod's fake or wrapped level object. */
    public static boolean isRealLevel(Level level) {
        return level instanceof ServerLevel serverLevel && realLevels.contains(serverLevel);
    }

    /**
     * True when the current thread is a dimension thread and {@code level} is another of the server's real levels.
     * Anything it changes in {@code level} races with that level's own thread (or with the main thread for serial
     * levels, whose chunk tasks it runs while waiting).
     */
    public static boolean isForeignLevelThread(Level level) {
        return Thread.currentThread() instanceof LevelThread t && LockState.parallel && t.level() != level && isRealLevel(level);
    }
}
