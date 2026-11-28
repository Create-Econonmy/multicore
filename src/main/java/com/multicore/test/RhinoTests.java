package com.multicore.test;

import com.multicore.MultiCore;
import com.multicore.core.LockSource;
import com.multicore.core.ModLock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Two dimension threads run a KubeJS "entity spawned" script at once, one already holding the mod lock (as a locked
 * block entity does) and one not. Rhino runs a script inside its own Java lock; the live server froze on 2026-11-28
 * with the two locks taken in opposite orders. A deadlock stops the whole server, so it shows as a run that never ends
 * with "has waited 10 s for the mod lock" in the log.
 * <p>
 * Needs KubeJS and tools/test-kubejs/multicore_rhino_test.js in the game directory's kubejs/server_scripts;
 * without the script the test passes without checking anything and says so in the log.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RhinoTests {
    private static final String SEEN = "multicore_rhino";
    private static final int TICKS = 400;
    private static final int PER_TICK = 40;

    private RhinoTests() {
    }

    @GameTest(template = "empty", batch = "rhino", timeoutTicks = 2000)
    public static void scriptsOnTwoThreadsDoNotDeadlock(GameTestHelper helper) {
        ServerLevel over = helper.getLevel();
        ServerLevel nether = over.getServer().getLevel(Level.NETHER);
        if (!over.getServer().getFile("kubejs/server_scripts/multicore_rhino_test.js").toFile().isFile()) {
            MultiCore.LOGGER.warn("[MultiCore] Rhino deadlock test skipped: no test script");
            helper.succeed();
            return;
        }
        BlockPos o = helper.absolutePos(new BlockPos(2, 20, 2));
        nether.setChunkForced(0, 0, true);
        Pig overPig = pig(over, o);
        Pig netherPig = pig(nether, new BlockPos(8, 120, 8));
        UUID overId = overPig.getUUID(), netherId = netherPig.getUUID();
        AtomicInteger overRuns = new AtomicInteger(), netherRuns = new AtomicInteger();
        AtomicInteger spawned = new AtomicInteger(), seen = new AtomicInteger();
        LockSource source = LockSource.of("rhino test");
        Object listener = new Object() {
            @SubscribeEvent
            public void tick(EntityTickEvent.Pre e) {
                UUID id = e.getEntity().getUUID();
                boolean locked = id.equals(overId);
                if (!locked && !id.equals(netherId)) return;
                AtomicInteger runs = locked ? overRuns : netherRuns;
                if (runs.get() >= TICKS) return;
                runs.incrementAndGet();
                ServerLevel level = (ServerLevel) e.getEntity().level();
                for (int i = 0; i < PER_TICK; i++) {
                    if (locked) ModLock.acquire(source);
                    try {
                        ItemEntity item = new ItemEntity(level, e.getEntity().getX(), e.getEntity().getY() + 1,
                                e.getEntity().getZ(), new ItemStack(Items.STICK));
                        level.addFreshEntity(item);
                        spawned.incrementAndGet();
                        if (item.getTags().contains(SEEN)) seen.incrementAndGet();
                        item.discard();
                    } finally {
                        if (locked) ModLock.release();
                    }
                }
            }
        };
        NeoForge.EVENT_BUS.register(listener);
        helper.succeedWhen(() -> {
            helper.assertTrue(overRuns.get() >= TICKS && netherRuns.get() >= TICKS,
                    "still running: " + overRuns.get() + " / " + netherRuns.get());
            NeoForge.EVENT_BUS.unregister(listener);
            overPig.discard();
            netherPig.discard();
            nether.setChunkForced(0, 0, false);
            helper.assertTrue(seen.get() == spawned.get(), "the script ran for " + seen.get() + " of " + spawned.get() + " items");
        });
    }

    private static Pig pig(ServerLevel level, BlockPos pos) {
        Pig pig = EntityType.PIG.create(level);
        pig.setNoAi(true);
        pig.setNoGravity(true);
        pig.setInvulnerable(true);
        pig.setPersistenceRequired();
        pig.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        level.addFreshEntity(pig);
        return pig;
    }
}
