package com.multicore.testmod.mixin;

import com.multicore.testmod.Capture;
import com.multicore.testmod.Shared;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The same capture pattern as {@link LivingEntityCaptureMixin}, but with the static field declared in the mixin (as
 * most mods do). MultiCore gives each dimension thread its own copy of such a scoped flag instead of locking all of
 * baseTick.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityScopedFlagMixin {
    @Unique
    private static LivingEntity mcutest$baseTicking;

    @Inject(method = "baseTick", at = @At("HEAD"))
    private void mcutest$beginBaseTick(CallbackInfo ci) {
        mcutest$baseTicking = (LivingEntity) (Object) this;
    }

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void mcutest$endBaseTick(CallbackInfo ci) {
        mcutest$baseTicking = null;
    }

    @Inject(method = "tickEffects", at = @At("HEAD"))
    private void mcutest$checkBaseTick(CallbackInfo ci) {
        LivingEntity seen = mcutest$baseTicking;
        if (Shared.recording && seen != null) {
            Capture.SCOPED_CHECKS.incrementAndGet();
            if (seen != (Object) this) {
                Capture.SCOPED_MISMATCHES.incrementAndGet();
            }
        }
    }
}
