package com.multicore.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose. These Create classes hold state that every machine (and every dimension and region thread) shares:
 * the stress networks of all kinetic blocks and the redstone link networks of a level (logistics: see
 * {@link LogisticsPauseCarrier}). {@code MultiCoreMixinPlugin.postApply} makes every method of
 * them run under the mod lock while the rest of Create runs in parallel ({@code MethodLockTransformer.wrapAllMethods}).
 */
@Pseudo
@Mixin(targets = {
        "com.simibubi.create.content.kinetics.TorquePropagator",
        "com.simibubi.create.content.kinetics.KineticNetwork",
        "com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler"
}, remap = false)
public abstract class SharedStateLockCarrier {
}
