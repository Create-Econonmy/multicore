package com.multicore.testmod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * State shared by every dimension, written the way a mod written for one server thread would: plain HashMaps,
 * ArrayLists and counters with no synchronization at all. The Atomic* "reference" counters count the same events
 * safely, so a lost update shows up as a difference.
 */
public final class Shared {
    public static final Map<Integer, Integer> COUNTS = new HashMap<>();
    public static final List<Integer> LOG = new ArrayList<>();
    public static final Map<String, Set<String>> THREADS_BY_DIMENSION = new HashMap<>();
    public static final Map<String, Integer> ENTITY_TICKS = new HashMap<>();
    public static long plainCounter;
    public static long entityTickCounter;
    public static final AtomicLong REFERENCE = new AtomicLong();
    public static final AtomicLong ENTITY_REFERENCE = new AtomicLong();
    public static final AtomicLong CROSS_DIMENSION_READS = new AtomicLong();
    public static final AtomicReference<Throwable> ERROR = new AtomicReference<>();
    public static volatile boolean recording;

    private Shared() {
    }

    /** Called by every racer block entity every tick. */
    public static void racerTick(ServerLevel level, BlockPos pos) {
        if (!recording) {
            return;
        }
        try {
            THREADS_BY_DIMENSION.computeIfAbsent(level.dimension().location().toString(), k -> new HashSet<>())
                    .add(Thread.currentThread().getName());
            for (int i = 0; i < 50; i++) {
                COUNTS.merge(i & 31, 1, Integer::sum);
                plainCounter++;
                LOG.add(i);
                if (LOG.size() > 4096) {
                    LOG.subList(0, 2048).clear();
                }
            }
            REFERENCE.addAndGet(50);
            SyncRegistry.INSTANCE.touch(level.dimension().location().toString());
            SharedData.get(level.getServer()).bump(level.dimension().location().toString());
            // Now and then read a block in another dimension while holding the mod lock (cross-dimension wait).
            if ((level.getGameTime() + pos.hashCode()) % 40 == 0) {
                MinecraftServer server = level.getServer();
                ServerLevel other = level.dimension() == Level.OVERWORLD ? server.getLevel(Level.NETHER) : server.overworld();
                if (other != null) {
                    other.getBlockState(new BlockPos(pos.getX() + 2000 + (int) (level.getGameTime() % 7) * 48, 64, pos.getZ()));
                    CROSS_DIMENSION_READS.incrementAndGet();
                }
            }
        } catch (Throwable t) {
            ERROR.compareAndSet(null, t);
        }
    }

    /** EntityTickEvent.Post listener. */
    public static void onEntityTick(Entity entity) {
        if (!recording) {
            return;
        }
        try {
            ENTITY_TICKS.merge(entity.getType().toString(), 1, Integer::sum);
            entityTickCounter++;
            ENTITY_REFERENCE.incrementAndGet();
        } catch (Throwable t) {
            ERROR.compareAndSet(null, t);
        }
    }

    /** For /mcutest report on a live server: each unsafe structure next to its safe reference count. */
    public static List<String> report(MinecraftServer server) {
        long reference = REFERENCE.get();
        long saved = SharedData.get(server).total;
        long ticks = ENTITY_REFERENCE.get();
        long perType = ENTITY_TICKS.values().stream().mapToLong(Integer::longValue).sum();
        boolean ok = ERROR.get() == null && plainCounter == reference && countsSum() == reference
                && SyncRegistry.INSTANCE.total() * 50 == reference && entityTickCounter == ticks && perType == ticks
                && Hooks.sum() == Hooks.REFERENCE.get() && Capture.MISMATCHES.get() == 0 && Capture.SCOPED_MISMATCHES.get() == 0
                && Capture.STACK_MISMATCHES.get() == 0;
        List<String> out = new ArrayList<>();
        out.add("mcutest " + (ok ? "CONSISTENT" : "BROKEN") + (recording ? " (recording)" : " (not recording)"));
        out.add("  racer updates: counter " + plainCounter + ", HashMap " + countsSum() + ", synchronized " + SyncRegistry.INSTANCE.total() * 50
                + ", saved data " + saved * 50 + " / reference " + reference);
        out.add("  entity listener: counter " + entityTickCounter + ", HashMap " + perType + " / reference " + ticks);
        out.add("  serverAiStep hook: HashMap " + Hooks.sum() + " / reference " + Hooks.REFERENCE.get());
        out.add("  capture field: " + Capture.MISMATCHES.get() + " mixups in " + Capture.CHECKS.get() + " checks; mixin flag: "
                + Capture.SCOPED_MISMATCHES.get() + " mixups in " + Capture.SCOPED_CHECKS.get() + " checks; stack: "
                + Capture.STACK_MISMATCHES.get() + " mixups in " + Capture.STACK_CHECKS.get() + " checks");
        out.add("  threads: " + THREADS_BY_DIMENSION + ", cross-dimension reads " + CROSS_DIMENSION_READS.get()
                + (ERROR.get() != null ? ", error: " + ERROR.get() : ""));
        return out;
    }

    public static long countsSum() {
        long sum = 0;
        for (int v : COUNTS.values()) {
            sum += v;
        }
        return sum;
    }

    public static void reset() {
        COUNTS.clear();
        LOG.clear();
        THREADS_BY_DIMENSION.clear();
        ENTITY_TICKS.clear();
        plainCounter = 0;
        entityTickCounter = 0;
        REFERENCE.set(0);
        ENTITY_REFERENCE.set(0);
        CROSS_DIMENSION_READS.set(0);
        ERROR.set(null);
        SyncRegistry.INSTANCE.reset();
        Capture.reset();
        Hooks.reset();
    }

    /** A singleton with synchronized methods, like many mods' managers. */
    public static final class SyncRegistry {
        public static final SyncRegistry INSTANCE = new SyncRegistry();
        private final Map<String, Long> touches = new HashMap<>();
        private long total;

        public synchronized void touch(String key) {
            this.touches.merge(key, 1L, Long::sum);
            this.total++;
        }

        public synchronized long total() {
            return this.total;
        }

        synchronized void reset() {
            this.touches.clear();
            this.total = 0;
        }
    }

    /** Global data in the overworld's storage, reached from every dimension. */
    public static final class SharedData extends SavedData {
        private static final SavedData.Factory<SharedData> FACTORY = new SavedData.Factory<>(SharedData::new, (tag, provider) -> new SharedData(), null);
        public final Map<String, Long> bumps = new HashMap<>();
        public long total;

        public static SharedData get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "mcu_testmod_shared");
        }

        public void bump(String key) {
            this.bumps.merge(key, 1L, Long::sum);
            this.total++;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            return tag;
        }
    }
}
