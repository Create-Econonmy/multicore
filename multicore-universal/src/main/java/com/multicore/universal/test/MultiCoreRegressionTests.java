package com.multicore.universal.test;

import com.multicore.universal.MultiCore;
import com.multicore.universal.api.LevelScheduler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeCache;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * One test per bug found in the bug hunt, run with real dimension threads. Each reproduces the conditions of the bug
 * (two dimensions doing the same thing at the same time) and checks the fixed behaviour.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MultiCoreRegressionTests {
    private static final String EMPTY = "empty";

    private MultiCoreRegressionTests() {
    }

    private static ServerLevel nether(GameTestHelper helper) {
        return helper.getLevel().getServer().getLevel(Level.NETHER);
    }

    /** Bug #14: mods (Open Parties and Claims, Lootr, Polymorph) switch off unless isSameThread() is true. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void levelThreadCountsAsServerThread(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicReference<Boolean> same = new AtomicReference<>();
        LevelScheduler.run(nether(helper), () -> same.set(server.isSameThread()));
        helper.succeedWhen(() -> helper.assertTrue(Boolean.TRUE.equals(same.get()), "isSameThread() on a dimension thread was " + same.get()));
    }

    /** Bug #14: Open Parties and Claims' ServerData.from() returned null off the main thread, so claims did not protect. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void claimDataAvailableOnLevelThread(GameTestHelper helper) {
        Method from;
        try {
            from = Class.forName("xaero.pac.common.server.ServerData").getMethod("from", MinecraftServer.class);
        } catch (ReflectiveOperationException e) {
            helper.succeed(); // Open Parties and Claims is not installed
            return;
        }
        MinecraftServer server = helper.getLevel().getServer();
        AtomicReference<Object> data = new AtomicReference<>("pending");
        LevelScheduler.run(nether(helper), () -> {
            try {
                data.set(from.invoke(null, server));
            } catch (ReflectiveOperationException e) {
                data.set(e);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!"pending".equals(data.get()), "not run yet");
            helper.assertTrue(data.get() != null && !(data.get() instanceof Throwable), "claim data on a dimension thread: " + data.get());
        });
    }

    /** Bug #11: setting a block in another dimension from a dimension thread raced with that dimension's thread. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void foreignSetBlockIsDeferred(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        BlockPos abs = helper.absolutePos(new BlockPos(3, 2, 3));
        AtomicReference<String> result = new AtomicReference<>();
        LevelScheduler.run(nether(helper), () -> {
            boolean ok = overworld.setBlock(abs, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
            boolean immediate = overworld.getBlockState(abs).is(Blocks.EMERALD_BLOCK);
            result.set(ok + "/" + immediate);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue("true/false".equals(result.get()), "setBlock returned/applied immediately: " + result.get());
            helper.assertBlockPresent(Blocks.EMERALD_BLOCK, new BlockPos(3, 2, 3));
        });
    }

    /** Bug #11: force-loading a chunk of another dimension changed that dimension's ticket tables from the wrong thread. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void foreignForcedChunkIsDeferred(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        ChunkPos chunk = new ChunkPos(helper.absolutePos(BlockPos.ZERO).offset(16 * 40, 0, 16 * 40));
        LevelScheduler.run(nether(helper), () -> overworld.setChunkForced(chunk.x, chunk.z, true));
        helper.succeedWhen(() -> {
            helper.assertTrue(overworld.getForcedChunks().contains(chunk.toLong()), "chunk was not force-loaded");
            overworld.setChunkForced(chunk.x, chunk.z, false);
        });
    }

    /** Bug #8: NeoForge's static captured-drops list mixed up drops of blocks broken in two dimensions at once. */
    @GameTest(template = EMPTY, timeoutTicks = 600)
    public static void blockDropsStayInTheirDimension(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = nether(helper);
        BlockPos owBase = helper.absolutePos(new BlockPos(1, 2, 1)).offset(0, 40, 0);
        BlockPos netherBase = new BlockPos(64, 110, 64);
        int breaks = 400;
        LevelScheduler.run(nether, () -> nether.setChunkForced(4, 4, true));
        AtomicInteger done = new AtomicInteger();
        Runnable owWork = () -> {
            breakMany(overworld, owBase, Blocks.COBBLESTONE, breaks);
            done.incrementAndGet();
        };
        Runnable netherWork = () -> {
            breakMany(nether, netherBase, Blocks.OAK_PLANKS, breaks);
            done.incrementAndGet();
        };
        // A freshly generated nether chunk only shows its entities to lookups once it is entity-ticking.
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(nether.isPositionEntityTicking(netherBase), "nether chunk not loaded yet"))
                .thenExecute(() -> {
                    LevelScheduler.run(overworld, owWork);
                    LevelScheduler.run(nether, netherWork);
                })
                .thenWaitUntil(() -> helper.assertTrue(done.get() == 2, "breaking not finished"))
                .thenExecuteAfter(2, () -> checkDrops(helper, overworld, nether, owBase, netherBase, breaks))
                .thenSucceed();
    }

    private static void checkDrops(GameTestHelper helper, ServerLevel overworld, ServerLevel nether, BlockPos owBase,
                                   BlockPos netherBase, int breaks) {
        int owCobble = countItems(overworld, owBase, Items.COBBLESTONE);
        int owPlanks = countItems(overworld, owBase, Items.OAK_PLANKS);
        int netherPlanks = countItems(nether, netherBase, Items.OAK_PLANKS);
        int netherCobble = countItems(nether, netherBase, Items.COBBLESTONE);
        discardItems(overworld, owBase);
        LevelScheduler.run(nether, () -> {
            discardItems(nether, netherBase);
            nether.setChunkForced(4, 4, false);
        });
        helper.assertTrue(owCobble == breaks && netherPlanks == breaks && owPlanks == 0 && netherCobble == 0,
                "drops ended up in the wrong place: overworld cobble=" + owCobble + " planks=" + owPlanks
                        + ", nether planks=" + netherPlanks + " cobble=" + netherCobble);
    }

    private static void breakMany(ServerLevel level, BlockPos base, net.minecraft.world.level.block.Block block, int count) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 2);
            }
        }
        for (int i = 0; i < count; i++) {
            level.setBlock(base, block.defaultBlockState(), 2);
            level.destroyBlock(base, true, null);
        }
    }

    private static int countItems(ServerLevel level, BlockPos base, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(base).inflate(8))) {
            if (e.getItem().is(item)) {
                total += e.getItem().getCount();
            }
        }
        return total;
    }

    private static void discardItems(ServerLevel level, BlockPos base) {
        level.getEntitiesOfClass(ItemEntity.class, new AABB(base).inflate(8)).forEach(ItemEntity::discard);
    }

    /** Bug #7: the Crafter's static recipe cache could hand one dimension's crafter the other dimension's recipe. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void crafterCacheGivesTheRightRecipe(GameTestHelper helper) throws ReflectiveOperationException {
        Field field = CrafterBlock.class.getDeclaredField("RECIPE_CACHE");
        field.setAccessible(true);
        RecipeCache cache = (RecipeCache) field.get(null);
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = nether(helper);
        AtomicInteger wrong = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        LevelScheduler.run(overworld, () -> lookUpMany(cache, overworld, Items.OAK_LOG, Items.OAK_PLANKS, wrong, done));
        LevelScheduler.run(nether, () -> lookUpMany(cache, nether, Items.BIRCH_LOG, Items.BIRCH_PLANKS, wrong, done));
        helper.succeedWhen(() -> {
            helper.assertTrue(done.get() == 2, "lookups not finished");
            helper.assertTrue(wrong.get() == 0, wrong.get() + " lookups returned another dimension's recipe");
        });
    }

    private static void lookUpMany(RecipeCache cache, ServerLevel level, net.minecraft.world.item.Item log,
                                   net.minecraft.world.item.Item planks, AtomicInteger wrong, AtomicInteger done) {
        CraftingInput input = CraftingInput.of(1, 1, List.of(new ItemStack(log)));
        CraftingInput other = CraftingInput.of(1, 1, List.of(new ItemStack(Items.STICK)));
        for (int i = 0; i < 20_000; i++) {
            Optional<RecipeHolder<CraftingRecipe>> recipe = cache.get(level, (i & 3) == 0 ? other : input);
            if ((i & 3) != 0) {
                ItemStack out = recipe.map(r -> r.value().assemble(input, level.registryAccess())).orElse(ItemStack.EMPTY);
                if (!out.is(planks)) {
                    wrong.incrementAndGet();
                }
            }
        }
        done.incrementAndGet();
    }

    /** Bug #6: redstone torches in two dimensions updated one static WeakHashMap at the same time. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void redstoneTorchBookkeepingInParallel(GameTestHelper helper) throws ReflectiveOperationException {
        Method check = RedstoneTorchBlock.class.getDeclaredMethod("isToggledTooFrequently", Level.class, BlockPos.class, boolean.class);
        check.setAccessible(true);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger done = new AtomicInteger();
        for (ServerLevel level : List.of(helper.getLevel(), nether(helper))) {
            LevelScheduler.run(level, () -> {
                try {
                    for (int i = 0; i < 50_000; i++) {
                        check.invoke(null, level, BlockPos.ZERO, false);
                    }
                } catch (Throwable t) {
                    error.set(t);
                }
                done.incrementAndGet();
            });
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(done.get() == 2, "not finished");
            helper.assertTrue(error.get() == null, "torch bookkeeping failed: " + error.get());
        });
    }

    /**
     * Bug #25: while one dimension thread measured the power around a redstone wire, wires in every other dimension
     * reported no power (the switch is a field of the one shared RedStoneWireBlock).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void redstoneWireKeepsPowerInOtherDimensions(GameTestHelper helper) throws ReflectiveOperationException {
        Method measure = RedStoneWireBlock.class.getDeclaredMethod("calculateTargetStrength", Level.class, BlockPos.class);
        measure.setAccessible(true);
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        ServerLevel overworld = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        AtomicInteger silenced = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        LevelScheduler.run(overworld, () -> {
            try {
                for (int i = 0; i < 200_000; i++) {
                    measure.invoke(Blocks.REDSTONE_WIRE, overworld, pos);
                }
            } catch (Throwable t) {
                error.set(t);
            }
            done.incrementAndGet();
        });
        LevelScheduler.run(nether(helper), () -> {
            for (int i = 0; i < 2_000_000; i++) {
                if (!wire.isSignalSource()) {
                    silenced.incrementAndGet();
                }
            }
            done.incrementAndGet();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(done.get() == 2, "not finished");
            helper.assertTrue(error.get() == null, "measuring failed: " + error.get());
            helper.assertTrue(silenced.get() == 0, "nether wires lost their power " + silenced.get() + " times");
        });
    }
}
