package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.multicore.core.PlayerThreads;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A player's connection tick (its whole player tick) runs on its dimension's thread (see {@link PlayerThreads}). */
@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;tick()V"))
    private void multicore$tickOnDimensionThread(Connection connection, Operation<Void> original) {
        if (!PlayerThreads.handOff(connection)) {
            original.call(connection);
        }
    }
}
