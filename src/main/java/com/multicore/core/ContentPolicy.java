package com.multicore.core;

import java.util.Collection;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Decides whether a piece of content (a block entity, entity, block or event listener) must hold the {@link ModLock}
 * while it runs on a dimension thread. Content from mods listed in threadSafeMods runs freely in parallel.
 * <p>
 * Decisions are cached on the registry object itself (see {@link PolicyHolder}) and invalidated by bumping the
 * generation when the config changes.
 */
public final class ContentPolicy {
    private static volatile int generation = 1;
    private static volatile boolean serializeUnsafe = true;
    private static volatile Set<String> safeNamespaces = Set.of("minecraft", "neoforge", "multicore");

    private ContentPolicy() {
    }

    private static volatile Set<String> safeListeners = Set.of();
    private static volatile Set<String> regionSafeNamespaces = Set.of("minecraft", "neoforge", "multicore");
    private static volatile Set<String> regionSafeListeners = Set.of();
    /** Some thread-safe mods are not region-safe: region threads must look again. */
    private static volatile boolean regionStricter;
    private static final java.util.Map<String, LockSource> REGION_SOURCES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Mods (and single listeners) whose code may run on several region threads of the same dimension at once. Being
     * safe on different dimensions (threadSafeMods) is not enough: a mod may keep state per dimension.
     */
    public static void configureRegions(Collection<? extends String> safeMods, Collection<? extends String> listeners) {
        regionSafeNamespaces = Set.copyOf(safeMods);
        regionSafeListeners = Set.copyOf(listeners);
        regionStricter = !regionSafeNamespaces.containsAll(safeNamespaces) || !regionSafeListeners.containsAll(safeListeners);
        generation++;
    }

    private static boolean onRegionThread() {
        return Thread.currentThread() instanceof RegionThread;
    }

    public static void configure(boolean serialize, Collection<? extends String> safeMods, Collection<? extends String> listeners) {
        serializeUnsafe = serialize;
        safeNamespaces = Set.copyOf(safeMods);
        safeListeners = Set.copyOf(listeners);
        regionStricter = !regionSafeNamespaces.containsAll(safeNamespaces) || !regionSafeListeners.containsAll(safeListeners);
        generation++;
    }

    public static int generation() {
        return generation;
    }

    public static boolean isSafeNamespace(@Nullable String namespace) {
        return namespace != null && safeNamespaces.contains(namespace)
                && (!regionStricter || !onRegionThread() || regionSafeNamespaces.contains(namespace));
    }

    /** A mod's listeners for one event, listed as "modid:EventName" (nested events as "EntityTickEvent$Pre"). */
    public static boolean isSafeListener(String modId, String eventName) {
        return safeNamespaces.contains(modId) || safeListeners.contains(modId + ":" + eventName);
    }

    /** {@link #isSafeListener} for region threads. */
    public static boolean isRegionSafeListener(String modId, String eventName) {
        return isSafeListener(modId, eventName)
                && (regionSafeNamespaces.contains(modId) || regionSafeListeners.contains(modId + ":" + eventName));
    }

    /** Does a region thread have to look again (some thread-safe mods are not region-safe)? */
    public static boolean regionStricter() {
        return regionStricter;
    }

    public static Set<String> safeNamespaces() {
        return safeNamespaces;
    }

    /** Fast pre-check shared by all hooks: only dimension threads during the parallel phase ever need the lock. */
    public static boolean lockingActive() {
        return serializeUnsafe && Thread.currentThread() instanceof LevelThread && TickThreads.inParallelPhase();
    }

    /** @return the lock source to hold while this block entity ticks, or null to run it without the lock */
    @Nullable
    public static LockSource lockFor(BlockEntityType<?> type) {
        return lockingActive() ? source((PolicyHolder) type, "block entity ", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type)) : null;
    }

    @Nullable
    public static LockSource lockFor(Entity entity) {
        if (!lockingActive()) return null;
        EntityType<?> type = entity.getType();
        return source((PolicyHolder) type, "entity ", BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    @Nullable
    public static LockSource lockFor(Block block) {
        return lockingActive() ? source((PolicyHolder) block, "block ", BuiltInRegistries.BLOCK.getKey(block)) : null;
    }

    @Nullable
    private static LockSource source(PolicyHolder holder, String kind, @Nullable ResourceLocation id) {
        int gen = generation;
        if (holder.multicore$policyStamp() != gen) {
            boolean unsafe = id == null || !safeNamespaces.contains(id.getNamespace());
            // Source first, stamp second: a thread that sees the new stamp also sees the new source.
            holder.multicore$setLockSource(unsafe ? LockSource.of(kind + id) : null);
            holder.multicore$setPolicyStamp(gen);
        }
        LockSource source = holder.multicore$lockSource();
        if (source == null && regionStricter && id != null && onRegionThread() && !regionSafeNamespaces.contains(id.getNamespace())) {
            String name = kind + id + " (region)";
            return REGION_SOURCES.computeIfAbsent(name, LockSource::of);
        }
        return source;
    }

    /** Implemented by mixins on BlockEntityType, EntityType and Block. */
    public interface PolicyHolder {
        int multicore$policyStamp();

        void multicore$setPolicyStamp(int stamp);

        @Nullable
        LockSource multicore$lockSource();

        void multicore$setLockSource(@Nullable LockSource source);
    }
}
