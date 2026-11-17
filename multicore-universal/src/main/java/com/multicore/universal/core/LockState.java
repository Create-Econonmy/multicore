package com.multicore.universal.core;

/**
 * The few flags woven mod code checks on every call. No Minecraft types, for the same reason as {@link TickThread}.
 */
final class LockState {
    /** True while dimension threads are ticking. */
    static volatile boolean parallel;
    /** The main server thread, which may run mod code during the parallel phase (serial levels' chunk tasks). */
    static volatile Thread serverThread;
    /** config: compatibility.serializeUnsafeMods */
    static volatile boolean serialize = true;
    /** ModLock depth of the main server thread. Only that thread touches it. */
    static int mainDepth;
    /** Mod static initializers the main server thread is running (see TickThread.staticInitDepth). */
    static int mainStaticInitDepth;
    /**
     * The main server thread is running work a lock holder is blocked on (ParallelTicker's deadlock breaker), so it
     * acts as that holder: its mod code runs without taking the lock, which nobody else can take meanwhile.
     */
    static boolean mainBorrowed;
    /** The main server thread's copies of scoped static flags during the parallel phase (see StaticOverlay). */
    static Object[] mainOverlay;

    private LockState() {
    }
}
