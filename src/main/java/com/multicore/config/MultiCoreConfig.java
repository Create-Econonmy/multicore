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
    public static final ModConfigSpec.BooleanValue REGIONS_ENABLED;
    public static final ModConfigSpec.BooleanValue REGION_AUTO_SCALE;
    public static final ModConfigSpec.DoubleValue REGION_TARGET_MS;
    public static final ModConfigSpec.DoubleValue REGION_RELAX_MS;
    public static final ModConfigSpec.IntValue REGION_MIN_SIZE_CHUNKS;
    public static final ModConfigSpec.IntValue REGION_SIZE_CHUNKS;
    public static final ModConfigSpec.IntValue REGION_THREADS;
    public static final ModConfigSpec.IntValue REGION_MIN_ENTITIES;
    public static final ModConfigSpec.IntValue REGION_MIN_BLOCK_ENTITIES;
    public static final ModConfigSpec.BooleanValue REGION_ONLY_WHEN_FASTER;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> REGION_EXCLUDED_DIMENSIONS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> REGION_SAFE_MODS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> REGION_SAFE_CONTENT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> THREAD_SAFE_CONTENT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LOCKED_CONTENT;
    public static final ModConfigSpec.BooleanValue UNLOCK_ALL_MODS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LOCKED_LISTENERS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> REGION_SAFE_LISTENERS;
    public static final ModConfigSpec.BooleanValue MAP_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> MAP_BIND;
    public static final ModConfigSpec.IntValue MAP_PORT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MAP_ALLOWED;
    public static final ModConfigSpec.BooleanValue MAP_TERRAIN;

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
                        "Dimensions that always share one thread, one entry per thread. Each entry gets that thread to itself (other",
                        "dimensions are packed onto other threads), so a single dimension can be given its own thread.",
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
        AUTO_IDLE_MS = b.comment("Under load, a dimension without players whose tick costs less than this (ms) ticks on the main thread.",
                        "0 (the default) = never: light dimensions share a dimension thread instead (see threadGroups), so no",
                        "game work is left on the main thread.")
                .defineInRange("idleMs", 0.0, 0.0, 50.0);
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
        UNLOCK_ALL_MODS = b.comment(
                        "Treat every mod as thread-safe (the default): the machines (block entities, entities, blocks) AND the event",
                        "listeners of all mods run in parallel on dimension threads and region threads, without the mod lock.",
                        "What MultiCore knows to be shared stays guarded: content in lockedContent and listeners in lockedListeners",
                        "hold the lock; the classes it patches (Create's stress, link and track networks, Supplementaries' hourglass",
                        "times, FallingTree's caches, Sable's physics engine, KubeJS, per-thread flags) are made safe; and code that",
                        "reaches into another dimension (a block entity there, Create's logistics) stops every other tick thread for",
                        "the rest of that block entity or entity tick (a world pause). false = every mod but vanilla and NeoForge on",
                        "the mod lock, as before 0.4.0.")
                .define("unlockAllMods", true);
        LOCKED_LISTENERS = b.comment(
                        "Event listeners (\"modid:EventName\", \"modid:*\" for all of a mod's, \"*:EventName\" for every mod's) that always",
                        "hold the mod lock, even with unlockAllMods. The defaults: level tick events, which now run on each world's own",
                        "thread (not the main thread) and are where mods do their once-per-tick global work.")
                .defineListAllowEmpty("lockedListeners", List.of("*:LevelTickEvent$Pre", "*:LevelTickEvent$Post"), () -> "", o -> o instanceof String);
        THREAD_SAFE_CONTENT = b.comment(
                        "Mod ids whose block entities, entities and blocks may run on several dimension threads at once, while the",
                        "mod's event listeners still hold the mod lock (unless listed in threadSafeListeners). Content listed in",
                        "lockedContent still holds the lock. EXPERIMENTAL, empty by default. [\"create\"] runs Create's machines in",
                        "parallel (the few things every machine shares are made safe by MultiCore), but a locked logistics hub (stock",
                        "ticker, packager) can then pull from a vault or chest in another dimension or region while that vault's own",
                        "unlocked belts and funnels use it on their own thread: items may be lost or duplicated. Not for a live server.")
                .defineListAllowEmpty("threadSafeContent", List.of(), () -> "", o -> o instanceof String);
        LOCKED_CONTENT = b.comment(
                        "Block entity, entity and block ids (\"modid:path\", * matches anything) that always hold the mod lock, even",
                        "when their mod is in threadSafeContent. The defaults are the parts of Create that share objects across the",
                        "whole world: trains (tracks, stations, signals, bogeys), logistics (packagers, stock tickers, frogports,",
                        "factory gauges, stock links), display links (they read trains), toolboxes and cart assemblers.")
                .defineListAllowEmpty("lockedContent", List.of(
                        "create:*track*", "create:*signal*", "create:*bogey*", "create:*station*", "create:controls",
                        "create:*train*", "create:carriage_contraption", "create:cart_assembler",
                        "create:packager", "create:repackager", "create:package_frogport", "create:package_postbox",
                        "create:packager_link", "create:stock_ticker", "create:redstone_requester", "create:factory_panel",
                        "create:table_cloth", "create:display_link", "create:toolbox",
                        "railways:*"
                ), () -> "", o -> o instanceof String);
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

        b.push("regions");
        REGIONS_ENABLED = b.comment(
                        "Region threading: one busy dimension ticked by several threads at once (like Folia).",
                        "Every tick, the entities and the block entities of a dimension are grouped into regions: squares of regionSizeChunks",
                        "that have something in them, joined with the squares around them. Regions are at least one empty square apart",
                        "(128 blocks with the default 8), so players far apart in the same world get their own thread.",
                        "Spawning, removing and moving entities, scheduled block ticks and chunk tickets from a region thread are applied",
                        "by the dimension thread right after, in order. Only works on dimensions that tick on a dimension thread.")
                .define("enabled", true);
        REGION_SIZE_CHUNKS = b.comment("Size of a square in chunks (rounded down to 2, 4, 8, 16, 32...). Regions are one square apart at least.")
                .defineInRange("regionSizeChunks", 8, 2, 64);
        REGION_THREADS = b.comment("At most this many region threads per world (0 = every logical core).")
                .defineInRange("threadsPerDimension", 0, 0, 256);
        REGION_AUTO_SCALE = b.comment(
                        "Give each world as many region threads as it needs to keep up with 20 TPS: every 5 seconds a world whose",
                        "tick takes longer than targetTickMs gets more threads (up to threadsPerDimension); if every region already",
                        "has its own thread its squares are made smaller (down to minRegionSizeChunks) so more regions appear. A world",
                        "under relaxTickMs gives threads back, then goes back to regionSizeChunks. false = always threadsPerDimension.")
                .define("autoScale", true);
        REGION_TARGET_MS = b.comment("A world whose tick takes longer than this (ms) gets more region threads.")
                .defineInRange("targetTickMs", 40.0, 1.0, 1000.0);
        REGION_RELAX_MS = b.comment("A world whose tick takes less than this (ms) gives a region thread back.")
                .defineInRange("relaxTickMs", 15.0, 0.0, 1000.0);
        REGION_MIN_SIZE_CHUNKS = b.comment("autoScale never makes squares smaller than this (chunks; 2, 4, 8...).")
                .defineInRange("minRegionSizeChunks", 4, 2, 64);
        REGION_MIN_ENTITIES = b.comment("Only split a dimension's entities when it has at least this many ticking.")
                .defineInRange("minEntities", 32, 0, 1_000_000);
        REGION_MIN_BLOCK_ENTITIES = b.comment("Only split a dimension's block entities when it has at least this many ticking.")
                .defineInRange("minBlockEntities", 64, 0, 1_000_000);
        REGION_ONLY_WHEN_FASTER = b.comment(
                        "Time every split dimension both ways (one thread vs region threads, per entity and per block entity) and only",
                        "split while the split is clearly faster. Worlds full of mod machines (Create) run all their code under the one",
                        "mod lock, so splitting them is slower than one thread; those fall back to one thread and retry later.")
                .define("onlyWhenFaster", true);
        REGION_EXCLUDED_DIMENSIONS = b.comment("Dimensions that are never split, e.g. [\"minecraft:the_end\"].")
                .defineListAllowEmpty("excludedDimensions", List.of(), () -> "", o -> o instanceof String);
        REGION_SAFE_MODS = b.comment(
                        "Mod ids whose code may run on several region threads of the same dimension at once. Code of every other mod",
                        "(even threadSafeMods) holds the mod lock on a region thread: a mod may keep state per dimension.")
                .defineListAllowEmpty("regionSafeMods", List.of("minecraft", "neoforge", "multicore"), () -> "", o -> o instanceof String);
        REGION_SAFE_CONTENT = b.comment(
                        "Mods whose block entities, entities and blocks (see threadSafeContent) may run on several region threads of",
                        "the same dimension at once. EXPERIMENTAL, empty by default; see threadSafeContent for why.")
                .defineListAllowEmpty("regionSafeContent", List.of(), () -> "", o -> o instanceof String);
        REGION_SAFE_LISTENERS = b.comment(
                        "Single event listeners that may run on several region threads of one dimension at once (\"modid:EventName\").",
                        "The defaults only touch the entity they are given.")
                .defineListAllowEmpty("regionSafeListeners", List.of(
                        "create:EntityTickEvent$Pre",
                        "create:LivingBreatheEvent",
                        "citadel:EntityTickEvent$Post"
                ), () -> "", o -> o instanceof String);
        b.pop();

        b.push("map");
        MAP_ENABLED = b.comment(
                        "A flat (2D) web map of every world: which dimension thread runs it, which chunks are loaded and ticking,",
                        "forced chunks, players and the world border. Only answers addresses in allowedNetworks.")
                .define("enabled", true);
        MAP_BIND = b.comment("Address the map listens on (0.0.0.0 = every network card of the server). Needs a restart.")
                .define("bind", "0.0.0.0");
        MAP_PORT = b.comment("Port of the map: open http://<server address>:<port>/ in a browser. Needs a restart.")
                .defineInRange("port", 18200, 1, 65535);
        MAP_ALLOWED = b.comment(
                        "Who may open the map, as address/prefix-length. Everyone else is disconnected without an answer.",
                        "The default is loopback and the private (home/office network) ranges, so it never works from the internet.")
                .defineListAllowEmpty("allowedNetworks", List.of("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12",
                        "192.168.0.0/16", "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10"), () -> "", o -> o instanceof String);
        MAP_TERRAIN = b.comment(
                        "Draw the ground under the chunks (top block colours, like a vanilla map). Read from loaded chunks only,",
                        "a few each tick on the main thread (about 1 ms at most), and kept in multicore-map/ between restarts.")
                .define("terrain", true);
        b.pop();

        SPEC = b.build();
    }

    private MultiCoreConfig() {
    }
}
