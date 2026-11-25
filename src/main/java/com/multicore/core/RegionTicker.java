package com.multicore.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.MultiCore;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.entity.EntityInLevelCallback;

/**
 * Region threading: one dimension ticked by several threads at once.
 * <p>
 * Inside a dimension's tick, the entity loop and the block entity loop are split by {@link RegionPlan} into regions at
 * least one empty square (128 blocks by default) apart, and each region runs on its own {@link RegionThread}. The
 * dimension thread waits and, when a region thread needs a chunk loaded, pauses every region thread and runs the chunk
 * tasks itself (a write lock; region threads hold the read lock while they tick one entity or block entity).
 * <p>
 * Region threads never change the dimension's shared structures directly. Spawning, removing and moving entities between
 * sections, scheduled block ticks, block events, new block entity tickers and chunk tickets are queued per job and
 * applied by the dimension thread right after the split, in order. Neighbour updates get a per-thread updater; POI
 * storage and raids are synchronized. Mod code runs under the mod lock unless the mod is in regionSafeMods.
 */
public final class RegionTicker {
    private static final long SLOW_SPLIT_NANOS = 10_000_000_000L;
    private static volatile boolean enabled = true;
    private static volatile int shift = 3;
    private static volatile int threads = 0;
    private static volatile int minEntities = 32;
    private static volatile int minBlockEntities = 64;
    private static volatile Set<String> excluded = Set.of();
    @Nullable
    private static volatile String disabledReason;
    /** Per dimension: what the last split did, for /multicore status. */
    private static final Map<String, String> LAST = new ConcurrentHashMap<>();
    /** Per dimension: region count of the last entity split (1 = not split), for the thread map. */
    private static final Map<String, Integer> LAST_REGIONS = new ConcurrentHashMap<>();

    private RegionTicker() {
    }

    public static void configure(boolean on, int sizeChunks, int threadsPerDimension, int minEnt, int minBe, Collection<? extends String> excludedDims) {
        enabled = on;
        shift = Math.max(1, 31 - Integer.numberOfLeadingZeros(Math.max(2, sizeChunks)));
        threads = threadsPerDimension;
        minEntities = minEnt;
        minBlockEntities = minBe;
        excluded = Set.copyOf(excludedDims);
        if (on) {
            disabledReason = null;
        }
        LAST.clear();
        LAST_REGIONS.clear();
    }

    /** /multicore regions on|off: until the next restart or config reload. Switching on also clears an error switch-off. */
    public static void setEnabled(boolean on) {
        enabled = on;
        RegionGovernor.reset();
        if (on) {
            disabledReason = null;
        }
    }

    public static boolean enabled() {
        return enabled && disabledReason == null;
    }

    @Nullable
    public static String disabledReason() {
        return disabledReason;
    }

    public static int squareChunks() {
        return 1 << shift;
    }

