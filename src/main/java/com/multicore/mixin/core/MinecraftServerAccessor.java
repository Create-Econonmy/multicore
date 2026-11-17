package com.multicore.mixin.core;

import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Invoker("synchronizeTime")
    void multicore$synchronizeTime(ServerLevel level);

    @Accessor("perWorldTickTimes")
    Map<ResourceKey<Level>, long[]> multicore$perWorldTickTimes();
}
