package com.multicore.test;

import com.multicore.MultiCore;
import com.multicore.api.LevelScheduler;
import com.multicore.core.ParallelTicker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Brute force: in every dimension at once, place every block that has a block entity (which covers every block entity
 * type of every mod) and spawn every summonable entity type, then let them all tick. Run once with MultiCore on and
 * once with it off, at different coordinates, so errors that also happen without MultiCore (the mods' own bugs) can be
 * told apart from MultiCore's. Everything is logged between "[MultiCore fuzz]" markers for tools/fuzz-report.ps1.
 * <p>
 * NeoForge's "remove erroring block entities / entities" switches are on for the duration, so one broken block entity
 * does not end the run; each removal is logged with its exception.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MultiCoreFuzzTests {
    private static final int TICKS = 200;
    private static final int COLUMNS = 40;
    private static final int SPACING = 2;
    private static final int ENTITY_AREA_OFFSET = 150;

    private MultiCoreFuzzTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 2 * TICKS + 400, required = false, batch = "fuzz")
    public static void everyBlockEntityAndEntityInEveryDimension(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);
        List<Block> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.defaultBlockState().hasBlockEntity()) {
                blocks.add(block);
            }
        }
        List<EntityType<?>> types = new ArrayList<>();
        List<String> needData = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.canSummon() && type != EntityType.PLAYER) {
                String problem = probe(type, helper.getLevel());
                if (problem == null) {
                    types.add(type);
                } else {
                    needData.add(BuiltInRegistries.ENTITY_TYPE.getKey(type) + " (" + problem + ")");
                }
            }
        }
        MultiCore.LOGGER.info("[MultiCore fuzz] left out {} entity types that break without the data their item or block "
                + "gives them (they crash the same way without MultiCore): {}", needData.size(), needData);
        MultiCore.LOGGER.info("[MultiCore fuzz] {} dimensions, {} blocks with block entities ({} block entity types registered), "
                + "{} entity types", levels.size(), blocks.size(), BuiltInRegistries.BLOCK_ENTITY_TYPE.size(), types.size());

        boolean oldRemoveBlockEntities = NeoForgeConfig.SERVER.removeErroringBlockEntities.get();
        boolean oldRemoveEntities = NeoForgeConfig.SERVER.removeErroringEntities.get();
        setConfig(NeoForgeConfig.SERVER.removeErroringBlockEntities, true);
        setConfig(NeoForgeConfig.SERVER.removeErroringEntities, true);
        GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);

        BlockPos parallelBase = new BlockPos(4096, 150, 4096);
        BlockPos vanillaBase = new BlockPos(-8192, 150, 4096);
        AtomicInteger placed = new AtomicInteger();

        MultiCore.LOGGER.info("[MultiCore fuzz] BEGIN parallel (MultiCore on)");
        ParallelTicker.get().setEnabled(true);
        for (ServerLevel level : levels) {
            LevelScheduler.run(level, () -> populate(level, parallelBase, blocks, types, placed));
        }
        helper.runAfterDelay(TICKS, () -> {
            String reason = ParallelTicker.get().disabledReason();
            MultiCore.LOGGER.info("[MultiCore fuzz] END parallel: {} dimensions populated, MultiCore {}", placed.get(),
                    reason == null ? "still running in parallel" : "FELL BACK TO VANILLA: " + reason);
            for (ServerLevel level : levels) {
                LevelScheduler.run(level, () -> clear(level, parallelBase));
            }
            helper.assertTrue(reason == null, "MultiCore fell back to vanilla ticking: " + reason);
            ParallelTicker.get().setEnabled(false);
            placed.set(0);
            MultiCore.LOGGER.info("[MultiCore fuzz] BEGIN vanilla (MultiCore off)");
            for (ServerLevel level : levels) {
                LevelScheduler.run(level, () -> populate(level, vanillaBase, blocks, types, placed));
            }
        });
        helper.runAfterDelay(2L * TICKS + 20, () -> {
            MultiCore.LOGGER.info("[MultiCore fuzz] END vanilla: {} dimensions populated", placed.get());
            for (ServerLevel level : levels) {
                LevelScheduler.run(level, () -> clear(level, vanillaBase));
            }
            ParallelTicker.get().setEnabled(true);
            setConfig(NeoForgeConfig.SERVER.removeErroringBlockEntities, oldRemoveBlockEntities);
            setConfig(NeoForgeConfig.SERVER.removeErroringEntities, oldRemoveEntities);
            rules.getRule(GameRules.RULE_MOBGRIEFING).set(true, server);
            rules.getRule(GameRules.RULE_DOFIRETICK).set(true, server);
            helper.succeed();
        });
    }

    /** These options are marked "needs a world restart", so set() alone leaves the cached value that get() returns. */
    private static void setConfig(ModConfigSpec.BooleanValue option, boolean value) {
        option.set(value);
        option.clearCache();
        if (option.get() != value) {
            throw new IllegalStateException("Could not set " + option.getPath() + " to " + value);
        }
    }

    /**
     * Server thread, outside the parallel phase: creates the entity away from everything, ticks it once and removes it.
     * Some mod entities (contraption carriers, wires between two blocks...) only work with data their item or block
     * fills in and crash on tick or even on removal without it; those cannot be spawned blindly.
     */
    @javax.annotation.Nullable
    private static String probe(EntityType<?> type, ServerLevel level) {
        Entity entity;
        try {
            entity = type.create(level);
        } catch (Throwable t) {
            return "create: " + t;
        }
        if (entity == null) {
            return "create returned null";
        }
        String problem = null;
        try {
            entity.moveTo(0.5, level.getMaxBuildHeight() + 64, 0.5, 0F, 0F);
            entity.tick();
        } catch (Throwable t) {
            problem = "tick: " + t;
        }
        try {
            entity.discard();
        } catch (Throwable t) {
            problem = problem == null ? "remove: " + t : problem;
        }
        return problem;
    }

    /** Runs on the dimension's own thread. */
    private static void populate(ServerLevel level, BlockPos base, List<Block> blocks, List<EntityType<?>> types, AtomicInteger placed) {
        String dim = level.dimension().location().toString();
        int rows = (blocks.size() + COLUMNS - 1) / COLUMNS;
        int width = COLUMNS * SPACING + 8;
        int depth = ENTITY_AREA_OFFSET + 40;
        for (int cx = (base.getX() - 4) >> 4; cx <= (base.getX() + width) >> 4; cx++) {
            for (int cz = (base.getZ() - 4) >> 4; cz <= (base.getZ() + depth) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        BlockState floor = Blocks.BEDROCK.defaultBlockState();
        for (int x = -4; x < width; x++) {
            for (int z = -4; z < depth; z++) {
                level.setBlock(base.offset(x, -1, z), floor, Block.UPDATE_CLIENTS);
            }
        }

        Set<BlockEntityType<?>> covered = ConcurrentHashMap.newKeySet();
        Map<String, Integer> placeErrors = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            BlockPos pos = base.offset((i % COLUMNS) * SPACING, 0, (i / COLUMNS) * SPACING);
            try {
                level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_CLIENTS);
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null) {
                    covered.add(be.getType());
                }
            } catch (Throwable t) {
                placeErrors.merge(BuiltInRegistries.BLOCK.getKey(block) + ": " + t, 1, Integer::sum);
            }
        }

        Map<String, Integer> spawnErrors = new LinkedHashMap<>();
        int spawned = 0;
        BlockPos entityBase = base.offset(0, 0, ENTITY_AREA_OFFSET);
        for (int i = 0; i < types.size(); i++) {
            EntityType<?> type = types.get(i);
            try {
                Entity entity = type.create(level);
                if (entity == null) {
                    continue;
                }
                entity.moveTo(entityBase.getX() + (i % COLUMNS) * SPACING + 0.5, entityBase.getY() + 0.5,
                        entityBase.getZ() + (i / COLUMNS) * SPACING + 0.5, 0F, 0F);
                if (entity instanceof Mob mob) {
                    EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.COMMAND, null);
                }
                if (level.addFreshEntity(entity)) {
                    spawned++;
                }
            } catch (Throwable t) {
                spawnErrors.merge(BuiltInRegistries.ENTITY_TYPE.getKey(type) + ": " + t, 1, Integer::sum);
            }
        }
        MultiCore.LOGGER.info("[MultiCore fuzz] {}: placed {} blocks in {} rows covering {} block entity types, spawned {} entities "
                + "on thread {}", dim, blocks.size(), rows, covered.size(), spawned, Thread.currentThread().getName());
        placeErrors.forEach((e, n) -> MultiCore.LOGGER.warn("[MultiCore fuzz] {}: placing failed x{}: {}", dim, n, e));
        spawnErrors.forEach((e, n) -> MultiCore.LOGGER.warn("[MultiCore fuzz] {}: spawning failed x{}: {}", dim, n, e));
        placed.incrementAndGet();
    }

    /** Runs on the dimension's own thread. */
    private static void clear(ServerLevel level, BlockPos base) {
        AABB area = new AABB(base.offset(-64, -64, -64).getCenter(), base.offset(COLUMNS * SPACING + 64, 128, ENTITY_AREA_OFFSET + 128).getCenter());
        for (Entity e : level.getEntitiesOfClass(Entity.class, area, e -> !(e instanceof Player))) {
            e.discard();
        }
        int width = COLUMNS * SPACING + 8;
        int depth = ENTITY_AREA_OFFSET + 40;
        for (int cx = (base.getX() - 4) >> 4; cx <= (base.getX() + width) >> 4; cx++) {
            for (int cz = (base.getZ() - 4) >> 4; cz <= (base.getZ() + depth) >> 4; cz++) {
                level.setChunkForced(cx, cz, false);
            }
        }
    }
}
