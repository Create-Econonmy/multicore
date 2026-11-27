package com.multicore.map;

import com.multicore.MultiCore;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Top-down colours of every chunk the server has had loaded (16x16 RGB per chunk), read like a vanilla map. Chunks are
 * coloured on the main thread outside the parallel phase, a few per tick within a time budget; the web server only
 * reads the finished arrays. Kept on disk between restarts so the map is not empty after a reboot.
 */
final class TerrainCache {
    private static final int MAGIC = TerrainFile.MAGIC;
    /** A loaded chunk is coloured again after this long (players build). */
    private static final long REFRESH_TICKS = 20L * 60 * 5;

    record Entry(byte[] rgb, long tick) {
    }

    private final Map<String, ConcurrentHashMap<Long, Entry>> chunks = new ConcurrentHashMap<>();
    /** Per dimension: region (32x32 chunks) -> version, bumped whenever one of its chunks changes. */
    private final Map<String, ConcurrentHashMap<Long, Integer>> regions = new ConcurrentHashMap<>();
    /**
     * Chunks waiting to be coloured, per dimension. Filled by the main thread outside the parallel phase; each dimension's
     * queue is then worked off by the thread that ticks that dimension (see {@link #workLevel}).
     */
    private final Map<String, LinkedHashSet<Long>> pending = new ConcurrentHashMap<>();

    ConcurrentHashMap<Long, Integer> regionVersions(String dim) {
        return this.regions.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
    }

    /** Main thread. Called for each loaded full chunk when the map snapshot is taken. */
    void offer(String dim, long chunk, long now) {
        Entry e = this.chunks.computeIfAbsent(dim, k -> new ConcurrentHashMap<>()).get(chunk);
        if (e == null || now - e.tick >= REFRESH_TICKS || now < e.tick) {
            LinkedHashSet<Long> q = this.pending.computeIfAbsent(dim, k -> new LinkedHashSet<>());
            if (q.size() < 50_000) q.add(chunk);
        }
    }

    /**
     * Main thread, outside the parallel phase: colours the queued chunks of dimensions that tick on the main thread (the
     * others are done by their own dimension thread, {@link #workLevel}) until the budget is used up.
     */
    void work(MinecraftServer server, long budgetNanos, java.util.function.Predicate<ServerLevel> ownThread) {
        if (this.pending.isEmpty()) return;
        long end = System.nanoTime() + budgetNanos;
        for (Map.Entry<String, LinkedHashSet<Long>> d : this.pending.entrySet()) {
            ServerLevel level = ThreadMap.levelById(server, d.getKey());
            if (level == null) {
                d.getValue().clear();
                continue;
            }
            if (ownThread.test(level)) continue;
            if (!this.colourSome(level, d.getKey(), d.getValue(), end, server.getTickCount())) return;
        }
    }

    /**
     * The thread ticking {@code level} (its dimension thread, right after the tick, while it owns the level): colours
     * that dimension's queued chunks until the budget is used up. Moves this work off the main thread.
     */
    void workLevel(ServerLevel level, long budgetNanos) {
        String dim = level.dimension().location().toString();
        LinkedHashSet<Long> queue = this.pending.get(dim);
        if (queue == null || queue.isEmpty()) return;
        this.colourSome(level, dim, queue, System.nanoTime() + budgetNanos, level.getServer().getTickCount());
    }

    /** @return false if the time ran out */
    private boolean colourSome(ServerLevel level, String dim, LinkedHashSet<Long> queue, long end, long tick) {
        Iterator<Long> it = queue.iterator();
        while (it.hasNext()) {
            if (System.nanoTime() > end) return false;
            long key = it.next();
            it.remove();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            try {
                put(dim, key, colour(level, chunk), tick);
            } catch (RuntimeException ex) {
                MultiCore.LOGGER.debug("[MultiCore] map: could not colour chunk {} in {}", new ChunkPos(key), dim, ex);
            }
        }
        return true;
    }

    private void put(String dim, long key, byte[] rgb, long tick) {
        Entry old = this.chunks.computeIfAbsent(dim, k -> new ConcurrentHashMap<>()).put(key, new Entry(rgb, tick));
        if (old == null || !java.util.Arrays.equals(old.rgb, rgb)) {
            this.regionVersions(dim).merge(regionKey(ChunkPos.getX(key) >> 5, ChunkPos.getZ(key) >> 5), 1, Integer::sum);
        }
    }

    static long regionKey(int rx, int rz) {
        return ChunkPos.asLong(rx, rz);
    }

