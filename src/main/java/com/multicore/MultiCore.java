package com.multicore;

import com.mojang.logging.LogUtils;
import com.multicore.command.MultiCoreCommand;
import com.multicore.compat.DimThreadCompat;
import com.multicore.config.MultiCoreConfig;
import com.multicore.core.ContentPolicy;
import com.multicore.core.CrossDimension;
import com.multicore.core.CrossLevel;
import com.multicore.core.ListenerSerializer;
import com.multicore.core.ParallelTicker;
import com.multicore.core.ShutdownWatchdog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(MultiCore.MOD_ID)
public final class MultiCore {
    public static final String MOD_ID = "multicore";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MultiCore(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, MultiCoreConfig.SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, e -> applyConfig());
        modBus.addListener(ModConfigEvent.Reloading.class, e -> applyConfig());

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> MultiCoreCommand.register(e.getDispatcher()));
        // Wrap listeners once every mod has registered, and again after startup to catch late registrations.
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, e -> ListenerSerializer.wrapAll(NeoForge.EVENT_BUS));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> {
            ListenerSerializer.wrapAll(NeoForge.EVENT_BUS);
            // Logs whether dimthread or MultiCore ticks the dimensions (dimthread's hook may run before MultiCore's).
            DimThreadCompat.isTicking(e.getServer());
            com.multicore.map.ThreadMap.onServerStarted(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> {
            com.multicore.map.ThreadMap.onServerStopping(e.getServer());
            ShutdownWatchdog.onStopping(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> {
            ShutdownWatchdog.onStopped();
            ParallelTicker.get().retireAll();
        });
        // Mods can register listeners at any time (on reload, when a feature switches on); pick those up every 30 s.
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            com.multicore.map.ThreadMap.onTick(e.getServer());
            if (e.getServer().getTickCount() % 600 == 0) {
                ListenerSerializer.wrapAll(NeoForge.EVENT_BUS);
                if (DimThreadCompat.isTicking(e.getServer())) {
                    ParallelTicker.get().retireAll();
                }
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
        // ThreadGroupTests groups them itself.
        boolean gameTests = System.getProperty("neoforge.enabledGameTestNamespaces") != null;
        ParallelTicker.get().configureGroups(new com.multicore.core.ThreadGroups.Settings(MultiCoreConfig.MAX_THREADS.get(),
                gameTests ? 1 : MultiCoreConfig.MAX_DIMENSIONS_PER_THREAD.get(), MultiCoreConfig.THREAD_TARGET_MS.get()),
                gameTests ? java.util.List.of() : MultiCoreConfig.THREAD_GROUPS.get());
        ContentPolicy.configure(MultiCoreConfig.SERIALIZE_UNSAFE_MODS.get(), MultiCoreConfig.THREAD_SAFE_MODS.get(),
                MultiCoreConfig.THREAD_SAFE_LISTENERS.get());
        CrossDimension.setDeferWrites(MultiCoreConfig.DEFER_CROSS_DIMENSION_WRITES.get());
        CrossLevel.setLogging(MultiCoreConfig.LOG_CROSS_DIMENSION_ACCESS.get());
        // The GameTest server keeps auto-adjust off: its tests check where each dimension ticks. Its own tests turn it on.
        boolean gameTestServer = System.getProperty("neoforge.enabledGameTestNamespaces") != null;
        long retry = MultiCoreConfig.AUTO_RETRY_MINUTES.get() * 1200L;
        com.multicore.core.AutoAdjust.get().configure(new com.multicore.core.AutoAdjust.Settings(
                MultiCoreConfig.AUTO_ADJUST.get() && !gameTestServer,
                MultiCoreConfig.AUTO_IDLE_MS.get(), MultiCoreConfig.AUTO_BUSY_MS.get(), MultiCoreConfig.AUTO_LOAD_MS.get(),
                MultiCoreConfig.AUTO_MIN_SPEEDUP.get(), 100, MultiCoreConfig.AUTO_LOAD_SECONDS.get() * 20, retry, Math.max(retry, 20L * 60 * 60)));
    }
}
