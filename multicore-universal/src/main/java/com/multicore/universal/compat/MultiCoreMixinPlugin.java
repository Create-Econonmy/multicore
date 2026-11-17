package com.multicore.universal.compat;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Registers the {@link MixinLockExtension} (Mixin loads config plugins before it applies any mixin) and filters
 * MultiCore's own mixins.
 */
public final class MultiCoreMixinPlugin implements IMixinConfigPlugin {
    private static final String TEST_PACKAGE = "com.multicore.universal.mixin.test.";

    @Override
    public void onLoad(String mixinPackage) {
        MixinLockExtension.register();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(TEST_PACKAGE)) {
            // Test harness patches never load on a real server.
            return System.getProperty("neoforge.enabledGameTestNamespaces") != null;
        }
        if (mixinClassName.endsWith(".safety.RedStoneWireBlockMixin")) {
            // dimthread overwrites the same methods with its own per-thread switch.
            return !isModLoaded("dimthread");
        }
        return true;
    }

    private static boolean isModLoaded(String modId) {
        try {
            return FMLLoader.getLoadingModList().getModFileById(modId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
