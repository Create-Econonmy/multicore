package com.multicore.mixin.safety;

import com.multicore.core.CrossLevel;
import com.multicore.core.LevelThread;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Code that waits for the server thread to run something would deadlock on a dimension thread: the server thread
 * is itself waiting for the dimension threads. In vanilla, tick code is on the server thread, where these calls
 * run the task immediately; MultiCore does the same on dimension threads.
 * <p>
 * Plain {@code execute()} is left alone: it queues the task for the server thread, which is safe and never waits.
 */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin {
    @Inject(method = "executeBlocking", at = @At("HEAD"), cancellable = true)
    private void multicore$runBlockingInline(Runnable task, CallbackInfo ci) {
        if ((Object) this instanceof MinecraftServer && Thread.currentThread() instanceof LevelThread) {
            CrossLevel.noteAccess("waits for the server thread (executeBlocking); ran inline", null);
            task.run();
            ci.cancel();
        }
    }

    @Inject(method = "submit(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private <V> void multicore$submitSupplierInline(Supplier<V> supplier, CallbackInfoReturnable<CompletableFuture<V>> cir) {
        if ((Object) this instanceof MinecraftServer && Thread.currentThread() instanceof LevelThread) {
            cir.setReturnValue(CompletableFuture.completedFuture(supplier.get()));
        }
    }

    @Inject(method = "submit(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void multicore$submitRunnableInline(Runnable task, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if ((Object) this instanceof MinecraftServer && Thread.currentThread() instanceof LevelThread) {
            task.run();
            cir.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }
}
