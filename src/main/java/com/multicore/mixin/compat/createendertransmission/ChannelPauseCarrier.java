package com.multicore.mixin.compat.createendertransmission;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose. Create: Ender Transmission links machines, inventories and tanks on the same channel wherever they
 * are (other regions, other dimensions) through two shared channel lists, which hold the machines and inventories
 * themselves. Two threads walking and changing them broke a list (ConcurrentModificationException in
 * getConnectedTransmitters, found 2026-11-25 with every mod unlocked), and a channel's inventory is shared by every
 * transmitter on it. {@code MultiCoreMixinPlugin.postApply} makes each method of these block entities that uses a
 * channel list take the lock and pause every other tick thread for the rest of that tick (a world pause).
 */
@Pseudo
@Mixin(targets = {
        "com.forsteri.createendertransmission.blocks.energyTransmitter.EnergyTransmitterBlockEntity",
        "com.forsteri.createendertransmission.blocks.AbstractMatterTransmitterBlockEntity"
}, remap = false)
public abstract class ChannelPauseCarrier {
}
