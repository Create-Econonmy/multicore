package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.core.RegionThread;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;

/**
 * One world border per dimension, and every ticking block entity registers a listener on it the first time it checks
 * the border (Lithium caches "inside the border" that way and keeps the listeners in a WeakHashMap). Region threads of
 * the same dimension do that at the same time, which corrupted the map and left them spinning forever. Region threads
 * take turns; the border itself only changes on the dimension or main thread.
 */
@Mixin(WorldBorder.class)
public abstract class RegionWorldBorderMixin {
    @WrapMethod(method = "addListener")
    private void multicore$syncAddListener(BorderChangeListener listener, Operation<Void> original) {
        if (RegionThread.working() == null) {
            original.call(listener);
            return;
        }
        synchronized (this) {
            original.call(listener);
        }
    }

    @WrapMethod(method = "removeListener")
    private void multicore$syncRemoveListener(BorderChangeListener listener, Operation<Void> original) {
        if (RegionThread.working() == null) {
            original.call(listener);
            return;
        }
        synchronized (this) {
            original.call(listener);
        }
    }
}
