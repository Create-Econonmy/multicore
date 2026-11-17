package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.ContentPolicy;
import com.multicore.core.ModLock;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chunks are saved and loaded on their dimension's thread (unloads in ChunkMap.processUnloads, loads in the chunk
 * system's tasks), so a mod block entity's saveAdditional / loadAdditional runs there in parallel with other dimensions.
 * Mods keep static caches in that code: Supplementaries' TextHolder memoises codecs in a static Int2ObjectArrayMap,
 * which threw ArrayIndexOutOfBoundsException when two dimensions saved Doormats at once. Mod block entities now
 * save and load under the mod lock, like their ticks.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntitySaveLoadMixin {
    @WrapOperation(method = {"saveWithoutMetadata", "saveCustomOnly"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;saveAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V"))
    private void multicore$lockSave(BlockEntity be, CompoundTag tag, HolderLookup.Provider registries, Operation<Void> original) {
        ModLock.runLocked(ContentPolicy.lockFor(be.getType()), () -> original.call(be, tag, registries));
    }

    @WrapOperation(method = {"loadWithComponents", "loadCustomOnly"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V"))
    private void multicore$lockLoad(BlockEntity be, CompoundTag tag, HolderLookup.Provider registries, Operation<Void> original) {
        ModLock.runLocked(ContentPolicy.lockFor(be.getType()), () -> original.call(be, tag, registries));
    }
}
