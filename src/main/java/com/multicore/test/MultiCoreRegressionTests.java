package com.multicore.test;

import com.multicore.MultiCore;
import com.multicore.api.LevelScheduler;
import com.multicore.compat.ThreadStatics;
import com.multicore.core.ListenerSerializer;
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

    /** For {@link #classInitializerWaitingForTheModLockDoesNotDeadlock}: a class whose static setup takes the mod lock. */
    static final class ClinitProbe {
        static final java.util.concurrent.CountDownLatch INIT_STARTED = new java.util.concurrent.CountDownLatch(1);
        static final java.util.concurrent.CountDownLatch LOCK_TAKEN = new java.util.concurrent.CountDownLatch(1);
        static final int VALUE;

        static {
            INIT_STARTED.countDown();
            try {
                LOCK_TAKEN.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Like Create's CapabilityMinecartController.<clinit> making a guarded WorldAttached.
            com.multicore.core.ModLock.runLocked(com.multicore.core.LockSource.of("test clinit"), () -> { });
            VALUE = 42;
        }

        static int value() {
            return VALUE;
        }
    }

    /**
     * Live server freeze 2026-11-26: one thread inside a class initializer waits for the mod lock, the holder waits for
     * that class. Java's class-init wait has no timeout, so this hung the server on its first tick.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void classInitializerWaitingForTheModLockDoesNotDeadlock(GameTestHelper helper) {
        AtomicReference<String> result = new AtomicReference<>();
        Thread initializer = new Thread(() -> {
            try {
                Class.forName(ClinitProbe.class.getName(), true, ClinitProbe.class.getClassLoader());
            } catch (Throwable t) {
                result.compareAndSet(null, "initializer failed: " + t);
            }
        }, "MultiCore test clinit");
        Thread holder = new Thread(() -> {
            try {
                ClinitProbe.INIT_STARTED.await();
                com.multicore.core.ModLock.runLocked(com.multicore.core.LockSource.of("test holder"), () -> {
                    ClinitProbe.LOCK_TAKEN.countDown();
                    int v = ClinitProbe.value(); // waits for the initializer, while holding the lock
                    result.compareAndSet(null, v == 42 ? "ok" : "wrong value " + v);
                });
            } catch (Throwable t) {
                result.compareAndSet(null, "holder failed: " + t);
            }
        }, "MultiCore test holder");
        initializer.setDaemon(true);
        holder.setDaemon(true);
        initializer.start();
        holder.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(result.get() != null, "still deadlocked: class initializer and mod lock holder wait for each other");
            helper.assertTrue("ok".equals(result.get()), result.get());
            helper.assertFalse(com.multicore.core.ModLock.heldByCurrentThread(), "lock leaked");
        });
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
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void blockDropsStayInTheirDimension(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = nether(helper);
        BlockPos owBase = helper.absolutePos(new BlockPos(1, 2, 1)).offset(0, 40, 0);
        BlockPos netherBase = new BlockPos(64, 110, 64);
        int breaks = 400;
        LevelScheduler.run(nether, () -> nether.setChunkForced(4, 4, true));
        Runnable owWork = () -> breakMany(overworld, owBase, Blocks.COBBLESTONE, breaks);
        java.util.concurrent.atomic.AtomicReference<String> netherSeen = new java.util.concurrent.atomic.AtomicReference<>("not run");
        java.util.concurrent.atomic.AtomicInteger eventsFirst = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger dropsFirst = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger dropsLast = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger canceled = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockDropsEvent> first = e -> {
            if (e.getLevel() == nether && e.getPos().equals(netherBase)) { eventsFirst.incrementAndGet(); dropsFirst.addAndGet(e.getDrops().size()); }
        };
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockDropsEvent> last = e -> {
            if (e.getLevel() == nether && e.getPos().equals(netherBase)) { dropsLast.addAndGet(e.getDrops().size()); if (e.isCanceled()) canceled.incrementAndGet(); }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, true, net.neoforged.neoforge.event.level.BlockDropsEvent.class, first);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, true, net.neoforged.neoforge.event.level.BlockDropsEvent.class, last);
        Runnable netherWork = () -> {
            breakMany(nether, netherBase, Blocks.OAK_PLANKS, breaks);
            netherSeen.set(countItems(nether, netherBase, Items.OAK_PLANKS) + " planks right after, on " + Thread.currentThread().getName()
                    + ", doTileDrops " + nether.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS));
        };
        helper.runAfterDelay(5, () -> {
            LevelScheduler.run(overworld, owWork);
            LevelScheduler.run(nether, netherWork);
        });
        helper.runAfterDelay(20, () -> {
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
                            + ", nether planks=" + netherPlanks + " cobble=" + netherCobble
                            + " (nether chunk loaded " + nether.isLoaded(netherBase) + ", block there " + nether.getBlockState(netherBase)
                            + ", floor " + nether.getBlockState(netherBase.below()) + ", nether item entities near it "
                            + nether.getEntitiesOfClass(ItemEntity.class, new AABB(netherBase).inflate(32)).size() + ", nether game time " + nether.getGameTime() + "; " + netherSeen.get() + "; drop events " + eventsFirst.get() + ", drops in them first " + dropsFirst.get()
                            + ", last " + dropsLast.get() + ", canceled " + canceled.get() + ")");
            helper.succeed();
        });
    }

    private static void breakMany(ServerLevel level, BlockPos base, net.minecraft.world.level.block.Block block, int count) {
        // A closed bedrock box: in the nether the spot can be over a lava lake, and drops that bounce off an open
        // platform burn there (the test then saw "no drops" although nothing was wrong).
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 2);
                boolean wall = Math.abs(x) == 3 || Math.abs(z) == 3;
                for (int y = 0; y <= 2; y++) {
                    level.setBlock(base.offset(x, y, z), wall ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
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

    /** Bug #24: a deployer's drop capture in one dimension took drops of blocks broken at the same spot in another. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void deployerDropCaptureStaysInItsDimension(GameTestHelper helper) {
        if (!classExists("com.simibubi.create.content.kinetics.deployer.DeployerHandler")) {
            helper.succeed();
            return;
        }
        String key = "com/simibubi/create/content/kinetics/deployer/DeployerHandler.CAPTURED_BLOCK_DROPS";
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = nether(helper);
        // Same coordinates in both dimensions; the overworld ones are inside the (loaded) test area.
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        AtomicReference<String> netherResult = new AtomicReference<>();
        AtomicReference<String> owResult = new AtomicReference<>();
        LevelScheduler.run(nether, () -> {
            @SuppressWarnings("unchecked")
            Map<BlockPos, List<ItemEntity>> capture = (Map<BlockPos, List<ItemEntity>>) ThreadStatics.getObject(key);
            List<ItemEntity> captured = new ArrayList<>();
            capture.put(pos, captured);
            try {
                for (int i = 0; i < 200; i++) {
                    Block.popResource(nether, pos, new ItemStack(Items.DIAMOND));
                }
            } finally {
                capture.remove(pos);
            }
            netherResult.set(captured.size() + "/" + countItems(nether, pos, Items.DIAMOND));
            discardItems(nether, pos);
        });
        LevelScheduler.run(overworld, () -> {
            for (int i = 0; i < 200; i++) {
                Block.popResource(overworld, pos, new ItemStack(Items.EMERALD));
            }
            owResult.set(String.valueOf(countItems(overworld, pos, Items.EMERALD)));
            discardItems(overworld, pos);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(netherResult.get() != null && owResult.get() != null, "not finished");
            helper.assertTrue("200/0".equals(netherResult.get()), "nether deployer captured/dropped: " + netherResult.get());
            helper.assertTrue("200".equals(owResult.get()), "overworld drops that reached the world: " + owResult.get() + " of 200");
        });
    }

    /** Bug #22: Steam 'n' Rails' "a ghast fireball is exploding" flag set in one dimension changed explosions in another. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void railsExplosionFlagIsPerDimension(GameTestHelper helper) throws ReflectiveOperationException {
        if (!classExists("com.railwayteam.railways.util.MixinVariables")) {
            helper.succeed();
            return;
        }
        String key = "com/railwayteam/railways/util/MixinVariables.largeGhastFireballExplosion";
        Object trackEntry = Class.forName("com.simibubi.create.AllBlocks").getField("TRACK").get(null);
        BlockState track = ((Block) trackEntry.getClass().getMethod("get").invoke(trackEntry)).defaultBlockState();
        ExplosionDamageCalculator calculator = new ExplosionDamageCalculator();
        BlockPos pos = new BlockPos(0, 64, 0);
        AtomicReference<String> netherResult = new AtomicReference<>();
        AtomicReference<String> owResult = new AtomicReference<>();
        for (ServerLevel level : List.of(helper.getLevel(), nether(helper))) {
            boolean isNether = level.dimension() == Level.NETHER;
            LevelScheduler.run(level, () -> {
                Explosion explosion = new Explosion(level, null, 0, 64, 0, 4F, false, Explosion.BlockInteraction.DESTROY);
                int breaks = 0;
                ThreadStatics.setBoolean(isNether, key);
                try {
                    for (int i = 0; i < 100_000; i++) {
                        if (calculator.shouldBlockExplode(explosion, level, pos, track, 4F)) {
                            breaks++;
                        }
                    }
                } finally {
                    ThreadStatics.setBoolean(false, key);
                }
                (isNether ? netherResult : owResult).set(String.valueOf(breaks));
            });
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(netherResult.get() != null && owResult.get() != null, "not finished");
            helper.assertTrue("0".equals(netherResult.get()), "tracks broken by the nether's fireball explosion: " + netherResult.get());
            helper.assertTrue("100000".equals(owResult.get()), "overworld explosions that could break tracks: " + owResult.get());
        });
    }

    /** Bug #23: Create: Mobile Package Couriers' shared target map was written by every dimension's signs at once. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void courierTargetsSurviveParallelUpdates(GameTestHelper helper) throws ReflectiveOperationException {
        Class<?> targetClass;
        try {
            targetClass = Class.forName("com.kreidev.cmpackagecouriers.CourierTarget");
        } catch (ClassNotFoundException e) {
            helper.succeed();
            return;
        }
        Constructor<?> create = targetClass.getConstructor(String.class, Level.class, BlockPos.class);
        Method add = targetClass.getMethod("addOrUpdateTarget", targetClass);
        Method find = targetClass.getMethod("getActiveTarget", String.class, boolean.class);
        Object map = targetClass.getField("activeTargets").get(null);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger done = new AtomicInteger();
        for (ServerLevel level : List.of(helper.getLevel(), nether(helper))) {
            LevelScheduler.run(level, () -> {
                try {
                    for (int i = 0; i < 20_000; i++) {
                        add.invoke(null, create.newInstance("multicore-test-" + (i % 64), level, new BlockPos(i % 64, 0, 0)));
                        find.invoke(null, "multicore-test-" + (i % 7), false);
                    }
                } catch (Throwable t) {
                    error.set(t);
                }
                done.incrementAndGet();
            });
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(done.get() == 2, "not finished");
            helper.assertTrue(map instanceof ConcurrentHashMap, "activeTargets is still a " + map.getClass().getName());
            helper.assertTrue(error.get() == null, "parallel updates failed: " + error.get());
        });
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, MultiCoreRegressionTests.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Bug #3: a mod unregistering its listener object left MultiCore's wrapper firing. */
    @GameTest(template = EMPTY, timeoutTicks = 20)
    public static void unregisteredListenerStopsFiring(GameTestHelper helper) {
        CountingListener listener = new CountingListener();
        NeoForge.EVENT_BUS.register(listener);
        ListenerSerializer.setWrapOwnListenersForTest(true);
        try {
            ListenerSerializer.wrapAll(NeoForge.EVENT_BUS);
        } finally {
            ListenerSerializer.setWrapOwnListenersForTest(false);
        }
        NeoForge.EVENT_BUS.post(new TestEvent());
        NeoForge.EVENT_BUS.unregister(listener);
        NeoForge.EVENT_BUS.post(new TestEvent());
        helper.assertTrue(listener.calls.get() == 1, "listener fired " + listener.calls.get() + " times, expected 1");
        helper.succeed();
    }

    public static final class TestEvent extends Event {
    }

    public static final class CountingListener {
        final AtomicInteger calls = new AtomicInteger();

        @SubscribeEvent
        public void onTest(TestEvent event) {
            this.calls.incrementAndGet();
        }
    }
}
