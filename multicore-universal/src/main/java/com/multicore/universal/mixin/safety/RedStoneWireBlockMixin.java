package com.multicore.universal.mixin.safety;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.universal.core.LevelThread;
import net.minecraft.world.level.block.RedStoneWireBlock;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Redstone wire switches off its own power output ({@code shouldSignal = false}) while it measures the power around it.
 * That switch is a field of the one RedStoneWireBlock object every dimension shares, so while one dimension thread
 * measures, wires in every other dimension briefly report no power and circuits there misfire. Each dimension thread
 * gets its own switch; the server thread keeps using the field. (Not applied with dimthread installed: dimthread
 * replaces these methods with its own per-thread version.)
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {
    private static final String SHOULD_SIGNAL = "Lnet/minecraft/world/level/block/RedStoneWireBlock;shouldSignal:Z";

    @WrapOperation(method = "calculateTargetStrength", at = @At(value = "FIELD", target = SHOULD_SIGNAL, opcode = Opcodes.PUTFIELD))
    private void mcu$setPerThread(RedStoneWireBlock self, boolean value, Operation<Void> original) {
        if (Thread.currentThread() instanceof LevelThread thread) {
            thread.redstoneWireSilenced = !value;
        } else {
            original.call(self, value);
        }
    }

    @ModifyExpressionValue(method = {"getDirectSignal", "getSignal", "isSignalSource"},
            at = @At(value = "FIELD", target = SHOULD_SIGNAL, opcode = Opcodes.GETFIELD))
    private boolean mcu$readPerThread(boolean shared) {
        return Thread.currentThread() instanceof LevelThread thread ? !thread.redstoneWireSilenced : shared;
    }
}
