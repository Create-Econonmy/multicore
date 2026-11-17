package com.multicore.testmod;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Mob;

/** Static helpers called from mixin hooks, mutating static state one call away from the hook. */
public final class Hooks {
    public static final Map<String, Long> AI_STEPS = new HashMap<>();
    public static final AtomicLong REFERENCE = new AtomicLong();

    private Hooks() {
    }

    public static void onServerAiStep(Mob mob) {
        if (!Shared.recording) {
            return;
        }
        AI_STEPS.merge(mob.level().dimension().location().toString(), 1L, Long::sum);
        REFERENCE.incrementAndGet();
    }

    public static long sum() {
        long sum = 0;
        for (long v : AI_STEPS.values()) {
            sum += v;
        }
        return sum;
    }

    static void reset() {
        AI_STEPS.clear();
        REFERENCE.set(0);
    }
}
