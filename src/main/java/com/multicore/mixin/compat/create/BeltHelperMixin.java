package com.multicore.mixin.compat.create;

import java.util.Collections;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Create's "stands upright on a belt" cache is an unsynchronized fastutil map that every belt fills on first use. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.belt.BeltHelper", remap = false)
public abstract class BeltHelperMixin {
    @Shadow(remap = false)
    public static Map uprightCache;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void multicore$synchronizedCache(CallbackInfo ci) {
        uprightCache = Collections.synchronizedMap(uprightCache);
    }
}
