package com.multicore.universal.mixin.safety;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Redstone torches remember recent toggles in one static WeakHashMap keyed by level. A WeakHashMap is not safe even for
 * concurrent reads, and torches in different dimensions now update at the same time, so access is serialized.
 */
@Mixin(RedstoneTorchBlock.class)
public abstract class RedstoneTorchBlockMixin {
    @Unique
    private static final Object MULTICORE$LOCK = new Object();

    @WrapMethod(method = "isToggledTooFrequently")
    private static boolean mcu$syncToggles(Level level, BlockPos pos, boolean logToggle, Operation<Boolean> original) {
        synchronized (MULTICORE$LOCK) {
            return original.call(level, pos, logToggle);
        }
    }
}
