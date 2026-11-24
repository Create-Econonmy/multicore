package com.multicore.test;

import com.multicore.core.ParallelTicker;
import com.multicore.core.RegionThread;
import com.multicore.core.RegionTicker;
import com.multicore.core.Stats;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** See {@link CreateTests}. Only loaded when Create is installed. */
final class CreateScenarios {
    private static final Map<UUID, Consumer<Entity>> ACTIONS = new ConcurrentHashMap<>();
    /** Threads Create's train carriages ticked on. */
    private static final Map<String, Boolean> CARRIAGE_THREADS = new ConcurrentHashMap<>();
    private static final AtomicBoolean LISTENING = new AtomicBoolean();

    private CreateScenarios() {
    }

    private static void listen() {
        if (LISTENING.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(EntityTickEvent.Pre.class, e -> {
                if (e.getEntity() instanceof CarriageContraptionEntity) {
                    CARRIAGE_THREADS.put(Thread.currentThread().getName(), true);
                }
                Consumer<Entity> action = ACTIONS.get(e.getEntity().getUUID());
                if (action != null) action.accept(e.getEntity());
            });
        }
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, T value) {
        return state.hasProperty(property) ? state.setValue(property, value) : state;
    }

    private static void force(ServerLevel level, int x, int z, boolean on) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced((x >> 4) + dx, (z >> 4) + dz, on);
                if (on) level.getChunk((x >> 4) + dx, (z >> 4) + dz);
            }
        }
    }

    private static List<Pig> crowd(ServerLevel level, int x, int y, int z) {
        List<Pig> out = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Pig pig = EntityType.PIG.create(level);
            pig.setNoAi(true);
            pig.setNoGravity(true);
            pig.setInvulnerable(true);
            pig.moveTo(x + (i % 6), y + 10, z + 4 + (i / 6), 0, 0);
            level.addFreshEntity(pig);
            out.add(pig);
        }
        return out;
    }

    private static void assertStillOn(GameTestHelper helper) {
        helper.assertTrue(RegionTicker.enabled(), "region threading switched itself off: " + RegionTicker.disabledReason());
        helper.assertTrue(ParallelTicker.get().isActive(), "MultiCore switched itself off: " + ParallelTicker.get().disabledReason());
    }

    // ---- logistics ----

    /**
     * Region B: a chest with 128 iron ingots, a packager on top of it and a stock link on the packager, all on one network.
     * Region A, thousands of blocks away: a pig's tick (on a region thread) sends the network a request for 16 iron, the
     * call a stock ticker or a factory gauge makes. The packager in region B must pack exactly those 16.
     */
    static void packageRequestFromAnotherRegion(GameTestHelper helper) {
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int y = 150;
        int ax = o.getX() + 23000, bx = o.getX() - 23000, z = o.getZ() + 7000;
        force(level, ax, z, true);
        force(level, bx, z, true);
        List<Pig> pigs = new ArrayList<>(crowd(level, ax, y, z));
        pigs.addAll(crowd(level, bx, y, z));

        BlockPos chestPos = new BlockPos(bx, y, z);
        BlockPos packagerPos = chestPos.above();
        BlockPos linkPos = packagerPos.above();
        level.setBlock(chestPos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        chest.setItem(1, new ItemStack(Items.IRON_INGOT, 64));
        level.setBlock(packagerPos, with(block("create:packager").defaultBlockState(), BlockStateProperties.FACING, Direction.UP), 3);
        BlockState link = block("create:stock_link").defaultBlockState();
        link = with(link, BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
        link = with(link, BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        level.setBlock(linkPos, link, 3);
        UUID network = UUID.nameUUIDFromBytes("multicore-region-test".getBytes());
        PackagerLinkBlockEntity linkBe = (PackagerLinkBlockEntity) level.getBlockEntity(linkPos);
        linkBe.behaviour.freqId = network;

        AtomicReference<String> request = new AtomicReference<>();
        AtomicInteger ticks = new AtomicInteger();
        long pausesBefore = Stats.REGION_EXCLUSIVE.sum();
        ACTIONS.put(pigs.get(0).getUUID(), e -> {
            // Wait for the link to join its network (it registers on its first ticks), then ask once.
            if (request.get() != null || !(Thread.currentThread() instanceof RegionThread) || ticks.incrementAndGet() < 60) return;
            boolean sent = LogisticsManager.broadcastPackageRequest(network, LogisticallyLinkedBehaviour.RequestType.PLAYER,
                    PackageOrderWithCrafts.simple(List.of(new BigItemStack(new ItemStack(Items.IRON_INGOT), 16))), null, "multicore-test");
            request.set((sent ? "sent" : "not sent") + " on " + Thread.currentThread().getName());
        });

        helper.succeedWhen(() -> {
            PackagerBlockEntity packager = (PackagerBlockEntity) level.getBlockEntity(packagerPos);
            helper.assertTrue(request.get() != null, "no request yet (link on network: "
                    + !LogisticallyLinkedBehaviour.getAllPresent(network, false).isEmpty() + ")");
            helper.assertTrue(request.get().startsWith("sent"), "request " + request.get() + "; packager sees an inventory: "
                    + (packager != null && packager.targetInventory.hasInventory()));
            int left = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).is(Items.IRON_INGOT)) left += chest.getItem(slot).getCount();
            }
            boolean packed = packager != null && (!packager.heldBox.isEmpty() || !packager.queuedExitingPackages.isEmpty());
            helper.assertTrue(left == 128 - 16, "the chest has " + left + " iron left, expected 112 (" + request.get() + ")");
            helper.assertTrue(packed, "the packager has not packed the order yet");
            helper.assertTrue(Stats.REGION_EXCLUSIVE.sum() > pausesBefore, "the request from the other region never paused region B");
            assertStillOn(helper);
            ACTIONS.remove(pigs.get(0).getUUID());
            pigs.forEach(Entity::discard);
            for (BlockPos p : List.of(linkPos, packagerPos, chestPos)) level.removeBlock(p, false);
            force(level, ax, z, false);
            force(level, bx, z, false);
        });
    }

    private static String receiverState(net.minecraft.world.level.block.entity.BlockEntity be, ServerLevel level) {
        try {
            java.lang.reflect.Field link = be.getClass().getDeclaredField("link");
            link.setAccessible(true);
            java.lang.reflect.Field init = com.simibubi.create.foundation.blockEntity.SmartBlockEntity.class.getDeclaredField("initialized");
            init.setAccessible(true);
            return "[link=" + link.get(be) + ", initialized=" + init.get(be) + ", removed=" + be.isRemoved()
                    + ", loaded=" + level.isLoaded(be.getBlockPos()) + ", sameBE=" + (level.getBlockEntity(be.getBlockPos()) == be) + "]";
        } catch (ReflectiveOperationException e) {
            return "[" + e + "]";
        }
    }

    /** The station's last assembly error (a protected field), for the test log. */
    private static String assemblyProblem(StationBlockEntity station) {
        try {
            java.lang.reflect.Field f = StationBlockEntity.class.getDeclaredField("lastException");
            f.setAccessible(true);
            AssemblyException ex = (AssemblyException) f.get(station);
            return ex == null ? "no exception" : ex.component.getString();
        } catch (ReflectiveOperationException e) {
            return "unknown (" + e + ")";
        }
    }

    // ---- machines that run without the mod lock ----

    /**
     * Four regions far apart, each with a crowd (so the dimension is split) and a creative motor driving a line of shafts,
     * plus a redstone link transmitter powered by a redstone block and a receiver three blocks away. All four share Create's
     * stress networks and redstone link handler. Every shaft must reach the motor's speed, every receiver must be powered,
     * and none of the shafts' ticks may have taken the mod lock (Create's machines run in parallel; only the shared
     * managers lock).
     */
    static void machinesInManyRegions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int y = 150, count = 4, shafts = 6;
        long locksBefore = com.multicore.core.LockSource.acquisitionsOf("block entity create:shaft")
                + com.multicore.core.LockSource.acquisitionsOf("block entity create:creative_motor")
                + com.multicore.core.LockSource.acquisitionsOf("block entity create:redstone_link");
        List<Pig> pigs = new ArrayList<>();
        List<List<BlockPos>> shaftPositions = new ArrayList<>();
        List<BlockPos> receivers = new ArrayList<>();
        List<int[]> spots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int x = o.getX() + 31000 + i * 5000, z = o.getZ() + 12000 - i * 3000;
            spots.add(new int[]{x, z});
            force(level, x, z, true);
            pigs.addAll(crowd(level, x, y, z));
            int mz = z - 8;
            for (int dx = -1; dx <= shafts + 1; dx++) level.setBlock(new BlockPos(x + dx, y - 1, mz), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, y, mz), with(block("create:creative_motor").defaultBlockState(), BlockStateProperties.FACING, Direction.EAST), 3);
            List<BlockPos> line = new ArrayList<>();
            for (int s = 1; s <= shafts; s++) {
                BlockPos p = new BlockPos(x + s, y, mz);
                level.setBlock(p, with(block("create:shaft").defaultBlockState(), BlockStateProperties.AXIS, Direction.Axis.X), 3);
                line.add(p);
            }
            shaftPositions.add(line);
            int lz = z - 14;
            for (int dz = -1; dz <= 5; dz++) level.setBlock(new BlockPos(x, y - 1, lz + dz), Blocks.STONE.defaultBlockState(), 3);
            BlockState link = with(block("create:redstone_link").defaultBlockState(), BlockStateProperties.FACING, Direction.UP);
            level.setBlock(new BlockPos(x, y, lz), link, 3);
            // After the link: its neighbour update schedules the tick that makes it transmit.
            level.setBlock(new BlockPos(x + 1, y, lz), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            BlockPos receiver = new BlockPos(x, y, lz + 3);
            // Placed as a transmitter and switched after it has ticked, like a player with a wrench (a link that never
            // ticked as a transmitter has no link behaviour to switch).
            level.setBlock(receiver, link, 3);
            helper.runAfterDelay(60, () -> ((com.simibubi.create.content.redstone.link.RedstoneLinkBlock) link.getBlock())
                    .toggleMode(level.getBlockState(receiver), level, receiver));
            receivers.add(receiver);
        }
        helper.succeedWhen(() -> {
            for (int i = 0; i < count; i++) {
                for (BlockPos p : shaftPositions.get(i)) {
                    var be = level.getBlockEntity(p);
                    helper.assertTrue(be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity, "no shaft at " + p);
                    float speed = ((com.simibubi.create.content.kinetics.base.KineticBlockEntity) be).getSpeed();
                    helper.assertTrue(Math.abs(speed) > 0.5F, "the shaft at " + p + " does not turn (speed " + speed + ")");
                }
                var receiver = level.getBlockEntity(receivers.get(i));
                var transmitterPos = receivers.get(i).south(-3);
                var transmitter = level.getBlockEntity(transmitterPos);
                helper.assertTrue(receiver instanceof com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity link
                        && link.getReceivedSignal() > 0, "the receiver at " + receivers.get(i) + " is not powered (region " + i
                        + "; transmitter transmits "
                        + (transmitter instanceof com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity t ? t.getSignal() + "" : "no BE")
                        + "; networks "
                        + com.simibubi.create.Create.REDSTONE_LINK_NETWORK_HANDLER.networksIn(level).values().stream().map(set -> set.stream().map(l -> l.getLocation().toShortString() + (l.isListening() ? "R" : "T")).toList().toString()).toList()
                        + ", receiver block " + level.getBlockState(receivers.get(i)) + " " + receiverState(receiver, level)
                        + ", block " + level.getBlockState(transmitterPos) + ", redstone block "
                        + level.getBlockState(transmitterPos.east()) + ", receiver BE " + receiver + ")");
            }
            long locksNow = com.multicore.core.LockSource.acquisitionsOf("block entity create:shaft")
                    + com.multicore.core.LockSource.acquisitionsOf("block entity create:creative_motor")
                    + com.multicore.core.LockSource.acquisitionsOf("block entity create:redstone_link");
            // The shared managers lock (TorquePropagator...), under their own names; the machines themselves must not.
            helper.assertTrue(locksNow == locksBefore, "the machines still take the mod lock (" + (locksNow - locksBefore) + " times)");
            assertStillOn(helper);
            pigs.forEach(Entity::discard);
            for (int i = 0; i < count; i++) force(level, spots.get(i)[0], spots.get(i)[1], false);
        });
    }

    // ---- trains ----

    /**
     * A straight track of 1200 blocks with a station at each end and one in the middle. A train (a small bogey with
     * controls on it) is assembled at the middle station by the station itself, then Create's navigation drives it to
     * whichever end station is ahead, 600 blocks away: out of its region and into the region of a crowd waiting there.
     */
    static void trainDrivesFromOneRegionIntoAnother(GameTestHelper helper) {
        listen();
        CARRIAGE_THREADS.clear();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int y = 150;
        int x0 = o.getX() + 26000, length = 1200, z = o.getZ() - 8000;
        int mid = x0 + length / 2;
        for (int x = x0 - 16; x <= x0 + length + 16; x += 16) {
            level.setChunkForced(x >> 4, z >> 4, true);
            level.setChunkForced(x >> 4, (z >> 4) + 1, true);
            level.getChunk(x >> 4, z >> 4);
        }
        List<Pig> pigs = new ArrayList<>(crowd(level, x0 + 4, y, z + 6));
        pigs.addAll(crowd(level, x0 + length - 10, y, z + 6));

        BlockState track = with(block("create:track").defaultBlockState(),
                com.simibubi.create.content.trains.track.TrackBlock.SHAPE, com.simibubi.create.content.trains.track.TrackShape.XO);
        for (int x = x0; x <= x0 + length; x++) {
            level.setBlock(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(new BlockPos(x, y, z), track, 3);
        }
        int[] stationX = {x0 + 20, mid, x0 + length - 20};
        BlockPos[] stations = new BlockPos[3];
        for (int i = 0; i < 3; i++) {
            stations[i] = new BlockPos(stationX[i], y, z + 1);
            level.setBlock(stations[i], block("create:track_station").defaultBlockState(), 3);
            StationBlockEntity station = (StationBlockEntity) level.getBlockEntity(stations[i]);
            CompoundTag tag = new CompoundTag();
            tag.put("TargetTrack", NbtUtils.writeBlockPos(new BlockPos(0, 0, -1)));
            tag.putBoolean("TargetDirection", true);
            station.edgePoint.read(tag, level.registryAccess(), false);
        }

        AtomicInteger phase = new AtomicInteger();
        AtomicReference<String> log = new AtomicReference<>("");
        AtomicReference<Train> trainRef = new AtomicReference<>();
        AtomicReference<GlobalStation> target = new AtomicReference<>();
        AtomicInteger waited = new AtomicInteger();
        AtomicBoolean flipped = new AtomicBoolean();

        helper.onEachTick(() -> {
            StationBlockEntity middle = (StationBlockEntity) level.getBlockEntity(stations[1]);
            if (middle == null) return;
            int t = waited.incrementAndGet();
            if (phase.get() == 0 && t > 40) {
                // The stations have found their track by now. Put the bogey and controls where the station assembles.
                GlobalStation gs = middle.getStation();
                if (gs == null) {
                    if (t % 100 == 0) log.set("middle station not on the track graph yet");
                    return;
                }
                Direction dir = middle.getAssemblyDirection();
                // The station's marker: the track block it targets. The frontmost bogey stands on top of it.
                BlockPos marker = middle.edgePoint.getGlobalPosition();
                // The station looks for bogeys from the block after its marker, in the assembly direction.
                BlockPos bogey = marker.relative(dir).above();
                level.setBlock(bogey, with(block("create:small_bogey").defaultBlockState(), BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X), 3);
                Direction controls = flipped.get() ? dir : dir.getOpposite();
                level.setBlock(bogey.above(), with(block("create:controls").defaultBlockState(), BlockStateProperties.HORIZONTAL_FACING, controls), 3);
                // Super glue over the bogey and the controls, as a player would apply it: the bogey only sticks to blocks
                // in front of and behind it.
                level.addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level,
                        new net.minecraft.world.phys.AABB(bogey).minmax(new net.minecraft.world.phys.AABB(bogey.above()))));
                log.set("marker " + marker + ", assembly direction " + dir + ", controls facing " + controls
                        + ", bogey block placed: " + level.getBlockState(bogey).getBlock() + ", under it: " + level.getBlockState(marker).getBlock());
                phase.set(1);
                return;
            }
            if (phase.get() == 1 && t > 60) {
                middle.tryEnterAssemblyMode();
                middle.refreshAssemblyInfo();
                middle.assemble(UUID.randomUUID());
                GlobalStation gs = middle.getStation();
                Train train = gs == null ? null : gs.getPresentTrain();
                if (train == null) {
                    String why = assemblyProblem(middle);
                    BlockPos marker = middle.edgePoint.getGlobalPosition();
                    BlockPos spot = marker.relative(middle.getAssemblyDirection()).above();
                    log.set(log.get() + "; at assembly the bogey spot holds " + level.getBlockState(spot).getBlock()
                            + " and the controls spot " + level.getBlockState(spot.above()).getBlock());
                    log.set(log.get() + "; assembly failed: " + why);
                    if (!flipped.get() && why.toLowerCase().contains("control")) {
                        flipped.set(true); // the other way round
                        waited.set(41);
                        phase.set(0);
                    } else {
                        phase.set(9);
                    }
                    return;
                }
                trainRef.set(train);
                log.set(log.get() + "; assembled " + train.name.getString());
                phase.set(2);
                return;
            }
            if (phase.get() == 2) {
                Train train = trainRef.get();
                for (int end : new int[] {2, 0}) {
                    StationBlockEntity station = (StationBlockEntity) level.getBlockEntity(stations[end]);
                    GlobalStation dest = station == null ? null : station.getStation();
                    if (dest == null) continue;
                    DiscoveredPath path = train.navigation.findPathTo(dest, Double.MAX_VALUE);
                    if (path != null) {
                        train.navigation.startNavigation(path);
                        target.set(dest);
                        log.set(log.get() + "; driving to the station at x " + stationX[end]);
                        phase.set(3);
                        return;
                    }
                }
                log.set(log.get() + "; no path to either end");
                phase.set(9);
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(phase.get() != 9, "train: " + log.get());
            helper.assertTrue(phase.get() >= 3, "train not driving yet: " + log.get());
            Train train = trainRef.get();
            GlobalStation dest = target.get();
            helper.assertTrue(!train.derailed, "the train derailed: " + log.get());
            helper.assertTrue(train.getCurrentStation() == dest, "the train has not arrived yet (navigation active: "
                    + train.navigation.isActive() + ", speed " + String.format("%.2f", train.speed) + "): " + log.get());
            helper.assertTrue(CARRIAGE_THREADS.keySet().stream().anyMatch(n -> n.contains("region")),
                    "the carriage never ticked on a region thread: " + CARRIAGE_THREADS.keySet());
            assertStillOn(helper);
            // Clean up: the train goes away with its carriage entity, then the blocks and chunks.
            train.invalid = true;
            train.carriages.forEach(c -> c.forEachPresentEntity(Entity::discard));
            pigs.forEach(Entity::discard);
            for (BlockPos s : stations) level.removeBlock(s, false);
            for (int x = x0; x <= x0 + length; x++) {
                level.removeBlock(new BlockPos(x, y + 2, z), false);
                level.removeBlock(new BlockPos(x, y + 1, z), false);
                level.removeBlock(new BlockPos(x, y, z), false);
            }
            for (int x = x0 - 16; x <= x0 + length + 16; x += 16) {
                level.setChunkForced(x >> 4, z >> 4, false);
                level.setChunkForced(x >> 4, (z >> 4) + 1, false);
            }
        });
    }
}
