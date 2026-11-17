package com.multicore.mixin.compat.cmpackagecouriers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create: Mobile Package Couriers keeps every active delivery target in one static HashMap. Its patch to the vanilla
 * sign block entity adds to it every few ticks in every dimension (without the mod lock, since signs are vanilla), and
 * couriers iterate it; on parallel dimension threads that corrupts the map or throws ConcurrentModificationException.
 * The map becomes a ConcurrentHashMap (the mod only uses put, iteration, entry.setValue and iterator.remove).
 */
@Pseudo
@Mixin(targets = "com.kreidev.cmpackagecouriers.CourierTarget", remap = false)
public abstract class CourierTargetMixin {
    @Shadow
    public static Map<Object, Integer> activeTargets;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void multicore$concurrentTargets(CallbackInfo ci) {
        activeTargets = new ConcurrentHashMap<>(activeTargets);
    }
}
