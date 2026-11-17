package com.multicore.core;

import net.minecraft.world.level.Level;

/** Rules for moving things between dimensions while dimension threads are running. */
public final class CrossDimension {
    private static volatile boolean deferWrites = true;

    private CrossDimension() {
    }

    public static void setDeferWrites(boolean value) {
        deferWrites = value;
    }

    /**
     * A dimension thread wants to move something from {@code from} into {@code to}. Doing it now would write into a
     * level another thread is ticking, so it has to wait for the serial phase.
     */
    public static boolean mustDefer(Level from, Level to) {
        return from != to && deferWrites && Thread.currentThread() instanceof LevelThread && TickThreads.inParallelPhase()
                && TickThreads.isRealLevel(to);
    }

    /**
     * A dimension thread wants to change {@code target}, another of the server's real levels. Mods' fake or wrapped
     * levels (Create's WrappedServerLevel, Moonlight's FakeServerLevel) are not affected.
     */
    public static boolean mustDeferWriteTo(Level target) {
        return deferWrites && TickThreads.isForeignLevelThread(target);
    }
}
