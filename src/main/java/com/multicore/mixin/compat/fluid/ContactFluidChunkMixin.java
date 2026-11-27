package com.multicore.mixin.compat.fluid;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create: Fluid 2.1.6 checks the fluid at every block of every entity's bounding box, every tick, with
 * Level.getFluidState. For an entity at the edge of the loaded area that box reaches a chunk that is not loaded, and
 * getFluidState then loads it synchronously. On a region thread that is a chunk wait: the thread stops until the world
 * thread has loaded the chunk, and the whole entity split waits for it (spark 2026-11-27: region threads 4.4% in
 * CrossLevel.getChunk from here, world thread 22% waiting for the split). A block in a chunk that is not loaded has no
 * fluid the entity can be touching: answer "empty" without loading.
 */
@Pseudo
@Mixin(targets = "com.adonis.fluid.handler.FluidContactEffectHandler", remap = false)
public abstract class ContactFluidChunkMixin {
    @WrapOperation(method = "fluid$getContactFluid",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
            remap = false, require = 0)
    private static FluidState multicore$noChunkLoad(Level level, BlockPos pos, Operation<FluidState> original) {
        if (!level.isClientSide && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return original.call(level, pos);
    }
}
