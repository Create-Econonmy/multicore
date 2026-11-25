package com.multicore.mixin.safety;

import com.mojang.authlib.GameProfile;
import com.multicore.core.LevelThread;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge keeps one fake player per level and name in a plain HashMap, and mods (Moonlight, for Supplementaries' thrown
 * bricks; many machines) move it around, give it items and let it act. On two tick threads at once that broke the map
 * (ConcurrentModificationException, found 2026-11-25 with every mod unlocked) and would have had two threads steer the
 * same fake player. Each dimension and region thread gets its own fake players instead; the main thread keeps NeoForge's.
 * Fake players are never in the world, so there being one per thread changes nothing else.
 */
@Mixin(value = FakePlayerFactory.class, remap = false)
public abstract class FakePlayerFactoryMixin {
    @Unique
    private static final ThreadLocal<Map<Object, FakePlayer>> MULTICORE$PER_THREAD = ThreadLocal.withInitial(() -> {
        Map<Object, FakePlayer> map = new java.util.HashMap<>();
        FakePlayerFactoryMixin.MULTICORE$ALL.add(map);
        return map;
    });
    @Unique
    private static final Set<Map<Object, FakePlayer>> MULTICORE$ALL = ConcurrentHashMap.newKeySet();

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private static void multicore$perThread(ServerLevel level, GameProfile username, CallbackInfoReturnable<FakePlayer> cir) {
        if (Thread.currentThread() instanceof LevelThread) {
            Map<Object, FakePlayer> map = MULTICORE$PER_THREAD.get();
            Object key = java.util.List.of(level, username);
            FakePlayer player;
            synchronized (map) {
                player = map.computeIfAbsent(key, k -> new FakePlayer(level, username));
            }
            cir.setReturnValue(player);
        }
    }

    /** Main thread, when a level unloads: drop every thread's fake players of it. */
    @Inject(method = "unloadLevel", at = @At("HEAD"))
    private static void multicore$unloadPerThread(ServerLevel level, CallbackInfo ci) {
        for (Map<Object, FakePlayer> map : MULTICORE$ALL) {
            synchronized (map) {
                map.values().removeIf(p -> p.level() == level);
            }
        }
    }
}
