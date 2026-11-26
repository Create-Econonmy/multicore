package com.multicore.core;

import com.multicore.MultiCore;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Players on their dimension's thread instead of the main thread (like Folia).
 * <p>
 * In vanilla every player's tick (movement, food, effects, advancements: {@code ServerPlayer.doTick} from the
 * connection tick) and every packet a player sends (moving, mining, placing, clicking in a container) runs on the one
 * main thread, one player after another. Here they run on the thread of the dimension the player is in, during the
 * parallel phase: players in different dimensions are handled at the same time.
 * <ul>
 * <li>The connection tick of a player whose dimension ticked on its own thread is skipped on the main thread and run
 * at the end of that dimension's next tick instead (same order as vanilla: the level, then the connections).</li>
 * <li>Packets that act on the player's own world are queued for its dimension and run at the start of that
 * dimension's tick. Chat, commands, mod payloads and everything else stay on the main thread.</li>
 * <li>A disconnect seen on a dimension thread (it removes the player from the player list) is handled on the main
 * thread at the end of the tick.</li>
 * </ul>
 * Each player tick and each packet is one item for {@link WorldPause}.
 */
public final class PlayerThreads {
    private static volatile boolean enabled = true;
    /** Connections whose tick is owed to their dimension's next tick. */
    private static final Map<ServerLevel, Queue<Connection>> OWED = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Queue<Runnable>> PACKETS = new ConcurrentHashMap<>();
    private static final Set<Class<?>> LEVEL_PACKETS = Set.of(
            ServerboundMovePlayerPacket.Pos.class, ServerboundMovePlayerPacket.PosRot.class, ServerboundMovePlayerPacket.Rot.class,
            ServerboundMovePlayerPacket.StatusOnly.class, ServerboundMoveVehiclePacket.class, ServerboundAcceptTeleportationPacket.class,
            ServerboundPlayerActionPacket.class, ServerboundUseItemOnPacket.class, ServerboundUseItemPacket.class,
            ServerboundSwingPacket.class, ServerboundInteractPacket.class, ServerboundPlayerCommandPacket.class,
            ServerboundPlayerInputPacket.class, ServerboundSetCarriedItemPacket.class, ServerboundContainerClickPacket.class,
            ServerboundContainerClosePacket.class, ServerboundContainerButtonClickPacket.class,
            ServerboundSetCreativeModeSlotPacket.class, ServerboundPickItemPacket.class, ServerboundPaddleBoatPacket.class,
            ServerboundSignUpdatePacket.class, ServerboundPlaceRecipePacket.class, ServerboundSelectTradePacket.class,
            ServerboundRenameItemPacket.class, ServerboundChunkBatchReceivedPacket.class, ServerboundContainerSlotStateChangedPacket.class,
            ServerboundSetBeaconPacket.class, ServerboundSetCommandBlockPacket.class, ServerboundSetStructureBlockPacket.class,
            ServerboundSetJigsawBlockPacket.class, ServerboundBlockEntityTagQueryPacket.class, ServerboundEntityTagQueryPacket.class);
    /** Packets touching the open container: a mod's container holds the mod lock, as when it syncs. */
    private static final Set<Class<?>> MENU_PACKETS = Set.of(ServerboundContainerClickPacket.class, ServerboundContainerClosePacket.class,
            ServerboundContainerButtonClickPacket.class, ServerboundPlaceRecipePacket.class, ServerboundSelectTradePacket.class,
            ServerboundRenameItemPacket.class, ServerboundContainerSlotStateChangedPacket.class);

    private PlayerThreads() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    private static boolean active() {
        return enabled && ParallelTicker.get().isActive();
    }

    // ---- connection ticks ----

    /**
     * Main thread, from ServerConnectionListener.tick: should this connection tick on its player's dimension thread?
     * Then it is remembered for that dimension's next tick and skipped here.
     */
    public static boolean handOff(Connection connection) {
        if (!active() || !(connection.getPacketListener() instanceof ServerGamePacketListenerImpl game)) {
            return false;
        }
        ServerLevel level = game.player.serverLevel();
        if (!TickThreads.isRealLevel(level) || !ParallelTicker.get().tickedOnOwnThread(level)) {
            return false;
        }
        OWED.computeIfAbsent(level, l -> new ConcurrentLinkedQueue<>()).add(connection);
        Stats.PLAYER_TICKS_MOVED.increment();
        return true;
    }

