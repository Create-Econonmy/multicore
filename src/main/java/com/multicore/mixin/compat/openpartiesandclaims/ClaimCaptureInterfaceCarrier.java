package com.multicore.mixin.compat.openpartiesandclaims;

import net.minecraft.world.level.EntityGetter;
import org.spongepowered.asm.mixin.Mixin;

/** Same as {@link ClaimCaptureCarrier}, for the interface Open Parties and Claims also mixes into. */
@Mixin(value = EntityGetter.class, priority = 5000)
public interface ClaimCaptureInterfaceCarrier {
}
