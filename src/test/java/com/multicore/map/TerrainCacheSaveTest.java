package com.multicore.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Soak test 2026-11-26: saving the map's terrain cache stalled the server thread for up to 58 s, because Map.copyOf of
 * chunk keys (Long.hashCode of a packed x/z is x ^ z, which collides a lot) is quadratic. A big square of explored
 * chunks must save quickly, and read back the same (read here the way TerrainCache.load does).
 */
class TerrainCacheSaveTest {
    @TempDir
    Path dir;

    /** ChunkPos.asLong, without Minecraft on the unit test classpath. */
    private static long key(int x, int z) {
        return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32);
    }

    private static Map<Long, byte[]> read(Path file) throws Exception {
        Map<Long, byte[]> out = new HashMap<>();
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                new java.util.zip.GZIPInputStream(Files.newInputStream(file))))) {
            assertEquals(TerrainFile.MAGIC, in.readInt());
            in.readUTF();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                byte[] rgb = new byte[768];
                in.readFully(rgb);
                out.put(k, rgb);
            }
        }
        return out;
    }

    private static Map<String, ConcurrentHashMap<Long, byte[]>> square(String dim, int from, int to) {
        ConcurrentHashMap<Long, byte[]> map = new ConcurrentHashMap<>();
        for (int x = from; x < to; x++) {
            for (int z = from; z < to; z++) {
                byte[] rgb = new byte[768];
                rgb[0] = (byte) x;
                rgb[767] = (byte) z;
                map.put(key(x, z), rgb);
            }
        }
        Map<String, ConcurrentHashMap<Long, byte[]>> chunks = new ConcurrentHashMap<>();
        chunks.put(dim, map);
        return chunks;
    }

    @Test
    void aBigExploredWorldSavesQuicklyAndReadsBack() throws Exception {
        Map<String, ConcurrentHashMap<Long, byte[]>> chunks = square("minecraft:overworld", -275, 275); // 302,500 chunks
        TerrainFile file = new TerrainFile(msg -> { throw new AssertionError(msg); });

        long start = System.nanoTime();
        java.util.List<TerrainFile.Snapshot> snap = TerrainFile.snapshot(chunks, Function.identity());
        long copyMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(copyMs < 2_000, "copying 302,500 chunks took " + copyMs + " ms on the calling thread");
        assertEquals(302_500, snap.get(0).keys().length);

        file.save(this.dir, chunks, Function.identity());
        Map<Long, byte[]> back = read(this.dir.resolve("minecraft_overworld.bin.gz"));
        assertEquals(302_500, back.size());
        assertEquals((byte) 17, back.get(key(17, -40))[0]);
        assertEquals((byte) -40, back.get(key(17, -40))[767]);
    }

    @Test
    void theBackgroundSaveWritesTheSameAndASaveAfterItWaits() throws Exception {
        Map<String, ConcurrentHashMap<Long, byte[]>> chunks = square("multiworld:north", 0, 400); // 160,000 chunks
        TerrainFile file = new TerrainFile(msg -> { throw new AssertionError(msg); });
        long start = System.nanoTime();
        file.saveInBackground(this.dir, chunks, Function.identity());
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 2_000, "the calling thread spent " + ms + " ms");
        file.save(this.dir, chunks, Function.identity()); // waits for the background write, then writes again
        assertEquals(160_000, read(this.dir.resolve("multiworld_north.bin.gz")).size());
    }
}
