package com.multicore.universal.core.fixtures;

/**
 * Mod methods that another mod's mixin hooks at their returns ({@code @Inject(at = @At("RETURN"))}). The tests insert
 * a call to {@link #tailHook} before every return after weaving, exactly where Mixin puts such a hook: after the woven
 * lock exit, right before the return instruction.
 */
public final class TailHookModCode {
    public static int calls;

    private TailHookModCode() {
    }

    public static int value(int x) {
        UnsafeModCode.count(x);
        return x * 2;
    }

    public static synchronized int syncValue(int x) {
        UnsafeModCode.count(x);
        return x * 3;
    }

    /** The injected hook: fails for negative input, like a mod hook that throws. */
    public static void tailHook(int x) {
        calls++;
        if (x < 0) {
            throw new IllegalStateException("hook failed");
        }
    }
}
