package com.multicore.mixin.compat.supplementaries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose. The hourglass sand times are one object for the whole server that fills a lookup map the first
 * time an hourglass asks (ArrayIndexOutOfBoundsException when two threads did it at once, found 2026-11-24 with
 * every mod unlocked). {@code MultiCoreMixinPlugin.postApply} makes every method of it take the mod lock.
 */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.supplementaries.common.block.hourglass.HourglassTimesManager", remap = false)
public abstract class SharedStateLockCarrier {
}
