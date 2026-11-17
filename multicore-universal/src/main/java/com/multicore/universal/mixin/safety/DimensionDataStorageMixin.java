package com.multicore.universal.mixin.safety;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.universal.core.LockSource;
import com.multicore.universal.core.ModLock;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mods (and vanilla maps) keep global data in the overworld's data storage and reach it from every dimension.
 * Its cache is a plain HashMap, so every access is made atomic. Loading or creating saved data can run mod code, so on
 * a dimension thread the mod lock is taken first (fixed lock order, no deadlock with mod code that holds it).
 */
@Mixin(DimensionDataStorage.class)
public abstract class DimensionDataStorageMixin {
    @Unique
    private static final LockSource MULTICORE$SOURCE = LockSource.of("vanilla saved data storage");

    @WrapMethod(method = "computeIfAbsent")
    private <T extends SavedData> T mcu$syncComputeIfAbsent(SavedData.Factory<T> factory, String name, Operation<T> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                return original.call(factory, name);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "get")
    private <T extends SavedData> T mcu$syncGet(SavedData.Factory<T> factory, String name, Operation<T> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                return original.call(factory, name);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "set")
    private void mcu$syncSet(String name, SavedData data, Operation<Void> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                original.call(name, data);
            }
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "save")
    private void mcu$syncSave(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }
}
