package com.multicore.universal.test;

import com.multicore.universal.MultiCore;
import com.multicore.universal.api.LevelScheduler;
import com.multicore.universal.core.LockSource;
import com.multicore.universal.core.ParallelTicker;
import com.multicore.universal.core.Stats;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Same mob load (penned husks, which do not burn in daylight) in the overworld, nether and end, then the server's average tick time is measured
 * with MultiCore on and with it off. The numbers are written to the log; the test fails only on errors.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MultiCoreBenchmark {
    private static final int MOBS_PER_DIMENSION = 300;
    private static final int WARMUP = 200;
    private static final int WINDOW = 100;

    private MultiCoreBenchmark() {
    }

    @GameTest(template = "empty", timeoutTicks = 1200, required = false, batch = "benchmark")
    public static void threeDimensionMobBenchmark(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<ServerLevel> levels = new ArrayList<>();
        for (var key : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            ServerLevel level = server.getLevel(key);
            if (level != null) {
                levels.add(level);
            }
        }
        // Chunk-aligned so the whole pen (and its 3x3 forced chunks) is entity-ticking.
        BlockPos pen = new BlockPos(20_008, 200, 20_008);
        List<Zombie> zombies = java.util.Collections.synchronizedList(new ArrayList<>());
        for (ServerLevel level : levels) {
            LevelScheduler.run(level, () -> buildPenWithZombies(level, pen, zombies));
        }

        // Windows are exactly 100 ticks, the span MinecraftServer.getAverageTickTimeNanos() covers:
        // ON, then OFF, then ON again (to see whether the load drifted).
        long[] mark = new long[2];
        double[] tick = new double[3];
        helper.runAfterDelay(WARMUP, () -> {
            mark[0] = Stats.totalPhaseNanos();
            mark[1] = Stats.totalSumOfLevelsNanos();
            LockSource.reset();
            com.multicore.universal.core.Hotspots.reset();
        });
        helper.runAfterDelay(WARMUP + WINDOW, () -> {
            tick[0] = server.getAverageTickTimeNanos() / 1e6;
            double phase = (Stats.totalPhaseNanos() - mark[0]) / 1e6 / WINDOW;
            double sum = (Stats.totalSumOfLevelsNanos() - mark[1]) / 1e6 / WINDOW;
            MultiCore.LOGGER.info("[MultiCore benchmark] {} dimensions x {} husks. ON: server tick {} ms; dimension phase {} ms, "
                            + "sum of dimension times {} ms",
                    levels.size(), MOBS_PER_DIMENSION, fmt(tick[0]), fmt(phase), fmt(sum));
            for (String line : LockSource.report(15)) {
                MultiCore.LOGGER.info("[MultiCore benchmark] lock: {}", line);
            }
            for (String line : com.multicore.universal.core.Hotspots.report(20)) {
                MultiCore.LOGGER.info("[MultiCore benchmark] hotspot: {}", line);
            }
            ParallelTicker.get().setEnabled(false);
        });
        helper.runAfterDelay(WARMUP + 2 * WINDOW, () -> {
            tick[1] = server.getAverageTickTimeNanos() / 1e6;
            ParallelTicker.get().setEnabled(true);
        });
        helper.runAfterDelay(WARMUP + 3 * WINDOW, () -> {
            tick[2] = server.getAverageTickTimeNanos() / 1e6;
            double on = (tick[0] + tick[2]) / 2;
            MultiCore.LOGGER.info("[MultiCore benchmark] server tick ON {} ms, OFF {} ms, ON again {} ms -> speedup {}x",
                    fmt(tick[0]), fmt(tick[1]), fmt(tick[2]), fmt(on > 0 ? tick[1] / on : 0));
            for (Zombie zombie : zombies) {
                ServerLevel level = (ServerLevel) zombie.level();
                level.getServer().execute(zombie::discard);
            }
            for (ServerLevel level : levels) {
                ChunkPos c = new ChunkPos(pen);
                level.getServer().execute(() -> {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            level.setChunkForced(c.x + dx, c.z + dz, false);
                        }
                    }
                });
            }
            helper.succeed();
        });
    }

    private static void buildPenWithZombies(ServerLevel level, BlockPos center, List<Zombie> out) {
        ChunkPos chunk = new ChunkPos(center);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(chunk.x + dx, chunk.z + dz, true);
            }
        }
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                boolean wall = Math.abs(x) == 7 || Math.abs(z) == 7;
                if (wall) {
                    for (int y = 0; y < 4; y++) {
                        level.setBlock(center.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
        }
        for (int i = 0; i < MOBS_PER_DIMENSION; i++) {
            Zombie zombie = EntityType.HUSK.create(level);
            if (zombie == null) {
                continue;
            }
            zombie.moveTo(center.getX() + 0.5 + (i % 11) - 5, center.getY(), center.getZ() + 0.5 + (i / 11 % 11) - 5, 0, 0);
            zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(center), MobSpawnType.COMMAND, null);
            zombie.setPersistenceRequired();
            level.addFreshEntity(zombie);
            out.add(zombie);
        }
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
