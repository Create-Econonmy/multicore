package com.multicore.universal.mixin.safety;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * There is one scoreboard for the whole server. Entities look up their team from it constantly (collisions,
 * targeting) and deaths/kills update scores, from every dimension thread. Its maps become synchronized maps.
 */
@Mixin(Scoreboard.class)
public abstract class ScoreboardMixin {
    @Shadow @Final @Mutable private Object2ObjectMap<String, Objective> objectivesByName;
    @Shadow @Final @Mutable private Reference2ObjectMap<ObjectiveCriteria, List<Objective>> objectivesByCriteria;
    @SuppressWarnings("rawtypes")
    @Shadow @Final @Mutable private Map playerScores;
    @Shadow @Final @Mutable private Map<DisplaySlot, Objective> displayObjectives;
    @Shadow @Final @Mutable private Object2ObjectMap<String, PlayerTeam> teamsByName;
    @Shadow @Final @Mutable private Object2ObjectMap<String, PlayerTeam> teamsByPlayer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mcu$synchronizeMaps(CallbackInfo ci) {
        this.objectivesByName = Object2ObjectMaps.synchronize(this.objectivesByName);
        this.objectivesByCriteria = Reference2ObjectMaps.synchronize(this.objectivesByCriteria);
        this.playerScores = Collections.synchronizedMap((Map<?, ?>) this.playerScores);
        this.displayObjectives = Collections.synchronizedMap(this.displayObjectives);
        this.teamsByName = Object2ObjectMaps.synchronize(this.teamsByName);
        this.teamsByPlayer = Object2ObjectMaps.synchronize(this.teamsByPlayer);
    }
}
