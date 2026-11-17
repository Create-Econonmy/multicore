package com.multicore.universal.coremod;

import cpw.mods.modlauncher.api.ITransformer;
import java.util.List;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the MultiCore Universal coremod (loaded by NeoForge from this library jar, nested in the mod jar).
 * <p>
 * It indexes every installed mod and registers one transformer for all of their classes, which makes their code take
 * MultiCore's shared mod lock while it runs on a dimension thread. The mod itself reads the result through system
 * properties, so it can refuse to run dimensions in parallel when the coremod did not load.
 */
public final class UniversalCoreMod implements ICoreMod {
    public static final Logger LOGGER = LoggerFactory.getLogger("MultiCore Universal");
    /** Comma-separated mod ids; a mod's position is the index woven into its classes. */
    public static final String MODS_PROPERTY = "multicore_universal.coremod.mods";
    /** "active", or "failed: reason". */
    public static final String STATUS_PROPERTY = "multicore_universal.coremod.status";

    @Override
    public Iterable<? extends ITransformer<?>> getTransformers() {
        try {
            long start = System.nanoTime();
            ModCatalog catalog = ModCatalog.build();
            ModCatalog.setInstance(catalog);
            System.setProperty(MODS_PROPERTY, String.join(",", catalog.modIds()));
            System.setProperty(STATUS_PROPERTY, "active");
            LOGGER.info("[MultiCore Universal] Indexed {} mods, {} classes will take the mod lock on dimension threads "
                            + "({} left alone because they reference classes that are not installed) in {} ms",
                    catalog.modIds().size(), catalog.targetCount(), catalog.skippedCount(), (System.nanoTime() - start) / 1_000_000L);
            return List.of(new ModClassTransformer(catalog));
        } catch (Throwable t) {
            LOGGER.error("[MultiCore Universal] The coremod failed to start; dimensions will tick on one thread", t);
            System.setProperty(STATUS_PROPERTY, "failed: " + t);
            return List.of();
        }
    }
}
