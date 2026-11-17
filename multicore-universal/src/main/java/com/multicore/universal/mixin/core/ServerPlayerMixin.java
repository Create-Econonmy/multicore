package com.multicore.universal.mixin.core;

import com.multicore.universal.core.CrossDimension;
import com.multicore.universal.core.CrossLevel;
import com.multicore.universal.core.SerialQueue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerPlayer.class, priority = 100)
public abstract class ServerPlayerMixin {
    /** ServerPlayer overrides changeDimension without calling super, so it needs its own hook. */
    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void mcu$deferDimensionChange(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (CrossDimension.mustDefer(self.level(), transition.newLevel())) {
            CrossLevel.noteAccess("moves a player to another dimension (deferred to end of tick)", transition.newLevel());
            SerialQueue.defer("player changeDimension", () -> self.changeDimension(transition));
            cir.setReturnValue(null);
        }
    }
}
