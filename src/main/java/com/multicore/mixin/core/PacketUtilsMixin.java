package com.multicore.mixin.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.multicore.core.PlayerThreads;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Packets that act on the player's own world are handled on that world's thread (see {@link PlayerThreads}). */
@Mixin(PacketUtils.class)
public abstract class PacketUtilsMixin {
    @WrapOperation(method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/BlockableEventLoop;executeIfPossible(Ljava/lang/Runnable;)V"))
    private static void multicore$toDimensionThread(BlockableEventLoop<?> executor, Runnable task, Operation<Void> original,
                                                    @Local(argsOnly = true) Packet<?> packet, @Local(argsOnly = true) PacketListener processor) {
        if (!PlayerThreads.queuePacket(packet, processor, task)) {
            original.call(executor, task);
        }
    }
}
