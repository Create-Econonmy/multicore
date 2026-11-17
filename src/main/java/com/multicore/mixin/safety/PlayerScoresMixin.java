package com.multicore.mixin.safety;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.function.Consumer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Score;
import org.spongepowered.asm.mixin.Mixin;

/** The per-holder score map of the shared scoreboard; see {@link ScoreboardMixin}. */
@Mixin(targets = "net.minecraft.world.scores.PlayerScores")
public abstract class PlayerScoresMixin {
    @WrapMethod(method = "get")
    private Score multicore$get(Objective objective, Operation<Score> original) {
        synchronized (this) {
            return original.call(objective);
        }
    }

    @WrapMethod(method = "getOrCreate")
    private Score multicore$getOrCreate(Objective objective, Consumer<Score> creator, Operation<Score> original) {
        synchronized (this) {
            return original.call(objective, creator);
        }
    }

    @WrapMethod(method = "remove")
    private boolean multicore$remove(Objective objective, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(objective);
        }
    }

    @WrapMethod(method = "hasScores")
    private boolean multicore$hasScores(Operation<Boolean> original) {
        synchronized (this) {
            return original.call();
        }
    }

    @WrapMethod(method = "listScores")
    private Object2IntMap<Objective> multicore$listScores(Operation<Object2IntMap<Objective>> original) {
        synchronized (this) {
            return original.call();
        }
    }
}
