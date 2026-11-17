package com.multicore.universal.mixin.safety;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequences;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Every dimension shares the overworld's RandomSequences (loot rolls use it). Creating a sequence writes to a
 * plain map, so lookups are made atomic.
 */
@Mixin(RandomSequences.class)
public abstract class RandomSequencesMixin {
    @WrapMethod(method = "get")
    private RandomSource mcu$syncGet(ResourceLocation location, Operation<RandomSource> original) {
        synchronized (this) {
            return original.call(location);
        }
    }
}
