package com.multicore.universal.core;

import com.multicore.universal.MultiCore;
import com.multicore.universal.compat.DimThreadCompat;
import com.multicore.universal.mixin.core.BlockableEventLoopAccessor;
import com.multicore.universal.mixin.core.MinecraftServerAccessor;
import com.multicore.universal.access.LevelThreadAccess;
import com.multicore.universal.access.ServerChunkCacheAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Replaces the per-level loop in {@code MinecraftServer.tickChildren}.
 * <pre>
 * serial   : time sync + LevelTickEvent.Pre for every level          (main thread)
 * parallel : every parallel level ticks on its own LevelThread         (main thread waits)
 * serial   : deferred work, serial levels, LevelTickEvent.Post for all (main thread)
 * </pre>
 * Everything the vanilla loop does still happens, once per level per tick. Mod event handlers for LevelTickEvent
 * all run on the main thread, so the many mods that do global work there are unaffected.
 */
public final class ParallelTicker {
    private static final ParallelTicker INSTANCE = new ParallelTicker();
    private static final long CHECK_INTERVAL_NANOS = 250_000_000L;
    private static final long DEADLOCK_CHECK_AFTER_NANOS = 2_000_000_000L;

    /** Dimension threads; thread i runs the dimensions of group i (see {@link ThreadGroups}). */
    private final List<LevelThread> pool = new ArrayList<>();
    private volatile ThreadGroups.Settings groupSettings = ThreadGroups.Settings.DEFAULT;
    private volatile List<Set<String>> configuredGroups = List.of();
    private List<List<String>> groups = List.of();
    private List<String> groupedFor = List.of();
    private volatile String groupDescription = "";
    /** Recent tick time per dimension (ms), for grouping. Main thread only. */
    private final Map<String, Double> costMs = new java.util.HashMap<>();

    /** How dimensions share threads (see {@link ThreadGroups}). */
    public void configureGroups(ThreadGroups.Settings settings, Collection<? extends String> groupEntries) {
        this.groupSettings = settings;
        this.configuredGroups = ThreadGroups.parse(groupEntries);
        this.groupedFor = List.of();
    }

    /** For /multicore status: which dimensions share which thread right now. */
    public String groupDescription() {
        return this.groupDescription;
    }
    private volatile boolean enabled = true;
    @Nullable
    private volatile String disabledReason;
    private volatile Set<String> serialDimensions = Set.of();
    private volatile boolean fallbackOnError = true;
    private volatile long slowPhaseNanos = 10_000_000_000L;
    private boolean warnedNoCoremod;

    private ParallelTicker() {
    }

    public static ParallelTicker get() {
        return INSTANCE;
    }

    public void configure(boolean enabled, Collection<? extends String> serialDims, boolean fallback, int slowPhaseMs) {
        this.enabled = enabled;
        this.serialDimensions = Set.copyOf(serialDims);
        this.fallbackOnError = fallback;
        this.slowPhaseNanos = slowPhaseMs * 1_000_000L;
    }

    public boolean isActive() {
        return this.enabled && this.disabledReason == null;
    }

