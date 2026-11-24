package com.multicore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.multicore.compat.DimThreadCompat;
import com.multicore.core.AutoAdjust;
import com.multicore.core.ContentPolicy;
import com.multicore.core.LockSource;
import com.multicore.core.ParallelTicker;
import com.multicore.core.Stats;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** /multicore status | on | off | auto on|off | regions on|off | locks [reset] */
public final class MultiCoreCommand {
    private MultiCoreCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("multicore")
                .requires(s -> s.hasPermission(3))
                .executes(MultiCoreCommand::status)
                .then(Commands.literal("status").executes(MultiCoreCommand::status))
                .then(Commands.literal("on").executes(ctx -> toggle(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> toggle(ctx, false)))
                .then(Commands.literal("auto")
                        .then(Commands.literal("on").executes(ctx -> auto(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> auto(ctx, false))))
                .then(Commands.literal("regions")
                        .then(Commands.literal("on").executes(ctx -> regions(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> regions(ctx, false))))
                .then(Commands.literal("locks")
                        .executes(MultiCoreCommand::locks)
                        .then(Commands.literal("reset").executes(ctx -> {
                            LockSource.reset();
                            ctx.getSource().sendSuccess(() -> Component.literal("MultiCore lock statistics reset"), false);
                            return 1;
                        }))));
    }

    /** Which mods hold the shared mod lock the most: the best candidates to check and mark thread-safe. */
    private static int locks(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("Top mod lock users since start (or last reset):").withStyle(ChatFormatting.GOLD), false);
        for (String line : LockSource.report(15)) {
            src.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    /** Region threads on or off at once (until restart); dimension threads are not affected. */
    private static int regions(CommandContext<CommandSourceStack> ctx, boolean on) {
        com.multicore.core.RegionTicker.setEnabled(on);
        ctx.getSource().sendSuccess(() -> Component.literal("MultiCore region threads " + (on ? "enabled" : "disabled")
                + " (until restart; set [regions] enabled in config/multicore-common.toml to make it permanent)"), true);
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx, boolean on) {
        ParallelTicker.get().setEnabled(on);
        ctx.getSource().sendSuccess(() -> Component.literal("MultiCore dimension threads " + (on ? "enabled" : "disabled")
                + " (until restart; set it in config/multicore-common.toml to make it permanent)"), true);
        return 1;
    }

    private static int auto(CommandContext<CommandSourceStack> ctx, boolean on) {
        AutoAdjust.Settings s = AutoAdjust.get().settings();
        AutoAdjust.get().configure(new AutoAdjust.Settings(on, s.idleMs(), s.busyMs(), s.loadMs(), s.minSpeedup(),
                s.windowTicks(), s.loadTicks(), s.retryTicks(), s.maxRetryTicks()));
        if (!on) {
            AutoAdjust.get().reset();
        }
        ctx.getSource().sendSuccess(() -> Component.literal("MultiCore auto-adjust " + (on ? "on" : "off (until restart or config reload)")), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ParallelTicker ticker = ParallelTicker.get();
        String reason = ticker.disabledReason();
        if (DimThreadCompat.isTicking(src.getServer())) {
            line(src, "Mode: ", "standing by - dimthread is ticking the dimensions (remove dimthread, or /gamerule dimthread_active false)",
                    ChatFormatting.YELLOW);
        } else if (ticker.isActive()) {
            line(src, "Mode: ", "parallel dimensions", ChatFormatting.GREEN);
        } else {
            line(src, "Mode: ", "vanilla (single thread)" + (reason != null ? " - auto-disabled: " + reason : ""), ChatFormatting.YELLOW);
        }

        double phase = Stats.avgPhaseMillis();
        double sum = Stats.avgSumOfLevelsMillis();
        if (Stats.phases() > 0) {
            line(src, "Parallel tick: ", String.format(Locale.ROOT, "%.2f ms (would be %.2f ms on one thread, %.2fx)",
                    phase, sum, phase > 0 ? sum / phase : 1.0), ChatFormatting.AQUA);
            line(src, "Serial part: ", String.format(Locale.ROOT, "%.2f ms last tick", Stats.lastSerialMillis()), ChatFormatting.AQUA);
        }

        AutoAdjust auto = AutoAdjust.get();
        long tick = src.getServer().getTickCount();
        if (!auto.settings().enabled()) {
            line(src, "Auto-adjust: ", "off", ChatFormatting.GRAY);
        } else {
            long left = auto.singleThreadTicksLeft(tick);
            String state = left > 0
                    ? String.format(Locale.ROOT, "one thread for %d:%02d more (parallel did not help under load)", left / 1200, left / 20 % 60)
                    : Double.isNaN(auto.lastSpeedup()) ? "watching" : String.format(Locale.ROOT, "watching, last speedup %.2fx", auto.lastSpeedup());
            line(src, "Auto-adjust: ", state, left > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
            long low = auto.lowTpsTicks();
            line(src, "  server tick: ", String.format(Locale.ROOT, "%.1f ms (%s)", auto.serverTickMs(),
                    low > 0 ? String.format(Locale.ROOT, "TPS under 20 for %d of %d s before it acts", low / 20,
                            auto.settings().loadTicks() / 20) : "TPS fine, nothing changes"),
                    low > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
            line(src, "  last change: ", auto.lastAction(), ChatFormatting.GRAY);
        }
        Map<ResourceKey<Level>, Long> times = ticker.lastTickNanos();
        for (ServerLevel level : src.getServer().getAllLevels()) {
            String id = level.dimension().location().toString();
            Long nanos = times.get(level.dimension());
            double avg = auto.averageMs(id);
            String cost = Double.isNaN(avg) ? "" : String.format(Locale.ROOT, ", avg %.2f ms", avg);
            String how = ticker.isConfiguredSerial(level) ? "main thread (serialDimensions)" + cost
                    : auto.isIdle(id) ? "main thread (idle)" + cost
                    : nanos == null ? "main thread" + cost
                    : String.format(Locale.ROOT, "dimension thread, %.2f ms", nanos / 1e6) + cost;
            line(src, "  " + id + ": ", how + ", " + level.players().size() + " players", ChatFormatting.WHITE);
        }

        if (!ticker.groupDescription().isEmpty()) {
            line(src, "Dimension threads: ", ticker.groupDescription(), ChatFormatting.WHITE);
        }
        String regionsOff = com.multicore.core.RegionTicker.disabledReason();
        line(src, "Region threads: ", !com.multicore.core.RegionTicker.enabled()
                ? (regionsOff != null ? "off (" + regionsOff + ")" : "off")
                : String.format(Locale.ROOT, "on, squares of %d chunks, up to %d threads per dimension, %d splits, %d deferred changes, "
                                + "%d cross-region pauses (%d timed out)",
                        com.multicore.core.RegionTicker.squareChunks(), com.multicore.core.RegionTicker.threadsPerDimension(),
                        Stats.REGION_SPLITS.sum(), Stats.REGION_DEFERRED.sum(), Stats.REGION_EXCLUSIVE.sum(),
                        Stats.REGION_EXCLUSIVE_TIMEOUTS.sum()), ChatFormatting.WHITE);
        com.multicore.core.RegionTicker.lastSplits().forEach((dim, what) -> line(src, "  " + dim + ": ", what, ChatFormatting.WHITE));
        if (com.multicore.core.RegionGovernor.enabled() && com.multicore.core.RegionTicker.enabled()) {
            com.multicore.core.RegionGovernor.describe().forEach((k, what) -> line(src, "  only when faster, " + k + ": ", what, ChatFormatting.WHITE));
        }
        // Everything else in the server's tick (player packets and movement, commands, mods' server-tick work, autosave)
        // is vanilla's main-thread work: what is left of the tick after the parts MultiCore measures.
        double measured = Stats.avgPreMs + Stats.avgWaitMs + Stats.avgDeferredMs + Stats.avgSerialLevelsMs + Stats.avgPostMs;
        line(src, "Main thread per tick: ", String.format(Locale.ROOT,
                "level-tick events %.2f ms (pre) + %.2f ms (post), deferred work %.2f ms, main-thread dimensions %.2f ms, "
                        + "waiting for dimension threads %.2f ms, rest of the server tick (players, commands, mods' server tick) %.2f ms",
                Stats.avgPreMs, Stats.avgPostMs, Stats.avgDeferredMs, Stats.avgSerialLevelsMs, Stats.avgWaitMs,
                Math.max(0, Stats.avgServerTickMs - measured)), ChatFormatting.WHITE);
        line(src, "Mod lock: ", String.format(Locale.ROOT, "%d acquisitions, %.1f ms total waiting",
                Stats.MOD_LOCK_ACQUIRES.sum(), Stats.MOD_LOCK_WAIT_NANOS.sum() / 1e6), ChatFormatting.GRAY);
        line(src, "Cross-dimension: ", String.format(Locale.ROOT, "%d reads, %d chunk loads, %d deferred actions",
                Stats.CROSS_LEVEL_READS.sum(), Stats.CROSS_LEVEL_LOADS.sum(), Stats.DEFERRED.sum()), ChatFormatting.GRAY);
        line(src, "Thread-safe mods: ", String.join(", ", ContentPolicy.safeNamespaces()), ChatFormatting.GRAY);
        if (!ContentPolicy.safeContent().isEmpty()) {
            line(src, "Thread-safe machines (not their listeners): ", String.join(", ", ContentPolicy.safeContent()), ChatFormatting.GRAY);
        }
        return 1;
    }

    private static void line(CommandSourceStack src, String label, String value, ChatFormatting color) {
        src.sendSuccess(() -> Component.literal(label).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(value).withStyle(color)), false);
    }
}
