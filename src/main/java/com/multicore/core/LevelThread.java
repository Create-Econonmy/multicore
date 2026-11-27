package com.multicore.core;

import com.multicore.MultiCore;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.util.thread.SidedThreadGroups;

/**
 * A persistent dimension thread. It runs one or more dimensions (see {@link ThreadGroups}): each tick it ticks them one
 * after another, then keeps serving all of their chunk tasks until every dimension thread is done. It sleeps between
 * ticks and is handed a {@link Phase} by the main thread, which also sets which dimensions it runs (only between ticks).
 * <p>
 * It is created in NeoForge's SERVER thread group so {@code EffectiveSide.get()} still reports the server side.
 */
public class LevelThread extends Thread {
    private final ParallelTicker ticker;
    private volatile ServerLevel[] levels;
    private volatile Phase pending;
    private volatile boolean retired;
    long lastTickNanos;
    /** Per dimension of {@link #levels}, the time its last tick took. */
    long[] levelTickNanos = new long[0];
    /** Time this thread spent waiting for the mod lock during its last tick (written by ModLock on this thread). */
    long lockWaitNanos;
    long lastLockWaitNanos;
    /** This thread's copy of RedStoneWireBlock's shared "shouldSignal" switch (see RedStoneWireBlockMixin). */
    public boolean redstoneWireSilenced;
    /** Region threads of this dimension thread (see {@link RegionTicker}); created on first use. Only this thread uses it. */
    RegionThread[] regionWorkers = new RegionThread[0];
    /** Block entities collected for a region split during Level.tickBlockEntities (see {@link RegionTicker}). */
    java.util.List<Object> collectedBlockEntities;
    net.minecraft.world.level.Level collectingFor;
    /**
     * Work its region threads did during this thread's last tick beyond the tick's own time (their busy time minus
     * mod-lock waits, minus the time the split took): a split dimension's tick is shorter than the work in it.
     */
    long regionExtraNanos;
    /** The dimension this thread is splitting right now (see {@link RegionTicker}); its tasks only run under the split's write lock. */
    volatile ServerLevel splitLevel;
    /**
     * Above zero while this thread is idle, waiting, or stopped for someone's {@link WorldPause}: it is not touching
     * the world then. Only this thread writes it.
     */
    volatile int safe = 1;
    /** Nesting of block entity / entity ticks (see {@link WorldPause#beginItem}). Only this thread uses it. */
    int itemDepth;
    /** This thread took the mod lock for a world pause it holds. */
    boolean pauseModLock;
    /** This thread held a world pause that a wait inside the same item ended: take it again once the wait is over. */
    boolean resumePause;

    /** For {@link RegionThread}. */
    protected LevelThread(String name, ServerLevel level, ClassLoader contextLoader) {
        super(SidedThreadGroups.SERVER, null, name);
        this.ticker = null;
        this.levels = new ServerLevel[] {level};
        this.setDaemon(true);
        this.setContextClassLoader(contextLoader);
        this.setUncaughtExceptionHandler((t, e) -> MultiCore.LOGGER.error("MultiCore thread {} died", t.getName(), e));
    }

    LevelThread(ParallelTicker ticker, ServerLevel[] levels, int index, ClassLoader contextLoader) {
        super(SidedThreadGroups.SERVER, null, "MultiCore " + index);
        this.ticker = ticker;
        this.setLevels(levels);
        this.setDaemon(true);
        this.setContextClassLoader(contextLoader);
        this.setUncaughtExceptionHandler((t, e) -> MultiCore.LOGGER.error("MultiCore thread {} died", t.getName(), e));
    }

