package com.multicore.mixin.compat.railways;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose. Marks the classes Steam 'n' Rails merges code into that pass flags through the statics of
 * {@code com.railwayteam.railways.util.MixinVariables} (a carriage is travelling, switches are skipped, a ghast
 * fireball is exploding, recursion depths). {@code MultiCoreMixinPlugin.postApply} makes those fields per-thread, so a
 * flag set by one dimension thread (or createthreadedtrains' worker) is not seen by another. Priority 5000 so the
 * Rails mixins are already merged when it runs.
 */
@Pseudo
@Mixin(targets = {
        "com.simibubi.create.content.trains.entity.Carriage",
        "com.simibubi.create.content.trains.entity.CarriageContraptionEntity",
        "com.simibubi.create.content.trains.entity.Navigation",
        "com.simibubi.create.content.trains.entity.TravellingPoint",
        "com.simibubi.create.content.trains.graph.TrackEdge",
        "com.simibubi.create.content.trains.signal.SignalPropagator",
        "net.minecraft.world.entity.projectile.LargeFireball",
        "net.minecraft.world.level.ExplosionDamageCalculator",
        "net.minecraft.world.level.EntityBasedExplosionDamageCalculator"
}, priority = 5000, remap = false)
public abstract class MixinVariablesCarrier {
}
