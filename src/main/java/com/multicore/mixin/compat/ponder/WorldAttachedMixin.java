package com.multicore.mixin.compat.ponder;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create (and its addons) keep almost all per-dimension state in Catnip's {@code WorldAttached}: one map from level to
 * data. It is a plain WeakHashMap, which is unsafe even for concurrent reads (a WeakHashMap cleans itself up inside
 * get()). With dimensions on different threads every lookup races, so the map is synchronized and creating the
 * per-level entry is made atomic. The per-level data itself is then only touched by that dimension's thread.
 */
@Pseudo
@Mixin(targets = "net.createmod.catnip.data.WorldAttached", remap = false)
public abstract class WorldAttachedMixin {
    @Shadow
    static List<WeakReference<Map<LevelAccessor, ?>>> allMaps;

    @Shadow
    @Final
    @Mutable
    private Map<LevelAccessor, Object> attached;

    @Shadow
    @Final
    private Function<LevelAccessor, Object> factory;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void multicore$synchronizeRegistry(CallbackInfo ci) {
        allMaps = Collections.synchronizedList(allMaps);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void multicore$synchronizeMap(CallbackInfo ci) {
        Map<LevelAccessor, Object> original = this.attached;
        this.attached = Collections.synchronizedMap(original);
        // The constructor registered the unwrapped map for invalidateWorld(); register the wrapper instead so removals
        // also go through the lock.
        synchronized (allMaps) {
            allMaps.removeIf(ref -> ref.get() == original);
            allMaps.add(new WeakReference<>(this.attached));
        }
    }

    /**
     * The entry is created outside the lock (factories can run arbitrary mod code, which must never happen while
     * holding a lock another thread may need) and published atomically; if two threads race, both get the first one.
     */
    @WrapMethod(method = "get")
    private Object multicore$atomicGet(LevelAccessor world, Operation<Object> original) {
        Object existing = this.attached.get(world);
        if (existing != null) {
            return existing;
        }
        Object created = this.factory.apply(world);
        synchronized (this.attached) {
            Object winner = this.attached.get(world);
            if (winner != null) {
                return winner;
            }
            this.attached.put(world, created);
            return created;
        }
    }
}
