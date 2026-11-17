package com.multicore.mixin.compat.openpartiesandclaims;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose. Marks every class that reads or writes Open Parties and Claims' "capture" statics in
 * {@code ServerCore} (the dying entity, the projectile that is hitting, the entity dropping loot...), including
 * the Create and vanilla classes its own mixins are merged into. {@code MultiCoreMixinPlugin.postApply} turns those
 * fields into per-thread values. Priority 5000 so Open Parties and Claims' mixins are already merged when it runs.
 */
@Pseudo
@Mixin(targets = {
        "xaero.pac.common.server.core.ServerCore",
        "com.simibubi.create.content.kinetics.base.BlockBreakingKineticBlockEntity",
        "com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour",
        "com.simibubi.create.content.contraptions.Contraption",
        "com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour",
        "com.simibubi.create.content.equipment.symmetryWand.SymmetryWandItem",
        "net.minecraft.world.level.block.ButtonBlock"
}, priority = 5000, remap = false)
public abstract class ClaimCaptureCarrier {
}
