package com.multicore.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;

/**
 * A thread that ticks one region of a dimension (see {@link RegionTicker}). It is a {@link LevelThread}, so everything
 * MultiCore does for dimension threads (the mod lock, deferring writes into other dimensions, the per-thread profiler,
 * redstone wire switch, ...) applies to it as well. It owns its dimension for the duration of a job, but never runs
 * that dimension's chunk tasks: the dimension thread does that, while every region thread is paused.
 */
public final class RegionThread extends LevelThread {
    final LevelThread owner;
    final int index;
    volatile RegionTicker.Split pending;
    /** The split and job this thread is working on, or null between jobs. Only this thread writes them. */
    @Nullable
    RegionTicker.Split split;
    int job = -1;
    @Nullable
    Deferred deferred;
    /** This thread holds its split's write lock: every other region thread is paused (see {@link RegionTicker#crossRegion}). */
    boolean exclusive;
    /** This thread took the mod lock once more for an exclusive stretch; released at the end of the item. */
    boolean exclusiveModLock;
    /** This thread's copy of ServerLevel.isUpdatingNavigations. */
    public boolean updatingNavigations;
    private Level updaterLevel;
    private NeighborUpdater updater;

    RegionThread(LevelThread owner, int index, ServerLevel level) {
        super(owner.getName() + " region " + (index + 1), level, owner.getContextClassLoader());
        this.owner = owner;
        this.index = index;
    }

    /** Between splits only (the thread is parked). */
    void setLevel(ServerLevel level) {
        if (this.level() != level) {
            this.setLevels(new ServerLevel[] {level});
        }
        this.setName("MultiCore " + level.dimension().location().getPath() + " region " + (this.index + 1));
    }

    @Nullable
    public static RegionThread current() {
        return Thread.currentThread() instanceof RegionThread t ? t : null;
    }

    /** The current thread is a region thread in the middle of a job. */
    @Nullable
    public static RegionThread working() {
        return Thread.currentThread() instanceof RegionThread t && t.deferred != null ? t : null;
    }

    /** Region threads never run chunk tasks; their dimension thread does (see {@link RegionTicker.Split#coordinate}). */
    @Override
    public boolean pollOwnTasks() {
        return false;
    }

    @Override
    public void run() {
        while (!this.isRetired()) {
            RegionTicker.Split s = this.pending;
            if (s == null) {
                LockSupport.park(this);
                continue;
            }
            this.pending = null;
            s.work(this);
        }
    }

    /** Neighbour updates queue their work in the updater; every region thread needs its own. */
    public NeighborUpdater neighborUpdater(Level level, NeighborUpdater shared) {
        if (this.deferred == null || level != this.level()) {
            return shared;
        }
        if (this.updaterLevel != level) {
            this.updater = new CollectingNeighborUpdater(level, level.getServer() == null ? 1_000_000 : level.getServer().getMaxChainedNeighborUpdates());
            this.updaterLevel = level;
        }
        return this.updater;
    }

    /** Does the chunk lie in a square of another job (another region thread's part of the world)? */
    public boolean isForeign(int chunkX, int chunkZ) {
        RegionTicker.Split s = this.split;
        if (s == null || this.job < 0) {
            return false;
        }
        int owner = s.squareJob.get(RegionPlan.squareKey(chunkX >> s.shift, chunkZ >> s.shift));
        return owner >= 0 && owner != this.job;
    }

    /** Does the chunk belong to the job this thread is running? */
    public boolean covers(int chunkX, int chunkZ) {
        RegionTicker.Split s = this.split;
        return s != null && this.job >= 0 && s.plan.jobCovers(this.job, chunkX, chunkZ, s.shift);
    }

    /** Changes to the dimension's shared structures made by one job, applied by the dimension thread afterwards. */
    static final class Deferred {
        final List<Runnable> ops = new ArrayList<>();
        final Set<Object> moved = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> removed = Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
