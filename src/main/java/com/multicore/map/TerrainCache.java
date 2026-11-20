package com.multicore.map;

import com.multicore.MultiCore;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
    private static final int MAGIC = 0x4d434d31; // "MCM1"
    /** A loaded chunk is coloured again after this long (players build). */
    private static final long REFRESH_TICKS = 20L * 60 * 5;

    record Entry(byte[] rgb, long tick) {
    }

    private final Map<String, ConcurrentHashMap<Long, Entry>> chunks = new ConcurrentHashMap<>();
    /** Per dimension: region (32x32 chunks) -> version, bumped whenever one of its chunks changes. */
    private final Map<String, ConcurrentHashMap<Long, Integer>> regions = new ConcurrentHashMap<>();
    /** Main thread only. */
    private final Map<String, LinkedHashSet<Long>> pending = new LinkedHashMap<>();

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

    /** Main thread, outside the parallel phase: colours queued chunks until the budget is used up. */
    void work(MinecraftServer server, long budgetNanos) {
        if (this.pending.isEmpty()) return;
        long end = System.nanoTime() + budgetNanos;
        Iterator<Map.Entry<String, LinkedHashSet<Long>>> dims = this.pending.entrySet().iterator();
        while (dims.hasNext()) {
            Map.Entry<String, LinkedHashSet<Long>> d = dims.next();
            ServerLevel level = ThreadMap.levelById(server, d.getKey());
            Iterator<Long> it = d.getValue().iterator();
            while (it.hasNext()) {
                if (System.nanoTime() > end) return;
                long key = it.next();
                it.remove();
                if (level == null) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (chunk == null) continue;
                try {
                    put(d.getKey(), key, colour(level, chunk), server.getTickCount());
                } catch (RuntimeException ex) {
                    MultiCore.LOGGER.debug("[MultiCore] map: could not colour chunk {} in {}", new ChunkPos(key), d.getKey(), ex);
                }
            }
            dims.remove();
        }
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

    void save(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            MultiCore.LOGGER.warn("[MultiCore] map: could not create {}: {}", dir, e.toString());
            return;
        }
        for (Map.Entry<String, ConcurrentHashMap<Long, Entry>> d : this.chunks.entrySet()) {
            Path file = dir.resolve(d.getKey().replaceAll("[^a-zA-Z0-9_.-]", "_") + ".bin.gz");
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(MAGIC);
                out.writeUTF(d.getKey());
                Map<Long, Entry> copy = Map.copyOf(d.getValue());
                out.writeInt(copy.size());
                for (Map.Entry<Long, Entry> e : copy.entrySet()) {
                    out.writeLong(e.getKey());
                    out.write(e.getValue().rgb);
                }
            } catch (IOException e) {
                MultiCore.LOGGER.warn("[MultiCore] map: could not save {}: {}", file, e.toString());
                continue;
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                MultiCore.LOGGER.warn("[MultiCore] map: could not save {}: {}", file, e.toString());
            }
        }
    }
}
