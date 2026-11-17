package com.multicore.universal.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.universal.core.LevelThread;
import com.multicore.universal.core.ParallelTicker;
import com.multicore.universal.core.TickThreads;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands the per-level loop of tickChildren to {@link ParallelTicker}. No other mod in the pack hooks this loop
 * (checked with the pack analyzer), and when MultiCore is off the original array is returned untouched.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Unique
    private static final ServerLevel[] MULTICORE$NO_LEVELS = new ServerLevel[0];
    @Unique
    private BooleanSupplier mcu$hasTimeLeft = () -> true;

    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void mcu$captureTimeLeft(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        this.mcu$hasTimeLeft = hasTimeLeft;
    }

    @WrapOperation(method = "tickChildren",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getWorldArray()[Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel[] mcu$tickLevels(MinecraftServer self, Operation<ServerLevel[]> original) {
        ServerLevel[] levels = original.call(self);
        return ParallelTicker.get().tickLevels(self, levels, this.mcu$hasTimeLeft) ? MULTICORE$NO_LEVELS : levels;
    }

    /**
     * A dimension thread is the server thread for the code running on it, as the single server thread is in vanilla.
     * Mods check {@code server.isSameThread()} (which compares against this) before touching game state and otherwise
     * skip the work (Open Parties and Claims' protection, Lootr, Polymorph...). It also makes {@code server.execute()}
     * run inline on the calling tick thread, as it does in vanilla.
     */
    @Inject(method = "getRunningThread", at = @At("HEAD"), cancellable = true)
    private void mcu$levelThreadIsServerThread(CallbackInfoReturnable<Thread> cir) {
        Thread current = Thread.currentThread();
        if (current instanceof LevelThread && TickThreads.inParallelPhase()) {
            cir.setReturnValue(current);
        }
    }

    /** The server profiler is not thread-safe; dimension threads get a no-op one. */
    @Inject(method = "getProfiler", at = @At("HEAD"), cancellable = true)
    private void mcu$levelThreadProfiler(CallbackInfoReturnable<ProfilerFiller> cir) {
        if (Thread.currentThread() instanceof LevelThread) {
            cir.setReturnValue(InactiveProfiler.INSTANCE);
        }
    }
}
