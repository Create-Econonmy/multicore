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

    /**
     * Region threads as needed: the overworld starts unsplit (on its shared dimension thread). Four groups of busy pigs far
     * apart make its tick slower than the target, so it must get region threads, one step per window, until each group
     * has one; when the pigs stop working it must give them back, down to none. Short windows (10 ticks) and a low target
     * (10 ms, 29 ms of pig work; threads go back under 6 ms) so the test does not have to wait minutes.
     */
    @GameTest(template = "empty", batch = "autoadjust_scale", timeoutTicks = 600)
    public static void slowWorldGetsRegionThreadsAndGivesThemBack(GameTestHelper helper) {
        com.multicore.core.RegionScaler scaler = com.multicore.core.RegionScaler.get();
        scaler.override(new com.multicore.core.RegionScaler.Settings(true, 10.0, 6.0, 1, 4, 2, 3, 10));
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();
        net.minecraft.core.BlockPos o = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        int[][] spots = {{o.getX() + 4000, o.getZ()}, {o.getX() - 4000, o.getZ()}, {o.getX(), o.getZ() + 4000}, {o.getX(), o.getZ() - 4000}};
        java.util.concurrent.atomic.AtomicBoolean working = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.List<net.minecraft.world.entity.animal.Pig> pigs = new java.util.ArrayList<>();
        java.util.Set<java.util.UUID> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (int[] spot : spots) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced((spot[0] >> 4) + dx, (spot[1] >> 4) + dz, true);
            }
            for (int i = 0; i < 24; i++) {
                net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(level);
                pig.setNoAi(true);
                pig.setNoGravity(true);
                pig.setInvulnerable(true);
                pig.moveTo(spot[0] + 0.5 + (i % 6), 150, spot[1] + 0.5 + (i / 6), 0, 0);
                level.addFreshEntity(pig);
                pigs.add(pig);
                ids.add(pig.getUUID());
            }
        }
        Object listener = new Object() {
            @net.neoforged.bus.api.SubscribeEvent
            public void tick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre e) {
                if (working.get() && ids.contains(e.getEntity().getUUID())) busy(300_000L);
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(listener);
        java.util.concurrent.atomic.AtomicInteger most = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger stoppedAt = new java.util.concurrent.atomic.AtomicInteger(-1);
        Runnable cleanUp = () -> {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
            pigs.forEach(net.minecraft.world.entity.Entity::discard);
            for (int[] spot : spots) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    level.setChunkForced((spot[0] >> 4) + dx, (spot[1] >> 4) + dz, false);
                }
            }
            scaler.clearOverride();
        };
        helper.onEachTick(() -> {
            int t = scaler.threads(dim);
            most.accumulateAndGet(t, Math::max);
            if (working.get() && t >= 4) {
                working.set(false);
                stoppedAt.set((int) level.getGameTime());
            }
        });
        helper.runAfterDelay(590, cleanUp);
        helper.succeedWhen(() -> {
            helper.assertTrue(most.get() >= 4, "only got " + most.get() + " region threads while slow: " + scaler.describe().get(dim));
            helper.assertTrue(!working.get(), "still working");
            helper.assertTrue(scaler.threads(dim) == 1, "still " + scaler.threads(dim) + " region threads after the work stopped: "
                    + scaler.describe().get(dim));
            cleanUp.run();
        });
    }

    private static void busy(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }
}
