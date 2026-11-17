package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.ContentPolicy;
import com.multicore.core.ModLock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge calls {@code BlockEntity.onLoad} for freshly added block entities at the start of
 * {@code Level.tickBlockEntities}, on the dimension thread. Mods do global work there: Create registers logistics links
 * in its server-wide GlobalLogisticsManager (a plain HashMap), and Create: Mobile Packages' Bee Port threw
 * ConcurrentModificationException when two dimensions loaded one at the same moment. Mod block entities' onLoad now
 * holds the mod lock, like their ticks ({@link BoundTickingBlockEntityMixin}).
 */
@Mixin(Level.class)
public abstract class LevelBlockEntityLoadMixin {
    @WrapOperation(method = "lambda$tickBlockEntities$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;onLoad()V"))
    private static void multicore$lockOnLoad(BlockEntity be, Operation<Void> original) {
        ModLock.runLocked(ContentPolicy.lockFor(be.getType()), () -> original.call(be));
    }
}
