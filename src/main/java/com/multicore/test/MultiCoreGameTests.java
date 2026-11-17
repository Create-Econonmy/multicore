package com.multicore.test;

import com.multicore.MultiCore;
import com.multicore.api.LevelScheduler;
import com.multicore.core.LockSource;
import com.multicore.core.ModLock;
import com.multicore.core.ParallelTicker;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the threading engine. Run with {@code gradlew runGameTestServer}; they are never registered in
 * a normal server. {@link LevelScheduler} is used to run code on a specific dimension's thread.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MultiCoreGameTests {
    private static final String EMPTY = "empty";

    private MultiCoreGameTests() {
    }

    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) {
            throw new IllegalStateException("No nether in the test server");
        }
        return nether;
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void dimensionsTickOnTheirOwnThreads(GameTestHelper helper) {
        AtomicReference<String> overworldThread = new AtomicReference<>();
        AtomicReference<String> netherThread = new AtomicReference<>();
        LevelScheduler.run(helper.getLevel(), () -> overworldThread.set(Thread.currentThread().getName()));
        LevelScheduler.run(nether(helper), () -> netherThread.set(Thread.currentThread().getName()));
        helper.succeedWhen(() -> {
            helper.assertTrue(overworldThread.get() != null && netherThread.get() != null, "tasks have not run yet");
            helper.assertTrue(netherThread.get().startsWith("MultiCore"), "nether ticked on " + netherThread.get());
            helper.assertTrue(!overworldThread.get().equals(netherThread.get()), "both dimensions ticked on " + netherThread.get());
            helper.assertTrue(ParallelTicker.get().isActive(), "MultiCore turned itself off: " + ParallelTicker.get().disabledReason());
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void crossDimensionTeleportIsDeferredAndHappens(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        nether.setChunkForced(0, 0, true);
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(2, 2, 2));
        UUID id = pig.getUUID();
        AtomicReference<String> result = new AtomicReference<>("pending");
        // Runs on the nether's thread while the overworld (which owns the pig) ticks on another thread.
        LevelScheduler.run(nether, () -> {
            Entity moved = pig.changeDimension(new DimensionTransition(nether, new Vec3(0.5, 100, 0.5), Vec3.ZERO, 0, 0,
                    DimensionTransition.DO_NOTHING));
            result.set(moved == null ? "deferred" : "immediate");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue("deferred".equals(result.get()), "changeDimension from a foreign thread was " + result.get());
            helper.assertTrue(pig.isRemoved(), "pig is still in the overworld");
            helper.assertTrue(nether.getEntity(id) != null, "pig did not arrive in the nether");
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void crossDimensionBlockRead(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 2, 1);
        helper.setBlock(rel, Blocks.DIAMOND_BLOCK);
        BlockPos abs = helper.absolutePos(rel);
        ServerLevel overworld = helper.getLevel();
        AtomicReference<BlockState> seen = new AtomicReference<>();
        LevelScheduler.run(nether(helper), () -> seen.set(overworld.getBlockState(abs)));
        helper.succeedWhen(() -> helper.assertTrue(seen.get() != null && seen.get().is(Blocks.DIAMOND_BLOCK), "saw " + seen.get()));
    }

    @GameTest(template = EMPTY, timeoutTicks = 600)
    public static void crossDimensionChunkLoadDoesNotDeadlock(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        int far = 4000 + RandomSource.create().nextInt(1000);
        AtomicReference<Object> got = new AtomicReference<>();
        LevelScheduler.run(nether(helper), () -> got.set(overworld.getChunk(far, far)));
        helper.succeedWhen(() -> helper.assertTrue(got.get() instanceof LevelChunk, "chunk " + far + " not loaded yet"));
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void commandFromDimensionThreadRuns(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos abs = helper.absolutePos(new BlockPos(2, 2, 2));
        String command = "setblock " + abs.getX() + " " + abs.getY() + " " + abs.getZ() + " minecraft:gold_block";
        LevelScheduler.run(nether(helper), () -> server.getCommands()
                .performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
        helper.succeedWhen(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(2, 2, 2)));
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void modLockIsMutuallyExclusive(GameTestHelper helper) {
        int[] counter = {0};
        AtomicInteger finished = new AtomicInteger();
        Runnable work = () -> {
            for (int i = 0; i < 200_000; i++) {
                ModLock.acquire(LockSource.of("test"));
                try {
                    counter[0]++;
                } finally {
                    ModLock.release();
                }
            }
            finished.incrementAndGet();
        };
        LevelScheduler.run(helper.getLevel(), work);
        LevelScheduler.run(nether(helper), work);
        helper.succeedWhen(() -> {
            helper.assertTrue(finished.get() == 2, "workers not finished");
            helper.assertTrue(counter[0] == 400_000, "lost updates: " + counter[0]);
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void sharedLegacyRandomDoesNotCrash(GameTestHelper helper) {
        RandomSource shared = new LegacyRandomSource(42L);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < 100_000; i++) {
                        shared.nextInt(100);
                    }
                } catch (Throwable e) {
                    error.set(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        helper.assertTrue(error.get() == null, "shared LegacyRandomSource threw " + error.get());
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void parallelMobStress(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        List<Entity> spawned = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 150; i++) {
            spawned.add(helper.spawn(EntityType.ZOMBIE, new BlockPos(1 + i % 3, 2, 1 + (i / 3) % 3)));
        }
        LevelScheduler.run(nether, () -> {
            nether.setChunkForced(1, 1, true);
            BlockPos base = new BlockPos(20, 120, 20);
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    nether.setBlockAndUpdate(base.offset(x, -1, z), Blocks.OBSIDIAN.defaultBlockState());
                }
            }
            for (int i = 0; i < 150; i++) {
                Zombie zombie = EntityType.ZOMBIE.create(nether);
                if (zombie != null) {
                    zombie.moveTo(base.getX() + 0.5 + (i % 5) - 2, base.getY(), base.getZ() + 0.5 + (i / 5 % 5) - 2, 0, 0);
                    zombie.finalizeSpawn(nether, nether.getCurrentDifficultyAt(base), MobSpawnType.COMMAND, null);
                    nether.addFreshEntity(zombie);
                    spawned.add(zombie);
                }
            }
        });
        helper.runAfterDelay(200, () -> {
            helper.assertTrue(ParallelTicker.get().isActive(), "MultiCore turned itself off: " + ParallelTicker.get().disabledReason());
            for (Entity e : spawned) {
                if (e.level() == nether) {
                    LevelScheduler.run(nether, e::discard);
                } else {
                    e.discard();
                }
            }
            LevelScheduler.run(nether, () -> nether.setChunkForced(1, 1, false));
            helper.succeed();
        });
    }
}
