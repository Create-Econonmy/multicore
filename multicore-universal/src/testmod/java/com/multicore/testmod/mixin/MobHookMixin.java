package com.multicore.testmod.mixin;

import com.multicore.testmod.Capture;
import com.multicore.testmod.Hooks;
import com.multicore.testmod.Shared;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A hook that mutates a static HashMap through a static helper (see {@link Hooks}), and a capture stack: a static
 * ArrayDeque pushed when serverAiStep starts, popped when it ends, and peeked by a call nested inside. A stack cannot
 * be made per thread, so MultiCore must hold the lock for the whole serverAiStep.
 */
@Mixin(Mob.class)
public abstract class MobHookMixin {
    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void mcutest$count(CallbackInfo ci) {
        Hooks.onServerAiStep((Mob) (Object) this);
        Capture.STACK.push((Mob) (Object) this);
    }

    @Inject(method = "serverAiStep", at = @At("TAIL"))
    private void mcutest$pop(CallbackInfo ci) {
        if (!Capture.STACK.isEmpty()) {
            Capture.STACK.pop();
        }
    }

    @Inject(method = "customServerAiStep", at = @At("HEAD"))
    private void mcutest$peek(CallbackInfo ci) {
        Object top = Capture.STACK.peek();
        if (Shared.recording && top != null) {
            Capture.STACK_CHECKS.incrementAndGet();
            if (top != (Object) this) {
                Capture.STACK_MISMATCHES.incrementAndGet();
            }
        }
    }
}
