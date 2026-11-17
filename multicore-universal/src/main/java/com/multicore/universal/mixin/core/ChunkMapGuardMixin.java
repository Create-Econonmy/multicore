package com.multicore.universal.mixin.core;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.universal.core.ChunkSystemGuard;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;

/** Marks the chunk unload pass as non-reentrant (it iterates the drop queue); see {@link ChunkSystemGuard}. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapGuardMixin {
    @WrapMethod(method = "tick(Ljava/util/function/BooleanSupplier;)V")
    private void mcu$guardUnloads(BooleanSupplier hasMoreTime, Operation<Void> original) {
        ChunkSystemGuard.enter();
        try {
            original.call(hasMoreTime);
        } finally {
            ChunkSystemGuard.exit();
        }
    }
}
