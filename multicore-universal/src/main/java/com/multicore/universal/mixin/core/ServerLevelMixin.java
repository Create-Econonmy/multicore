package com.multicore.universal.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.universal.api.LevelScheduler;
import com.multicore.universal.core.CrossDimension;
import com.multicore.universal.core.CrossLevel;
import com.multicore.universal.core.LevelThread;
import com.multicore.universal.core.SerialQueue;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.timers.TimerQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerLevel.class, priority = 100)
public abstract class ServerLevelMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void mcu$runScheduledTasks(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        LevelScheduler.runPending((ServerLevel) (Object) this);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void mcu$forgetScheduledTasks(CallbackInfo ci) {
        LevelScheduler.forget((ServerLevel) (Object) this);
    }

    // ---- /schedule'd functions live on the overworld's data but may touch any dimension ----

    @WrapOperation(method = "tickTime", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/timers/TimerQueue;tick(Ljava/lang/Object;J)V"))
    private void mcu$deferScheduledFunctions(TimerQueue<MinecraftServer> queue, Object server, long gameTime, Operation<Void> original) {
        if (Thread.currentThread() instanceof LevelThread) {
            SerialQueue.defer("scheduled functions", () -> queue.tick((MinecraftServer) server, gameTime));
        } else {
            original.call(queue, server, gameTime);
        }
    }

    // ---- A dimension thread adding entities to a level it does not own ----

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void mcu$foreignAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("spawns an entity in another dimension (deferred to end of tick)", self);
            SerialQueue.defer("addFreshEntity", () -> self.addFreshEntity(entity));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "addWithUUID", at = @At("HEAD"), cancellable = true)
    private void mcu$foreignAddWithUUID(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("adds an entity to another dimension (deferred to end of tick)", self);
            SerialQueue.defer("addWithUUID", () -> self.addWithUUID(entity));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tryAddFreshEntityWithPassengers", at = @At("HEAD"), cancellable = true)
    private void mcu$foreignAddWithPassengers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("adds entities to another dimension (deferred to end of tick)", self);
            SerialQueue.defer("tryAddFreshEntityWithPassengers", () -> self.tryAddFreshEntityWithPassengers(entity));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setChunkForced", at = @At("HEAD"), cancellable = true)
    private void mcu$foreignForceChunk(int chunkX, int chunkZ, boolean add, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("force-loads a chunk in another dimension (deferred to end of tick)", self);
            SerialQueue.defer("setChunkForced", () -> self.setChunkForced(chunkX, chunkZ, add));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "addDuringTeleport", at = @At("HEAD"), cancellable = true)
    private void mcu$foreignAddDuringTeleport(Entity entity, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("teleports an entity into another dimension (deferred to end of tick)", self);
            SerialQueue.defer("addDuringTeleport", () -> self.addDuringTeleport(entity));
            ci.cancel();
        }
    }
}