    private static byte[] colour(ServerLevel level, LevelChunk chunk) {
        byte[] out = new byte[768];
        boolean ceiling = level.dimensionType().hasCeiling();
        int minY = level.getMinBuildHeight();
        int ceilingStart = Math.min(level.getMaxBuildHeight(), minY + level.dimensionType().logicalHeight()) - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int[] prevRow = new int[16];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y;
                if (ceiling) {
                    y = ceilingStart;
                    // through the roof, then down through the air to the floor
                    while (y > minY && !chunk.getBlockState(pos.set(baseX + x, y, baseZ + z)).isAir()) y--;
                    while (y > minY && chunk.getBlockState(pos.set(baseX + x, y, baseZ + z)).isAir()) y--;
                } else {
                    y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                }
                BlockState state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                MapColor mc = state.getMapColor(level, pos);
                int guard = 0;
                while (mc == MapColor.NONE && y > minY && guard++ < 32) {
                    y--;
                    state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                    mc = state.getMapColor(level, pos);
                }
                int modifier;
                if (state.getFluidState().is(FluidTags.WATER)) {
                    int depth = 0;
                    while (depth < 12 && y - depth - 1 > minY
                            && chunk.getBlockState(pos.set(baseX + x, y - depth - 1, baseZ + z)).getFluidState().is(FluidTags.WATER)) depth++;
                    double shade = depth * 0.1 + ((x + z) & 1) * 0.2;
                    modifier = shade < 0.5 ? 255 : shade > 0.9 ? 180 : 220;
                } else {
                    int north = z == 0 ? y : prevRow[x];
                    modifier = y > north ? 255 : y < north ? 180 : 220;
                }
                prevRow[x] = y;
                int col = mc == MapColor.NONE ? 0x101010 : mc.col;
                int i = (z * 16 + x) * 3;
                out[i] = (byte) (((col >> 16) & 0xff) * modifier / 255);
                out[i + 1] = (byte) (((col >> 8) & 0xff) * modifier / 255);
                out[i + 2] = (byte) ((col & 0xff) * modifier / 255);
            }
        }
        return out;
    }

    /** Any thread. One 32x32-chunk region at one pixel per block, or null when nothing in it is known. */
    byte[] regionPng(String dim, int rx, int rz) {
        ConcurrentHashMap<Long, Entry> map = this.chunks.get(dim);
        if (map == null) return null;
        byte[] rgba = new byte[512 * 512 * 4];
        boolean any = false;
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                Entry e = map.get(ChunkPos.asLong((rx << 5) + cx, (rz << 5) + cz));
                if (e == null) continue;
                any = true;
                for (int z = 0; z < 16; z++) {
                    int row = ((cz * 16 + z) * 512 + cx * 16) * 4;
                    for (int x = 0; x < 16; x++) {
                        int s = (z * 16 + x) * 3;
                        int d = row + x * 4;
                        rgba[d] = e.rgb[s];
                        rgba[d + 1] = e.rgb[s + 1];
                        rgba[d + 2] = e.rgb[s + 2];
                        rgba[d + 3] = (byte) 0xff;
                    }
                }
            }
        }
        return any ? Png.encode(512, 512, rgba) : null;
    }

    // ---- disk ----

    void load(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!f.getFileName().toString().endsWith(".bin.gz")) continue;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
                    if (in.readInt() != MAGIC) continue;
                    String dim = in.readUTF();
                    int n = in.readInt();
                    ConcurrentHashMap<Long, Entry> map = this.chunks.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
                    ConcurrentHashMap<Long, Integer> versions = this.regionVersions(dim);
                    for (int i = 0; i < n; i++) {
                        long key = in.readLong();
                        byte[] rgb = new byte[768];
                        in.readFully(rgb);
                        // tick -1: coloured in an earlier run, so it is redone the first time the chunk is loaded again
                        map.put(key, new Entry(rgb, Long.MIN_VALUE / 2));
                        versions.merge(regionKey(ChunkPos.getX(key) >> 5, ChunkPos.getZ(key) >> 5), 1, Integer::sum);
                    }
                    MultiCore.LOGGER.info("[MultiCore] map: {} terrain chunks of {} loaded", n, dim);
                } catch (IOException e) {
                    MultiCore.LOGGER.warn("[MultiCore] map: could not read {}: {}", f, e.toString());
                }
            }
        } catch (IOException e) {
            MultiCore.LOGGER.warn("[MultiCore] map: could not list {}: {}", dir, e.toString());
        }
    }

    private final TerrainFile file = new TerrainFile(MultiCore.LOGGER::warn);

    /** Saves now, on this thread (server stopping). */
    void save(Path dir) {
        this.file.save(dir, this.chunks, Entry::rgb);
    }

    /** Saves in the background: the server thread only copies the references (see {@link TerrainFile}). */
    void saveInBackground(Path dir) {
        this.file.saveInBackground(dir, this.chunks, Entry::rgb);
    }
}
