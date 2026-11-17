package com.multicore.compat;

import com.multicore.MultiCore;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.neoforged.fml.loading.FMLLoader;

/**
 * dimthread also ticks every dimension on its own thread (it wraps the same call in {@code tickChildren}). Two of those
 * at once would tick levels twice or on threads the other does not know about, so MultiCore stands by whenever
 * dimthread is switched on (its {@code dimthread_active} gamerule, on by default) and takes over when it is off.
 * <p>
 * Note: dimthread 1.2.1 for NeoForge runs each level's tick inside one {@code static synchronized} method, so its
 * dimensions tick one after another anyway; removing it loses no parallelism.
 */
public final class DimThreadCompat {
    private static final String ACTIVE_RULE = "dimthread_active";
    private static final boolean LOADED = isLoaded();
    @Nullable
    private static GameRules.Key<GameRules.BooleanValue> activeKey;
    private static boolean lookedUp;
    private static volatile boolean lastSeenTicking;

    private DimThreadCompat() {
    }

    public static boolean isLoaded() {
        try {
            return FMLLoader.getLoadingModList().getModFileById("dimthread") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True if dimthread is installed and switched on, so it ticks the dimensions instead of MultiCore. Main thread. */
    public static boolean isTicking(MinecraftServer server) {
        if (!LOADED) {
            return false;
        }
        GameRules.Key<GameRules.BooleanValue> key = activeKey();
        boolean ticking = key != null && server.getGameRules().getBoolean(key);
        if (ticking != lastSeenTicking) {
            lastSeenTicking = ticking;
            if (ticking) {
                MultiCore.LOGGER.warn("[MultiCore] dimthread is installed and switched on (gamerule {} is true), so dimthread "
                        + "ticks the dimensions and MultiCore stays off. To use MultiCore, remove the dimthread jar (recommended) "
                        + "or run: /gamerule {} false", ACTIVE_RULE, ACTIVE_RULE);
            } else {
                MultiCore.LOGGER.info("[MultiCore] dimthread is installed but switched off; MultiCore ticks the dimensions.");
            }
        }
        return ticking;
    }

    /** For /multicore status. */
    public static boolean lastSeenTicking() {
        return lastSeenTicking;
    }

    @Nullable
    private static GameRules.Key<GameRules.BooleanValue> activeKey() {
        if (!lookedUp) {
            lookedUp = true;
            GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
                @Override
                public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                    if (key.getId().equals(ACTIVE_RULE)) {
                        activeKey = key;
                    }
                }
            });
            if (activeKey == null) {
                MultiCore.LOGGER.warn("[MultiCore] dimthread is installed but its gamerule {} was not found, so MultiCore "
                        + "assumes dimthread is off. Remove the dimthread jar to be sure.", ACTIVE_RULE);
            }
        }
        return activeKey;
    }
}
