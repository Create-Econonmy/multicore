package com.multicore.mixin.safety;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.core.LockSource;
import com.multicore.core.ModLock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * A map's data lives in the overworld's storage and is shared by every copy of that map, in any dimension: framed maps
 * update it from the frame's dimension thread, held maps from the holder's. Its decoration and viewer lists are plain
 * collections, so the entry points are made atomic.
 */
@Mixin(MapItemSavedData.class)
public abstract class MapItemSavedDataMixin {
    @Unique
    private static final LockSource MULTICORE$SOURCE = LockSource.of("vanilla map data");

    @WrapMethod(method = "tickCarriedBy")
    private void multicore$tickCarriedBy(Player player, ItemStack stack, Operation<Void> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                original.call(player, stack);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "getUpdatePacket")
    private Packet<?> multicore$getUpdatePacket(MapId id, Player player, Operation<Packet<?>> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                return original.call(id, player);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "removeDecoration")
    private void multicore$removeDecoration(String identifier, Operation<Void> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                original.call(identifier);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "toggleBanner")
    private boolean multicore$toggleBanner(LevelAccessor level, BlockPos pos, Operation<Boolean> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                return original.call(level, pos);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "checkBanners")
    private void multicore$checkBanners(BlockGetter reader, int x, int z, Operation<Void> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                original.call(reader, x, z);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "removedFromFrame")
    private void multicore$removedFromFrame(BlockPos pos, int entityId, Operation<Void> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                original.call(pos, entityId);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "updateColor")
    private boolean multicore$updateColor(int x, int z, byte color, Operation<Boolean> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                return original.call(x, z, color);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "locked")
    private MapItemSavedData multicore$locked(Operation<MapItemSavedData> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                return original.call();
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "scaled")
    private MapItemSavedData multicore$scaled(Operation<MapItemSavedData> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                return original.call();
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "save")
    private CompoundTag multicore$save(CompoundTag tag, HolderLookup.Provider registries, Operation<CompoundTag> original) {
        synchronized (this) {
            return original.call(tag, registries);
        }
    }
}
