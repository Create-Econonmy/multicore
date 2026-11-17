package com.multicore.mixin.compat.sable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose: it marks the Sable classes that call the Rapier native library. After it is applied,
 * {@code MultiCoreMixinPlugin.postApply} wraps every method in them that calls a native with the mod lock, because
 * the native engine keeps global state (its collider registry) shared by all dimensions.
 */
@Pseudo
@Mixin(targets = {
        "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D",
        "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline",
        "dev.ryanhcode.sable.physics.impl.rapier.box.RapierBoxHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery",
        "dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData",
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.fixed.RapierFixedConstraintHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.free.RapierFreeConstraintHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.rotary.RapierRotaryConstraintHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.rope.RapierRopeHandle"
}, remap = false)
public abstract class RapierNativeLockCarrier {
}
