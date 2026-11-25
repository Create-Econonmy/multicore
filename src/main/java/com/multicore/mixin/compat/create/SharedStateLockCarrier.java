package com.multicore.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose. These Create classes hold state that every machine (and every dimension and region thread) shares:
 * the stress networks of all kinetic blocks, the redstone link networks of a level, and the train track graphs
 * (a Steam 'n' Rails crossing ticking on one thread walked a graph that Create changed on another:
 * ConcurrentModificationException, found 2026-11-24 with every mod unlocked) (logistics: see
 * {@link LogisticsPauseCarrier}). {@code MultiCoreMixinPlugin.postApply} makes every method of
 * them run under the mod lock while the rest of Create runs in parallel ({@code MethodLockTransformer.wrapAllMethods}).
 */
@Pseudo
@Mixin(targets = {
        "com.simibubi.create.content.kinetics.TorquePropagator",
        "com.simibubi.create.content.kinetics.KineticNetwork",
        "com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler",
        "com.simibubi.create.content.trains.track.TrackPropagator",
        "com.simibubi.create.content.trains.graph.TrackGraph",
        "com.simibubi.create.content.trains.GlobalRailwayManager"
}, remap = false)
public abstract class SharedStateLockCarrier {
}
