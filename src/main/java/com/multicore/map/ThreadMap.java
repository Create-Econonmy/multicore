package com.multicore.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.multicore.MultiCore;
import com.multicore.compat.DimThreadCompat;
import com.multicore.config.MultiCoreConfig;
import com.multicore.core.AutoAdjust;
import com.multicore.core.ParallelTicker;
import com.multicore.core.Stats;
import com.multicore.mixin.core.ChunkMapAccessor;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;

/**
 * The thread map: a flat web map of which dimension thread runs each world and which chunks each world has loaded
 * (and how far: border, block ticking, entity ticking), forced chunks, players and the border. The snapshot is taken
 * on the main thread once a second, after the parallel phase, so nothing here races the dimension threads; the web
 * server only hands out the finished JSON.
 */
public final class ThreadMap {
    private static final int SNAPSHOT_EVERY_TICKS = 20;
    private static final long TERRAIN_BUDGET_NANOS = 1_000_000L;

    private static final TerrainCache TERRAIN = new TerrainCache();
    @Nullable
    private static MapHttpServer http;
    private static volatile String state = "{}";
    private static boolean terrain;
    private static boolean failed;

    private ThreadMap() {
    }

    public static void onServerStarted(MinecraftServer server) {
        if (!MultiCoreConfig.MAP_ENABLED.get()) return;
        terrain = MultiCoreConfig.MAP_TERRAIN.get();
        if (terrain) TERRAIN.load(dir(server));
        try {
            snapshot(server);
        } catch (RuntimeException e) {
            MultiCore.LOGGER.warn("[MultiCore] map: first snapshot failed", e);
        }
        MapHttpServer s = new MapHttpServer(TERRAIN, new NetFilter(MultiCoreConfig.MAP_ALLOWED.get()));
        if (s.start(MultiCoreConfig.MAP_BIND.get(), MultiCoreConfig.MAP_PORT.get())) {
            http = s;
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        if (http != null) {
            http.stop();
            http = null;
        }
        if (terrain) {
            TERRAIN.save(dir(server));
            terrain = false;
        }
    }

    /** Main thread, ServerTickEvent.Post (after the parallel phase). */
    public static void onTick(MinecraftServer server) {
        if (http == null) return;
        try {
            if (server.getTickCount() % SNAPSHOT_EVERY_TICKS == 0) snapshot(server);
            // Dimensions on their own thread colour their terrain there (afterLevelTick); only main-thread ones here.
            if (terrain) TERRAIN.work(server, TERRAIN_BUDGET_NANOS, ParallelTicker.get()::tickedOnOwnThread);
            if (terrain && server.getTickCount() % (20 * 60 * 15) == 0) TERRAIN.save(dir(server));
        } catch (RuntimeException e) {
            if (!failed) {
                failed = true;
                MultiCore.LOGGER.warn("[MultiCore] map: snapshot failed (logged once)", e);
            }
        }
    }

    /** On a dimension thread, right after it ticked {@code level}: colour some of that dimension's terrain. */
    public static void afterLevelTick(ServerLevel level) {
        if (http == null || !terrain) return;
        try {
            TERRAIN.workLevel(level, TERRAIN_BUDGET_NANOS);
        } catch (RuntimeException e) {
            if (!failed) {
                failed = true;
                MultiCore.LOGGER.warn("[MultiCore] map: terrain failed (logged once)", e);
            }
        }
    }

    static String stateJson() {
        return state;
    }

    private static Path dir(MinecraftServer server) {
        return server.getServerDirectory().resolve("multicore-map");
    }

    @Nullable
    static ServerLevel levelById(MinecraftServer server, String id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(id)) return level;
        }
        return null;
    }

    private static void snapshot(MinecraftServer server) {
        ParallelTicker ticker = ParallelTicker.get();
        AutoAdjust auto = AutoAdjust.get();
        boolean dimthread = DimThreadCompat.isTicking(server);
        List<List<String>> groups = ticker.isActive() && !dimthread ? ticker.currentGroups() : List.of();
        Map<ResourceKey<Level>, Long> lastNanos = ticker.lastTickNanos();
        long now = server.getTickCount();

        JsonObject root = new JsonObject();
        root.addProperty("time", System.currentTimeMillis());
        root.addProperty("tick", now);
        root.addProperty("mode", dimthread ? "dimthread" : ticker.isActive() ? "parallel" : "vanilla");
        if (ticker.disabledReason() != null) root.addProperty("disabledReason", ticker.disabledReason());
        double tickMs = server.getAverageTickTimeNanos() / 1e6;
        root.addProperty("tickMs", round(tickMs));
        root.addProperty("tps", round(Math.min(20.0, 1000.0 / Math.max(50.0, tickMs))));
        root.addProperty("parallelMs", round(Stats.avgPhaseMillis()));
        root.addProperty("oneThreadMs", round(Stats.avgSumOfLevelsMillis()));
        root.addProperty("autoAdjust", auto.settings().enabled() ? auto.lastAction() : "off");
        root.addProperty("terrain", terrain);
        root.addProperty("cores", Runtime.getRuntime().availableProcessors());

        JsonArray threads = new JsonArray();
        for (int i = 0; i < groups.size(); i++) {
            JsonObject t = new JsonObject();
            t.addProperty("index", i);
            JsonArray dims = new JsonArray();
            double sum = 0;
            for (String d : groups.get(i)) {
                dims.add(d);
                double avg = auto.averageMs(d);
                if (!Double.isNaN(avg)) sum += avg;
            }
            t.add("dims", dims);
            t.addProperty("avgMs", round(sum));
            threads.add(t);
        }
        root.add("threads", threads);

        JsonArray worlds = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            String id = level.dimension().location().toString();
            JsonObject w = new JsonObject();
            w.addProperty("id", id);
            int thread = -1;
            for (int i = 0; i < groups.size(); i++) if (groups.get(i).contains(id)) thread = i;
            w.addProperty("thread", thread);
            String where = dimthread ? "dimthread"
                    : !ticker.isActive() ? "main thread (MultiCore off)"
                    : ticker.isConfiguredSerial(level) ? "main thread (serialDimensions)"
                    : auto.isIdle(id) ? "main thread (idle, no players)"
                    : thread >= 0 ? "dimension thread " + (thread + 1) : "main thread";
            w.addProperty("where", where);
            Long nanos = lastNanos.get(level.dimension());
            if (nanos != null) w.addProperty("lastMs", round(nanos / 1e6));
            double avg = auto.averageMs(id);
            if (!Double.isNaN(avg)) w.addProperty("avgMs", round(avg));

            WorldBorder border = level.getWorldBorder();
            JsonObject b = new JsonObject();
            b.addProperty("x", border.getCenterX());
            b.addProperty("z", border.getCenterZ());
            b.addProperty("size", border.getSize());
            w.add("border", b);

            JsonArray players = new JsonArray();
            for (ServerPlayer p : level.players()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getGameProfile().getName());
                o.addProperty("x", Math.round(p.getX()));
                o.addProperty("y", Math.round(p.getY()));
                o.addProperty("z", Math.round(p.getZ()));
                players.add(o);
            }
            w.add("players", players);

            // chunks: flat [x, z, code, ...]; code 0 border (loaded, not ticking) 1 block ticking 2 entity ticking, +4 forced
            LongSet forced = level.getForcedChunks();
            JsonArray chunks = new JsonArray();
            int[] counts = new int[3];
            String dimId = id;
            for (ChunkHolder holder : ((ChunkMapAccessor) level.getChunkSource().chunkMap).multicore$getChunks()) {
                FullChunkStatus s = holder.getFullStatus();
                int code;
                if (s == FullChunkStatus.ENTITY_TICKING) code = 2;
                else if (s == FullChunkStatus.BLOCK_TICKING) code = 1;
                else if (s == FullChunkStatus.FULL) code = 0;
                else continue; // still generating / not a full chunk
                counts[code]++;
                ChunkPos pos = holder.getPos();
                long key = pos.toLong();
                if (forced.contains(key)) code += 4;
                chunks.add(pos.x);
                chunks.add(pos.z);
                chunks.add(code);
                if (terrain) TERRAIN.offer(dimId, key, now);
            }
            w.add("chunks", chunks);
            JsonObject c = new JsonObject();
            c.addProperty("border", counts[0]);
            c.addProperty("block", counts[1]);
            c.addProperty("entity", counts[2]);
            c.addProperty("forced", forced.size());
            w.add("counts", c);

            JsonObject tiles = new JsonObject();
            for (Map.Entry<Long, Integer> e : TERRAIN.regionVersions(id).entrySet()) {
                tiles.addProperty(ChunkPos.getX(e.getKey()) + "," + ChunkPos.getZ(e.getKey()), e.getValue());
            }
            w.add("tiles", tiles);
            worlds.add(w);
        }
        root.add("worlds", worlds);
        state = root.toString();
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
