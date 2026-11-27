package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.api.LevelScheduler;
import com.multicore.core.ContentPolicy;
import com.multicore.core.LockSource;
import com.multicore.core.CrossDimension;
import com.multicore.core.CrossLevel;
import com.multicore.core.LevelThread;
import com.multicore.core.ModLock;
import com.multicore.core.SerialQueue;
import com.multicore.core.WorldPause;
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
    private void multicore$runScheduledTasks(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        LevelScheduler.runPending((ServerLevel) (Object) this);
        com.multicore.core.PlayerThreads.runQueuedPackets((ServerLevel) (Object) this);
    }

    /** Vanilla ticks the connections after the levels: players whose dimension has its own thread tick here. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void multicore$tickPlayerConnections(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        com.multicore.core.PlayerThreads.tickOwedConnections((ServerLevel) (Object) this);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void multicore$forgetScheduledTasks(CallbackInfo ci) {
        LevelScheduler.forget((ServerLevel) (Object) this);
        com.multicore.core.PlayerThreads.forget((ServerLevel) (Object) this);
    }

    /**
     * The whole entity tick is one item for world pauses, NeoForge's EntityTickEvent.Pre/Post included (they run around
     * Entity.tick, outside the wrapper below). A mod's listener there that reaches into another dimension takes a world
     * pause; a chunk wait inside it ends the pause, and only inside an item is it taken back afterwards (WorldPause
     * .resumeIfNeeded). Soak 2026-11-27: the chaos test's nether mover lost its pause after its first move this way, and
     * an item went missing from a chest two threads were using.
     */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "tickNonPassenger")
    private void multicore$entityTickIsOneItem(Entity entity, Operation<Void> original) {
        WorldPause.beginItem();
        try {
            original.call(entity);
        } finally {
            WorldPause.endItem();
        }
    }

    // ---- Entities from unsafe mods tick under the mod lock ----

    @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void multicore$lockEntityTick(Entity entity, Operation<Void> original) {
        WorldPause.beginItem();
        try {
            LockSource lock = ContentPolicy.lockFor(entity);
            if (lock != null) {
                ModLock.acquire(lock);
                try {
                    original.call(entity);
                } finally {
                    ModLock.release();
                }
            } else {
                original.call(entity);
            }
        } finally {
            WorldPause.endItem();
        }
    }

    @WrapOperation(method = "tickPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
    private void multicore$lockPassengerTick(Entity entity, Operation<Void> original) {
        LockSource lock = ContentPolicy.lockFor(entity);
        if (lock != null) {
            ModLock.acquire(lock);
            try {
                original.call(entity);
            } finally {
                ModLock.release();
            }
        } else {
            original.call(entity);
        }
    }

    // ---- /schedule'd functions live on the overworld's data but may touch any dimension ----

    @WrapOperation(method = "tickTime", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/timers/TimerQueue;tick(Ljava/lang/Object;J)V"))
    private void multicore$deferScheduledFunctions(TimerQueue<MinecraftServer> queue, Object server, long gameTime, Operation<Void> original) {
        if (Thread.currentThread() instanceof LevelThread) {
            SerialQueue.defer("scheduled functions", () -> queue.tick((MinecraftServer) server, gameTime));
        } else {
            original.call(queue, server, gameTime);
        }
    }

    // ---- A dimension thread adding entities to a level it does not own ----

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void multicore$foreignAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("spawns an entity in another dimension (deferred to end of tick)", self);
            SerialQueue.defer("addFreshEntity", () -> self.addFreshEntity(entity));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "addWithUUID", at = @At("HEAD"), cancellable = true)
    private void multicore$foreignAddWithUUID(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("adds an entity to another dimension (deferred to end of tick)", self);
            SerialQueue.defer("addWithUUID", () -> self.addWithUUID(entity));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tryAddFreshEntityWithPassengers", at = @At("HEAD"), cancellable = true)
    private void multicore$foreignAddWithPassengers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("adds entities to another dimension (deferred to end of tick)", self);
            SerialQueue.defer("tryAddFreshEntityWithPassengers", () -> self.tryAddFreshEntityWithPassengers(entity));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setChunkForced", at = @At("HEAD"), cancellable = true)
    private void multicore$foreignForceChunk(int chunkX, int chunkZ, boolean add, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("force-loads a chunk in another dimension (deferred to end of tick)", self);
            SerialQueue.defer("setChunkForced", () -> self.setChunkForced(chunkX, chunkZ, add));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "addDuringTeleport", at = @At("HEAD"), cancellable = true)
    private void multicore$foreignAddDuringTeleport(Entity entity, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CrossDimension.mustDeferWriteTo(self)) {
            CrossLevel.noteAccess("teleports an entity into another dimension (deferred to end of tick)", self);
            SerialQueue.defer("addDuringTeleport", () -> self.addDuringTeleport(entity));
            ci.cancel();
        }
    }
}
