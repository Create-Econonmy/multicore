package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.multicore.access.LevelThreadAccess;
import com.multicore.core.CrossDimension;
import com.multicore.core.CrossLevel;
import com.multicore.core.LevelThread;
import com.multicore.core.SerialQueue;
import com.multicore.core.TickThreads;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin implements LevelThreadAccess {
    /**
     * The thread allowed to use this level. Vanilla and Lithium compare against it; MultiCore points it at the
     * dimension thread during the parallel phase.
     */
    @Shadow
    @Final
    @Mutable
    private Thread thread;

    @Override
    public void multicore$setThread(Thread thread) {
        this.thread = thread;
    }

    /**
     * Vanilla returns no block entity to any thread except the level's own. Other dimension threads are let through
     * (a cross-dimension read, logged once per caller).
     */
    @ModifyExpressionValue(method = "getBlockEntity", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;thread:Ljava/lang/Thread;"))
    private Thread multicore$allowLevelThreads(Thread original) {
        Thread current = Thread.currentThread();
        if (current != original && current instanceof LevelThread) {
            if (TickThreads.isForeignLevelThread((Level) (Object) this)) {
                CrossLevel.noteAccess("reads a block entity in another dimension", (Level) (Object) this);
            }
            return current;
        }
        return original;
    }

    /**
     * A dimension thread setting a block in another of the server's dimensions would modify that dimension's chunks while
     * its own thread uses them. The change is applied at the end of the tick on the main thread instead.
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void multicore$foreignSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (Thread.currentThread() instanceof LevelThread && CrossDimension.mustDeferWriteTo((Level) (Object) this)) {
            Level self = (Level) (Object) this;
            BlockPos immutable = pos.immutable();
            CrossLevel.noteAccess("sets a block in another dimension (deferred to end of tick)", self);
            SerialQueue.defer("setBlock", () -> self.setBlock(immutable, state, flags, recursionLeft));
            cir.setReturnValue(true);
        }
    }

    /** The server profiler is not thread-safe; dimension threads get a no-op one. */
    @Inject(method = "getProfiler", at = @At("HEAD"), cancellable = true)
    private void multicore$levelThreadProfiler(CallbackInfoReturnable<ProfilerFiller> cir) {
        if (Thread.currentThread() instanceof LevelThread) {
            cir.setReturnValue(InactiveProfiler.INSTANCE);
        }
    }
}
