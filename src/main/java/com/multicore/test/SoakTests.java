package com.multicore.test;

import com.multicore.MultiCore;
import com.multicore.core.LevelThread;
import com.multicore.core.ParallelTicker;
import com.multicore.core.RegionScaler;
import com.multicore.core.RegionTicker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Soak test: {@code -PsoakRounds=N} adds N rounds. Each round is one batch, so its tests run at the same time:
 * a Create train driving from one region into another, a Create logistics request across regions, Create machines in
 * many regions, and the chaos test below. Off (no rounds) unless the property is set.
 * <p>
 * The chaos test, for {@link #ROUND_TICKS} ticks: four groups of pigs far apart in the overworld switch their busy work
 * on and off in turn (so region threads are added and given back, see RegionScaler), far chunks are force-loaded and
 * released again in the overworld and the nether all the time, items go round in a circle overworld region A -> region B
 * -> nether -> region A on three different threads, and a player with a real (embedded) connection jumps between
 * worlds and regions. At the end: not one item lost or made up, region threading and parallel ticking still on, the
 * thread count of the overworld changed, and the player is still online and alive.
 */
@GameTestHolder(MultiCore.MOD_ID)
public final class SoakTests {
    private static final int ROUND_TICKS = 1600;
    private static final int ITEMS = 640;

    private SoakTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> soakRounds() {
        int rounds = Integer.getInteger("multicore.soakRounds", 0);
        List<TestFunction> out = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            String batch = String.format("soak_%03d", r);
            int round = r;
            out.add(new TestFunction(batch, "soak_train_" + r, "multicore:empty", 2400, 0L, true,
                    h -> CreateScenarios.trainDrivesFromOneRegionIntoAnother(h)));
            out.add(new TestFunction(batch, "soak_logistics_" + r, "multicore:empty", 800, 0L, true,
                    h -> CreateScenarios.packageRequestFromAnotherRegion(h)));
            out.add(new TestFunction(batch, "soak_machines_" + r, "multicore:empty", 1200, 0L, true,
                    h -> CreateScenarios.machinesInManyRegions(h)));
            out.add(new TestFunction(batch, "soak_chaos_" + r, "multicore:empty", ROUND_TICKS + 400, 0L, true,
                    h -> chaos(h, round)));
        }
        return out;
    }

    private static void force(ServerLevel level, int x, int z, boolean on) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced((x >> 4) + dx, (z >> 4) + dz, on);
            }
        }
    }

    private static List<Pig> group(ServerLevel level, int x, int z, int count) {
        force(level, x, z, true);
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

    private static void answerKeepAlive(ServerPlayer player) {
        try {
            java.lang.reflect.Field f = net.minecraft.server.network.ServerCommonPacketListenerImpl.class.getDeclaredField("keepAlivePending");
            f.setAccessible(true);
            f.setBoolean(player.connection, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void busy(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }

    private static int count(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
        return n;
    }

    /** Moves one item from {@code from} to {@code to}; false if {@code from} is empty or {@code to} is full. */
    private static boolean moveOne(Container from, Container to) {
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack s = from.getItem(i);
            if (s.isEmpty()) continue;
            for (int j = 0; j < to.getContainerSize(); j++) {
                ItemStack t = to.getItem(j);
                if (t.isEmpty()) {
                    to.setItem(j, s.split(1));
                    from.setChanged();
                    to.setChanged();
                    return true;
                }
                if (ItemStack.isSameItemSameComponents(t, s) && t.getCount() < t.getMaxStackSize()) {
                    s.shrink(1);
                    t.grow(1);
                    from.setChanged();
                    to.setChanged();
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static void chaos(GameTestHelper helper, int round) {
        ServerLevel over = helper.getLevel();
        ServerLevel nether = over.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether");
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        Random random = new Random(round * 7919L + o.asLong());
        int base = 20_000 + (round % 20) * 3_000;
        RegionScaler scaler = RegionScaler.get();
        scaler.override(new RegionScaler.Settings(true, 8.0, 4.0, 1, 8, 2, 3, 10));
        String dim = over.dimension().location().toString();

        // Four groups far apart; only some of them work at any time.
        int[][] spots = {{o.getX() + base, o.getZ()}, {o.getX() - base, o.getZ()}, {o.getX(), o.getZ() + base}, {o.getX(), o.getZ() - base}};
        List<List<Pig>> groups = new ArrayList<>();
        for (int[] s : spots) groups.add(group(over, s[0], s[1], 24));
        int nx = (o.getX() >> 3) + 600, nz = (o.getZ() >> 3) - 600;
        List<Pig> netherGroup = group(nether, nx, nz, 12);
        boolean[] working = new boolean[spots.length];

        // The item circle: chest A (group 0) -> chest B (group 2) -> nether chest -> chest A.
        BlockPos a = new BlockPos(spots[0][0], 150, spots[0][1] + 10);
        BlockPos b = new BlockPos(spots[2][0], 150, spots[2][1] + 10);
        BlockPos c = new BlockPos(nx, 150, nz + 10);
        over.setBlock(a, Blocks.CHEST.defaultBlockState(), 3);
        over.setBlock(b, Blocks.CHEST.defaultBlockState(), 3);
        nether.setBlock(c, Blocks.CHEST.defaultBlockState(), 3);
        if (over.getBlockEntity(a) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < ITEMS / 64; i++) chest.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        AtomicInteger moves = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        Map<UUID, Consumer<Entity>> actions = new ConcurrentHashMap<>();
        // On group 0's thread: A -> B (another region). On group 2's thread: B -> nether (another dimension).
        // On the nether's thread: nether -> A (another dimension).
        actions.put(groups.get(0).get(0).getUUID(), e -> {
            if (!stop.get() && over.getBlockEntity(a) instanceof ChestBlockEntity ca && over.getBlockEntity(b) instanceof ChestBlockEntity cb) {
                for (int k = 0; k < 4; k++) if (moveOne(ca, cb)) moves.incrementAndGet();
            }
        });
        actions.put(groups.get(2).get(0).getUUID(), e -> {
            if (!stop.get() && over.getBlockEntity(b) instanceof ChestBlockEntity cb && nether.getBlockEntity(c) instanceof ChestBlockEntity cc) {
                for (int k = 0; k < 4; k++) if (moveOne(cb, cc)) moves.incrementAndGet();
            }
        });
        actions.put(netherGroup.get(0).getUUID(), e -> {
            if (!stop.get() && nether.getBlockEntity(c) instanceof ChestBlockEntity cc && over.getBlockEntity(a) instanceof ChestBlockEntity ca) {
                for (int k = 0; k < 4; k++) if (moveOne(cc, ca)) moves.incrementAndGet();
            }
        });
        Set<UUID> busyIds = ConcurrentHashMap.newKeySet();
        Map<UUID, Integer> groupOf = new ConcurrentHashMap<>();
        for (int g = 0; g < groups.size(); g++) for (Pig p : groups.get(g)) groupOf.put(p.getUUID(), g);
        Map<String, Integer> actionThreads = new ConcurrentHashMap<>();
        Object listener = new Object() {
            @SubscribeEvent
            public void tick(EntityTickEvent.Pre e) {
                UUID id = e.getEntity().getUUID();
                Consumer<Entity> action = actions.get(id);
                if (action != null) {
                    actionThreads.merge(Thread.currentThread().getName(), 1, Integer::sum);
                    action.accept(e.getEntity());
                }
                Integer g = groupOf.get(id);
                if (g != null && working[g]) busy(250_000L);
            }
        };
        NeoForge.EVENT_BUS.register(listener);

        // A player with a connection that ticks like a real one, jumping between worlds and regions.
        ServerPlayer player = PlayerTests.join(over, "mcrtSoak" + round, new BlockPos(spots[1][0], 120, spots[1][1]));
        PlayerTests.ONLINE.remove(player);
        net.minecraft.network.Connection connection = player.connection.getConnection();
        List<net.minecraft.network.Connection> connections = ((com.multicore.mixin.test.ServerConnectionListenerAccessor) over.getServer().getConnection())
                .multicore$connections();
        connections.add(connection);

        Set<Integer> threadCounts = ConcurrentHashMap.newKeySet();
        List<int[]> churn = new ArrayList<>();
        AtomicInteger ticks = new AtomicInteger();
        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanUp = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            NeoForge.EVENT_BUS.unregister(listener);
            connections.remove(connection);
            PlayerTests.leave(player);
            for (List<Pig> g : groups) g.forEach(Entity::discard);
            netherGroup.forEach(Entity::discard);
            for (int[] s : spots) force(over, s[0], s[1], false);
            force(nether, nx, nz, false);
            for (int[] ch : churn) force(ch[2] == 0 ? over : nether, ch[0], ch[1], false);
            over.removeBlock(a, false);
            over.removeBlock(b, false);
            nether.removeBlock(c, false);
            scaler.clearOverride();
        };
        helper.onEachTick(() -> {
            int t = ticks.incrementAndGet();
            threadCounts.add(scaler.threads(dim));
            if (t >= ROUND_TICKS) {
                stop.set(true);
                return;
            }
            // Every 150 ticks a different pair of groups works.
            if (t % 150 == 1) {
                int phase = (t / 150) % 4;
                for (int g = 0; g < working.length; g++) working[g] = phase == 3 ? false : (g == phase || g == (phase + 1) % 4);
            }
            // Chunk churn: a new far area every 40 ticks (overworld or nether), the oldest one released.
            if (t % 40 == 0) {
                int w = random.nextInt(2);
                int x = (random.nextBoolean() ? 1 : -1) * (5_000 + random.nextInt(40_000)) >> (w == 1 ? 3 : 0);
                int z = (random.nextBoolean() ? 1 : -1) * (5_000 + random.nextInt(40_000)) >> (w == 1 ? 3 : 0);
                force(w == 0 ? over : nether, x, z, true);
                churn.add(new int[] {x, z, w});
                if (churn.size() > 6) {
                    int[] old = churn.remove(0);
                    force(old[2] == 0 ? over : nether, old[0], old[1], false);
                }
            }
            // The player jumps every 100 ticks: another region, or the other dimension.
            if (t % 100 == 50 && !player.isRemoved()) {
                int[] s = spots[random.nextInt(spots.length)];
                if (random.nextInt(3) == 0 && player.level() == over) {
                    nether.setBlock(new BlockPos(nx + 3, 150, nz + 3), Blocks.STONE.defaultBlockState(), 3);
                    player.teleportTo(nether, nx + 3.5, 151, nz + 3.5, 0, 0);
                } else {
                    over.setBlock(new BlockPos(s[0] + 3, 150, s[1] + 3), Blocks.STONE.defaultBlockState(), 3);
                    player.teleportTo(over, s[0] + 3.5, 151, s[1] + 3.5, 0, 0);
                }
            }
            // A real client answers the keep-alive checks; this one has nobody on the other end.
            if (t % 10 == 0) answerKeepAlive(player);
        });
        // The verdict is taken once, 40 ticks after the circle stopped, then everything is cleaned up.
        java.util.concurrent.atomic.AtomicReference<String> verdict = new java.util.concurrent.atomic.AtomicReference<>();
        helper.runAfterDelay(ROUND_TICKS + 40, () -> {
            List<String> problems = new ArrayList<>();
            int total = 0;
            if (over.getBlockEntity(a) instanceof Container ca) total += count(ca); else problems.add("chest A is gone");
            if (over.getBlockEntity(b) instanceof Container cb) total += count(cb); else problems.add("chest B is gone");
            if (nether.getBlockEntity(c) instanceof Container cc) total += count(cc); else problems.add("the nether chest is gone");
            if (total != ITEMS) problems.add("items lost or made up: " + total + " instead of " + ITEMS + " after " + moves.get() + " moves");
            if (moves.get() <= 300) problems.add("only " + moves.get() + " items moved");
            int onTickThreads = actionThreads.entrySet().stream().filter(e -> e.getKey().startsWith("MultiCore")).mapToInt(Map.Entry::getValue).sum();
            if (onTickThreads <= 1000) problems.add("the item circle did not run on tick threads: " + actionThreads);
            if (!RegionTicker.enabled()) problems.add("region threading switched itself off: " + RegionTicker.disabledReason());
            if (!ParallelTicker.get().isActive()) problems.add("MultiCore switched itself off: " + ParallelTicker.get().disabledReason());
            if (threadCounts.size() < 2) problems.add("the overworld's region threads never changed: " + threadCounts);
            if (player.server.getPlayerList().getPlayer(player.getUUID()) == null) problems.add("the player is not online any more");
            verdict.set(problems.isEmpty() ? "ok" : String.join("; ", problems));
            cleanUp.run();
        });
        helper.succeedWhen(() -> {
            String v = verdict.get();
            helper.assertTrue(v != null, "still running (" + ticks.get() + " ticks)");
            helper.assertTrue(v.equals("ok"), v);
        });
    }
}
