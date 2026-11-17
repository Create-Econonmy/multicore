package com.multicore.mixin.safety;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.multicore.core.LevelThread;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla batches packets during the tick and flushes them once at the end, but only for the server thread.
 * Packets sent from dimension threads are batched the same way instead of being flushed one by one.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @ModifyExpressionValue(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    private boolean multicore$batchFromLevelThreads(boolean sameThread) {
        return sameThread || Thread.currentThread() instanceof LevelThread;
    }
}
