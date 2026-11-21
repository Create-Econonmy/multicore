package com.multicore.test;

import com.multicore.MultiCore;
import com.multicore.core.ParallelTicker;
import com.multicore.core.RegionThread;
import com.multicore.core.RegionTicker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Region threading: one dimension (the overworld) ticked by several threads (see {@link RegionTicker}). Each test builds
 * groups of pigs (or hoppers) thousands of blocks apart in force-loaded chunks, far from the test structures, so they
 * land in different regions. With the default limits (32 entities, 64 block entities) the groups are big enough to be
 * split. Code is run inside the pigs' ticks with an EntityTickEvent listener, i.e. on the region threads.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RegionTests {
    private static final Map<UUID, Consumer<Entity>> ACTIONS = new ConcurrentHashMap<>();
    private static final AtomicBoolean LISTENING = new AtomicBoolean();

    private RegionTests() {
    }

    private static void listen() {
        if (LISTENING.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(EntityTickEvent.Pre.class, e -> {
                Consumer<Entity> action = ACTIONS.get(e.getEntity().getUUID());
                if (action != null) {
                    action.accept(e.getEntity());
                }
            });
        }
    }

    private static boolean onRegionThread() {
        return Thread.currentThread() instanceof RegionThread;
    }

    /** Force-loads the 3x3 chunks around (x, z) and puts {@code count} still, floating pigs there. */
    private static List<Pig> pigs(ServerLevel level, int x, int z, int count) {
        forceArea(level, x, z, true);
        List<Pig> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Pig pig = EntityType.PIG.create(level);
            pig.setNoAi(true);
            pig.setNoGravity(true);
            pig.setInvulnerable(true);
            pig.setPersistenceRequired();
            pig.moveTo(x + 0.5 + (i % 6), 150, z + 0.5 + (i / 6), 0, 0);
            level.addFreshEntity(pig);
            out.add(pig);
        }
        return out;
    }

    private static void forceArea(ServerLevel level, int x, int z, boolean force) {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(cx + dx, cz + dz, force);
                if (force) {
                    level.getChunk(cx + dx, cz + dz);
                }
            }
        }
    }

    private static void cleanUp(ServerLevel level, List<? extends Entity> entities, int[][] areas) {
        for (Entity e : entities) {
            ACTIONS.remove(e.getUUID());
            e.discard();
        }
        for (int[] area : areas) {
            forceArea(level, area[0], area[1], false);
        }
    }

    private static void assertRegionsStillOn(GameTestHelper helper) {
        helper.assertTrue(RegionTicker.enabled(), "region threading switched itself off: " + RegionTicker.disabledReason());
        helper.assertTrue(ParallelTicker.get().isActive(), "MultiCore switched itself off: " + ParallelTicker.get().disabledReason());
    }

    @GameTest(template = "empty", batch = "regions_threads", timeoutTicks = 300)
    public static void farApartGroupsTickOnDifferentRegionThreads(GameTestHelper helper) {
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int ax = o.getX() + 3000, bx = o.getX() - 3000, z = o.getZ();
        List<Pig> a = pigs(level, ax, z, 24);
        List<Pig> b = pigs(level, bx, z, 24);
        Map<Long, String> aThread = new ConcurrentHashMap<>();
        Map<Long, String> bThread = new ConcurrentHashMap<>();
        // A little work per pig, so that one region thread cannot finish both groups before the other one starts.
        for (Pig p : a) ACTIONS.put(p.getUUID(), e -> {
            aThread.put(level.getGameTime(), Thread.currentThread().getName());
            busy(150_000L);
        });
        for (Pig p : b) ACTIONS.put(p.getUUID(), e -> {
            bThread.put(level.getGameTime(), Thread.currentThread().getName());
            busy(150_000L);
        });
        List<Pig> all = new ArrayList<>(a);
        all.addAll(b);
        helper.succeedWhen(() -> {
            boolean split = false;
            for (Map.Entry<Long, String> e : aThread.entrySet()) {
                String other = bThread.get(e.getKey());
                if (other != null && e.getValue().contains("region") && other.contains("region") && !e.getValue().equals(other)) {
                    split = true;
                    break;
                }
            }
            helper.assertTrue(split, "the two groups never ticked on two region threads in the same tick; group A on "
                    + aThread.values().stream().distinct().toList() + ", group B on " + bThread.values().stream().distinct().toList());
            assertRegionsStillOn(helper);
            String status = RegionTicker.lastSplits().getOrDefault("minecraft:overworld", "");
            helper.assertTrue(status.contains("regions"), "status does not show the split: " + status);
            cleanUp(level, all, new int[][] {{ax, z}, {bx, z}});
        });
    }

    private static void busy(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    @GameTest(template = "empty", batch = "regions_changes", timeoutTicks = 300)
    public static void spawnsRemovalsAndMovesOnRegionThreadsAreApplied(GameTestHelper helper) {
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int ax = o.getX() + 5000, bx = o.getX() - 5000, z = o.getZ() + 2000;
        List<Pig> a = pigs(level, ax, z, 24);
        List<Pig> b = pigs(level, bx, z, 24);
        Pig victim = a.get(0);
        Pig mover = a.get(1);
        Pig spawner = b.get(0);
        AtomicReference<String> victimSeen = new AtomicReference<>();
        AtomicBoolean moved = new AtomicBoolean();
        AtomicReference<BlockPos> movedTo = new AtomicReference<>();
        AtomicInteger spawned = new AtomicInteger();
        List<ItemEntity> items = new java.util.concurrent.CopyOnWriteArrayList<>();

        ACTIONS.put(victim.getUUID(), e -> {
            if (!onRegionThread() || victimSeen.get() != null) return;
            e.discard();
            // Same tick, same region thread: it must already be gone for queries.
            boolean byId = level.getEntity(e.getId()) == null;
            boolean byBox = !level.getEntitiesOfClass(Pig.class, e.getBoundingBox().inflate(1)).contains(e);
            victimSeen.set(byId && byBox ? "gone" : "still visible (by id: " + !byId + ", by box: " + !byBox + ")");
        });
        ACTIONS.put(mover.getUUID(), e -> {
            if (!onRegionThread() || !moved.compareAndSet(false, true)) return;
            e.setPos(e.getX() + 20, e.getY(), e.getZ()); // into the next chunk: a new entity section
            movedTo.set(e.blockPosition());
        });
        ACTIONS.put(spawner.getUUID(), e -> {
            if (!onRegionThread() || spawned.get() >= 5) return;
            ItemEntity item = new ItemEntity(level, e.getX(), e.getY() + 2, e.getZ(), new ItemStack(Items.DIAMOND));
            item.setNoGravity(true);
            item.setNeverPickUp();
            item.setUnlimitedLifetime();
            level.addFreshEntity(item);
            items.add(item);
            spawned.incrementAndGet();
        });
        List<Entity> all = new ArrayList<>(a);
        all.addAll(b);
        helper.succeedWhen(() -> {
            helper.assertTrue("gone".equals(victimSeen.get()), "removed pig: " + victimSeen.get());
            helper.assertTrue(victim.isRemoved() && level.getEntity(victim.getId()) == null, "removed pig is still in the world");
            helper.assertTrue(movedTo.get() != null, "the moving pig has not moved yet");
            BlockPos to = movedTo.get();
            helper.assertTrue(level.getEntitiesOfClass(Pig.class, new AABB(to).inflate(2)).contains(mover),
                    "the moved pig is not found at its new place " + to);
            int before = mover.tickCount;
            helper.assertTrue(spawned.get() == 5, "spawned " + spawned.get() + " of 5 items");
            for (ItemEntity item : items) {
                helper.assertTrue(level.getEntity(item.getId()) == item, "an item spawned on a region thread is not in the world");
            }
            assertRegionsStillOn(helper);
            all.addAll(items);
            cleanUp(level, all, new int[][] {{ax, z}, {bx, z}});
            helper.assertTrue(before > 0, "the moved pig stopped ticking");
        });
    }

    @GameTest(template = "empty", batch = "regions_blockentities", timeoutTicks = 400)
    public static void hoppersInFarApartRegionsAllWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int[][] areas = {{o.getX() + 7000, o.getZ() - 2000}, {o.getX() - 7000, o.getZ() - 2000}};
        List<BlockPos> chests = new ArrayList<>();
        List<BlockPos> placed = new ArrayList<>();
        for (int[] area : areas) {
            forceArea(level, area[0], area[1], true);
            // 40 mob spawners (enough block entities to split; unlike idle hoppers, Lithium never puts them to sleep, and
            // with no player near they spawn nothing) and a 5-hopper chain into a chest.
            for (int i = 0; i < 40; i++) {
                BlockPos p = new BlockPos(area[0] - 8 + (i % 8), 150, area[1] - 8 + (i / 8));
                level.setBlock(p, Blocks.SPAWNER.defaultBlockState(), 3);
                placed.add(p);
            }
            BlockPos start = new BlockPos(area[0], 152, area[1]);
            for (int i = 0; i < 5; i++) {
                BlockPos p = start.east(i);
                level.setBlock(p, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST), 3);
                placed.add(p);
            }
            BlockPos chest = start.east(5);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            placed.add(chest);
            chests.add(chest);
            if (level.getBlockEntity(start) instanceof HopperBlockEntity hopper) {
                hopper.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
            }
        }
        helper.succeedWhen(() -> {
            for (BlockPos chest : chests) {
                helper.assertTrue(level.getBlockEntity(chest) instanceof ChestBlockEntity c && !c.getItem(0).isEmpty()
                        && c.getItem(0).getCount() == 3, "items did not reach the chest at " + chest);
            }
            String status = RegionTicker.lastSplits().getOrDefault("minecraft:overworld", "");
            helper.assertTrue(status.matches(".*block entities: \\d+ in ([2-9]|\\d\\d+) regions.*"), "block entities were not split: " + status);
            assertRegionsStillOn(helper);
            for (BlockPos p : placed) {
                level.removeBlock(p, false);
            }
            for (int[] area : areas) {
                forceArea(level, area[0], area[1], false);
            }
        });
    }

    @GameTest(template = "empty", batch = "regions_chunkload", timeoutTicks = 600)
    public static void regionThreadCanLoadAChunk(GameTestHelper helper) {
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int ax = o.getX() + 9000, bx = o.getX() - 9000, z = o.getZ() + 4000;
        List<Pig> a = pigs(level, ax, z, 24);
        List<Pig> b = pigs(level, bx, z, 24);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicBoolean asked = new AtomicBoolean();
        ACTIONS.put(a.get(0).getUUID(), e -> {
            if (!onRegionThread() || !asked.compareAndSet(false, true)) return;
            // A chunk nobody has loaded: the region thread waits while its dimension thread loads it.
            int cx = (ax >> 4) + 64;
            ChunkAccess chunk = level.getChunk(cx, z >> 4);
            result.set(chunk != null && chunk.getPos().x == cx ? "ok on " + Thread.currentThread().getName() : "got " + chunk);
        });
        List<Pig> all = new ArrayList<>(a);
        all.addAll(b);
        helper.succeedWhen(() -> {
            helper.assertTrue(result.get() != null, "the chunk has not been loaded yet");
            helper.assertTrue(result.get().startsWith("ok"), "chunk load from a region thread: " + result.get());
            assertRegionsStillOn(helper);
            cleanUp(level, all, new int[][] {{ax, z}, {bx, z}});
        });
    }

    /**
     * Like a Create stock ticker or factory gauge pulling from a vault on the far side of the world: a pig in region A
     * takes items out of a chest in region B every tick, while the 24 pigs of region B keep putting items into it at the
     * same moment. The access from A must pause region B (see RegionTicker.crossRegion), so not one item is lost or
     * made up.
     */
    @GameTest(template = "empty", batch = "regions_crossregion", timeoutTicks = 400)
    public static void pullingFromAChestInAnotherRegionLosesNothing(GameTestHelper helper) {
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int ax = o.getX() + 11000, bx = o.getX() - 11000, z = o.getZ() - 4000;
        List<Pig> a = pigs(level, ax, z, 24);
        List<Pig> b = pigs(level, bx, z, 24);
        BlockPos chestPos = new BlockPos(bx, 150, z + 8);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger pulled = new AtomicInteger();
        AtomicInteger pullTicks = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        long pausesBefore = com.multicore.core.Stats.REGION_EXCLUSIVE.sum();

        for (Pig p : b) {
            ACTIONS.put(p.getUUID(), e -> {
                if (stop.get() || !onRegionThread() || !(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) return;
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    ItemStack stack = chest.getItem(slot);
                    if (stack.isEmpty()) {
                        chest.setItem(slot, new ItemStack(Items.COBBLESTONE));
                    } else if (stack.getCount() < stack.getMaxStackSize()) {
                        stack.grow(1);
                        chest.setChanged();
                    } else {
                        continue;
                    }
                    inserted.incrementAndGet();
                    return;
                }
            });
        }
        ACTIONS.put(a.get(0).getUUID(), e -> {
            if (stop.get() || !onRegionThread()) return;
            if (pullTicks.incrementAndGet() >= 120) {
                stop.set(true);
            }
            if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) return; // another region: pauses region B
            for (int n = 0; n < 24; n++) {
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    if (!chest.getItem(slot).isEmpty()) {
                        chest.removeItem(slot, 1);
                        pulled.incrementAndGet();
                        break;
                    }
                }
            }
        });
        List<Pig> all = new ArrayList<>(a);
        all.addAll(b);
        helper.succeedWhen(() -> {
            helper.assertTrue(stop.get(), "still running (" + pullTicks.get() + " ticks)");
            int inChest = 0;
            if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
                for (int slot = 0; slot < chest.getContainerSize(); slot++) inChest += chest.getItem(slot).getCount();
            }
            helper.assertTrue(inserted.get() > 500, "only " + inserted.get() + " items were put in");
            helper.assertTrue(pulled.get() > 100, "only " + pulled.get() + " items were taken out");
            helper.assertTrue(inChest == inserted.get() - pulled.get(), "items lost or made up: put in " + inserted.get()
                    + ", taken out " + pulled.get() + ", in the chest " + inChest);
            long pauses = com.multicore.core.Stats.REGION_EXCLUSIVE.sum() - pausesBefore;
            helper.assertTrue(pauses > 0, "the access from the other region never paused region B");
            assertRegionsStillOn(helper);
            level.removeBlock(chestPos, false);
            cleanUp(level, all, new int[][] {{ax, z}, {bx, z}});
        });
    }
}
