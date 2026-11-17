package com.multicore.testmod.mixin;

import com.multicore.testmod.Capture;
import com.multicore.testmod.Shared;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** See {@link Capture}. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityCaptureMixin {
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void mcutest$begin(CallbackInfo ci) {
        Capture.current = (LivingEntity) (Object) this;
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void mcutest$end(CallbackInfo ci) {
        Capture.current = null;
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void mcutest$check(Vec3 input, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        LivingEntity seen = Capture.current;
        if (Shared.recording && seen != null && !self.isVehicle() && !self.isPassenger()) {
            Capture.CHECKS.incrementAndGet();
            if (seen != self) {
                Capture.MISMATCHES.incrementAndGet();
            }
        }
    }
}
