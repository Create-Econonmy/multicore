package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.RegionThread;
import com.multicore.core.RegionTicker;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Region threading inside ServerLevel (see {@link RegionTicker}). */
@Mixin(value = ServerLevel.class, priority = 100)
public abstract class RegionServerLevelMixin {
    @Shadow
    volatile boolean isUpdatingNavigations;

    /** The entity loop: collected in vanilla order, then ticked region by region with vanilla's own per-entity code. */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void multicore$regionEntities(EntityTickList list, Consumer<Entity> action, Operation<Void> original) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!RegionTicker.wantsRegions(self)) {
            original.call(list, action);
            return;
        }
        List<Entity> all = new ArrayList<>();
        original.call(list, (Consumer<Entity>) all::add);
        RegionTicker.tickEntities(self, all, action);
    }

    // ---- shared structures: changed by the dimension thread after the split ----

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void multicore$regionAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (RegionTicker.defer(self, () -> self.addFreshEntity(entity))) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "addWithUUID", at = @At("HEAD"), cancellable = true)
    private void multicore$regionAddWithUUID(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (RegionTicker.defer(self, () -> self.addWithUUID(entity))) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tryAddFreshEntityWithPassengers", at = @At("HEAD"), cancellable = true)
    private void multicore$regionAddWithPassengers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (RegionTicker.defer(self, () -> self.tryAddFreshEntityWithPassengers(entity))) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "addDuringTeleport", at = @At("HEAD"), cancellable = true)
    private void multicore$regionAddDuringTeleport(Entity entity, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (RegionTicker.defer(self, () -> self.addDuringTeleport(entity))) {
            ci.cancel();
        }
    }

    @Inject(method = "setChunkForced", at = @At("HEAD"), cancellable = true)
    private void multicore$regionForceChunk(int chunkX, int chunkZ, boolean add, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (RegionTicker.defer(self, () -> self.setChunkForced(chunkX, chunkZ, add))) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void multicore$regionBlockEvent(BlockPos pos, Block block, int eventID, int eventParam, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        BlockPos immutable = pos.immutable();
        if (RegionTicker.defer(self, () -> self.blockEvent(immutable, block, eventID, eventParam))) {
            ci.cancel();
        }
    }

    // ---- path updates after a block change: only this region's mobs, with this thread's own "updating" flag ----

    @ModifyExpressionValue(method = "sendBlockUpdated", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;navigatingMobs:Ljava/util/Set;"))
    private Set<Mob> multicore$regionMobs(Set<Mob> all) {
        return RegionTicker.navigatingMobs(all);
    }

    @Redirect(method = "sendBlockUpdated", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;isUpdatingNavigations:Z", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private boolean multicore$regionReadUpdating(ServerLevel self) {
        RegionThread t = RegionThread.working();
        return t != null ? t.updatingNavigations : this.isUpdatingNavigations;
    }

    @Redirect(method = "sendBlockUpdated", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;isUpdatingNavigations:Z", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void multicore$regionWriteUpdating(ServerLevel self, boolean value) {
        RegionThread t = RegionThread.working();
        if (t != null) {
            t.updatingNavigations = value;
        } else {
            this.isUpdatingNavigations = value;
        }
    }

    // ---- every region thread has its own neighbour updater ----

    @ModifyExpressionValue(method = {
            "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
            "updateNeighborsAtExceptFromFacing",
            "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)V",
            "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V"
    }, at = @At(value = "FIELD", target = "neighborUpdater:Lnet/minecraft/world/level/redstone/NeighborUpdater;"))
    private NeighborUpdater multicore$regionNeighborUpdater(NeighborUpdater shared) {
        RegionThread t = RegionThread.working();
        return t != null ? t.neighborUpdater((ServerLevel) (Object) this, shared) : shared;
    }
}
