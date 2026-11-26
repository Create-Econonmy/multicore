package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.core.LevelThread;
import com.multicore.core.WorldPause;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vanilla's own wait for a chunk (ServerChunkCache.getChunk -> managedBlock, which runs the level's chunk tasks until
 * the chunk is there) counts as stopped for a world pause, like MultiCore's waits: otherwise a pause asked elsewhere
 * waited 2 s for a thread that was only loading chunks (found by the soak test 2026-11-26).
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public abstract class ChunkWaitSafePointMixin {
    @WrapMethod(method = "managedBlock")
    private void multicore$safeWhileWaiting(BooleanSupplier done, Operation<Void> original) {
        if (!(Thread.currentThread() instanceof LevelThread t)) {
            original.call(done);
            return;
        }
        WorldPause.enterSafe(t);
        try {
            original.call(done);
        } finally {
            WorldPause.leaveSafe(t);
        }
    }
}
