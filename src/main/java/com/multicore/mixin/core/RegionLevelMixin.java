package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.RegionThread;
import com.multicore.core.RegionTicker;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Region threading inside Level: the block entity loop and per-thread neighbour updates (see {@link RegionTicker}). */
@Mixin(value = Level.class, priority = 100)
public abstract class RegionLevelMixin {
    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void multicore$beginRegionBlockEntities(CallbackInfo ci) {
        RegionTicker.beginBlockEntities((Level) (Object) this);
    }

    /** The vanilla loop still runs (removal of dead tickers, the "should tick here" check); only the ticks are collected. */
    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void multicore$collectBlockEntityTick(TickingBlockEntity ticker, Operation<Void> original) {
        if (!RegionTicker.collectBlockEntity((Level) (Object) this, ticker, original)) {
            original.call(ticker);
        }
    }

    /** Before tickingBlockEntities goes back to false: run the collected ticks, region by region. */
    @Inject(method = "tickBlockEntities", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;tickingBlockEntities:Z",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD, ordinal = 1))
    private void multicore$flushRegionBlockEntities(CallbackInfo ci) {
        RegionTicker.flushBlockEntities((Level) (Object) this);
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"))
    private void multicore$crossRegionBlockEntity(net.minecraft.core.BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<BlockEntity> cir) {
        RegionTicker.crossRegion(pos.getX(), pos.getZ());
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void multicore$crossRegionSetBlock(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
                                               int flags, int recursionLeft, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        RegionTicker.crossRegion(pos.getX(), pos.getZ());
    }

    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"), cancellable = true)
    private void multicore$regionAddTicker(TickingBlockEntity ticker, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (RegionTicker.defer(self, () -> self.addBlockEntityTicker(ticker))) {
            ci.cancel();
        }
    }

    @Inject(method = "addFreshBlockEntities", at = @At("HEAD"), cancellable = true)
    private void multicore$regionAddFresh(Collection<BlockEntity> blockEntities, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        Collection<BlockEntity> copy = new ArrayList<>(blockEntities);
        if (RegionTicker.defer(self, () -> self.addFreshBlockEntities(copy))) {
            ci.cancel();
        }
    }

    @ModifyExpressionValue(method = "neighborShapeChanged", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;neighborUpdater:Lnet/minecraft/world/level/redstone/NeighborUpdater;"))
    private NeighborUpdater multicore$regionNeighborUpdater(NeighborUpdater shared) {
        RegionThread t = RegionThread.working();
        return t != null ? t.neighborUpdater((Level) (Object) this, shared) : shared;
    }
}
