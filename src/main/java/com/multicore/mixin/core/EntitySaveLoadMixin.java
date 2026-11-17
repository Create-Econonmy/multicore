package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.ContentPolicy;
import com.multicore.core.ModLock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Same as {@link BlockEntitySaveLoadMixin} for mod entities, which are saved and loaded with their chunk. */
@Mixin(Entity.class)
public abstract class EntitySaveLoadMixin {
    @WrapOperation(method = "saveWithoutId", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"))
    private void multicore$lockSave(Entity entity, CompoundTag tag, Operation<Void> original) {
        ModLock.runLocked(ContentPolicy.lockFor(entity), () -> original.call(entity, tag));
    }

    @WrapOperation(method = "load", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"))
    private void multicore$lockLoad(Entity entity, CompoundTag tag, Operation<Void> original) {
        ModLock.runLocked(ContentPolicy.lockFor(entity), () -> original.call(entity, tag));
    }
}