    /** Upper limit of region threads per world: the config value, or 0 = every logical core. */
    public static int threadsPerDimension() {
        int t = threads;
        if (t > 0) return t;
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    /** Per world, the most regions a split found this tick. Read and cleared by the main thread after the phase. */
    private static final Map<String, Integer> TICK_REGIONS = new ConcurrentHashMap<>();

    /** Main thread: the most regions {@code dim}'s splits found since the last call (1 = no split). */
    static int takeTickRegions(String dim) {
        Integer n = TICK_REGIONS.remove(dim);
        return n == null ? 1 : n;
    }

    public static Map<String, String> lastSplits() {
        return Map.copyOf(LAST);
    }

    public static int lastRegions(String dimension) {
        return LAST_REGIONS.getOrDefault(dimension, 1);
    }

    /**
     * May {@code level}'s loops be split right now? Only on its own dimension thread during the parallel phase: that is
     * where the mod lock and every other MultiCore safety net is active.
     */
    public static boolean wantsRegions(Level level) {
        if (!enabled || disabledReason != null || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        Thread t = Thread.currentThread();
        if (!(t instanceof LevelThread lt) || t instanceof RegionThread || !TickThreads.inParallelPhase() || !lt.owns(level)) {
            return false;
        }
        return TickThreads.isRealLevel(level) && !excluded.contains(serverLevel.dimension().location().toString());
    }

    // ---- entities ----

    /** Ticks {@code all} (the dimension's entity tick list, in order) with vanilla's per-entity {@code action}. */
    public static void tickEntities(ServerLevel level, List<Entity> all, Consumer<Entity> action) {
        int n = all.size();
        int[] cx = new int[n];
        int[] cz = new int[n];
        for (int i = 0; i < n; i++) {
            Entity e = all.get(i);
            cx[i] = e.chunkPosition().x;
            cz[i] = e.chunkPosition().z;
        }
        run(level, all, cx, cz, action, "entities", minEntities, true);
    }

    // ---- block entities ----

    /** HEAD of Level.tickBlockEntities. */
    public static void beginBlockEntities(Level level) {
        if (!wantsRegions(level)) {
            return;
        }
        LevelThread t = TickThreads.currentLevelThread();
        t.collectingFor = level;
        t.collectedBlockEntities = new ArrayList<>();
    }

    /** Instead of TickingBlockEntity.tick() inside the loop: remember it for {@link #flushBlockEntities}. */
    public static boolean collectBlockEntity(Level level, TickingBlockEntity ticker, Operation<Void> tick) {
        Thread current = Thread.currentThread();
        if (!(current instanceof LevelThread t) || t.collectingFor != level) {
            return false;
        }
        t.collectedBlockEntities.add(new PendingTick(ticker, tick));
        return true;
    }

    /** Right before Level.tickBlockEntities sets tickingBlockEntities back to false. */
    @SuppressWarnings("unchecked")
    public static void flushBlockEntities(Level level) {
        Thread current = Thread.currentThread();
        if (!(current instanceof LevelThread t) || t.collectingFor != level) {
            return;
        }
        List<Object> raw = t.collectedBlockEntities;
        t.collectingFor = null;
        t.collectedBlockEntities = null;
        List<PendingTick> ticks = (List<PendingTick>) (List<?>) raw;
        int n = ticks.size();
        int[] cx = new int[n];
        int[] cz = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos pos = ticks.get(i).ticker.getPos();
            cx[i] = pos.getX() >> 4;
            cz[i] = pos.getZ() >> 4;
        }
        run((ServerLevel) level, ticks, cx, cz, p -> {
            if (!p.ticker.isRemoved()) {
                p.tick.call(p.ticker);
            }
        }, "block entities", minBlockEntities, false);
    }

    private record PendingTick(TickingBlockEntity ticker, Operation<Void> tick) {
    }

    // ---- the split ----

    private static <T> void run(ServerLevel level, List<T> items, int[] cx, int[] cz, Consumer<T> action, String what, int min, boolean entities) {
        String dim = level.dimension().location().toString();
        // How many threads and how fine a cut this world gets is worked out by RegionScaler from its tick times.
        int maxJobs = Math.min(threadsPerDimension(), RegionScaler.get().threads(dim));
        int shift = Math.min(RegionTicker.shift, RegionScaler.get().shift(dim));
        boolean bigEnough = items.size() >= min;
        RegionPlan plan = bigEnough && RegionGovernor.allowSplit(dim, entities) ? RegionPlan.plan(cx, cz, items.size(), shift, maxJobs) : null;
        if (plan == null) {
            long serialStart = System.nanoTime();
            for (T item : items) {
                action.accept(item);
            }
            // Only loops that could have been split count as the one-thread baseline.
            if (bigEnough) RegionGovernor.record(dim, entities, false, System.nanoTime() - serialStart, items.size());
            if (entities) LAST_REGIONS.put(dim, 1);
            LAST.merge(dim, describe(what, 1, 1, items.size()), RegionTicker::mergeDescription);
            return;
        }
        LevelThread owner = TickThreads.currentLevelThread();
        RegionThread[] workers = workersFor(owner, level, plan.jobs.length);
        @SuppressWarnings("unchecked")
        Split split = new Split(level, owner, plan, shift, (List<Object>) items, (Consumer<Object>) action, workers.length);
        Stats.REGION_SPLITS.increment();
        long splitStart = System.nanoTime();
        for (RegionThread worker : workers) {
            worker.pending = split;
            LockSupport.unpark(worker);
        }
        owner.splitLevel = level;
        try {
            split.coordinate();
        } finally {
            owner.splitLevel = null;
        }
        // The dimension's tick time already holds the split's wall time; what the region threads did beyond that is
        // extra work a single thread would have had to do (see AutoAdjust's speedup).
        long splitWall = System.nanoTime() - splitStart;
        long applyStart = System.nanoTime();
        owner.regionExtraNanos += Math.max(0L, split.busyNanos.sum() - splitWall);
        split.applyDeferred();
        // The queued changes are part of what the split costs.
        RegionGovernor.record(dim, entities, true, splitWall + (System.nanoTime() - applyStart), items.size());
        if (entities) LAST_REGIONS.put(dim, plan.regions);
        TICK_REGIONS.merge(dim, plan.regions, Math::max);
        LAST.merge(dim, describe(what, plan.regions, plan.jobs.length, items.size()), RegionTicker::mergeDescription);
        Throwable error = split.error;
        if (error != null) {
            handleError(level, error);
        }
    }

    private static String describe(String what, int regions, int threads, int count) {
        return what + ": " + count + " in " + regions + (regions == 1 ? " region" : " regions") + " on " + threads
                + (threads == 1 ? " thread" : " threads");
    }

    /** Keeps the latest entity line and the latest block entity line of one dimension. */
    private static String mergeDescription(String old, String now) {
        String kind = now.substring(0, now.indexOf(':'));
        StringBuilder sb = new StringBuilder();
        for (String part : old.split("; ")) {
            if (!part.startsWith(kind + ":")) {
                sb.append(part).append("; ");
            }
        }
        return sb.append(now).toString();
    }

    private static void handleError(ServerLevel level, Throwable error) {
        if (ParallelTicker.looksLikeThreadingBug(error)) {
            disabledReason = "threading error in " + level.dimension().location() + ": " + error;
            MultiCore.LOGGER.error("[MultiCore] Threading error in a region thread of {}. Region threading is switched off for the "
                    + "rest of this session (dimension threads keep running). Please report this log.", level.dimension().location(), error);
            return;
        }
        if (error instanceof ReportedException reported) {
            throw reported;
        }
        CrashReport report = CrashReport.forThrowable(error, "Ticking a region of " + level.dimension().location());
        level.fillReportDetails(report);
        throw new ReportedException(report);
    }

    private static RegionThread[] workersFor(LevelThread owner, ServerLevel level, int count) {
        RegionThread[] pool = owner.regionWorkers;
        if (pool.length < count) {
            RegionThread[] grown = java.util.Arrays.copyOf(pool, count);
            for (int i = pool.length; i < count; i++) {
                grown[i] = new RegionThread(owner, i, level);
                grown[i].start();
            }
            owner.regionWorkers = pool = grown;
        }
        RegionThread[] out = new RegionThread[count];
        for (int i = 0; i < count; i++) {
            if (!pool[i].isAlive()) {
                pool[i] = new RegionThread(owner, i, level);
                pool[i].start();
            }
            pool[i].setLevel(level);
            out[i] = pool[i];
        }
        return out;
    }

    /** One split of one loop of one dimension. */
    static final class Split {
        final ServerLevel level;
        final LevelThread coordinator;
        final RegionPlan plan;
        final int shift;
        final List<Object> items;
        final Consumer<Object> action;
        final RegionThread.Deferred[] deferred;
        /** Region threads hold the read lock while ticking one item; the dimension thread takes the write lock to run chunk tasks. */
        final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        final AtomicInteger nextJob = new AtomicInteger();
        final AtomicInteger running;
        final AtomicInteger chunkWaiters = new AtomicInteger();
        /** Time the region threads spent on jobs, minus their mod-lock waits. */
        final java.util.concurrent.atomic.LongAdder busyNanos = new java.util.concurrent.atomic.LongAdder();
        @Nullable
        volatile Throwable error;
        /** Square key -> job owning it (-1: no job, SHARED: in the border ring of more than one job). */
        final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap squareJob = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

        Split(ServerLevel level, LevelThread coordinator, RegionPlan plan, int shift, List<Object> items, Consumer<Object> action, int workers) {
            this.level = level;
            this.coordinator = coordinator;
            this.plan = plan;
            this.shift = shift;
            this.items = items;
            this.action = action;
            this.deferred = new RegionThread.Deferred[plan.jobs.length];
            for (int i = 0; i < this.deferred.length; i++) this.deferred[i] = new RegionThread.Deferred();
            this.running = new AtomicInteger(workers);
            this.squareJob.defaultReturnValue(-1);
            for (int j = 0; j < plan.jobSquares.length; j++) {
                for (long key : plan.jobSquares[j]) this.squareJob.put(key, j);
            }
            // A job also owns the empty squares around its own: what its entities and machines use (a chest just across a
            // square edge) lies there. A border square two jobs touch belongs to neither alone.
            for (int j = 0; j < plan.jobSquares.length; j++) {
                for (long key : plan.jobSquares[j]) {
                    int sx = (int) (key >> 32);
                    int sz = (int) key;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long ring = RegionPlan.squareKey(sx + dx, sz + dz);
                            int owner = this.squareJob.get(ring);
                            if (owner == -1) {
                                this.squareJob.put(ring, j);
                            } else if (owner != j && !this.occupied(ring)) {
                                this.squareJob.put(ring, SHARED);
                            }
                        }
                    }
                }
            }
        }

