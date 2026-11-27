package com.multicore.map;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the map's terrain cache to disk (one gzip file per dimension: magic, dimension, count, then key + 768 colour
 * bytes per chunk). No Minecraft classes, so it can be unit tested.
 * <p>
 * The copy taken before writing is linear in the number of chunks. An earlier version used Map.copyOf, whose immutable
 * map probes linearly, and chunk keys (Long.hashCode of a packed x/z = x ^ z) collide so much that the copy grew
 * quadratically: stalls of the server thread of 13-58 s in the soak test of 2026-11-26, worse on a big explored world.
 */
final class TerrainFile {
    static final int MAGIC = 0x4d434d31; // "MCM1"

    /** One dimension's chunks, copied for writing: the keys and the (never changed) colour arrays they point to. */
    record Snapshot(String dim, long[] keys, byte[][] rgb) {
    }

    private final Object writeLock = new Object();
    private final AtomicBoolean writing = new AtomicBoolean();
    private final Consumer<String> warn;

    TerrainFile(Consumer<String> warn) {
        this.warn = warn;
    }

    static <E> List<Snapshot> snapshot(Map<String, ? extends Map<Long, E>> chunks, Function<E, byte[]> rgbOf) {
        List<Snapshot> out = new ArrayList<>();
        for (Map.Entry<String, ? extends Map<Long, E>> d : chunks.entrySet()) {
            ArrayList<Map.Entry<Long, E>> list = new ArrayList<>(d.getValue().size() + 16);
            list.addAll(d.getValue().entrySet()); // concurrent maps: weakly consistent, never throws
            long[] keys = new long[list.size()];
            byte[][] rgb = new byte[list.size()][];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = list.get(i).getKey();
                rgb[i] = rgbOf.apply(list.get(i).getValue());
            }
            out.add(new Snapshot(d.getKey(), keys, rgb));
        }
        return out;
    }

    /** Saves now, on this thread (server stopping). Waits for a background save that is still writing. */
    <E> void save(Path dir, Map<String, ? extends Map<Long, E>> chunks, Function<E, byte[]> rgbOf) {
        write(dir, snapshot(chunks, rgbOf));
    }

    /** Saves in the background: the calling thread only copies references. Skipped while the last one still writes. */
    <E> void saveInBackground(Path dir, Map<String, ? extends Map<Long, E>> chunks, Function<E, byte[]> rgbOf) {
        if (!this.writing.compareAndSet(false, true)) return;
        List<Snapshot> snap;
        try {
            snap = snapshot(chunks, rgbOf);
        } catch (RuntimeException e) {
            this.writing.set(false);
            throw e;
        }
        Thread t = new Thread(() -> {
            try {
                write(dir, snap);
            } finally {
                this.writing.set(false);
            }
        }, "MultiCore map save");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void write(Path dir, List<Snapshot> snap) {
        synchronized (this.writeLock) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                this.warn.accept("[MultiCore] map: could not create " + dir + ": " + e);
                return;
            }
            for (Snapshot d : snap) {
                Path file = dir.resolve(d.dim().replaceAll("[^a-zA-Z0-9_.-]", "_") + ".bin.gz");
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                    out.writeInt(MAGIC);
                    out.writeUTF(d.dim());
                    out.writeInt(d.keys().length);
                    for (int i = 0; i < d.keys().length; i++) {
                        out.writeLong(d.keys()[i]);
                        out.write(d.rgb()[i]);
                    }
                } catch (IOException e) {
                    this.warn.accept("[MultiCore] map: could not save " + file + ": " + e);
                    continue;
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    this.warn.accept("[MultiCore] map: could not save " + file + ": " + e);
                }
            }
        }
    }
}
