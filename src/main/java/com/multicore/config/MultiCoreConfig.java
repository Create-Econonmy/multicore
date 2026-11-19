package com.multicore.config;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * config/multicore-common.toml. Every option can also be changed at runtime with /multicore.
 */
public final class MultiCoreConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SERIAL_DIMENSIONS;
    public static final ModConfigSpec.IntValue MAX_THREADS;
    public static final ModConfigSpec.IntValue MAX_DIMENSIONS_PER_THREAD;
    public static final ModConfigSpec.DoubleValue THREAD_TARGET_MS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> THREAD_GROUPS;
    public static final ModConfigSpec.BooleanValue SERIALIZE_UNSAFE_MODS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> THREAD_SAFE_MODS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> THREAD_SAFE_LISTENERS;
    public static final ModConfigSpec.BooleanValue DEFER_CROSS_DIMENSION_WRITES;
    public static final ModConfigSpec.BooleanValue LOG_CROSS_DIMENSION_ACCESS;
    public static final ModConfigSpec.IntValue SLOW_PHASE_WARN_MS;
    public static final ModConfigSpec.BooleanValue FALLBACK_TO_SERIAL_ON_ERROR;
    public static final ModConfigSpec.BooleanValue AUTO_ADJUST;
    public static final ModConfigSpec.DoubleValue AUTO_IDLE_MS;
    public static final ModConfigSpec.DoubleValue AUTO_BUSY_MS;
    public static final ModConfigSpec.DoubleValue AUTO_LOAD_MS;
    public static final ModConfigSpec.IntValue AUTO_LOAD_SECONDS;
    public static final ModConfigSpec.DoubleValue AUTO_MIN_SPEEDUP;
    public static final ModConfigSpec.IntValue AUTO_RETRY_MINUTES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        ENABLED = b.comment(
                        "Tick each dimension on its own thread.",
                        "If false the server ticks exactly like vanilla NeoForge.")
                .define("enabled", true);
        SERIAL_DIMENSIONS = b.comment(
                        "Dimensions that always tick on the main server thread, after the parallel phase.",
                        "Example: [\"minecraft:the_end\", \"multiworld:shop\"]")
                .defineListAllowEmpty("serialDimensions", List.of(), () -> "", o -> o instanceof String);
        MAX_THREADS = b.comment(
                        "At most this many dimension threads (0 = the CPU's logical cores minus 2, at least 2).")
                .defineInRange("maxThreads", 0, 0, 256);
        MAX_DIMENSIONS_PER_THREAD = b.comment(
                        "One dimension thread runs several dimensions, one after another: light dimensions (the Nether,",
                        "the End, a quiet hub...) share a thread, up to this many per thread; a heavy one gets its own.")
                .defineInRange("maxDimensionsPerThread", 6, 1, 64);
        THREAD_TARGET_MS = b.comment(
                        "Dimensions are added to a thread until it has about this much tick time (milliseconds, measured).")
                .defineInRange("threadTargetMs", 10.0, 0.1, 1000.0);
        THREAD_GROUPS = b.comment(
                        "Dimensions that always share one thread, one entry per thread.",
                        "Example: [\"minecraft:the_nether, minecraft:the_end, multiworld:hub\"]")
                .defineListAllowEmpty("threadGroups", List.of(), () -> "", o -> o instanceof String);
        FALLBACK_TO_SERIAL_ON_ERROR = b.comment(
                        "If a dimension thread throws a threading error (ConcurrentModificationException and similar),",
                        "log it, switch MultiCore off for the rest of the session and keep the server running where possible.")
                .define("fallbackToSerialOnError", true);
        SLOW_PHASE_WARN_MS = b.comment(
                        "Log a warning with thread dumps when the parallel phase takes longer than this (milliseconds).")
                .defineInRange("slowPhaseWarnMs", 10_000, 500, 600_000);
        b.pop();

        b.push("autoAdjust");
        AUTO_ADJUST = b.comment(
                        "Adjust the regions (one per dimension) to the load. Nothing changes while the server keeps up:",
                        "only once TPS has stayed under 20 (server tick over loadMs) for loadSeconds in a row it checks every 5 seconds:",
                        "- a dimension with no players whose tick costs less than idleMs ticks on the main thread instead of its own;",
                        "- if parallel ticking was less than minSpeedup times faster than one thread over that time, tick like vanilla",
                        "  for retryMinutes, then measure again (the wait doubles each time it still does not help, up to an hour).",
                        "An idle dimension always gets its own thread back when a player enters or its tick costs more than busyMs.",
                        "/multicore status shows what it decided and why.")
                .define("enabled", true);
        AUTO_IDLE_MS = b.comment("A dimension without players whose tick costs less than this (ms) is idle.")
                .defineInRange("idleMs", 0.5, 0.0, 50.0);
        AUTO_BUSY_MS = b.comment("An idle dimension whose tick costs more than this (ms) gets its own thread back.")
                .defineInRange("busyMs", 2.0, 0.0, 50.0);
        AUTO_LOAD_MS = b.comment("The server counts as overloaded when its average tick takes longer than this (ms).",
                        "50 ms = TPS under 20.")
                .defineInRange("loadMs", 50.0, 0.0, 1000.0);
        AUTO_LOAD_SECONDS = b.comment("Auto-adjust only acts after the server has been overloaded this long in a row (seconds,",
                        "checked every 5 seconds).")
                .defineInRange("loadSeconds", 30, 5, 3600);
        AUTO_MIN_SPEEDUP = b.comment("Under load, parallel ticking must be at least this many times faster than one thread.",
                        "1.0 never falls back.")
                .defineInRange("minSpeedup", 1.05, 1.0, 16.0);
        AUTO_RETRY_MINUTES = b.comment("How long to tick on one thread before measuring parallel ticking again (minutes).")
                .defineInRange("retryMinutes", 10, 1, 60);
        b.pop();

        b.push("compatibility");
        SERIALIZE_UNSAFE_MODS = b.comment(
                        "Code from mods that are not in threadSafeMods (their block entities, entities, block ticks",
                        "and event listeners) never runs on two dimension threads at the same time.",
                        "Vanilla content and thread-safe mods still run fully in parallel.",
                        "Turning this off is faster but only safe if every mod is thread-safe.")
                .define("serializeUnsafeMods", true);
        THREAD_SAFE_MODS = b.comment(
                        "Mod ids whose content may run on several dimension threads at once.",
                        "Only add a mod after checking it keeps no shared state between dimensions.")
                .defineListAllowEmpty("threadSafeMods", List.of("minecraft", "neoforge", "multicore"), () -> "", o -> o instanceof String);
        THREAD_SAFE_LISTENERS = b.comment(
                        "Single event listeners that may run on several dimension threads at once, as \"modid:EventName\"",
                        "(nested events with $, e.g. \"curios:EntityTickEvent$Post\"). /multicore locks shows the busiest ones.",
                        "Only add a listener after checking it only touches the entity or level it is given.")
                .defineListAllowEmpty("threadSafeListeners", List.of(
                        // Checked for the Create Economy pack: each only touches the entity (or its own level) it is given.
                        "create:EntityTickEvent$Pre",
                        "create:LivingBreatheEvent",
                        "citadel:EntityTickEvent$Post",
                        "fluid:EntityTickEvent$Pre",
                        "blocksyouneed_luna:EntityTickEvent$Pre"
                ), () -> "", o -> o instanceof String);
        DEFER_CROSS_DIMENSION_WRITES = b.comment(
                        "When code on one dimension thread moves an entity into, or spawns an entity in, another dimension,",
                        "do it at the end of the tick on the main thread instead of immediately.")
                .define("deferCrossDimensionWrites", true);
        LOG_CROSS_DIMENSION_ACCESS = b.comment(
                        "Log the first time each mod reaches into another dimension from a dimension thread (with a stack trace).")
                .define("logCrossDimensionAccess", true);
        b.pop();

        SPEC = b.build();
    }

    private MultiCoreConfig() {
    }
}
