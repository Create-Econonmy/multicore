package com.multicore.core;

import com.multicore.MultiCore;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.util.thread.SidedThreadGroups;

/**
 * One persistent thread per parallel dimension. It sleeps between ticks and is handed a {@link Phase} by the main thread.
 * <p>
 * It is created in NeoForge's SERVER thread group so {@code EffectiveSide.get()} still reports the server side.
 */
public final class LevelThread extends Thread {
    private final ServerLevel level;
    private final ParallelTicker ticker;
    private volatile Phase pending;
    private volatile boolean retired;
    long lastTickNanos;
    /** Time this thread spent waiting for the mod lock during its last tick (written by ModLock on this thread). */
    long lockWaitNanos;
    long lastLockWaitNanos;
    /** This thread's copy of RedStoneWireBlock's shared "shouldSignal" switch (see RedStoneWireBlockMixin). */
    public boolean redstoneWireSilenced;

    LevelThread(ParallelTicker ticker, ServerLevel level, ClassLoader contextLoader) {
        super(SidedThreadGroups.SERVER, null, "MultiCore " + level.dimension().location());
        this.ticker = ticker;
        this.level = level;
        this.setDaemon(true);
        this.setContextClassLoader(contextLoader);
        this.setUncaughtExceptionHandler((t, e) -> MultiCore.LOGGER.error("MultiCore thread {} died", t.getName(), e));
    }

    public ServerLevel level() {
        return this.level;
    }

    /** Called by the main thread after it has made this thread the owner of the level. */
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
     * Ticks the level once, then keeps serving other dimension threads until everyone is done. Whatever happens,
     * the phase is told this thread finished ticking and finished helping, exactly once each; otherwise the main
     * thread would wait forever.
     */
    private void runPhase(Phase phase) {
        try {
            long start = System.nanoTime();
            this.lockWaitNanos = 0;
            try {
                tickLevel(this.level, phase.hasTimeLeft());
            } catch (Throwable t) {
                phase.fail(this.level, t);
            }
            this.lastTickNanos = System.nanoTime() - start;
            this.lastLockWaitNanos = this.lockWaitNanos;
            phase.finishedTicking();
            // Keep running this level's chunk tasks until every level is done, so another dimension thread that is
            // waiting on a chunk from this level is never stuck.
            while (!phase.allTicked()) {
                if (!this.pollOwnTasks()) {
                    LockSupport.parkNanos(this, 200_000L);
                }
            }
        } catch (Throwable t) {
            // finishedHelping() below also reports "finished ticking" if that did not happen yet.
            phase.fail(this.level, t);
        } finally {
            phase.finishedHelping();
        }
    }

    /**
     * Runs one pending chunk task of this thread's own level. Used by every wait on a dimension thread. Does nothing
     * while the thread is inside chunk bookkeeping (see {@link ChunkSystemGuard}): running tasks there would re-enter it.
     */
    public boolean pollOwnTasks() {
        if (ChunkSystemGuard.inside()) {
            return false;
        }
        try {
            return this.level.getChunkSource().pollTask();
        } catch (Throwable t) {
            MultiCore.LOGGER.error("Error running chunk task for {} while waiting", this.level.dimension().location(), t);
            return false;
        }
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