    @Nullable
    public String disabledReason() {
        return this.disabledReason;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            this.disabledReason = null;
        }
    }

    public boolean isSerial(ServerLevel level) {
        return this.serialDimensions.contains(level.dimension().location().toString());
    }

    /** Main thread only. Snapshot for /multicore status. */
    public Map<ResourceKey<Level>, Long> lastTickNanos() {
        Map<ResourceKey<Level>, Long> out = new IdentityHashMap<>();
        for (LevelThread thread : this.pool) {
            ServerLevel[] levels = thread.levels();
            long[] nanos = thread.levelTickNanos;
            for (int i = 0; i < levels.length && i < nanos.length; i++) out.put(levels[i].dimension(), nanos[i]);
        }
        return out;
    }

    /**
     * @return true if MultiCore ticked the levels; false to let the vanilla loop do it.
     */
    public boolean tickLevels(MinecraftServer server, ServerLevel[] levels, BooleanSupplier hasTimeLeft) {
        Thread main = Thread.currentThread();
        TickThreads.setServerThread(main);
        if (DimThreadCompat.isTicking(server)) {
            // dimthread is ticking the levels itself (and may already have emptied the array); never both at once.
            this.retireAll();
            return false;
        }
        TickThreads.setRealLevels(levels);
        if (!ModPolicy.coremodActive()) {
            if (!this.warnedNoCoremod) {
                this.warnedNoCoremod = true;
                MultiCore.LOGGER.error("[MultiCore] The MultiCore Universal coremod is not active ({}), so mod code would run "
                        + "unprotected on several threads. Dimensions tick on one thread instead.", ModPolicy.coremodStatus());
            }
            this.retireAll();
            return false;
        }
        if (!this.isActive() || levels.length < 2) {
            this.retireAll();
            return false;
        }

        ProfilerFiller profiler = server.getProfiler();
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        int tickCount = server.getTickCount();

        profiler.push("multicore_pre");
        for (ServerLevel level : levels) {
            if (tickCount % 20 == 0) {
                access.mcu$synchronizeTime(level);
            }
            EventHooks.fireLevelTickPre(level, hasTimeLeft);
        }

        List<ServerLevel> parallel = new ArrayList<>(levels.length);
        List<ServerLevel> serial = new ArrayList<>();
        for (ServerLevel level : levels) {
            (this.isSerial(level) ? serial : parallel).add(level);
        }

        profiler.popPush("multicore_parallel");
        if (parallel.size() >= 2) {
            this.runParallel(server, parallel, serial, hasTimeLeft, main, tickCount);
        } else {
            serial.addAll(0, parallel);
        }

        profiler.popPush("multicore_serial");
        long serialStart = System.nanoTime();
        SerialQueue.runAll();
        for (ServerLevel level : serial) {
            long start = System.nanoTime();
            LevelThread.tickLevel(level, hasTimeLeft);
            this.recordTickTime(access, level, tickCount, System.nanoTime() - start);
            SerialQueue.runAll();
        }
        for (ServerLevel level : levels) {
            EventHooks.fireLevelTickPost(level, hasTimeLeft);
        }
        SerialQueue.runAll();
        Stats.lastSerialNanos = System.nanoTime() - serialStart;
        profiler.pop();
        return true;
    }

    private void runParallel(MinecraftServer server, List<ServerLevel> parallel, List<ServerLevel> serial,
                             BooleanSupplier hasTimeLeft, Thread main, int tickCount) {
        LevelThread[] workers = this.workersFor(parallel, main, tickCount);
        Phase phase = new Phase(hasTimeLeft, main, workers);
        long start = System.nanoTime();

        for (LevelThread worker : workers) {
            for (ServerLevel level : worker.levels()) {
                setOwner(level, worker);
            }
        }
        TickThreads.setParallelPhase(true);
        Throwable mainError = null;
        try {
            for (LevelThread worker : workers) {
                worker.begin(phase);
            }
            long warnAt = start + this.slowPhaseNanos;
            long nextCheck = start + CHECK_INTERVAL_NANOS;
            boolean warned = false;
            // Never leave this loop before every dimension thread has stopped: the levels must not change hands while
            // one is still running. Errors on this thread are kept and rethrown afterwards.
            while (!phase.done()) {
                boolean worked = false;
                // Serial levels belong to this thread; a dimension thread may be waiting on one of their chunks.
                for (ServerLevel level : serial) {
                    try {
                        worked |= level.getChunkSource().pollTask();
                    } catch (Throwable t) {
                        if (mainError == null) {
                            mainError = t;
                        }
                        MultiCore.LOGGER.error("[MultiCore] Error running a chunk task for {} during the parallel tick",
                                level.dimension().location(), t);
                    }
                }
                if (!worked) {
                    LockSupport.parkNanos(this, 100_000L);
                }
                long now = System.nanoTime();
                if (now > nextCheck) {
                    nextCheck = now + CHECK_INTERVAL_NANOS;
                    phase.reapDeadThreads();
                    this.breakServerTaskDeadlock(server, phase, now - start);
                }
                if (!warned && now > warnAt) {
                    warned = true;
                    this.reportSlowPhase(workers, phase);
                }
            }
        } finally {
            TickThreads.setParallelPhase(false);
            StaticOverlay.clearMain();
            for (LevelThread worker : workers) {
                for (ServerLevel level : worker.levels()) {
                    setOwner(level, main);
                }
            }
        }
        if (mainError != null) {
            CrashReport report = CrashReport.forThrowable(mainError, "Running chunk tasks during MultiCore's parallel tick");
            throw new ReportedException(report);
        }

        long sum = 0;
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        for (LevelThread worker : workers) {
            sum += worker.lastTickNanos;
            ServerLevel[] levels = worker.levels();
            long[] nanos = worker.levelTickNanos;
            for (int i = 0; i < levels.length && i < nanos.length; i++) {
                this.recordTickTime(access, levels[i], tickCount, nanos[i]);
                this.costMs.merge(levels[i].dimension().location().toString(), nanos[i] / 1e6, (old, now) -> old * 0.9 + now * 0.1);
            }
        }
        Stats.recordPhase(System.nanoTime() - start, sum);

        if (!phase.errors.isEmpty()) {
            this.handleErrors(phase.errors);
        }
    }

    private void handleErrors(Map<ServerLevel, Throwable> errors) {
        Map.Entry<ServerLevel, Throwable> first = errors.entrySet().iterator().next();
        boolean threading = errors.values().stream().allMatch(ParallelTicker::looksLikeThreadingBug);
        if (threading && this.fallbackOnError) {
            this.disabledReason = "threading error in " + first.getKey().dimension().location() + ": " + rootCause(first.getValue());
            for (Map.Entry<ServerLevel, Throwable> e : errors.entrySet()) {
                MultiCore.LOGGER.error("[MultiCore] Threading error while ticking {} (first mod code in the trace: {}). MultiCore is "
                        + "switching to vanilla (single-thread) ticking for the rest of this session. Please report this log.",
                        e.getKey().dimension().location(), firstModFrame(e.getValue()), e.getValue());
            }
            return;
        }
        Throwable t = first.getValue();
        if (t instanceof ReportedException reported) {
            throw reported;
        }
        CrashReport report = CrashReport.forThrowable(t, "Exception ticking world");
        first.getKey().fillReportDetails(report);
        throw new ReportedException(report);
    }

    static boolean looksLikeThreadingBug(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ConcurrentModificationException) {
                return true;
            }
            String msg = String.valueOf(c.getMessage());
            if (msg.contains("multiple threads") || msg.contains("from multiple threads") || msg.contains("Accessing LegacyRandomSource")) {
                return true;
            }
            if ((c instanceof ArrayIndexOutOfBoundsException || c instanceof NullPointerException || c instanceof ClassCastException)
                    && c.getStackTrace().length > 0) {
                String top = c.getStackTrace()[0].getClassName();
                if (top.startsWith("java.util.HashMap") || top.startsWith("java.util.ArrayList") || top.startsWith("java.util.WeakHashMap")
                        || top.startsWith("it.unimi.dsi.fastutil.") || top.startsWith("java.util.IdentityHashMap")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first stack frame outside the JDK, vanilla, NeoForge, MultiCore and common libraries, for the error log. */
    static String firstModFrame(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            for (StackTraceElement e : c.getStackTrace()) {
                String n = e.getClassName();
                if (n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("sun.") || n.startsWith("net.minecraft.")
                        || n.startsWith("net.neoforged.") || n.startsWith("com.multicore.") || n.startsWith("com.mojang.")
                        || n.startsWith("it.unimi.") || n.startsWith("com.google.") || n.startsWith("org.spongepowered.")) {
                    continue;
                }
                return e.toString();
            }
        }
        return "none";
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.toString();
    }

    /**
     * A dimension thread blocked on a CompletableFuture that only the server thread would complete (a task it queued
     * with {@code server.tell()} and then waits for) can never finish, because the server thread is waiting for it.
     * As a last resort the queued server tasks are run here. Logged, because it means a mod waits on the main thread.
     */
    private void breakServerTaskDeadlock(MinecraftServer server, Phase phase, long elapsedNanos) {
        if (elapsedNanos < DEADLOCK_CHECK_AFTER_NANOS) {
            return;
        }
        Queue<Runnable> queue = ((BlockableEventLoopAccessor) server).mcu$pendingRunnables();
        if (queue.isEmpty()) {
            return;
        }
        LevelThread[] threads = phase.threads();
        LevelThread blocked = null;
        for (int i = 0; i < threads.length && blocked == null; i++) {
            if (!phase.hasTicked(i) && isWaitingOnFuture(threads[i])) {
                blocked = threads[i];
            }
        }
        if (blocked == null) {
            return;
        }
        MultiCore.LOGGER.warn("[MultiCore] {} is waiting for work queued on the server thread; running {} queued server task(s) "
                + "now to avoid a deadlock. Please report this with the stack below.", blocked.getName(), queue.size());
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : blocked.getStackTrace()) {
            sb.append("\n\tat ").append(e);
        }
        MultiCore.LOGGER.warn("[MultiCore] {}", sb);
        Runnable task;
        while ((task = queue.poll()) != null) {
            try {
                ModLock.runOnBehalfOf(blocked, task);
            } catch (Throwable t) {
                MultiCore.LOGGER.error("[MultiCore] Server task failed", t);
            }
        }
    }

    private static boolean isWaitingOnFuture(Thread thread) {
        Thread.State state = thread.getState();
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement e : thread.getStackTrace()) {
            if (e.getClassName().startsWith("java.util.concurrent.CompletableFuture")
                    && (e.getMethodName().equals("waitingGet") || e.getMethodName().equals("timedGet"))) {
                return true;
            }
        }
        return false;
    }

    private void reportSlowPhase(LevelThread[] workers, Phase phase) {
        MultiCore.LOGGER.warn("[MultiCore] Parallel tick is taking over {} ms ({} dimension(s) still ticking). Thread dumps follow.",
                this.slowPhaseNanos / 1_000_000L, phase.stillTicking());
        for (LevelThread worker : workers) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement e : worker.getStackTrace()) {
                sb.append("\n\tat ").append(e);
            }
            MultiCore.LOGGER.warn("[MultiCore] {} ({}):{}", worker.getName(), worker.getState(), sb);
        }
    }

    private void recordTickTime(MinecraftServerAccessor access, ServerLevel level, int tickCount, long nanos) {
        access.mcu$perWorldTickTimes().computeIfAbsent(level.dimension(), k -> new long[100])[tickCount % 100] = nanos;
    }

    /**
     * The threads for this tick's parallel dimensions: groups are worked out again when the set of parallel
     * dimensions changes and every 5 seconds (the load moves), then thread i gets the dimensions of group i.
     */
    private LevelThread[] workersFor(List<ServerLevel> parallel, Thread main, int tickCount) {
        List<String> ids = new ArrayList<>(parallel.size());
        Map<String, ServerLevel> byId = new java.util.HashMap<>();
        for (ServerLevel level : parallel) {
            String id = level.dimension().location().toString();
            ids.add(id);
            byId.put(id, level);
        }
        if (!ids.equals(this.groupedFor) || tickCount % 100 == 0) {
            List<List<String>> next = ThreadGroups.assign(ids, this.configuredGroups, this.groupSettings, this.costMs);
            if (!next.equals(this.groups)) {
                this.groups = next;
                this.groupDescription = ThreadGroups.describe(next);
            }
            this.groupedFor = ids;
        }
        LevelThread[] workers = new LevelThread[this.groups.size()];
        for (int i = 0; i < workers.length; i++) {
            List<String> group = this.groups.get(i);
            ServerLevel[] levels = new ServerLevel[group.size()];
            for (int j = 0; j < levels.length; j++) levels[j] = byId.get(group.get(j));
            LevelThread thread = i < this.pool.size() ? this.pool.get(i) : null;
            if (thread == null || thread.isRetired() || !thread.isAlive()) {
                thread = new LevelThread(this, levels, i, main.getContextClassLoader());
                thread.start();
                if (i < this.pool.size()) this.pool.set(i, thread);
                else this.pool.add(thread);
            } else if (!java.util.Arrays.equals(thread.levels(), levels)) {
                thread.setLevels(levels); // between ticks: the thread is parked
            }
            workers[i] = thread;
        }
        while (this.pool.size() > workers.length) {
            this.pool.remove(this.pool.size() - 1).retire();
        }
        return workers;
    }

    /** Main thread only. */
    public void retireAll() {
        if (this.pool.isEmpty()) {
            return;
        }
        this.pool.forEach(LevelThread::retire);
        this.pool.clear();
        this.groups = List.of();
        this.groupedFor = List.of();
        this.groupDescription = "";
    }

    /** Makes {@code owner} the thread that vanilla's (and Lithium's) "is this the level's thread?" checks accept. */
    static void setOwner(ServerLevel level, Thread owner) {
        ((ServerChunkCacheAccessor) level.getChunkSource()).mcu$setMainThread(owner);
        ((LevelThreadAccess) level).mcu$setThread(owner);
    }
}
