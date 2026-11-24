package com.multicore.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose. A brass tunnel hands out its items with two static scratch collections ({@code distributed},
 * {@code full}) that it clears at the start of every distribution. With machines ticking on several threads, another
 * tunnel cleared them in the middle of a distribution. {@code MultiCoreMixinPlugin.postApply} makes both per-thread.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity", remap = false)
public abstract class BrassTunnelStaticsCarrier {
}
