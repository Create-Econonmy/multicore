package com.multicore.universal;

import com.mojang.logging.LogUtils;
import com.multicore.universal.command.MultiCoreCommand;
import com.multicore.universal.compat.DimThreadCompat;
import com.multicore.universal.compat.MixinLockExtension;
import com.multicore.universal.config.MultiCoreConfig;
import com.multicore.universal.core.CrossDimension;
import com.multicore.universal.core.CrossLevel;
import com.multicore.universal.core.ModPolicy;
import com.multicore.universal.core.ParallelTicker;
import com.multicore.universal.core.ShutdownWatchdog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(MultiCore.MOD_ID)
public final class MultiCore {
    public static final String MOD_ID = "multicore_universal";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MultiCore(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, MultiCoreConfig.SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, e -> applyConfig());
        modBus.addListener(ModConfigEvent.Reloading.class, e -> applyConfig());

        if (ModPolicy.coremodActive()) {
            LOGGER.info("[MultiCore] Coremod active: code of {} mods takes the shared lock on dimension threads",
                    ModPolicy.wovenMods().size());
        } else {
            LOGGER.error("[MultiCore] Coremod not active ({}); dimensions will tick on one thread", ModPolicy.coremodStatus());
        }

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> MultiCoreCommand.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> {
            MixinLockExtension.logSummary();
            // Logs whether dimthread or MultiCore ticks the dimensions (dimthread's hook may run before MultiCore's).
            DimThreadCompat.isTicking(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> ShutdownWatchdog.onStopping(e.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> {
            ShutdownWatchdog.onStopped();
            ParallelTicker.get().retireAll();
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            if (e.getServer().getTickCount() % 600 == 0 && DimThreadCompat.isTicking(e.getServer())) {
                ParallelTicker.get().retireAll();
            }
        });
    }

    private static void applyConfig() {
        ParallelTicker.get().configure(
                MultiCoreConfig.ENABLED.get(),
                MultiCoreConfig.SERIAL_DIMENSIONS.get(),
                MultiCoreConfig.FALLBACK_TO_SERIAL_ON_ERROR.get(),
                MultiCoreConfig.SLOW_PHASE_WARN_MS.get());
        // The GameTest server keeps one dimension per thread: its tests check that dimensions tick in parallel.
        boolean gameTests = System.getProperty("neoforge.enabledGameTestNamespaces") != null;
        ParallelTicker.get().configureGroups(new com.multicore.universal.core.ThreadGroups.Settings(MultiCoreConfig.MAX_THREADS.get(),
                gameTests ? 1 : MultiCoreConfig.MAX_DIMENSIONS_PER_THREAD.get(), MultiCoreConfig.THREAD_TARGET_MS.get()),
                gameTests ? java.util.List.of() : MultiCoreConfig.THREAD_GROUPS.get());
        ModPolicy.configure(MultiCoreConfig.SERIALIZE_UNSAFE_MODS.get(), MultiCoreConfig.THREAD_SAFE_MODS.get());
        CrossDimension.setDeferWrites(MultiCoreConfig.DEFER_CROSS_DIMENSION_WRITES.get());
        CrossLevel.setLogging(MultiCoreConfig.LOG_CROSS_DIMENSION_ACCESS.get());
    }
}