    /** Main thread, between ticks only. */
    void setLevels(ServerLevel[] levels) {
        this.levels = levels.clone();
        this.levelTickNanos = new long[levels.length];
        StringBuilder name = new StringBuilder("MultiCore ");
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) name.append('+');
            name.append(levels[i].dimension().location().getPath());
        }
        this.setName(name.toString());
    }

    /** The first dimension this thread runs. */
    public ServerLevel level() {
        return this.levels[0];
    }

    public ServerLevel[] levels() {
        return this.levels;
    }

    /** Is {@code level} one of the dimensions this thread runs? */
    public boolean owns(net.minecraft.world.level.Level level) {
        for (ServerLevel l : this.levels) {
            if (l == level) {
                return true;
            }
        }
        return false;
    }

    /** Called by the main thread after it has made this thread the owner of its levels. */
    void begin(Phase phase) {
        this.pending = phase;
        LockSupport.unpark(this);
    }

    void retire() {
        this.retired = true;
        LockSupport.unpark(this);
        for (RegionThread worker : this.regionWorkers) {
            worker.retire();
        }
    }

    boolean isRetired() {
        return this.retired;
    }

    @Override
    public void run() {
        WorldPause.register(this);
        try {
            while (!this.retired) {
                Phase phase = this.pending;
                if (phase == null) {
                    LockSupport.park(this);
                    continue;
                }
                this.pending = null;
                this.runPhase(phase);
            }
        } finally {
            WorldPause.unregister(this);
        }
    }

    /**
     * Ticks every dimension of this thread once, then keeps serving them until everyone is done. Whatever happens, the
     * phase is told this thread finished ticking and finished helping, exactly once each; otherwise the main thread
     * would wait forever. One dimension failing does not stop the others on this thread from ticking.
     */
    private void runPhase(Phase phase) {
        try {
            long start = System.nanoTime();
            this.lockWaitNanos = 0;
            this.regionExtraNanos = 0;
            ServerLevel[] mine = this.levels;
            WorldPause.leaveSafe(this);
            try {
                for (int i = 0; i < mine.length; i++) {
                    long t0 = System.nanoTime();
                    try {
                        // What the main thread did for every level before 0.4.0: time sync and the level tick events,
                        // now on the level's own thread, in vanilla's order (pre, tick, post).
                        net.minecraft.server.MinecraftServer server = mine[i].getServer();
                        if (server.getTickCount() % 20 == 0) {
                            ((com.multicore.mixin.core.MinecraftServerAccessor) server).multicore$synchronizeTime(mine[i]);
                        }
                        net.neoforged.neoforge.event.EventHooks.fireLevelTickPre(mine[i], phase.hasTimeLeft());
                        tickLevel(mine[i], phase.hasTimeLeft());
                        net.neoforged.neoforge.event.EventHooks.fireLevelTickPost(mine[i], phase.hasTimeLeft());
                        com.multicore.map.ThreadMap.afterLevelTick(mine[i]);
                    } catch (Throwable t) {
                        phase.fail(mine[i], t);
                    } finally {
                        this.itemDepth = 0;
                        WorldPause.end(this);
                    }
                    this.levelTickNanos[i] = System.nanoTime() - t0;
                }
            } finally {
                WorldPause.enterSafe(this);
            }
            this.lastTickNanos = System.nanoTime() - start;
            this.lastLockWaitNanos = this.lockWaitNanos;
            phase.finishedTicking();
            // Keep running these levels' chunk tasks until every level is done, so another dimension thread that is
            // waiting on a chunk from one of them is never stuck.
            while (!phase.allTicked()) {
                if (!this.pollOwnTasks()) {
                    LockSupport.parkNanos(this, 200_000L);
                }
            }
        } catch (Throwable t) {
            // finishedHelping() below also reports "finished ticking" if that did not happen yet.
            phase.fail(this.level(), t);
        } finally {
            phase.finishedHelping();
        }
    }

    /**
     * Runs one pending chunk task of each of this thread's levels. Used by every wait on a dimension thread. Does
     * nothing while the thread is inside chunk bookkeeping (see {@link ChunkSystemGuard}): running tasks there would
     * re-enter it.
     */
    public boolean pollOwnTasks() {
        if (ChunkSystemGuard.inside()) {
            return false;
        }
        boolean worked = false;
        for (ServerLevel level : this.levels) {
            if (level == this.splitLevel) {
                continue;
            }
            try {
                worked |= level.getChunkSource().pollTask();
            } catch (Throwable t) {
                MultiCore.LOGGER.error("Error running chunk task for {} while waiting", level.dimension().location(), t);
            }
        }
        return worked;
    }

    /** Like {@link #pollOwnTasks} but leaves {@code skip} alone (it is in a region split, see {@link RegionTicker}). */
    boolean pollOtherLevels(ServerLevel skip) {
        if (ChunkSystemGuard.inside()) {
            return false;
        }
        boolean worked = false;
        for (ServerLevel level : this.levels) {
            if (level == skip) continue;
            try {
                worked |= level.getChunkSource().pollTask();
            } catch (Throwable t) {
                MultiCore.LOGGER.error("Error running chunk task for {} while waiting", level.dimension().location(), t);
            }
        }
        return worked;
    }

    static void tickLevel(ServerLevel level, BooleanSupplier hasTimeLeft) {
        try {
            level.tick(hasTimeLeft);
        } catch (ReportedException e) {
            throw e;
        } catch (Throwable throwable) {
            CrashReport report = CrashReport.forThrowable(throwable, "Exception ticking world");
            level.fillReportDetails(report);
            throw new ReportedException(report);
        }
    }
}
