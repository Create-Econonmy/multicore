package com.multicore.mixin.test;

import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test harness only: lets PlayerTests put a simulated player's connection where real connections are ticked. */
@Mixin(ServerConnectionListener.class)
public interface ServerConnectionListenerAccessor {
    @Accessor("connections")
    List<Connection> multicore$connections();
}
