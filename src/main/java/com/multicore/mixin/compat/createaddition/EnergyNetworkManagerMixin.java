package com.multicore.mixin.compat.createaddition;

import java.lang.reflect.Constructor;
import java.util.Map;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create Crafts &amp; Additions makes each world's EnergyNetworkManager only in LevelEvent.Load. Multiworld's worlds
 * (Fantasy runtime worlds: shop, north, war, creative...) never fire that event, so the first wire network there
 * crashed the server with a NullPointerException in EnergyNetwork.&lt;init&gt;, with or without MultiCore (found
 * 2026-11-26 with the live server's configs; on the live server only overworld, nether, end and pocket_dimension
 * get the event). The level tick listener calls tickWorld before any block entity of that world ticks, so the
 * manager is made here when it is missing.
 */
@Pseudo
@Mixin(targets = "com.mrh0.createaddition.energy.network.EnergyNetworkManager", remap = false)
public abstract class EnergyNetworkManagerMixin {
    @Shadow(remap = false)
    public static Map<LevelAccessor, Object> instances;

    private static Constructor<?> multicore$constructor;

    @Inject(method = "tickWorld", at = @At("HEAD"), remap = false)
    private static void multicore$makeMissingManager(LevelAccessor level, CallbackInfo ci) {
        if (instances == null || level.isClientSide() || instances.get(level) != null) {
            return;
        }
        try {
            if (multicore$constructor == null) {
                multicore$constructor = Class.forName("com.mrh0.createaddition.energy.network.EnergyNetworkManager")
                        .getConstructor(LevelAccessor.class);
            }
            multicore$constructor.newInstance(level); // puts itself into instances
        } catch (ReflectiveOperationException e) {
            com.multicore.MultiCore.LOGGER.warn("[MultiCore] Could not make Create Crafts & Additions' energy manager for {}", level, e);
        }
    }
}
