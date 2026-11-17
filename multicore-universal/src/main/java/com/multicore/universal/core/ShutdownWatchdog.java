package com.multicore.universal.core;

import com.multicore.universal.MultiCore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

/**
 * Vanilla's shutdown keeps ticking every chunk map until none has work left. If a chunk map never empties the server
 * hangs on "Saving worlds" forever. After 20 seconds this logs, per dimension, what is still pending so the cause
 * can be found. Reads are unsynchronized (diagnostics only).
 */
public final class ShutdownWatchdog {
    private static volatile boolean stopped;

    private ShutdownWatchdog() {
    }

    public static void onStopping(MinecraftServer server) {
        stopped = false;
        Thread thread = new Thread(() -> watch(server), "MultiCore shutdown watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    public static void onStopped() {
        stopped = true;
    }

    private static void watch(MinecraftServer server) {
        try {
            Thread.sleep(20_000L);
            while (!stopped) {
                MultiCore.LOGGER.warn("[MultiCore] Shutdown is taking long; chunk work still pending per dimension:");
                for (ServerLevel level : server.getAllLevels()) {
                    MultiCore.LOGGER.warn("[MultiCore]   {}: {}", level.dimension().location(), describe(level));
                }
                Thread.sleep(10_000L);
            }
        } catch (InterruptedException ignored) {
            // JVM exiting
        } catch (Throwable t) {
            MultiCore.LOGGER.warn("[MultiCore] Shutdown watchdog failed", t);
        }
    }

    private static String describe(ServerLevel level) {
        ChunkMap map = level.getChunkSource().chunkMap;
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("light=").append(level.getChunkSource().getLightEngine().hasLightWork());
            sb.append(" updating=").append(size(get(map, "updatingChunkMap")));
            sb.append(" pendingUnloads=").append(size(get(map, "pendingUnloads")));
            sb.append(" toDrop=").append(size(get(map, "toDrop")));
            sb.append(" unloadQueue=").append(size(get(map, "unloadQueue")));
            sb.append(" sorterWork=").append(call(get(map, "queueSorter"), "hasWork"));
            sb.append(" poiWork=").append(call(level.getChunkSource().getPoiManager(), "hasWork"));
            Object distanceManager = get(map, "distanceManager");
            Object tickets = getInHierarchy(distanceManager, "tickets");
            sb.append(" tickets=").append(size(tickets));
            if (tickets instanceof Map<?, ?> m && !m.isEmpty()) {
                sb.append(" e.g. ");
                m.entrySet().stream().limit(5).forEach(e -> sb.append(new net.minecraft.world.level.ChunkPos((Long) e.getKey())).append(e.getValue()).append(' '));
            }
        } catch (Throwable t) {
            sb.append(" (").append(t).append(')');
        }
        return sb.toString();
    }

    private static Object get(Object owner, String name) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private static Object getInHierarchy(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
                // keep looking
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object owner, String method) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(method);
                m.setAccessible(true);
                return m.invoke(owner);
            } catch (NoSuchMethodException ignored) {
                // keep looking
            }
        }
        return "?";
    }

    private static String size(Object o) {
        if (o instanceof Map<?, ?> m) return String.valueOf(m.size());
        if (o instanceof Collection<?> c) return String.valueOf(c.size());
        return String.valueOf(o);
    }
}
