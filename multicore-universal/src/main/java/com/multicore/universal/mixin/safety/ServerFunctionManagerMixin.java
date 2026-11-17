package com.multicore.universal.mixin.safety;

import com.multicore.universal.core.LevelThread;
import com.multicore.universal.core.SerialQueue;
import com.multicore.universal.core.TickThreads;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.ServerFunctionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Functions can run any command in any dimension. When one is started from a dimension thread (advancement rewards,
 * scripts, mods) it runs at the end of the tick on the main thread, like commands.
 */
@Mixin(value = ServerFunctionManager.class, priority = 100)
public abstract class ServerFunctionManagerMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void mcu$deferFunction(CommandFunction<CommandSourceStack> function, CommandSourceStack source, CallbackInfo ci) {
        if (Thread.currentThread() instanceof LevelThread && TickThreads.inParallelPhase()) {
            ServerFunctionManager self = (ServerFunctionManager) (Object) this;
            SerialQueue.defer("function " + function.id(), () -> self.execute(function, source));
            ci.cancel();
        }
    }
}
