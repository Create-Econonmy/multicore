package com.multicore.test;

import com.multicore.MultiCore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Create with region threads: a real logistics request (stock link + packager on a chest) asked from another region, and
 * a real train assembled at a station and driven by Create's own navigation from one region into another. The work is in
 * {@link CreateScenarios}, which uses Create's classes and is only loaded when Create is installed; without Create these
 * tests pass at once.
 */
@GameTestHolder(MultiCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateTests {
    private CreateTests() {
    }

    private static boolean noCreate(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return true;
        }
        return false;
    }

    @GameTest(template = "empty", batch = "create_logistics", timeoutTicks = 800)
    public static void packageRequestFromAnotherRegion(GameTestHelper helper) {
        if (!noCreate(helper)) CreateScenarios.packageRequestFromAnotherRegion(helper);
    }

    @GameTest(template = "empty", batch = "create_train", timeoutTicks = 2400)
    public static void trainDrivesFromOneRegionIntoAnother(GameTestHelper helper) {
        if (!noCreate(helper)) CreateScenarios.trainDrivesFromOneRegionIntoAnother(helper);
    }
}
