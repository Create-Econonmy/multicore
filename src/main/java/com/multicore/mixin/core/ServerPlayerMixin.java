package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.ContentPolicy;
import com.multicore.core.CrossDimension;
import com.multicore.core.CrossLevel;
import com.multicore.core.LockSource;
import com.multicore.core.ModLock;
import com.multicore.core.ModOwnership;
import com.multicore.core.SerialQueue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerPlayer.class, priority = 100)
public abstract class ServerPlayerMixin {
    /** ServerPlayer overrides changeDimension without calling super, so it needs its own hook. */
    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void multicore$deferDimensionChange(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (CrossDimension.mustDefer(self.level(), transition.newLevel())) {
            CrossLevel.noteAccess("moves a player to another dimension (deferred to end of tick)", transition.newLevel());
            SerialQueue.defer("player changeDimension", () -> self.changeDimension(transition));
            cir.setReturnValue(null);
        }
    }

    /**
     * The open container is synced every tick on the dimension thread. A mod's container reads that mod's storage,
     * which can be shared (e.g. backpack contents kept in overworld saved data), so it holds the mod lock like the
     * mod's other code.
     */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;broadcastChanges()V"))
    private void multicore$lockModMenuSync(AbstractContainerMenu menu, Operation<Void> original) {
        if (!ContentPolicy.lockingActive()) {
            original.call(menu);
            return;
        }
        String modId = ModOwnership.modIdOf(menu.getClass());
        if (ContentPolicy.isSafeNamespace(modId)) {
            original.call(menu);
            return;
        }
        ModLock.acquire(LockSource.of("menu " + modId));
        try {
            original.call(menu);
        } finally {
            ModLock.release();
        }
    }
}
