package com.multicore.test;

import com.multicore.MultiCore;
import com.multicore.api.LevelScheduler;
import com.multicore.core.AutoAdjust;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Auto-adjust in the real engine (the decisions themselves are unit tested in AutoAdjustTest). The GameTest server
 * keeps auto-adjust off; each test turns it on with short windows and turns it off again. Own batches, so no other
 * test runs while dimensions move between threads.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AutoAdjustTests {
    private AutoAdjustTests() {
    }

    private static ServerLevel nether(GameTestHelper helper) {
        return helper.getLevel().getServer().getLevel(Level.NETHER);
    }

    private static void captureThread(ServerLevel level, AtomicReference<String> into) {
        LevelScheduler.run(level, () -> into.set(Thread.currentThread().getName()));
    }

    private static void off(GameTestHelper helper, int timeout) {
        helper.runAfterDelay(timeout - 5, () -> AutoAdjust.get().configure(AutoAdjust.Settings.OFF));
    }

    @GameTest(template = "empty", batch = "autoadjust_idle", timeoutTicks = 300)
    public static void idleDimensionJoinsTheMainThreadAndLeavesWhenBusy(GameTestHelper helper) {
        // Every dimension without players counts as idle (idleMs 1000), every tick counts as low TPS (loadMs 0), 20-tick windows.
        AutoAdjust.get().configure(new AutoAdjust.Settings(true, 1000, 2000, 0, 1.0, 20, 20, 1200, 1200));
        AtomicReference<String> whileIdle = new AtomicReference<>();
        AtomicReference<String> whenBusy = new AtomicReference<>();
        helper.runAfterDelay(60, () -> captureThread(nether(helper), whileIdle));
        // Now every dimension counts as busy (busyMs 0): they get their own threads back at the next window.
        helper.runAfterDelay(80, () -> AutoAdjust.get().configure(new AutoAdjust.Settings(true, 0, 0, 0, 1.0, 20, 20, 1200, 1200)));
        helper.runAfterDelay(140, () -> captureThread(nether(helper), whenBusy));
        off(helper, 300);
        helper.succeedWhen(() -> {
            helper.assertTrue(whenBusy.get() != null, "not captured yet");
            AutoAdjust.get().configure(AutoAdjust.Settings.OFF);
            helper.assertTrue("Server thread".equals(whileIdle.get()), "idle nether ticked on " + whileIdle.get());
            helper.assertTrue(whenBusy.get().startsWith("MultiCore"), "busy nether ticked on " + whenBusy.get());
        });
    }

    @GameTest(template = "empty", batch = "autoadjust_fallback", timeoutTicks = 300)
    public static void noGainUnderLoadTicksOnOneThreadThenRetries(GameTestHelper helper) {
        // Any tick counts as TPS under 20 (loadMs 0) and no speedup is ever enough (minSpeedup 16): after 40 ticks of it
        // MultiCore must tick like vanilla for 100 ticks, then try parallel ticking again.
        AutoAdjust.get().configure(new AutoAdjust.Settings(true, 0, 0, 0, 16, 20, 40, 100, 100));
        AtomicReference<String> fallenBack = new AtomicReference<>();
        AtomicReference<String> retried = new AtomicReference<>();
        helper.runAfterDelay(70, () -> captureThread(nether(helper), fallenBack));
        helper.runAfterDelay(150, () -> captureThread(nether(helper), retried));
        off(helper, 300);
        helper.succeedWhen(() -> {
            helper.assertTrue(retried.get() != null, "not captured yet");
            AutoAdjust.get().configure(AutoAdjust.Settings.OFF);
            helper.assertTrue("Server thread".equals(fallenBack.get()), "after the fallback the nether ticked on " + fallenBack.get());
            helper.assertTrue(retried.get().startsWith("MultiCore"), "after the retry the nether ticked on " + retried.get());
        });
    }
}
