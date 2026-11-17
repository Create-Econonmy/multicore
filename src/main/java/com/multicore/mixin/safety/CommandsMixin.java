package com.multicore.mixin.safety;

import com.mojang.brigadier.ParseResults;
import com.multicore.core.LevelThread;
import com.multicore.core.SerialQueue;
import com.multicore.core.TickThreads;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Commands can touch any dimension (/tp, /execute in, /setblock). When one is started from a dimension thread
 * (command blocks, scripts, mods) it runs at the end of the tick on the main thread instead.
 */
@Mixin(value = Commands.class, priority = 100)
public abstract class CommandsMixin {
    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void multicore$deferPrefixed(CommandSourceStack source, String command, CallbackInfo ci) {
        if (Thread.currentThread() instanceof LevelThread && TickThreads.inParallelPhase()) {
            Commands self = (Commands) (Object) this;
            SerialQueue.defer("command /" + command, () -> self.performPrefixedCommand(source, command));
            ci.cancel();
        }
    }

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void multicore$deferCommand(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        if (Thread.currentThread() instanceof LevelThread && TickThreads.inParallelPhase()) {
            Commands self = (Commands) (Object) this;
            SerialQueue.defer("command /" + command, () -> self.performCommand(parse, command));
            ci.cancel();
        }
    }
}
