package com.multicore.universal.test;

import com.multicore.universal.MultiCore;
import com.multicore.universal.api.LevelScheduler;
import com.multicore.universal.compat.DimThreadCompat;
import com.multicore.universal.core.LevelThread;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The real server has dimthread installed, which ticks dimensions itself while its gamerule is on (the default). The
 * test server switches it off at startup so the other tests exercise MultiCore; this batch runs on its own and checks
 * that with dimthread on, MultiCore stands by and every dimension still ticks exactly once per server tick.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = MultiCore.MOD_ID)
public final class DimThreadTests {
    private static final String RULE = "dimthread_active";

    private DimThreadTests() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (System.getProperty("neoforge.enabledGameTestNamespaces") != null && DimThreadCompat.isLoaded()) {
            setRule(event.getServer(), false);
        }
    }

    @GameTest(template = "empty", batch = "dimthread", timeoutTicks = 200)
    public static void multiCoreStandsByWhileDimThreadTicks(GameTestHelper helper) {
        if (!DimThreadCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        MinecraftServer server = helper.getLevel().getServer();
        List<ServerLevel> levels = List.of(helper.getLevel(), server.getLevel(Level.NETHER), server.getLevel(Level.END));
        long[] start = new long[levels.size()];
        int[] serverStart = new int[1];
        AtomicReference<String> tickingThread = new AtomicReference<>();
        AtomicReference<Boolean> sameThread = new AtomicReference<>();
        AtomicReference<String> afterThread = new AtomicReference<>();
        setRule(server, true);
        helper.runAfterDelay(3, () -> {
            for (int i = 0; i < levels.size(); i++) {
                start[i] = levels.get(i).getGameTime();
            }
            serverStart[0] = server.getTickCount();
            LevelScheduler.run(levels.get(1), () -> {
                tickingThread.set(Thread.currentThread().getName());
                sameThread.set(server.isSameThread());
            });
        });
        helper.runAfterDelay(43, () -> {
            int ticks = server.getTickCount() - serverStart[0];
            long[] advanced = new long[levels.size()];
            for (int i = 0; i < levels.size(); i++) {
                advanced[i] = levels.get(i).getGameTime() - start[i];
            }
            // Switch back before any assertion can throw, so later batches never run under dimthread.
            setRule(server, false);
            LevelScheduler.run(levels.get(1), () -> afterThread.set(Thread.currentThread() instanceof LevelThread ? "multicore" : "other"));
            MultiCore.LOGGER.info("[MultiCore test] With dimthread ticking, server.isSameThread() inside a nether tick was {} "
                    + "(mods such as Open Parties and Claims skip their checks when it is false)", sameThread.get());
            for (int i = 0; i < levels.size(); i++) {
                helper.assertTrue(advanced[i] == ticks, levels.get(i).dimension().location() + " ticked " + advanced[i]
                        + " times in " + ticks + " server ticks");
            }
            helper.assertTrue(tickingThread.get() != null && tickingThread.get().startsWith("dimthread_server_"),
                    "with dimthread on, the nether ticked on " + tickingThread.get());
        });
        helper.runAfterDelay(50, () -> {
            helper.assertTrue("multicore".equals(afterThread.get()), "after switching dimthread off, the nether ticked on: " + afterThread.get());
            helper.succeed();
        });
    }

    private static void setRule(MinecraftServer server, boolean value) {
        GameRules.Key<GameRules.BooleanValue> key = findRule();
        if (key != null) {
            server.getGameRules().getRule(key).set(value, server);
        }
    }

    @Nullable
    private static GameRules.Key<GameRules.BooleanValue> findRule() {
        AtomicReference<GameRules.Key<GameRules.BooleanValue>> found = new AtomicReference<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                if (key.getId().equals(RULE)) {
                    found.set(key);
                }
            }
        });
        return found.get();
    }
}
