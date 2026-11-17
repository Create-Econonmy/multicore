package com.multicore.compat.sable;

/**
 * Per-thread replacements for Sable's static {@code SubLevelPhysicsSystem.IN_PHYSICS_STEP} and
 * {@code currentlySteppingSystem}. With one physics system per dimension, each dimension thread needs its own copy;
 * otherwise one dimension's physics step changes how another dimension handles block changes.
 */
public final class SableThreadState {
    private static final ThreadLocal<Boolean> IN_PHYSICS_STEP = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Object> STEPPING_SYSTEM = new ThreadLocal<>();

    private SableThreadState() {
    }

    public static boolean inPhysicsStep() {
        return IN_PHYSICS_STEP.get();
    }

    public static void setInPhysicsStep(boolean value) {
        IN_PHYSICS_STEP.set(value);
    }

    public static Object steppingSystem() {
        return STEPPING_SYSTEM.get();
    }

    public static void setSteppingSystem(Object system) {
        STEPPING_SYSTEM.set(system);
    }
}