        /** Is the square one with something ticking in it (not just a border square)? */
        private boolean occupied(long key) {
            for (long[] squares : this.plan.jobSquares) {
                if (java.util.Arrays.binarySearch(squares, key) >= 0) return true;
            }
            return false;
        }

        static final int SHARED = -2;

        /** On a region thread: take jobs until none are left. */
        void work(RegionThread self) {
            long started = System.nanoTime();
            long lockWaitBefore = self.lockWaitNanos;
            WorldPause.leaveSafe(self);
            try {
                int j;
                while ((j = this.nextJob.getAndIncrement()) < this.plan.jobs.length) {
                    self.split = this;
                    self.job = j;
                    self.deferred = this.deferred[j];
                    try {
                        for (int index : this.plan.jobs[j]) {
                            if (this.error != null) {
                                break;
                            }
                            // Blocked here only by another region thread's exclusive stretch or the dimension thread's chunk
                            // tasks: this thread is between two items, so it counts as stopped for a world pause.
                            WorldPause.enterSafe(self);
                            try {
                                this.rw.readLock().lock();
                            } finally {
                                WorldPause.leaveSafe(self);
                            }
                            try {
                                this.action.accept(this.items.get(index));
                            } catch (Throwable t) {
                                this.fail(t);
                            } finally {
                                this.rw.readLock().unlock();
                                self.itemDepth = 0;
                                WorldPause.end(self);
                                this.endExclusive(self);
                            }
                        }
                    } finally {
                        self.deferred = null;
                        self.job = -1;
                        self.split = null;
                    }
                }
            } catch (Throwable t) {
                this.fail(t);
            } finally {
                WorldPause.enterSafe(self);
                this.busyNanos.add(Math.max(0L, System.nanoTime() - started - (self.lockWaitNanos - lockWaitBefore)));
                if (this.running.decrementAndGet() == 0) {
                    LockSupport.unpark(this.coordinator);
                }
            }
        }

