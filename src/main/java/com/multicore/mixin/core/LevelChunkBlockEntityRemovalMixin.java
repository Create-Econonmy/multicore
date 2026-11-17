package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.ContentPolicy;
import com.multicore.core.LockSource;
import com.multicore.core.ModLock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The other half of {@link LevelBlockEntityLoadMixin}: a mod block entity's setRemoved / clearRemoved /
 * onChunkUnloaded (Create unregisters logistics links, kinetic networks and so on there) hold the mod lock too.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkBlockEntityRemovalMixin {
    @WrapOperation(method = "setBlockEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V"))
    private void multicore$lockReplaceRemoved(BlockEntity be, Operation<Void> original) {
        ModLock.runLocked(ContentPolicy.lockFor(be.getType()), () -> original.call(be));
    }

    @WrapOperation(method = "setBlockEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;clearRemoved()V"))
    private void multicore$lockClearRemoved(BlockEntity be, Operation<Void> original) {
        ModLock.runLocked(ContentPolicy.lockFor(be.getType()), () -> original.call(be));
    }

    @WrapOperation(method = "removeBlockEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V"))
    private void multicore$lockRemove(BlockEntity be, Operation<Void> original) {
        ModLock.runLocked(ContentPolicy.lockFor(be.getType()), () -> original.call(be));
    }

    /** Chunk unload calls onChunkUnloaded and setRemoved through method references, so the whole method is wrapped. */
    @WrapMethod(method = "clearAllBlockEntities")
    private void multicore$lockUnload(Operation<Void> original) {
        LockSource lock = null;
        if (ContentPolicy.lockingActive()) {
            for (BlockEntity be : ((LevelChunk) (Object) this).getBlockEntities().values()) {
                lock = ContentPolicy.lockFor(be.getType());
                if (lock != null) break;
            }
        }
        ModLock.runLocked(lock, () -> original.call());
    }
}
