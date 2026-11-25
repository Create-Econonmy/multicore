package com.multicore.mixin.core;

import com.multicore.core.PlayerThreads;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Players tick on their dimension's thread (see {@link PlayerThreads}): disconnects still go to the main thread. */
@Mixin(Connection.class)
public abstract class PlayerConnectionMixins {
    @Inject(method = "handleDisconnection", at = @At("HEAD"), cancellable = true)
    private void multicore$disconnectOnMainThread(CallbackInfo ci) {
        if (PlayerThreads.deferDisconnect((Connection) (Object) this)) {
            ci.cancel();
        }
    }
}
