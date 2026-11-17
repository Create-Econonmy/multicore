package com.multicore.universal.coremod.fixtures;

/** Like Open Parties and Claims' ServerCore: static capture state set and cleared by static helpers its hooks call. */
public final class CaptureHelper {
    public static boolean griefing;
    public static int griefingTick;
    public static boolean usable = true;
    public static int dirty;

    private CaptureHelper() {
    }

    public static void pre(int tick) {
        if (!usable) {
            return;
        }
        griefing = true;
        griefingTick = tick;
    }

    public static void post() {
        griefing = false;
    }

    public static boolean isGriefing(int tick) {
        return griefing && griefingTick == tick;
    }

    public static void broken() {
        usable = false;
    }

    public static void markDirty() {
        dirty = 1;
    }
}
