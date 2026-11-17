package com.multicore.universal.mixin.safety;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.universal.core.LockSource;
import com.multicore.universal.core.ModLock;
import java.util.Optional;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeCache;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * The Crafter block uses one static RecipeCache for every dimension. Its lookups shuffle a shared array, so two
 * crafters in different dimensions could get each other's recipe (crafting the wrong item). Lookups are made atomic.
 */
@Mixin(RecipeCache.class)
public abstract class RecipeCacheMixin {
    @Unique
    private static final LockSource MULTICORE$SOURCE = LockSource.of("vanilla crafter recipe cache");

    @WrapMethod(method = "get")
    private Optional<RecipeHolder<CraftingRecipe>> mcu$syncGet(Level level, CraftingInput input,
                                                                     Operation<Optional<RecipeHolder<CraftingRecipe>>> original) {
        boolean held = ModLock.enterSharedState(MULTICORE$SOURCE);
        try {
            synchronized (this) {
                return original.call(level, input);
            }
        } finally {
            ModLock.exit(held);
        }
    }
}