    /** End of ServerLevel.tick, on the level's thread: the connection ticks owed to it. */
    public static void tickOwedConnections(ServerLevel level) {
        Queue<Connection> owed = OWED.get(level);
        if (owed == null || owed.isEmpty()) return;
        Connection connection;
        while ((connection = owed.poll()) != null) {
            Connection c = connection;
            if (!(c.getPacketListener() instanceof ServerGamePacketListenerImpl game) || game.player.serverLevel() != level) {
                // Moved to another dimension (or logged out) since: tick it the vanilla way, on the main thread.
                SerialQueue.defer("player connection tick", () -> tickConnection(c));
                continue;
            }
            WorldPause.beginItem();
            try {
                tickConnection(c);
            } finally {
                WorldPause.endItem();
            }
        }
    }

    /** What ServerConnectionListener.tick does for one connection. */
    private static void tickConnection(Connection connection) {
        if (connection.isConnecting() || !connection.isConnected()) {
            return; // ServerConnectionListener handles the disconnect on the main thread
        }
        try {
            connection.tick();
        } catch (Exception e) {
            if (connection.isMemoryConnection()) {
                throw e;
            }
            MultiCore.LOGGER.warn("Failed to handle packet for {}", connection.getLoggableAddress(false), e);
            Component component = Component.literal("Internal server error");
            connection.send(new ClientboundDisconnectPacket(component), PacketSendListener.thenRun(() -> connection.disconnect(component)));
            connection.setReadOnly();
        }
    }

    /**
     * A disconnect handled on a dimension thread removes the player from the player list: not here. The main thread's
     * ServerConnectionListener.tick handles every closed connection itself at its next tick (queueing it as well made
     * vanilla log "handleDisconnection() called twice", found by the soak test 2026-11-26).
     */
    public static boolean deferDisconnect(Connection connection) {
        return Thread.currentThread() instanceof LevelThread && TickThreads.inParallelPhase();
    }

    // ---- packets ----

    /**
     * Netty thread, from PacketUtils.ensureRunningOnSameThread: queue a packet that acts on the player's world for that
     * world's thread instead of the main thread. Returns false to leave it to the main thread.
     */
    public static boolean queuePacket(Packet<?> packet, Object listener, Runnable handle) {
        if (!active() || !(listener instanceof ServerGamePacketListenerImpl game) || !LEVEL_PACKETS.contains(packet.getClass())) {
            return false;
        }
        ServerLevel level = game.player.serverLevel();
        if (!TickThreads.isRealLevel(level)) {
            return false;
        }
        boolean menu = MENU_PACKETS.contains(packet.getClass());
        PACKETS.computeIfAbsent(level, l -> new ConcurrentLinkedQueue<>()).add(new LevelPacket(game, handle, menu));
        Stats.PLAYER_PACKETS_MOVED.increment();
        return true;
    }

    /** Start of ServerLevel.tick, on the level's thread: the packets queued for it, in arrival order. */
    public static void runQueuedPackets(ServerLevel level) {
        Queue<Runnable> queue = PACKETS.get(level);
        if (queue == null || queue.isEmpty()) return;
        // Only what is there now: packets arriving meanwhile wait for the next tick, like vanilla's task queue.
        int n = queue.size();
        Runnable task;
        while (n-- > 0 && (task = queue.poll()) != null) {
            if (task instanceof LevelPacket p && p.game.player.serverLevel() != level) {
                ServerLevel now = p.game.player.serverLevel();
                PACKETS.computeIfAbsent(now, l -> new ConcurrentLinkedQueue<>()).add(p);
                continue;
            }
            WorldPause.beginItem();
            try {
                task.run();
            } catch (Throwable t) {
                MultiCore.LOGGER.error("[MultiCore] A player packet failed on {}", Thread.currentThread().getName(), t);
            } finally {
                WorldPause.endItem();
            }
        }
    }

    /** Main thread: nothing may stay queued for a level that is gone. */
    public static void forget(ServerLevel level) {
        Queue<Runnable> packets = PACKETS.remove(level);
        if (packets != null) packets.forEach(Runnable::run);
        Queue<Connection> owed = OWED.remove(level);
        if (owed != null) owed.forEach(PlayerThreads::tickConnection);
    }

    private record LevelPacket(ServerGamePacketListenerImpl game, Runnable handle, boolean menu) implements Runnable {
        @Override
        public void run() {
            if (!this.menu) {
                this.handle.run();
                return;
            }
            ServerPlayer player = this.game.player;
            String modId = ModOwnership.modIdOf(player.containerMenu.getClass());
            if (!ContentPolicy.lockingActive() || ContentPolicy.isSafeNamespace(modId)) {
                this.handle.run();
                return;
            }
            ModLock.acquire(LockSource.of("menu " + modId));
            try {
                this.handle.run();
            } finally {
                ModLock.release();
            }
        }
    }
}
