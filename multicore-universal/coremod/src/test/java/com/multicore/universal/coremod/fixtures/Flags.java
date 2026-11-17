package com.multicore.universal.coremod.fixtures;

/** Static fields used the ways mixins use them, for ScopedStatics. */
public class Flags {
    private static boolean building;
    private static Object captured;
    private static int dirty;
    private static Object cache = new Object();
    private static long depth;

    // A reentrancy guard: set and cleared in the same method.
    public void build() {
        if (building) {
            return;
        }
        building = true;
        try {
            this.work();
        } finally {
            building = false;
        }
    }

    private void work() {
    }

    // A capture field: a HEAD hook sets it, a TAIL hook clears it, and the target calls both.
    public void head(Object o) {
        captured = o;
    }

    public void tail() {
        captured = null;
    }

    public void target(Object o) {
        this.head(o);
        this.work();
        this.tail();
    }

    // A dirty flag: set in one operation, consumed in another. Must stay shared.
    public void markDirty() {
        dirty = 1;
    }

    public boolean consume() {
        boolean was = dirty != 0;
        dirty = 0;
        return was;
    }

    // A lazily replaced cache with a real initial value. Must stay shared.
    public void replaceCache() {
        cache = new Object();
    }

    public void clearCache() {
        cache = null;
    }

    public void bothCache() {
        replaceCache();
        clearCache();
    }

    // A counter that only ever goes up: never cleared, so not a flag.
    public void count() {
        depth = depth + 1;
    }

    public static boolean isBuilding() {
        return building;
    }

    public static Object captured() {
        return captured;
    }
}
