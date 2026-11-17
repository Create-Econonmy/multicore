package com.multicore.mixin.safety;

import com.multicore.core.CapturedDrops;
import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NeoForge collects the drops of a broken block in one static list ({@code Block.capturedDrops}) and then fires
 * BlockDropsEvent. With blocks breaking in two dimensions at once, one dimension would pick up (or drop) the other's
 * items. The list becomes per-thread.
 */
@Mixin(Block.class)
public abstract class BlockCapturedDropsMixin {
    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;"))
    private static List<ItemEntity> multicore$getCapturedDrops() {
        return CapturedDrops.get();
    }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;"))
    private static void multicore$setCapturedDrops(List<ItemEntity> drops) {
        CapturedDrops.set(drops);
    }
}
