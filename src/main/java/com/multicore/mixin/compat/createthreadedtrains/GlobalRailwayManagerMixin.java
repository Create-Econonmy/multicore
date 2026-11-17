package com.multicore.mixin.compat.createthreadedtrains;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.core.ModLock;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * createthreadedtrains runs Create's whole railway tick on its "Train Worker" thread, in parallel with the server tick.
 * With MultiCore the server tick itself runs Create code on several dimension threads, so the train tick takes the mod
 * lock: trains and the stations, signals and carriages they read are never updated at the same moment.
 */
@Mixin(value = GlobalRailwayManager.class, remap = false)
public abstract class GlobalRailwayManagerMixin {
    @WrapMethod(method = "tick")
    private void multicore$lockWorkerTick(Level level, Operation<Void> original) {
        boolean locked = ModLock.enterFromWorker("createthreadedtrains train tick");
        try {
            original.call(level);
        } finally {
            ModLock.exit(locked);
        }
    }
}
