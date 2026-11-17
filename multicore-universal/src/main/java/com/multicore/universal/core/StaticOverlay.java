package com.multicore.universal.core;

import java.util.Arrays;

/**
 * Per-dimension-thread copies of static fields that other mods' mixins use as scoped flags ("I am building this
 * cache", "this entity is dying"): set at the start of an operation and cleared at its end. With several dimension
 * threads one thread's flag would leak into another's operation; giving each dimension thread its own copy restores
 * the single-thread meaning without holding the mod lock for the whole vanilla method around the hooks.
 * <p>
 * Woven by the mixin extension (see the coremod's ScopedStatics). During the parallel phase a dimension thread reads
 * its own value if it set one during this tick and the real field otherwise. The main server thread, which runs
 * serial dimensions' chunk tasks meanwhile, keeps its own copy too: if it wrote the real field, dimension threads that
 * have not set the flag themselves would read the main thread's value. Every other thread, and every thread outside
 * the parallel phase, uses the real field. Copies are dropped at the end of each tick. No Minecraft types, like
 * {@link ModLock}.
 */
public final class StaticOverlay {
    private static final Object NULL = new Object();

    private StaticOverlay() {
    }

    private static Object read(int slot) {
        if (LockState.parallel) {
            Thread t = Thread.currentThread();
            Object[] values = t instanceof TickThread tt ? tt.overlay : t == LockState.serverThread ? LockState.mainOverlay : null;
            if (values != null && slot < values.length) {
                return values[slot];
            }
        }
        return null;
    }

    private static boolean write(Object value, int slot) {
        if (!LockState.parallel) {
            return false;
        }
        Thread t = Thread.currentThread();
        if (t instanceof TickThread tt) {
            tt.overlay = store(tt.overlay, value, slot);
            return true;
        }
        if (t == LockState.serverThread) {
            LockState.mainOverlay = store(LockState.mainOverlay, value, slot);
            return true;
        }
        return false;
    }

    private static Object[] store(Object[] values, Object value, int slot) {
        if (values == null || slot >= values.length) {
            int size = Math.max(slot + 1, values == null ? 16 : values.length * 2);
            values = values == null ? new Object[size] : Arrays.copyOf(values, size);
        }
        values[slot] = value == null ? NULL : value;
        return values;
    }

    static void clear(TickThread thread) {
        thread.overlay = null;
    }

    /** Main thread, when the parallel phase ends. */
    static void clearMain() {
        LockState.mainOverlay = null;
    }

    // Woven accessors: get*(real field value, slot) and set*(value, slot) -> true if stored per thread.

    public static int getI(int real, int slot) {
        Object v = read(slot);
        return v == null ? real : (Integer) v;
    }

    public static boolean setI(int value, int slot) {
        return write(value, slot);
    }

    public static boolean getZ(boolean real, int slot) {
        Object v = read(slot);
        return v == null ? real : (Boolean) v;
    }

    public static boolean setZ(boolean value, int slot) {
        return write(value, slot);
    }

    public static long getJ(long real, int slot) {
        Object v = read(slot);
        return v == null ? real : (Long) v;
    }

    public static boolean setJ(long value, int slot) {
        return write(value, slot);
    }

    public static float getF(float real, int slot) {
        Object v = read(slot);
        return v == null ? real : (Float) v;
    }

    public static boolean setF(float value, int slot) {
        return write(value, slot);
    }

    public static double getD(double real, int slot) {
        Object v = read(slot);
        return v == null ? real : (Double) v;
    }

    public static boolean setD(double value, int slot) {
        return write(value, slot);
    }

    public static Object getA(Object real, int slot) {
        Object v = read(slot);
        return v == null ? real : v == NULL ? null : v;
    }

    public static boolean setA(Object value, int slot) {
        return write(value, slot);
    }
}
