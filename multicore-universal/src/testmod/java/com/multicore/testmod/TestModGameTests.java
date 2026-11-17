package com.multicore.testmod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Runs this deliberately thread-unsafe mod in three dimensions at once and checks that nothing it does was lost or
 * mixed up: every counter matches its atomic reference, the capture field always holds the entity of the thread that
 * reads it, and the dimensions really did tick on separate threads. All of this only holds if MultiCore Universal
 * serialized the mod's code (and its mixin hooks) without being told anything about this mod.
 */
@GameTestHolder(TestMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TestModGameTests {
    private static final int RACERS_PER_DIMENSION = 48;
    private static final int PIGS_PER_DIMENSION = 40;
    private static final int TICKS = 200;

    private TestModGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = TICKS + 200, batch = "testmod")
    public static void unsafeModStaysConsistentAcrossDimensions(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Map<ServerLevel, BlockPos> arenas = new java.util.LinkedHashMap<>();
        arenas.put(helper.getLevel(), helper.absolutePos(BlockPos.ZERO).offset(32, 60, 32));
        arenas.put(server.getLevel(Level.NETHER), new BlockPos(1000, 100, 1000));
        arenas.put(server.getLevel(Level.END), new BlockPos(1000, 80, 1000));
        Shared.reset();
        List<Entity> pigs = new ArrayList<>();
        List<Runnable> cleanup = new ArrayList<>();
        arenas.forEach((level, base) -> cleanup.add(buildArena(level, base, pigs)));
        Shared.recording = true;

        helper.runAfterDelay(TICKS, () -> {
            Shared.recording = false;
            try {
                verify(helper);
            } finally {
                pigs.forEach(Entity::discard);
                cleanup.forEach(Runnable::run);
            }
            helper.succeed();
        });
    }

    private static void verify(GameTestHelper helper) {
        Throwable error = Shared.ERROR.get();
        helper.assertTrue(error == null, "the mod's code threw: " + error);

        Map<String, Set<String>> threads = new TreeMap<>(Shared.THREADS_BY_DIMENSION);
        helper.assertTrue(threads.size() == 3, "racers ticked in " + threads.keySet());
        Set<String> all = new java.util.HashSet<>();
        for (Map.Entry<String, Set<String>> e : threads.entrySet()) {
            for (String name : e.getValue()) {
                helper.assertTrue(name.startsWith("MultiCore"), e.getKey() + " ticked on " + name + " (not in parallel)");
                helper.assertTrue(all.add(name), "two dimensions ticked on " + name);
            }
        }

        long reference = Shared.REFERENCE.get();
        helper.assertTrue(reference > 0, "racers did not tick");
        helper.assertTrue(Shared.plainCounter == reference, "lost counter updates: " + Shared.plainCounter + " of " + reference);
        helper.assertTrue(Shared.countsSum() == reference, "lost HashMap updates: " + Shared.countsSum() + " of " + reference);
        helper.assertTrue(Shared.SyncRegistry.INSTANCE.total() * 50 == reference,
                "synchronized singleton: " + Shared.SyncRegistry.INSTANCE.total() * 50 + " of " + reference);
        long saved = Shared.SharedData.get(helper.getLevel().getServer()).total;
        helper.assertTrue(saved * 50 >= reference, "overworld saved data: " + saved * 50 + " of " + reference);

        long ticks = Shared.ENTITY_REFERENCE.get();
        helper.assertTrue(ticks > 0, "no entity ticks seen by the listener");
        helper.assertTrue(Shared.entityTickCounter == ticks, "listener lost updates: " + Shared.entityTickCounter + " of " + ticks);
        long perType = Shared.ENTITY_TICKS.values().stream().mapToLong(Integer::longValue).sum();
        helper.assertTrue(perType == ticks, "listener HashMap lost updates: " + perType + " of " + ticks);

        helper.assertTrue(Hooks.REFERENCE.get() > 0, "the serverAiStep hook never ran");
        helper.assertTrue(Hooks.sum() == Hooks.REFERENCE.get(), "hook HashMap lost updates: " + Hooks.sum() + " of " + Hooks.REFERENCE.get());

        helper.assertTrue(Capture.CHECKS.get() > 1000, "capture field checked only " + Capture.CHECKS.get() + " times");
        helper.assertTrue(Capture.MISMATCHES.get() == 0, "capture field held another dimension's entity "
                + Capture.MISMATCHES.get() + " times out of " + Capture.CHECKS.get());
        helper.assertTrue(Capture.SCOPED_CHECKS.get() > 1000, "scoped flag checked only " + Capture.SCOPED_CHECKS.get() + " times");
        helper.assertTrue(Capture.SCOPED_MISMATCHES.get() == 0, "mixin-declared scoped flag held another dimension's entity "
                + Capture.SCOPED_MISMATCHES.get() + " times out of " + Capture.SCOPED_CHECKS.get());
        helper.assertTrue(Capture.STACK_CHECKS.get() > 1000, "capture stack checked only " + Capture.STACK_CHECKS.get() + " times");
        helper.assertTrue(Capture.STACK_MISMATCHES.get() == 0, "capture stack held another dimension's mob "
                + Capture.STACK_MISMATCHES.get() + " times out of " + Capture.STACK_CHECKS.get());
        helper.assertTrue(Shared.CROSS_DIMENSION_READS.get() > 0, "no cross-dimension reads happened");
    }

    /** Obsidian floor with racers buried under it, glass walls, and pigs inside. Returns the cleanup. */
    private static Runnable buildArena(ServerLevel level, BlockPos base, List<Entity> pigs) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = -1; x <= 13; x += 14) {
            for (int z = -1; z <= 13; z += 14) {
                ChunkPos chunk = new ChunkPos(base.offset(x, 0, z));
                if (!chunks.contains(chunk)) {
                    chunks.add(chunk);
                    level.setChunkForced(chunk.x, chunk.z, true);
                }
            }
        }
        List<BlockPos> placed = new ArrayList<>();
        int racers = 0;
        for (int x = -1; x <= 13; x++) {
            for (int z = -1; z <= 13; z++) {
                boolean wall = x == -1 || z == -1 || x == 13 || z == 13;
                set(level, base.offset(x, -1, z), Blocks.OBSIDIAN, placed);
                if (wall) {
                    set(level, base.offset(x, 0, z), Blocks.GLASS, placed);
                    set(level, base.offset(x, 1, z), Blocks.GLASS, placed);
                } else if (racers < RACERS_PER_DIMENSION) {
                    level.setBlock(base.offset(x, -2, z), TestMod.RACER.get().defaultBlockState(), 3);
                    placed.add(base.offset(x, -2, z));
                    racers++;
                }
            }
        }
        for (int i = 0; i < PIGS_PER_DIMENSION; i++) {
            Pig pig = EntityType.PIG.create(level);
            if (pig != null) {
                pig.moveTo(base.getX() + 1.5 + (i % 10), base.getY(), base.getZ() + 1.5 + (i / 10) * 2, 0, 0);
                level.addFreshEntity(pig);
                pigs.add(pig);
            }
        }
        return () -> {
            for (BlockPos pos : placed) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            for (ChunkPos chunk : chunks) {
                level.setChunkForced(chunk.x, chunk.z, false);
            }
        };
    }

    private static void set(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block, List<BlockPos> placed) {
        level.setBlock(pos, block.defaultBlockState(), 3);
        placed.add(pos);
    }
}
