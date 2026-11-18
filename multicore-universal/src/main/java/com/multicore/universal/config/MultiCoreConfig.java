package com.multicore.universal.config;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * config/multicore_universal-common.toml. Every option except transformExclusions can also be changed at runtime.
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
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRANSFORM_EXCLUSIONS;
    public static final ModConfigSpec.BooleanValue DEFER_CROSS_DIMENSION_WRITES;
    public static final ModConfigSpec.BooleanValue LOG_CROSS_DIMENSION_ACCESS;
    public static final ModConfigSpec.IntValue SLOW_PHASE_WARN_MS;
    public static final ModConfigSpec.BooleanValue FALLBACK_TO_SERIAL_ON_ERROR;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        ENABLED = b.comment(
                        "Tick each dimension on its own thread.",
                        "If false the server ticks exactly like vanilla NeoForge.")
                .define("enabled", true);
        SERIAL_DIMENSIONS = b.comment(
                        "Dimensions that always tick on the main server thread, after the parallel phase.",
                        "Example: [\"minecraft:the_end\", \"somemod:some_dimension\"]")
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

        b.push("compatibility");
        SERIALIZE_UNSAFE_MODS = b.comment(
                        "Code from every mod that is not in threadSafeMods (any method of any of its classes, and its mixins)",
                        "never runs on two dimension threads at the same time. Vanilla code and thread-safe mods still run",
                        "fully in parallel. Turning this off is faster but only safe if every installed mod is thread-safe.")
                .define("serializeUnsafeMods", true);
        THREAD_SAFE_MODS = b.comment(
                        "Mod ids whose code may run on several dimension threads at once. /multicore locks shows which mods",
                        "hold the shared lock the most; those are the ones worth checking. Only add a mod after checking that",
                        "it keeps no shared state between dimensions. Takes effect immediately (no restart).")
                .defineListAllowEmpty("threadSafeMods", List.of("minecraft", "neoforge", "multicore_universal"), () -> "", o -> o instanceof String);
        TRANSFORM_EXCLUSIONS = b.comment(
                        "Mod ids whose classes are not rewritten at all (needs a restart). Only for a mod whose classes fail to",
                        "load with MultiCore Universal installed. Such a mod's code runs without the lock, so only exclude a mod",
                        "that is thread-safe, or switch MultiCore off.")
                .defineListAllowEmpty("transformExclusions", List.of(), () -> "", o -> o instanceof String);
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
