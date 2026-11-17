package com.multicore.core;

/**
 * Tracks, per thread, whether it is inside vanilla chunk bookkeeping that must not be re-entered: the distance
 * (ticket) manager update and the chunk unload pass. NeoForge fires events from inside both (ChunkTicketLevelUpdatedEvent,
 * ChunkEvent.Unload), so mod listeners run there. If such a listener waits for the mod lock, the waiting thread must
 * not run its level's chunk tasks, because that would start a second distance-manager update inside the first and
 * corrupt it (chunk holders missing, generation claims leaked, chunks that never unload).
 */
public final class ChunkSystemGuard {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private ChunkSystemGuard() {
    }

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        DEPTH.get()[0]--;
    }

    /** True if running chunk tasks right now would re-enter chunk bookkeeping. */
    public static boolean inside() {
        return DEPTH.get()[0] > 0;
    }
}
