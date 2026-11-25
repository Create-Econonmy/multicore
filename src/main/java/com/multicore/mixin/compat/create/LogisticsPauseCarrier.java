package com.multicore.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose. Create's logistics (stock tickers, factory gauges, redstone requesters, packagers) reach every
 * vault and chest of a network through references they keep, not through world lookups, so MultiCore's cross-region
 * pause (which watches block reads) does not see them. {@code MultiCoreMixinPlugin.postApply} makes every method of
 * these classes take the mod lock and, on a region thread, pause the other region threads of the dimension for the
 * rest of the entity or block entity tick. Found by CreateTests.packageRequestFromAnotherRegion: a request pulled
 * items out of a chest in another region while that region kept ticking.
 */
@Pseudo
@Mixin(targets = {
        "com.simibubi.create.content.logistics.packagerLink.LogisticsManager",
        "com.simibubi.create.content.logistics.packagerLink.GlobalLogisticsManager"
}, remap = false)
public abstract class LogisticsPauseCarrier {
}