        void fail(Throwable t) {
            if (this.error == null) {
                this.error = t;
            }
        }

        /**
         * On the dimension thread, until every region thread is done: runs the chunk tasks a region thread waits for (with
         * every region thread paused) and keeps the other dimensions of this thread served.
         */
        void coordinate() {
            // The dimension thread only waits and runs chunk tasks here: it counts as stopped for a world pause.
            WorldPause.enterSafe(this.coordinator);
            try {
                this.coordinateLoop();
            } finally {
                WorldPause.leaveSafe(this.coordinator);
            }
        }

        private void coordinateLoop() {
            long warnAt = System.nanoTime() + SLOW_SPLIT_NANOS;
            boolean warned = false;
            while (this.running.get() > 0) {
                if (!warned && System.nanoTime() > warnAt) {
                    warned = true;
                    this.reportSlow();
                }
                if (WorldPause.heldByOther(this.coordinator)) {
                    // A thread elsewhere holds the world pause (and the mod lock) and may need chunks of this dimension;
                    // this dimension's region threads are stopped and hold no read lock.
                    boolean worked = false;
                    if (this.rw.writeLock().tryLock()) {
                        try {
                            int budget = 256;
                            while (budget-- > 0 && this.level.getChunkSource().pollTask()) {
                                worked = true;
                            }
                        } catch (Throwable t) {
                            MultiCore.LOGGER.error("[MultiCore] Error running a chunk task of {} during a world pause", this.level.dimension().location(), t);
                        } finally {
                            this.rw.writeLock().unlock();
                        }
                    }
                    worked |= this.coordinator.pollOtherLevels(this.level);
                    if (!worked) LockSupport.parkNanos(this, 50_000L);
                    continue;
                }
                if (this.chunkWaiters.get() > 0) {
                    // Lock order: the mod lock first, then the write lock (see crossRegion).
                    ModLock.acquire(CHUNK_TASKS);
                    this.rw.writeLock().lock();
                    try {
                        int budget = 4096;
                        while (budget-- > 0 && this.chunkWaiters.get() > 0 && this.level.getChunkSource().pollTask()) {
                            // keep going while there is work
                        }
                    } catch (Throwable t) {
                        MultiCore.LOGGER.error("[MultiCore] Error running a chunk task of {} for a region thread", this.level.dimension().location(), t);
                    } finally {
                        this.rw.writeLock().unlock();
                        ModLock.release();
                    }
                    LockSupport.parkNanos(this, 20_000L);
                } else if (!this.coordinator.pollOtherLevels(this.level)) {
                    LockSupport.parkNanos(this, 100_000L);
                }
            }
        }

