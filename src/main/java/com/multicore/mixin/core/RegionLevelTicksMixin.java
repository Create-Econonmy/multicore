package com.multicore.mixin.core;

import com.multicore.core.RegionTicker;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scheduled block and fluid ticks from a region thread: the shared per-chunk bookkeeping is updated after the split. */
@Mixin(LevelTicks.class)
public abstract class RegionLevelTicksMixin<T> {
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void multicore$regionSchedule(ScheduledTick<T> tick, CallbackInfo ci) {
        @SuppressWarnings("unchecked")
        LevelTicks<T> self = (LevelTicks<T>) (Object) this;
        if (RegionTicker.deferAny(() -> self.schedule(tick))) {
            ci.cancel();
        }
    }
}
