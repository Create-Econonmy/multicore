package com.multicore.universal.mixin.core;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.universal.core.ChunkSystemGuard;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;

/** Marks the distance-manager update as non-reentrant; see {@link ChunkSystemGuard}. */
@Mixin(ServerChunkCache.class)
public abstract class ChunkSystemGuardMixins {
    @WrapMethod(method = "runDistanceManagerUpdates")
    private boolean mcu$guardDistanceUpdates(Operation<Boolean> original) {
        ChunkSystemGuard.enter();
        try {
            return original.call();
        } finally {
            ChunkSystemGuard.exit();
        }
    }
}
