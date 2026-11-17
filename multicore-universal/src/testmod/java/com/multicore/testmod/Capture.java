package com.multicore.testmod;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.LivingEntity;

/**
 * The "capture field" pattern: a mixin hook at the start of {@code LivingEntity.aiStep} stores the entity here, a hook
 * in {@code travel} (called from inside aiStep) reads it back. Correct with one server thread; with several, another
 * dimension's aiStep overwrites it in between unless the whole aiStep holds the lock.
 */
public final class Capture {
    public static LivingEntity current;
    public static final AtomicLong CHECKS = new AtomicLong();
    public static final AtomicLong MISMATCHES = new AtomicLong();
    public static final AtomicLong SCOPED_CHECKS = new AtomicLong();
    public static final AtomicLong SCOPED_MISMATCHES = new AtomicLong();
    public static final java.util.ArrayDeque<Object> STACK = new java.util.ArrayDeque<>();
    public static final AtomicLong STACK_CHECKS = new AtomicLong();
    public static final AtomicLong STACK_MISMATCHES = new AtomicLong();

    private Capture() {
    }

    static void reset() {
        current = null;
        CHECKS.set(0);
        MISMATCHES.set(0);
        SCOPED_CHECKS.set(0);
        SCOPED_MISMATCHES.set(0);
        STACK.clear();
        STACK_CHECKS.set(0);
        STACK_MISMATCHES.set(0);
    }
}
