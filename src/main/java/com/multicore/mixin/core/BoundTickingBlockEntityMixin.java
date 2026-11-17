package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.ContentPolicy;
import com.multicore.core.LockSource;
import com.multicore.core.ModLock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Block entities from mods that are not known to be thread-safe tick under the mod lock. */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void multicore$lockBlockEntityTick(BlockEntityTicker<BlockEntity> ticker, Level level, BlockPos pos, BlockState state,
                                               BlockEntity blockEntity, Operation<Void> original) {
        LockSource lock = ContentPolicy.lockFor(blockEntity.getType());
        if (lock != null) {
            ModLock.acquire(lock);
            try {
                original.call(ticker, level, pos, state, blockEntity);
            } finally {
                ModLock.release();
            }
        } else {
            original.call(ticker, level, pos, state, blockEntity);
        }
    }
}
