package com.multicore.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Soak test 2026-11-26: saving the map's terrain cache stalled the server thread for up to 58 s, because Map.copyOf of
 * chunk keys (Long.hashCode of a packed x/z is x ^ z, which collides a lot) is quadratic. A big square of explored
 * chunks must save in well under a second of copying, and read back the same.
 */
class TerrainCacheSaveTest {
    @TempDir
    Path dir;

    @SuppressWarnings("unchecked")
    private static Map<String, ConcurrentHashMap<Long, TerrainCache.Entry>> chunks(TerrainCache cache) throws Exception {
        Field f = TerrainCache.class.getDeclaredField("chunks");
        f.setAccessible(true);
        return (Map<String, ConcurrentHashMap<Long, TerrainCache.Entry>>) f.get(cache);
    }

    @Test
    void aBigExploredWorldSavesQuicklyAndLoadsBack() throws Exception {
        TerrainCache cache = new TerrainCache();
        ConcurrentHashMap<Long, TerrainCache.Entry> map = new ConcurrentHashMap<>();
        int side = 550; // 302,500 chunks: an 8,800 x 8,800 block square
        for (int x = -side / 2; x < side / 2; x++) {
            for (int z = -side / 2; z < side / 2; z++) {
                byte[] rgb = new byte[768];
                rgb[0] = (byte) x;
                rgb[767] = (byte) z;
                map.put(ChunkPos.asLong(x, z), new TerrainCache.Entry(rgb, 0));
            }
        }
        chunks(cache).put("minecraft:overworld", map);

        long start = System.nanoTime();
        cache.save(this.dir);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 20_000, "saving " + map.size() + " chunks took " + ms + " ms");
        assertTrue(Files.exists(this.dir.resolve("minecraft_overworld.bin.gz")));

        TerrainCache back = new TerrainCache();
        back.load(this.dir);
        ConcurrentHashMap<Long, TerrainCache.Entry> loaded = chunks(back).get("minecraft:overworld");
        assertEquals(map.size(), loaded.size());
        TerrainCache.Entry e = loaded.get(ChunkPos.asLong(17, -40));
        assertEquals((byte) 17, e.rgb()[0]);
        assertEquals((byte) -40, e.rgb()[767]);
    }

    @Test
    void theBackgroundSaveOnlyCopiesOnTheCallingThread() throws Exception {
        TerrainCache cache = new TerrainCache();
        ConcurrentHashMap<Long, TerrainCache.Entry> map = new ConcurrentHashMap<>();
        for (int x = 0; x < 400; x++) {
            for (int z = 0; z < 400; z++) {
                map.put(ChunkPos.asLong(x, z), new TerrainCache.Entry(new byte[768], 0));
            }
        }
        chunks(cache).put("multiworld:north", map);
        long start = System.nanoTime();
        cache.saveInBackground(this.dir);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 2_000, "the calling thread spent " + ms + " ms");
        cache.save(this.dir); // waits for the background write, then writes again
        TerrainCache back = new TerrainCache();
        back.load(this.dir);
        assertEquals(map.size(), chunks(back).get("multiworld:north").size());
    }
}
