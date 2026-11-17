package com.multicore.mixin.core;

import com.multicore.core.CrossDimension;
import com.multicore.core.CrossLevel;
import com.multicore.core.LevelThread;
import com.multicore.core.SerialQueue;
import com.multicore.core.TickThreads;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Priority 100 so these checks run before other mods' HEAD injections into the same methods. */
@Mixin(value = Entity.class, priority = 100)
public abstract class EntityMixin {
    @Shadow
    @Nullable
    public PortalProcessor portalProcess;

    @Shadow
    public abstract Level level();

    @Shadow
    protected abstract void handlePortal();

    /**
     * Finding the destination portal loads chunks and may build a portal in the other dimension, which another
     * thread is ticking. The whole portal step (including other mods' hooks in it, such as claim protection) runs at
     * the end of the tick on the main thread instead.
     */
    @Inject(method = "handlePortal", at = @At("HEAD"), cancellable = true)
    private void multicore$deferPortal(CallbackInfo ci) {
        if (this.portalProcess != null && Thread.currentThread() instanceof LevelThread && TickThreads.inParallelPhase()) {
            SerialQueue.defer("portal", () -> {
                if (!((Entity) (Object) this).isRemoved()) {
                    this.handlePortal();
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void multicore$deferDimensionChange(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        if (CrossDimension.mustDefer(this.level(), transition.newLevel())) {
            Entity self = (Entity) (Object) this;
            CrossLevel.noteAccess("moves an entity to another dimension (deferred to end of tick)", transition.newLevel());
            SerialQueue.defer("changeDimension", () -> self.changeDimension(transition));
            cir.setReturnValue(null);
        }
    }
}
