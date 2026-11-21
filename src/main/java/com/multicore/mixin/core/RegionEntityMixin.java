package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.RegionTicker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * On a region thread an entity's move into another section and its removal from the dimension's lists are done by the
 * dimension thread after the split (see {@link RegionTicker}). The entity's position and removed flag change at once.
 */
@Mixin(value = Entity.class, priority = 100)
public abstract class RegionEntityMixin {
    @WrapOperation(method = "setPosRaw", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityInLevelCallback;onMove()V"))
    private void multicore$regionMove(EntityInLevelCallback callback, Operation<Void> original) {
        if (!RegionTicker.deferMove((Entity) (Object) this, callback)) {
            original.call(callback);
        }
    }

    @WrapOperation(method = "setRemoved", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityInLevelCallback;onRemove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void multicore$regionRemove(EntityInLevelCallback callback, Entity.RemovalReason reason, Operation<Void> original) {
        if (!RegionTicker.deferRemove((Entity) (Object) this, callback, reason)) {
            original.call(callback, reason);
        }
    }
}
