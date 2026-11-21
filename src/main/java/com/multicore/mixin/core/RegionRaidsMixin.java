package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.core.RegionThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;

/** The raid list of a dimension, read and extended by region threads (bad omen, raiders looking for their raid). */
@Mixin(Raids.class)
public abstract class RegionRaidsMixin {
    @WrapMethod(method = "createOrExtendRaid")
    private Raid multicore$syncCreate(ServerPlayer player, BlockPos pos, Operation<Raid> original) {
        if (RegionThread.working() == null) return original.call(player, pos);
        synchronized (this) {
            return original.call(player, pos);
        }
    }

    @WrapMethod(method = "getNearbyRaid")
    private Raid multicore$syncNearby(BlockPos pos, int distance, Operation<Raid> original) {
        if (RegionThread.working() == null) return original.call(pos, distance);
        synchronized (this) {
            return original.call(pos, distance);
        }
    }
}
