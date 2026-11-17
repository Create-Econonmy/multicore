package com.multicore.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty on purpose. A deployer that breaks a block registers the position in {@code DeployerHandler.CAPTURED_BLOCK_DROPS}
 * and Create's patch to {@code Block.popResource} diverts drops at that position into the deployer. The map is shared by
 * every dimension, so a block broken at the same coordinates in another dimension at that moment lost its drops to the
 * deployer (and the HashMap was read while being written). {@code MultiCoreMixinPlugin.postApply} makes the map and its
 * read-only view per-thread. Priority 5000 so Create's Block mixin is already merged when it runs.
 */
@Pseudo
@Mixin(targets = {
        "com.simibubi.create.content.kinetics.deployer.DeployerHandler",
        "net.minecraft.world.level.block.Block"
}, priority = 5000, remap = false)
public abstract class DeployerCaptureCarrier {
}
