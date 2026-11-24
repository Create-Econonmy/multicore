package com.multicore.mixin.compat.create;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create's belt and chute shape caches are plain HashMaps filled lazily while blocks are asked for their shape, which
 * happens on every thread that ticks entities. Concurrent maps keep them intact.
 */
@Pseudo
@Mixin(targets = {"com.simibubi.create.content.kinetics.belt.BeltShapes", "com.simibubi.create.content.logistics.chute.ChuteShapes"}, remap = false)
public abstract class ShapeCachesMixin {
    @Shadow(remap = false)
    static Map cache;

    @Shadow(remap = false)
    static Map collisionCache;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void multicore$concurrentCaches(CallbackInfo ci) {
        cache = new ConcurrentHashMap<>();
        collisionCache = new ConcurrentHashMap<>();
    }
}
