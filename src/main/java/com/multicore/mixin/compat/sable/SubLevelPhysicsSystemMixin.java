package com.multicore.mixin.compat.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.compat.sable.SableThreadState;
import com.multicore.core.ModLock;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sable runs one physics system per dimension but tracks "which system is stepping" in two static fields.
 * They become per-thread, and a whole physics step (which calls into the native engine and into other mods'
 * callbacks) holds the mod lock.
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsSystemMixin {
    @WrapMethod(method = "tick")
    private void multicore$lockPhysicsTick(SubLevelContainer container, Operation<Void> original) {
        boolean held = ModLock.enterForMod("sable");
        try {
            original.call(container);
        } finally {
            ModLock.exit(held);
        }
    }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;IN_PHYSICS_STEP:Z"))
    private static boolean multicore$getInPhysicsStep() {
        return SableThreadState.inPhysicsStep();
    }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;IN_PHYSICS_STEP:Z"))
    private static void multicore$setInPhysicsStep(boolean value) {
        SableThreadState.setInPhysicsStep(value);
    }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;currentlySteppingSystem:Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;"))
    private static SubLevelPhysicsSystem multicore$getSteppingSystem() {
        return (SubLevelPhysicsSystem) SableThreadState.steppingSystem();
    }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;currentlySteppingSystem:Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;"))
    private static void multicore$setSteppingSystem(SubLevelPhysicsSystem value) {
        SableThreadState.setSteppingSystem(value);
    }
}
