package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.core.RegionThread;
import java.util.Optional;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Section storage (points of interest: beds, job sites, bells...) loads sections into one shared map when they are first
 * read. Region threads of one dimension read it at the same time (villager AI), so their access is synchronized. The
 * dimension thread only uses it while no region thread runs.
 */
@Mixin(SectionStorage.class)
public abstract class RegionSectionStorageMixin<R> {
    @WrapMethod(method = "get")
    private Optional<R> multicore$syncGet(long key, Operation<Optional<R>> original) {
        if (RegionThread.working() == null) return original.call(key);
        synchronized (this) {
            return original.call(key);
        }
    }

    @WrapMethod(method = "getOrLoad")
    private Optional<R> multicore$syncGetOrLoad(long key, Operation<Optional<R>> original) {
        if (RegionThread.working() == null) return original.call(key);
        synchronized (this) {
            return original.call(key);
        }
    }

    @WrapMethod(method = "getOrCreate")
    private R multicore$syncGetOrCreate(long key, Operation<R> original) {
        if (RegionThread.working() == null) return original.call(key);
        synchronized (this) {
            return original.call(key);
        }
    }

    @WrapMethod(method = "setDirty")
    private void multicore$syncSetDirty(long key, Operation<Void> original) {
        if (RegionThread.working() == null) {
            original.call(key);
            return;
        }
        synchronized (this) {
            original.call(key);
        }
    }

    @WrapMethod(method = "remove")
    private void multicore$syncRemove(long key, Operation<Void> original) {
        if (RegionThread.working() == null) {
            original.call(key);
            return;
        }
        synchronized (this) {
            original.call(key);
        }
    }
}
