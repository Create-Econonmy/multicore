package com.multicore.universal.core;

import com.multicore.universal.MultiCore;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.util.thread.SidedThreadGroups;

/**
 * A persistent dimension thread. It runs one or more dimensions (see {@link ThreadGroups}): each tick it ticks them one
 * after another (like vanilla's single thread does), then keeps serving all of their chunk tasks until every dimension
 * thread is done. It sleeps between ticks and is handed a {@link Phase} by the main thread, which also sets which
 * dimensions it runs (only between ticks).
 * <p>
 * It is created in NeoForge's SERVER thread group so {@code EffectiveSide.get()} still reports the server side.
 */
public final class LevelThread extends TickThread {
    private final ParallelTicker ticker;
    private volatile ServerLevel[] levels;
    private volatile Phase pending;
    private volatile boolean retired;
    long lastTickNanos;
    /** Per dimension of {@link #levels}, the time its last tick took. */
    long[] levelTickNanos = new long[0];
    /** This thread's copy of RedStoneWireBlock's shared "shouldSignal" switch (see RedStoneWireBlockMixin). */
    public boolean redstoneWireSilenced;

    LevelThread(ParallelTicker ticker, ServerLevel[] levels, int index, ClassLoader contextLoader) {
        super(SidedThreadGroups.SERVER, "MultiCore " + index);
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
    }

    boolean isRetired() {
        return this.retired;
    }

    @Override
    public void run() {
        while (!this.retired) {
            Phase phase = this.pending;
            if (phase == null) {
                LockSupport.park(this);
                continue;
            }
            this.pending = null;
            this.runPhase(phase);
        }
    }

    /**
     * Ticks every dimension of this thread once, then keeps serving them until everyone is done. Whatever happens,
     * the phase is told this thread finished ticking and finished helping, exactly once each; otherwise the main
     * thread would wait forever. One dimension failing does not stop the others on this thread from ticking.
     */
    private void runPhase(Phase phase) {
        try {
            long start = System.nanoTime();
            ServerLevel[] mine = this.levels;
            for (int i = 0; i < mine.length; i++) {
                long t0 = System.nanoTime();
                try {
                    tickLevel(mine[i], phase.hasTimeLeft());
                } catch (Throwable t) {
                    phase.fail(mine[i], t);
                }
                this.levelTickNanos[i] = System.nanoTime() - t0;
                String source = ModLock.ownerSourceName();
                int leaked = ModLock.releaseLeakedLock();
                if (leaked != 0) {
                    MultiCore.LOGGER.error("[MultiCore] {} still held the mod lock after ticking {} (depth {}, taken for {}). Released "
                            + "it so the other dimensions can continue; please report this.", this.getName(),
                            mine[i].dimension().location(), leaked, source);
                }
            }
            this.lastTickNanos = System.nanoTime() - start;
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
            int leftOver = ModLock.resetCurrentThread();
            if (leftOver != 0) {
                MultiCore.LOGGER.error("[MultiCore] {} still held the mod lock (depth {}) after its tick; released it",
                        this.getName(), leftOver);
            }
            phase.finishedHelping();
        }
    }

    /**
     * Runs one pending chunk task of each of this thread's levels. Used by every wait on a dimension thread. Does
     * nothing while the thread is inside chunk bookkeeping (see {@link ChunkSystemGuard}): running tasks there would
     * re-enter it.
     */
    @Override
    public boolean pollOwnTasks() {
        if (ChunkSystemGuard.inside()) {
            return false;
        }
        boolean worked = false;
        for (ServerLevel level : this.levels) {
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
