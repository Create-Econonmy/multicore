package com.multicore.mixin.compat.sable;

import com.multicore.compat.sable.SableThreadState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The Rapier pipeline also reads Sable's static physics-step flag; see {@link SubLevelPhysicsSystemMixin}. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierPhysicsPipelineMixin {
    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;IN_PHYSICS_STEP:Z"))
    private static boolean multicore$getInPhysicsStep() {
        return SableThreadState.inPhysicsStep();
    }
}
