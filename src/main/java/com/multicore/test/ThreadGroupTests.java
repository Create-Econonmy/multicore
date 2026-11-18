package com.multicore.test;

import com.multicore.MultiCore;
import com.multicore.api.LevelScheduler;
import com.multicore.core.ParallelTicker;
import com.multicore.core.ThreadGroups;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Several dimensions on one dimension thread (see {@link ThreadGroups}). The GameTest server keeps one dimension per
 * thread (other tests check that dimensions run in parallel); these tests group them and restore it. Own batches (tests of one batch run at the same time).
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ThreadGroupTests {
    private ThreadGroupTests() {
    }

    private static final ThreadGroups.Settings ONE_PER_THREAD = new ThreadGroups.Settings(0, 1, 10.0);

    private static void restoreLater(GameTestHelper helper, int timeout) {
        helper.runAfterDelay(timeout - 5, () -> ParallelTicker.get().configureGroups(ONE_PER_THREAD, List.of()));
    }

    @GameTest(template = "empty", batch = "threadgroups_shared", timeoutTicks = 200)
    public static void overworldNetherAndEndShareOneThreadAndAllTick(GameTestHelper helper) {
        ParallelTicker.get().configureGroups(new ThreadGroups.Settings(0, 6, 1000.0), List.of());
        restoreLater(helper, 200);
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        ServerLevel end = overworld.getServer().getLevel(Level.END);
        AtomicReference<String> owThread = new AtomicReference<>(), netherThread = new AtomicReference<>(), endThread = new AtomicReference<>();
        AtomicInteger netherTicks = new AtomicInteger(), endTicks = new AtomicInteger();
        helper.runAfterDelay(20, () -> {
            LevelScheduler.run(overworld, () -> owThread.set(Thread.currentThread().getName()));
            LevelScheduler.run(nether, () -> netherThread.set(Thread.currentThread().getName()));
            LevelScheduler.run(end, () -> endThread.set(Thread.currentThread().getName()));
        });
        long netherStart = nether.getGameTime(), endStart = end.getGameTime();
        helper.succeedWhen(() -> {
            helper.assertTrue(owThread.get() != null && netherThread.get() != null && endThread.get() != null, "tasks have not run yet");
            helper.assertTrue(netherThread.get().startsWith("MultiCore") && netherThread.get().contains("+"),
                    "the nether ticked on " + netherThread.get() + ", not on a shared dimension thread");
            helper.assertTrue(netherThread.get().equals(endThread.get()) && netherThread.get().equals(owThread.get()),
                    "not on one thread: " + owThread.get() + " / " + netherThread.get() + " / " + endThread.get());
            netherTicks.set((int) (nether.getGameTime() - netherStart));
            endTicks.set((int) (end.getGameTime() - endStart));
            helper.assertTrue(netherTicks.get() > 20 && endTicks.get() > 20, "dimensions on a shared thread stopped ticking");
            helper.assertTrue(ParallelTicker.get().isActive(), "MultiCore turned itself off: " + ParallelTicker.get().disabledReason());
            helper.assertTrue(ParallelTicker.get().groupDescription().contains("+"), "status shows " + ParallelTicker.get().groupDescription());
            ParallelTicker.get().configureGroups(ONE_PER_THREAD, List.of()); // passed: back to one per thread for the next tests
        });
    }

    @GameTest(template = "empty", batch = "threadgroups_entity", timeoutTicks = 200)
    public static void dimensionsOnTheSameThreadReachEachOtherDirectly(GameTestHelper helper) {
        // Only the Nether and the End share a thread; the overworld keeps its own.
        ParallelTicker.get().configureGroups(new ThreadGroups.Settings(0, 1, 10.0), List.of("minecraft:the_nether, minecraft:the_end"));
        restoreLater(helper, 200);
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        nether.setChunkForced(0, 0, true);
        end.setChunkForced(0, 0, true);
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(2, 2, 2));
        UUID id = pig.getUUID();
        BlockPos mark = new BlockPos(3, 120, 3);
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicReference<String> write = new AtomicReference<>("pending");
        helper.runAfterDelay(20, () -> LevelScheduler.run(nether, () -> {
            thread.set(Thread.currentThread().getName());
            // The End is ticked by this same thread: writing to it is safe right away (no need to defer).
            boolean done = end.setBlock(mark, Blocks.GOLD_BLOCK.defaultBlockState(), 3) && end.getBlockState(mark).is(Blocks.GOLD_BLOCK);
            write.set(done ? "immediate" : "deferred");
            // The overworld is another thread's: moving its pig to the End is deferred and still happens.
            pig.changeDimension(new DimensionTransition(end, new Vec3(0.5, 100, 0.5), Vec3.ZERO, 0, 0, DimensionTransition.DO_NOTHING));
        }));
        helper.succeedWhen(() -> {
            helper.assertTrue(thread.get() != null, "not run yet");
            helper.assertTrue(thread.get().contains("the_nether") && thread.get().contains("the_end") && thread.get().contains("+"),
                    "the nether ran on " + thread.get() + ", not on one thread with the End");
            helper.assertTrue("immediate".equals(write.get()), "a write to the End from its own thread was " + write.get());
            helper.assertTrue(pig.isRemoved(), "the pig is still in the overworld");
            helper.assertTrue(end.getEntity(id) != null, "the pig did not arrive in the End");
            end.setBlock(mark, Blocks.AIR.defaultBlockState(), 3);
            nether.setChunkForced(0, 0, false);
            end.setChunkForced(0, 0, false);
            ParallelTicker.get().configureGroups(ONE_PER_THREAD, List.of());
        });
    }
}