        /** A split that takes this long is stuck or nearly so: log what every region thread is doing. */
        private void reportSlow() {
            MultiCore.LOGGER.warn("[MultiCore] A region split of {} is taking over {} s ({} of {} region threads still running). Thread dumps follow.",
                    this.level.dimension().location(), SLOW_SPLIT_NANOS / 1_000_000_000L, this.running.get(), this.plan.jobs.length);
            for (RegionThread worker : this.coordinator.regionWorkers) {
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement e : worker.getStackTrace()) {
                    sb.append("\n\tat ").append(e);
                }
                MultiCore.LOGGER.warn("[MultiCore] {} ({}):{}", worker.getName(), worker.getState(), sb);
            }
        }

        /** On the dimension thread, after the split: everything the jobs queued, job by job, in order. */
        void applyDeferred() {
            for (RegionThread.Deferred d : this.deferred) {
                for (Runnable op : d.ops) {
                    try {
                        op.run();
                    } catch (Throwable t) {
                        MultiCore.LOGGER.error("[MultiCore] A change queued by a region thread of {} failed", this.level.dimension().location(), t);
                    }
                }
            }
        }

        /** A region thread is about to wait (for a chunk, a future...): let the dimension thread run chunk tasks meanwhile. */
        int beginWait() {
            int holds = this.rw.getReadHoldCount();
            for (int i = 0; i < holds; i++) {
                this.rw.readLock().unlock();
            }
            // An exclusive stretch ends here: the dimension thread needs the write lock to run the tasks waited for.
            RegionThread self = RegionThread.current();
            if (self != null && self.exclusive) {
                self.exclusive = false;
                while (this.rw.isWriteLockedByCurrentThread()) this.rw.writeLock().unlock();
            }
            this.chunkWaiters.incrementAndGet();
            LockSupport.unpark(this.coordinator);
            return holds;
        }

        /**
         * The current item touches another region's part of the world: pause every other region thread until the item
         * is done. The mod lock is taken first (lock order), then the read lock is swapped for the write lock.
         */
        void beginExclusive(RegionThread self) {
            if (self.exclusive) {
                return;
            }
            if (!self.exclusiveModLock) {
                ModLock.acquire(CROSS_REGION);
                self.exclusiveModLock = true;
            }
            int holds = this.rw.getReadHoldCount();
            for (int i = 0; i < holds; i++) {
                this.rw.readLock().unlock();
            }
            boolean got = false;
            try {
                // Bounded: if something else this thread holds keeps a region thread from reaching a safe point, carry on
                // without the pause rather than wait forever.
                got = this.rw.writeLock().tryLock(EXCLUSIVE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < holds; i++) {
                this.rw.readLock().lock(); // a writer may also hold read locks; the item's own unlock stays balanced
            }
            if (got) {
                self.exclusive = true;
                Stats.REGION_EXCLUSIVE.increment();
            } else {
                Stats.REGION_EXCLUSIVE_TIMEOUTS.increment();
                if (EXCLUSIVE_TIMEOUT_LOGGED.compareAndSet(false, true)) {
                    MultiCore.LOGGER.warn("[MultiCore] {} could not pause the other region threads of {} within {} ms for a "
                            + "cross-region access and went on without it. Please report this log.", self.getName(),
                            this.level.dimension().location(), EXCLUSIVE_WAIT_MS, new Throwable("where it happened"));
                }
            }
        }

        /** End of an item: an exclusive stretch ends with it. */
        void endExclusive(RegionThread self) {
            if (self.exclusive) {
                self.exclusive = false;
                while (this.rw.isWriteLockedByCurrentThread()) this.rw.writeLock().unlock();
            }
            if (self.exclusiveModLock) {
                self.exclusiveModLock = false;
                ModLock.release();
            }
        }

        void endWait(int holds) {
            this.chunkWaiters.decrementAndGet();
            for (int i = 0; i < holds; i++) {
                this.rw.readLock().lock();
            }
        }
    }

    // ---- deferring changes to the dimension's shared structures ----

    /** On a region thread working on {@code target}: queue {@code op} for the dimension thread and return true. */
    public static boolean defer(Level target, Runnable op) {
        RegionThread t = RegionThread.working();
        if (t == null || t.level() != target) {
            return false;
        }
        t.deferred.ops.add(op);
        Stats.REGION_DEFERRED.increment();
        return true;
    }

    /** Like {@link #defer(Level, Runnable)} for structures that do not know their level (LevelTicks). */
    public static boolean deferAny(Runnable op) {
        RegionThread t = RegionThread.working();
        if (t == null) {
            return false;
        }
        t.deferred.ops.add(op);
        Stats.REGION_DEFERRED.increment();
        return true;
    }

    /** Entity moved: the section change (and what it starts or stops) is done once, after the split, from its final position. */
    public static boolean deferMove(Entity entity, EntityInLevelCallback callback) {
        RegionThread t = RegionThread.working();
        if (t == null || callback == EntityInLevelCallback.NULL || entity.level() != t.level()) {
            return false;
        }
        if (t.deferred.moved.add(callback)) {
            t.deferred.ops.add(() -> {
                if (!entity.isRemoved()) {
                    callback.onMove();
                }
            });
        }
        return true;
    }

    /** Entity removed: it is flagged removed at once (so everything skips it); leaving its section and lists happens after the split. */
    public static boolean deferRemove(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
        RegionThread t = RegionThread.working();
        if (t == null || callback == EntityInLevelCallback.NULL || entity.level() != t.level()) {
            return false;
        }
        if (t.deferred.removed.add(callback)) {
            t.deferred.ops.add(() -> callback.onRemove(reason));
        }
        return true;
    }

    /** ServerLevel.sendBlockUpdated: only mobs of the current region re-plan their path (others belong to other threads). */
    public static Set<Mob> navigatingMobs(Set<Mob> all) {
        RegionThread t = RegionThread.working();
        if (t == null) {
            return all;
        }
        return new AbstractSet<>() {
            @Override
            public Iterator<Mob> iterator() {
                Iterator<Mob> it = all.iterator();
                return new Iterator<>() {
                    Mob next = advance();

                    private Mob advance() {
                        while (it.hasNext()) {
                            Mob m = it.next();
                            if (t.covers(m.chunkPosition().x, m.chunkPosition().z)) return m;
                        }
                        return null;
                    }

                    @Override
                    public boolean hasNext() {
                        return this.next != null;
                    }

                    @Override
                    public Mob next() {
                        if (this.next == null) throw new NoSuchElementException();
                        Mob m = this.next;
                        this.next = advance();
                        return m;
                    }
                };
            }

            @Override
            public int size() {
                int n = 0;
                for (Mob ignored : this) n++;
                return n;
            }
        };
    }

    private static final LockSource CHUNK_TASKS = LockSource.of("region split: chunk tasks for a region thread");
    private static final LockSource CROSS_REGION = LockSource.of("region split: cross-region access");
    private static final long EXCLUSIVE_WAIT_MS = 2_000L;
    private static final java.util.concurrent.atomic.AtomicBoolean EXCLUSIVE_TIMEOUT_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Code on a region thread reads a block entity or changes a block in another region's part of the world (a Create
     * stock ticker pulling from a vault far away, a long teleport...). The rest of the current entity or block entity
     * tick runs with every other region thread paused, as if the dimension had a single thread.
     */
    public static void crossRegion(int blockX, int blockZ) {
        RegionThread t = RegionThread.working();
        if (t == null || t.exclusive || !t.isForeign(blockX >> 4, blockZ >> 4)) {
            return;
        }
        t.split.beginExclusive(t);
    }

    /**
     * Code on a region thread is about to use something that may reach any part of the world through references it
     * keeps (Create's logistics: a request or a stock summary pulls from vaults and chests of the whole network). Pause
     * every other region thread for the rest of the current item, whether or not a foreign block is read on the way.
     */
    public static void pauseOtherRegions() {
        RegionThread t = RegionThread.working();
        if (t == null || t.exclusive) {
            return;
        }
        t.split.beginExclusive(t);
    }

    /** For ModLock: a region thread about to block on the mod lock lets go of its read lock. Returns the holds or 0. */
    static int releaseReadsForWait() {
        RegionThread t = RegionThread.working();
        if (t == null || t.exclusive) {
            return 0;
        }
        ReentrantReadWriteLock rw = t.split.rw;
        int holds = rw.getReadHoldCount();
        for (int i = 0; i < holds; i++) {
            rw.readLock().unlock();
        }
        return holds;
    }

    static void reacquireReads(int holds) {
        if (holds <= 0) return;
        RegionThread t = RegionThread.working();
        if (t != null) {
            for (int i = 0; i < holds; i++) t.split.rw.readLock().lock();
        }
    }

    /** A region thread about to block: see {@link Split#beginWait}. Returns a token for {@link #endWait}, or -1. */
    public static int beginWait() {
        RegionThread t = RegionThread.working();
        return t == null ? -1 : t.split.beginWait();
    }

    public static void endWait(int token) {
        if (token < 0) return;
        RegionThread t = RegionThread.working();
        if (t != null) {
            t.split.endWait(token);
        }
    }
}
