package com.multicore.test;

import com.mojang.authlib.GameProfile;
import com.multicore.MultiCore;
import com.multicore.core.ParallelTicker;
import com.multicore.core.RegionTicker;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What players do, with region threads on: simulated players (real ServerPlayers on a test connection, joined through
 * the player list like a real login) in two regions thousands of blocks apart in the same world. They use the pack's own
 * commands (Hub Essentials /tpa and /home, Create Economy Money /pay), chests, blocks, die on a region thread and respawn,
 * and ride a minecart from one region into the other. Commands run as the player, the way a typed command does.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlayerTests {
    /** Last thread each simulated player ticked on. */
    private static final Map<UUID, String> TICKED_ON = new ConcurrentHashMap<>();
    private static final AtomicBoolean LISTENING = new AtomicBoolean();
    /** Simulated players online; their connection's part of the tick is run for them (see {@link #listen}). */
    private static final List<ServerPlayer> ONLINE = new CopyOnWriteArrayList<>();
    /** Where each simulated player belongs right after login, and until which server tick it is held there. */
    private static final Map<UUID, BlockPos> SPOT = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> HOLD_UNTIL = new ConcurrentHashMap<>();

    private PlayerTests() {
    }

    private static void listen() {
        if (LISTENING.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(EntityTickEvent.Pre.class, e -> {
                if (e.getEntity() instanceof ServerPlayer p && p.getGameProfile().getName().startsWith("mcrt")) {
                    TICKED_ON.put(p.getUUID(), Thread.currentThread().getName());
                }
            });
            // A real player's connection runs player.doTick() (movement, fluids, damage, death) on the main thread once per
            // tick, after the dimensions; simulated players have no network ticking, so it is done for them here.
            NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> {
                int now = e.getServer().getTickCount();
                for (ServerPlayer p : ONLINE) {
                    if (p.hasDisconnected() || p.isRemoved()) continue;
                    // The pack sends a player who just logged in to the hub/spawn a moment later; put it back on its spot.
                    BlockPos spot = SPOT.get(p.getUUID());
                    Integer until = HOLD_UNTIL.get(p.getUUID());
                    if (spot != null && until != null && now <= until && p.blockPosition().distManhattan(spot) > 1) {
                        p.teleportTo((ServerLevel) p.level(), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
                    }
                    p.doTick();
                }
            });
        }
    }

    // ---- setting up ----

    /** Joins a simulated player through the player list (login events and all), standing on a stone platform at {@code at}. */
    private static ServerPlayer join(ServerLevel level, String name, BlockPos at) {
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("multicore-test:" + name).getBytes(StandardCharsets.UTF_8)), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        // Act like a fully compatible NeoForge client with every mod's channels, so mods may send their packets.
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        platform(level, at);
        player.teleportTo(level, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 0, 0);
        SPOT.put(player.getUUID(), at.above());
        HOLD_UNTIL.put(player.getUUID(), server.getTickCount() + 40);
        ONLINE.add(player);
        return player;
    }

    private static void leave(ServerPlayer player) {
        ONLINE.remove(player);
        SPOT.remove(player.getUUID());
        HOLD_UNTIL.remove(player.getUUID());
        if (player.server.getPlayerList().getPlayer(player.getUUID()) != null) {
            player.server.getPlayerList().remove(player);
        }
        TICKED_ON.remove(player.getUUID());
    }

    private static void platform(ServerLevel level, BlockPos at) {
        forceArea(level, at.getX(), at.getZ(), true);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlock(at.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    private static void forceArea(ServerLevel level, int x, int z, boolean force) {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(cx + dx, cz + dz, force);
                if (force) level.getChunk(cx + dx, cz + dz);
            }
        }
    }

    /** 30 still pigs around a spot, so the area is busy enough to be split even without the players. */
    private static List<Pig> crowd(ServerLevel level, BlockPos at) {
        List<Pig> out = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Pig pig = EntityType.PIG.create(level);
            pig.setNoAi(true);
            pig.setNoGravity(true);
            pig.setInvulnerable(true);
            pig.moveTo(at.getX() + 6 + (i % 6), at.getY() + 8, at.getZ() + 6 + (i / 6), 0, 0);
            level.addFreshEntity(pig);
            out.add(pig);
        }
        return out;
    }

    /** Runs a command as the player, recording what it answers. */
    private static List<String> run(ServerPlayer player, String command) {
        List<String> out = new CopyOnWriteArrayList<>();
        CommandSourceStack source = player.createCommandSourceStack().withSource(recorder(out));
        player.server.getCommands().performPrefixedCommand(source, command);
        return out;
    }

    private static List<String> runAsServer(MinecraftServer server, String command) {
        List<String> out = new CopyOnWriteArrayList<>();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(recorder(out)).withPermission(4), command);
        return out;
    }

    private static CommandSource recorder(List<String> out) {
        return new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                out.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
    }

    /** These tests use the pack's own mods; without them (the light test pack) they pass at once. */
    private static boolean missing(GameTestHelper helper, String modId) {
        if (net.neoforged.fml.ModList.get().isLoaded(modId)) return false;
        helper.succeed();
        return true;
    }

    private static void assertOnRegionThread(GameTestHelper helper, ServerPlayer p) {
        String thread = TICKED_ON.get(p.getUUID());
        helper.assertTrue(thread != null && thread.contains("region"),
                p.getGameProfile().getName() + " did not tick on a region thread (last: " + thread + ")");
    }

    /** The player really is where the test put it (and not back at spawn). */
    private static void assertAt(GameTestHelper helper, ServerPlayer p, BlockPos spot) {
        helper.assertTrue(p.blockPosition().distManhattan(spot.above()) <= 2, p.getGameProfile().getName() + " is at "
                + p.blockPosition() + ", not at its spot " + spot.above());
    }

    private static void assertStillOn(GameTestHelper helper) {
        helper.assertTrue(RegionTicker.enabled(), "region threading switched itself off: " + RegionTicker.disabledReason());
        helper.assertTrue(ParallelTicker.get().isActive(), "MultiCore switched itself off: " + ParallelTicker.get().disabledReason());
    }

    private static void cleanUp(ServerLevel level, List<ServerPlayer> players, List<? extends Entity> entities, BlockPos... areas) {
        players.forEach(PlayerTests::leave);
        entities.forEach(Entity::discard);
        for (BlockPos a : areas) forceArea(level, a.getX(), a.getZ(), false);
    }

    // ---- /tpa (Hub Essentials) ----

    @GameTest(template = "empty", batch = "players_tpa", timeoutTicks = 900)
    public static void tpaFromOneRegionToAnother(GameTestHelper helper) {
        if (missing(helper, "hubessentials")) return;
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        BlockPos atA = new BlockPos(o.getX() + 13000, 120, o.getZ() + 3000);
        BlockPos atB = new BlockPos(o.getX() - 13000, 120, o.getZ() + 3000);
        List<Pig> pigs = new ArrayList<>(crowd(level, atA));
        pigs.addAll(crowd(level, atB));
        ServerPlayer a = join(level, "mcrtTpaA", atA);
        ServerPlayer b = join(level, "mcrtTpaB", atB);
        AtomicReference<String> sent = new AtomicReference<>();
        AtomicInteger step = new AtomicInteger();
        helper.runAfterDelay(60, () -> {
            assertAt(helper, a, atA);
            assertAt(helper, b, atB);
            assertOnRegionThread(helper, a);
            assertOnRegionThread(helper, b);
            sent.set(String.join(" / ", run(b, "tpa mcrtTpaA")) + " | accept: " + String.join(" / ", run(a, "tpaccept")));
            step.set(1);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(step.get() == 1, "commands not sent yet");
            assertAt(helper, a, atA);
            helper.assertTrue(b.distanceToSqr(a) < 9, "B was not teleported to A (B at " + b.blockPosition() + ", A at "
                    + a.blockPosition() + "); the commands answered: " + sent.get());
            helper.assertTrue(level.getEntitiesOfClass(ServerPlayer.class, new AABB(a.blockPosition()).inflate(4)).contains(b),
                    "B is next to A but not found there by the world");
            int before = b.tickCount;
            helper.assertTrue(before > 0, "B stopped ticking");
            assertOnRegionThread(helper, b);
            assertStillOn(helper);
            cleanUp(level, List.of(a, b), pigs, atA, atB);
        });
    }

    // ---- /home (Hub Essentials, through its menu) ----

    @GameTest(template = "empty", batch = "players_home", timeoutTicks = 900)
    public static void homeSetInOneRegionUsedFromAnother(GameTestHelper helper) {
        if (missing(helper, "hubessentials")) return;
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        BlockPos home = new BlockPos(o.getX() + 15000, 120, o.getZ() - 3000);
        BlockPos away = new BlockPos(o.getX() - 15000, 120, o.getZ() - 3000);
        platform(level, away);
        List<Pig> pigs = new ArrayList<>(crowd(level, home));
        pigs.addAll(crowd(level, away));
        ServerPlayer p = join(level, "mcrtHome", home);
        AtomicReference<String> log = new AtomicReference<>("");
        AtomicInteger step = new AtomicInteger();
        helper.runAfterDelay(60, () -> {
            assertAt(helper, p, home);
            log.set("open: " + run(p, "home"));
            AbstractContainerMenu menu = p.containerMenu;
            log.set(log.get() + ", menu " + menu.getClass().getSimpleName() + ", save: " + menu.clickMenuButton(p, 0));
            p.closeContainer();
            p.teleportTo(level, away.getX() + 0.5, away.getY() + 1, away.getZ() + 0.5, 0, 0);
            step.set(1);
        });
        AtomicInteger waited = new AtomicInteger();
        helper.onEachTick(() -> {
            // Once the player ticks on a region thread at the far spot, use the home from there.
            if (step.get() == 1 && p.blockPosition().distManhattan(away.above()) <= 2) {
                String t = TICKED_ON.get(p.getUUID());
                if (waited.incrementAndGet() > 20 && t != null && t.contains("region")) {
                    run(p, "home");
                    AbstractContainerMenu menu = p.containerMenu;
                    log.set(log.get() + ", teleport from " + t + ": " + menu.clickMenuButton(p, 1));
                    p.closeContainer();
                    step.set(2);
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(step.get() == 2, "not there yet (" + log.get() + ", last tick on " + TICKED_ON.get(p.getUUID()) + ")");
            helper.assertTrue(p.blockPosition().distManhattan(home.above()) <= 2, "the home teleport did not arrive: at "
                    + p.blockPosition() + ", home " + home.above() + "; " + log.get());
            assertOnRegionThread(helper, p);
            run(p, "home");
            p.containerMenu.clickMenuButton(p, 2); // delete the test home again
            p.closeContainer();
            assertStillOn(helper);
            cleanUp(level, List.of(p), pigs, home, away);
        });
    }

    // ---- /pay (Create Economy Money) ----

    private static final Pattern NUMBER = Pattern.compile("(-?[0-9][0-9,]*(?:\\.[0-9]+)?)");

    private static double balance(ServerPlayer p) {
        List<String> out = run(p, "bal");
        for (String line : out) {
            Matcher m = NUMBER.matcher(line);
            double last = Double.NaN;
            while (m.find()) last = Double.parseDouble(m.group(1).replace(",", ""));
            if (!Double.isNaN(last)) return last;
        }
        return Double.NaN;
    }

    @GameTest(template = "empty", batch = "players_pay", timeoutTicks = 400)
    public static void payBetweenPlayersInTwoRegions(GameTestHelper helper) {
        if (missing(helper, "cemoney")) return;
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        BlockPos atA = new BlockPos(o.getX() + 17000, 120, o.getZ());
        BlockPos atB = new BlockPos(o.getX() - 17000, 120, o.getZ());
        List<Pig> pigs = new ArrayList<>(crowd(level, atA));
        pigs.addAll(crowd(level, atB));
        ServerPlayer a = join(level, "mcrtPayA", atA);
        ServerPlayer b = join(level, "mcrtPayB", atB);
        AtomicReference<String> log = new AtomicReference<>();
        AtomicReference<double[]> balances = new AtomicReference<>();
        helper.runAfterDelay(60, () -> {
            assertAt(helper, a, atA);
            assertAt(helper, b, atB);
            assertOnRegionThread(helper, a);
            assertOnRegionThread(helper, b);
            List<String> give = runAsServer(level.getServer(), "money give mcrtPayA 1000");
            double a0 = balance(a), b0 = balance(b);
            List<String> pay = run(a, "pay mcrtPayB 250");
            double a1 = balance(a), b1 = balance(b);
            balances.set(new double[] {a0, b0, a1, b1});
            log.set("give: " + give + ", pay: " + pay + ", A " + a0 + " -> " + a1 + ", B " + b0 + " -> " + b1);
        });
        helper.succeedWhen(() -> {
            double[] v = balances.get();
            helper.assertTrue(v != null, "not paid yet");
            helper.assertTrue(!Double.isNaN(v[0]) && !Double.isNaN(v[3]), "could not read balances: " + log.get());
            helper.assertTrue(Math.abs((v[0] - v[2]) - 250) < 0.01 && Math.abs((v[3] - v[1]) - 250) < 0.01, "payment wrong: " + log.get());
            assertStillOn(helper);
            cleanUp(level, List.of(a, b), pigs, atA, atB);
        });
    }

    // ---- chests, blocks, dying on a region thread, respawning ----

    @GameTest(template = "empty", batch = "players_life", timeoutTicks = 600)
    public static void chestBlocksDeathAndRespawn(GameTestHelper helper) {
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        BlockPos atA = new BlockPos(o.getX() + 19000, 120, o.getZ() + 5000);
        BlockPos atB = new BlockPos(o.getX() - 19000, 120, o.getZ() + 5000);
        List<Pig> pigs = new ArrayList<>(crowd(level, atA));
        pigs.addAll(crowd(level, atB));
        ServerPlayer p = join(level, "mcrtLife", atA);
        ServerPlayer other = join(level, "mcrtLifeB", atB);
        BlockPos chestPos = atA.offset(2, 1, 0);
        BlockPos lava = atA.offset(-2, 1, 0);
        AtomicReference<String> log = new AtomicReference<>("");
        AtomicReference<ServerPlayer> respawned = new AtomicReference<>();
        AtomicInteger step = new AtomicInteger();
        helper.runAfterDelay(60, () -> {
            assertAt(helper, p, atA);
            assertOnRegionThread(helper, p);
            // A chest: open it and take the diamonds out through the menu, like shift-clicking.
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
            p.openMenu(chest);
            AbstractContainerMenu menu = p.containerMenu;
            menu.clicked(0, 0, ClickType.QUICK_MOVE, p);
            p.closeContainer();
            log.set("diamonds in inventory: " + p.getInventory().countItem(Items.DIAMOND));
            // Break a block and place one.
            BlockPos dirt = atA.offset(0, 1, 2);
            level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 3);
            boolean broken = p.gameMode.destroyBlock(dirt);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 4));
            BlockPos on = atA.offset(0, 0, -2);
            p.gameMode.useItemOn(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(on).add(0, 0.5, 0), Direction.UP, on, false));
            log.set(log.get() + ", dirt broken " + broken + ", cobblestone placed " + level.getBlockState(on.above()).is(Blocks.COBBLESTONE));
            step.set(1);
        });
        helper.runAfterDelay(90, () -> {
            // Into lava with one heart: the player dies during its own tick, on the region thread.
            p.setHealth(1.0F);
            level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 3);
            p.teleportTo(level, lava.getX() + 0.5, lava.getY(), lava.getZ() + 0.5, 0, 0);
            p.setInvulnerable(false);
            step.set(2);
        });
        helper.onEachTick(() -> {
            if (step.get() == 2 && p.isDeadOrDying() && respawned.get() == null) {
                List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(lava).inflate(6));
                log.set(log.get() + ", died with " + drops.size() + " item stacks dropped nearby");
                respawned.set(level.getServer().getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED));
                step.set(3);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(step.get() >= 1 && log.get().contains("diamonds in inventory: 5"), "chest: " + log.get());
            helper.assertTrue(log.get().contains("dirt broken true") && log.get().contains("cobblestone placed true"), "blocks: " + log.get());
            helper.assertTrue(step.get() == 3, "the player has not died in the lava yet (health " + p.getHealth() + ")");
            ServerPlayer again = respawned.get();
            helper.assertTrue(again != null && !again.isDeadOrDying() && again.tickCount > 5, "the respawned player is not ticking");
            assertStillOn(helper);
            level.removeBlock(lava, false);
            level.removeBlock(chestPos, false);
            cleanUp(level, List.of(again, other), pigs, atA, atB);
        });
    }

    // ---- riding from one region into another ----

    @GameTest(template = "empty", batch = "players_ride", timeoutTicks = 1600)
    public static void rideAMinecartFromOneRegionIntoAnother(GameTestHelper helper) {
        listen();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int y = 120;
        int startX = o.getX() + 21000, endX = startX + 300, z = o.getZ() - 6000;
        BlockPos atA = new BlockPos(startX, y, z);
        BlockPos atB = new BlockPos(endX, y, z);
        List<Pig> pigs = new ArrayList<>(crowd(level, atA.offset(-12, 0, 0)));
        pigs.addAll(crowd(level, atB.offset(4, 0, 0)));
        // Powered rail the whole way, every chunk on the way force-loaded.
        for (int x = startX - 16; x <= endX + 16; x += 16) {
            level.setChunkForced(x >> 4, z >> 4, true);
            level.getChunk(x >> 4, z >> 4);
        }
        for (int x = startX; x <= endX; x++) {
            level.setBlock(new BlockPos(x, y - 1, z), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, y, z), Blocks.POWERED_RAIL.defaultBlockState()
                    .setValue(PoweredRailBlock.POWERED, true).setValue(BaseRailBlock.WATERLOGGED, false)
                    .setValue(PoweredRailBlock.SHAPE, RailShape.EAST_WEST), 3);
        }
        level.setBlock(new BlockPos(endX + 1, y, z), Blocks.STONE.defaultBlockState(), 3); // buffer stop
        ServerPlayer rider = join(level, "mcrtRider", atA.offset(-3, -1, 0));
        ServerPlayer waiting = join(level, "mcrtWaiter", atB.offset(3, -1, 3));
        AtomicReference<Minecart> cart = new AtomicReference<>();
        Map<String, Boolean> threads = new ConcurrentHashMap<>();
        helper.runAfterDelay(60, () -> {
            assertAt(helper, rider, atA.offset(-3, -1, 0));
            Minecart m = EntityType.MINECART.create(level);
            m.moveTo(startX + 0.5, y + 0.1, z + 0.5, 0, 0);
            m.setDeltaMovement(0.4, 0, 0);
            level.addFreshEntity(m);
            rider.startRiding(m, true);
            cart.set(m);
        });
        helper.onEachTick(() -> {
            String t = TICKED_ON.get(rider.getUUID());
            if (t != null && cart.get() != null) threads.put(t, true);
        });
        helper.succeedWhen(() -> {
            Minecart m = cart.get();
            helper.assertTrue(m != null, "no cart yet");
            helper.assertTrue(m.getX() >= endX - 3, "the cart is at x " + (int) m.getX() + " of " + endX);
            helper.assertTrue(rider.getVehicle() == m, "the rider fell off the cart");
            helper.assertTrue(level.getEntitiesOfClass(Minecart.class, new AABB(m.blockPosition()).inflate(3)).contains(m),
                    "the cart is not found where it is (its section was not updated)");
            helper.assertTrue(threads.keySet().stream().anyMatch(n -> n.contains("region")), "the rider never ticked on a region thread: " + threads.keySet());
            assertStillOn(helper);
            m.discard();
            for (int x = startX; x <= endX + 1; x++) {
                level.removeBlock(new BlockPos(x, y, z), false);
                level.removeBlock(new BlockPos(x, y - 1, z), false);
            }
            for (int x = startX - 16; x <= endX + 16; x += 16) level.setChunkForced(x >> 4, z >> 4, false);
            cleanUp(level, List.of(rider, waiting), pigs);
        });
    }
}
