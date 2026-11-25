package com.multicore.mixin.compat.fallingtree;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FallingTree remembers per block whether it is a log, leaf or wart block in plain HashMaps (computeIfAbsent). Its
 * neighbour-update listener asks from every dimension and region thread at once, which broke a HashMap
 * (ConcurrentModificationException, found 2026-11-24 with every mod unlocked). Concurrent maps instead.
 */
@Pseudo
@Mixin(targets = "fr.rakambda.fallingtree.neoforge.common.FallingTreeCommonsImpl", remap = false)
public abstract class BlockCachesMixin {
    @Shadow(remap = false) @Final @Mutable private Map<Object, Boolean> isLogBlockCache;
    @Shadow(remap = false) @Final @Mutable private Map<Object, Boolean> isLeafBlockCache;
    @Shadow(remap = false) @Final @Mutable private Map<Object, Boolean> isWartBlockCache;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void multicore$concurrentCaches(CallbackInfo ci) {
        this.isLogBlockCache = new ConcurrentHashMap<>(this.isLogBlockCache);
        this.isLeafBlockCache = new ConcurrentHashMap<>(this.isLeafBlockCache);
        this.isWartBlockCache = new ConcurrentHashMap<>(this.isWartBlockCache);
    }
}
