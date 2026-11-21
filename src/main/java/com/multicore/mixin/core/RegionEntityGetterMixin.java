package com.multicore.mixin.core;

import com.multicore.core.RegionThread;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entities removed on a region thread stay in their section until the split ends (see RegionTicker). Queries from region
 * threads skip them, as they would in vanilla, which takes them out at once.
 */
@Mixin(LevelEntityGetterAdapter.class)
public abstract class RegionEntityGetterMixin<T extends EntityAccess> {
    private static boolean multicore$gone(Object e) {
        return e instanceof Entity entity && entity.isRemoved();
    }

    @Inject(method = "get(I)Lnet/minecraft/world/level/entity/EntityAccess;", at = @At("RETURN"), cancellable = true)
    private void multicore$regionGetById(int id, CallbackInfoReturnable<T> cir) {
        if (RegionThread.working() != null && multicore$gone(cir.getReturnValue())) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "get(Ljava/util/UUID;)Lnet/minecraft/world/level/entity/EntityAccess;", at = @At("RETURN"), cancellable = true)
    private void multicore$regionGetByUuid(UUID id, CallbackInfoReturnable<T> cir) {
        if (RegionThread.working() != null && multicore$gone(cir.getReturnValue())) {
            cir.setReturnValue(null);
        }
    }

    @ModifyVariable(method = "get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V", at = @At("HEAD"), argsOnly = true)
    private Consumer<T> multicore$regionSkipRemoved(Consumer<T> consumer) {
        if (RegionThread.working() == null) {
            return consumer;
        }
        return e -> {
            if (!multicore$gone(e)) consumer.accept(e);
        };
    }

    @ModifyVariable(method = "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at = @At("HEAD"), argsOnly = true)
    private AbortableIterationConsumer<Object> multicore$regionSkipRemovedTyped(AbortableIterationConsumer<Object> consumer) {
        return multicore$skip(consumer);
    }

    @ModifyVariable(method = "get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at = @At("HEAD"), argsOnly = true)
    private AbortableIterationConsumer<Object> multicore$regionSkipRemovedAll(AbortableIterationConsumer<Object> consumer) {
        return multicore$skip(consumer);
    }

    private static AbortableIterationConsumer<Object> multicore$skip(AbortableIterationConsumer<Object> consumer) {
        if (RegionThread.working() == null) {
            return consumer;
        }
        return e -> multicore$gone(e) ? AbortableIterationConsumer.Continuation.CONTINUE : consumer.accept(e);
    }
}
