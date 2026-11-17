package com.multicore.universal.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.multicore.universal.compat.DimThreadCompat;
import com.multicore.universal.compat.MixinLockExtension;
import com.multicore.universal.core.LockSource;
import com.multicore.universal.core.ModPolicy;
import com.multicore.universal.core.ParallelTicker;
import com.multicore.universal.core.Stats;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** /multicore status | on | off | locks [reset] */
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
                .then(Commands.literal("locks")
                        .executes(MultiCoreCommand::locks)
                        .then(Commands.literal("reset").executes(ctx -> {
                            LockSource.reset();
                            com.multicore.universal.core.Hotspots.reset();
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
        src.sendSuccess(() -> Component.literal("Methods that take it most often (sampled):").withStyle(ChatFormatting.GOLD), false);
        for (String line : com.multicore.universal.core.Hotspots.report(10)) {
            src.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx, boolean on) {
        ParallelTicker.get().setEnabled(on);
        ctx.getSource().sendSuccess(() -> Component.literal("MultiCore dimension threads " + (on ? "enabled" : "disabled")
                + " (until restart; set it in config/multicore_universal-common.toml to make it permanent)"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ParallelTicker ticker = ParallelTicker.get();
        String reason = ticker.disabledReason();
        if (!ModPolicy.coremodActive()) {
            line(src, "Mode: ", "vanilla (single thread) - coremod not active: " + ModPolicy.coremodStatus(), ChatFormatting.RED);
        } else if (DimThreadCompat.isTicking(src.getServer())) {
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

        Map<ResourceKey<Level>, Long> times = ticker.lastTickNanos();
        for (ServerLevel level : src.getServer().getAllLevels()) {
            Long nanos = times.get(level.dimension());
            String how = ticker.isSerial(level) ? "serial" : nanos == null ? "main thread" : String.format(Locale.ROOT, "%.2f ms", nanos / 1e6);
            line(src, "  " + level.dimension().location() + ": ", how, ChatFormatting.WHITE);
        }

        line(src, "Mod lock: ", String.format(Locale.ROOT, "%d acquisitions, %.1f ms total waiting",
                Stats.MOD_LOCK_ACQUIRES.sum(), Stats.MOD_LOCK_WAIT_NANOS.sum() / 1e6), ChatFormatting.GRAY);
        line(src, "Protected code: ", String.format(Locale.ROOT, "%d mods, %d mixin hooks, %d vanilla methods locked whole%s",
                ModPolicy.wovenMods().size(), MixinLockExtension.handlerCount(), MixinLockExtension.wholeMethodCount(),
                ModPolicy.serializeUnsafe() ? "" : " (serializeUnsafeMods is OFF)"), ChatFormatting.GRAY);
        line(src, "Cross-dimension: ", String.format(Locale.ROOT, "%d reads, %d chunk loads, %d deferred actions",
                Stats.CROSS_LEVEL_READS.sum(), Stats.CROSS_LEVEL_LOADS.sum(), Stats.DEFERRED.sum()), ChatFormatting.GRAY);
        line(src, "Thread-safe mods: ", String.join(", ", ModPolicy.safeMods()), ChatFormatting.GRAY);
        return 1;
    }

    private static void line(CommandSourceStack src, String label, String value, ChatFormatting color) {
        src.sendSuccess(() -> Component.literal(label).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(value).withStyle(color)), false);
    }
}
