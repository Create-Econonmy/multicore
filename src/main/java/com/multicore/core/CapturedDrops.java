package com.multicore.core;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Per-thread replacement for NeoForge's static {@code Block.capturedDrops}. Kept outside Block so it is initialized on
 * first use, not in Block's static initializer (which itself writes the field).
 */
public final class CapturedDrops {
    private static final ThreadLocal<List<ItemEntity>> CAPTURED = new ThreadLocal<>();

    private CapturedDrops() {
    }

    @Nullable
    public static List<ItemEntity> get() {
        return CAPTURED.get();
    }

    public static void set(@Nullable List<ItemEntity> drops) {
        if (drops == null) {
            CAPTURED.remove();
        } else {
            CAPTURED.set(drops);
        }
    }
}
