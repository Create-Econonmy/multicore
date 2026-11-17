package com.multicore.mixin.test;

import java.io.File;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.GameProfileCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test harness only (applied only when GameTests are enabled, see MultiCoreMixinPlugin): the GameTest server has no
 * player profile cache, and Open Parties and Claims needs one at startup. An empty in-memory cache lets the full
 * pack boot in the test server.
 */
@Mixin(MinecraftServer.class)
public abstract class GameTestServerProfileCacheMixin {
    @Unique
    private GameProfileCache multicore$testProfileCache;

    @Inject(method = "getProfileCache", at = @At("RETURN"), cancellable = true)
    private void multicore$emptyProfileCache(CallbackInfoReturnable<GameProfileCache> cir) {
        if (cir.getReturnValue() == null) {
            if (this.multicore$testProfileCache == null) {
                this.multicore$testProfileCache = new GameProfileCache(null, new File("multicore-test-usercache.json"));
            }
            cir.setReturnValue(this.multicore$testProfileCache);
        }
    }
}
