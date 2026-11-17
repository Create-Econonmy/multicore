package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.ContentPolicy;
import com.multicore.core.LockSource;
import com.multicore.core.ModLock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block callbacks that run during a level tick (scheduled ticks, random ticks, neighbour updates, placement,
 * removal, entities walking into blocks). Blocks from mods that are not known to be thread-safe run under the mod lock.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;tick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void multicore$lockTick(Block block, BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
        LockSource lock = ContentPolicy.lockFor(block);
        if (lock != null) {
            ModLock.acquire(lock);
            try {
                original.call(block, state, level, pos, random);
            } finally {
                ModLock.release();
            }
        } else {
            original.call(block, state, level, pos, random);
        }
    }

    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;randomTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void multicore$lockRandomTick(Block block, BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
        LockSource lock = ContentPolicy.lockFor(block);
        if (lock != null) {
            ModLock.acquire(lock);
            try {
                original.call(block, state, level, pos, random);
            } finally {
                ModLock.release();
            }
        } else {
            original.call(block, state, level, pos, random);
        }
    }

    @WrapOperation(method = "handleNeighborChanged", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V"))
    private void multicore$lockNeighborChanged(Block block, BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos fromPos,
                                               boolean moving, Operation<Void> original) {
        LockSource lock = ContentPolicy.lockFor(block);
        if (lock != null) {
            ModLock.acquire(lock);
            try {
                original.call(block, state, level, pos, neighbor, fromPos, moving);
            } finally {
                ModLock.release();
            }
        } else {
            original.call(block, state, level, pos, neighbor, fromPos, moving);
        }
    }

    @WrapOperation(method = "onPlace", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;onPlace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private void multicore$lockOnPlace(Block block, BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moving,
                                       Operation<Void> original) {
        LockSource lock = ContentPolicy.lockFor(block);
        if (lock != null) {
            ModLock.acquire(lock);
            try {
                original.call(block, state, level, pos, oldState, moving);
            } finally {
                ModLock.release();
            }
        } else {
            original.call(block, state, level, pos, oldState, moving);
        }
    }

    @WrapOperation(method = "onRemove", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private void multicore$lockOnRemove(Block block, BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving,
                                        Operation<Void> original) {
        LockSource lock = ContentPolicy.lockFor(block);
        if (lock != null) {
            ModLock.acquire(lock);
            try {
                original.call(block, state, level, pos, newState, moving);
            } finally {
                ModLock.release();
            }
        } else {
            original.call(block, state, level, pos, newState, moving);
        }
    }

    @WrapOperation(method = "entityInside", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V"))
    private void multicore$lockEntityInside(Block block, BlockState state, Level level, BlockPos pos, Entity entity, Operation<Void> original) {
        LockSource lock = ContentPolicy.lockFor(block);
        if (lock != null) {
            ModLock.acquire(lock);
            try {
                original.call(block, state, level, pos, entity);
            } finally {
                ModLock.release();
            }
        } else {
            original.call(block, state, level, pos, entity);
        }
    }
}
