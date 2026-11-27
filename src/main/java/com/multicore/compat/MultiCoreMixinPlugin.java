package com.multicore.compat;

import com.multicore.MultiCore;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Compatibility mixins live in {@code com.multicore.mixin.compat.<modid>} and are only applied when that mod is
 * installed. Also runs bytecode rewrites that plain mixins cannot express (see {@link MethodLockTransformer}).
 */
public final class MultiCoreMixinPlugin implements IMixinConfigPlugin {
    private static final String COMPAT_PACKAGE = "com.multicore.mixin.compat.";
    private static final String TEST_PACKAGE = "com.multicore.mixin.test.";

    @Override
    public void onLoad(String mixinPackage) {
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
        if (!mixinClassName.startsWith(COMPAT_PACKAGE)) {
            return true;
        }
        String rest = mixinClassName.substring(COMPAT_PACKAGE.length());
        String modId = rest.substring(0, rest.indexOf('.'));
        return isModLoaded(modId);
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
        if (mixinClassName.contains(".guard.StaticGuardCarrier")) {
            String simple = targetClassName.substring(targetClassName.lastIndexOf('.') + 1);
            int wrapped = StaticGuard.isPerWorldState(targetClass.name)
                    ? MethodLockTransformer.wrapAllMethods(targetClass, "per-world " + simple)
                    : MethodLockTransformer.wrapMethodsReading(targetClass, StaticGuard.fields(), "static " + simple);
            int total = StaticGuard.noteWrapped(wrapped);
            int classes = StaticGuard.noteClass();
            if (classes <= 3 || classes % 100 == 0) {
                MultiCore.LOGGER.info("[MultiCore] Static guard: {} ({} methods); {} classes, {} methods so far", targetClassName, wrapped, classes, total);
            }
            return;
        }
        if (mixinClassName.endsWith(".createendertransmission.ChannelPauseCarrier")) {
            int wrapped = MethodLockTransformer.wrapMethodsTouching(targetClass, ENDER_CHANNELS,
                    "createendertransmission channels", "enterSharedPausing");
            MultiCore.LOGGER.info("[MultiCore] {}: {} methods that use the transmitter channels take the lock and pause the world", targetClassName, wrapped);
            return;
        }
        if (mixinClassName.endsWith(".sable.RapierNativeLockCarrier")) {
            int wrapped = MethodLockTransformer.wrapMethodsCalling(targetClass, "dev/ryanhcode/sable/physics/impl/rapier/Rapier3D", "sable");
            MultiCore.LOGGER.info("[MultiCore] {}: {} methods that call Sable's native physics now take the mod lock", targetClassName, wrapped);
        } else if (mixinClassName.endsWith(".openpartiesandclaims.ClaimCaptureCarrier")
                || mixinClassName.endsWith(".openpartiesandclaims.ClaimCaptureInterfaceCarrier")) {
            int rewritten = ThreadLocalStaticsTransformer.rewrite(targetClass, OPAC_SERVER_CORE, OPAC_CAPTURE_FIELDS);
            MultiCore.LOGGER.info("[MultiCore] {}: {} uses of Open Parties and Claims capture fields made per-thread", targetClassName, rewritten);
        } else if (mixinClassName.endsWith(".railways.MixinVariablesCarrier")) {
            int rewritten = ThreadLocalStaticsTransformer.rewrite(targetClass, RAILS_MIXIN_VARIABLES, RAILS_FIELDS);
            MultiCore.LOGGER.info("[MultiCore] {}: {} uses of Steam 'n' Rails flag fields made per-thread", targetClassName, rewritten);
        } else if (mixinClassName.endsWith(".SharedStateLockCarrier")) {
            String rest = mixinClassName.substring(COMPAT_PACKAGE.length());
            String modId = rest.substring(0, rest.indexOf('.'));
            int wrapped = MethodLockTransformer.wrapAllMethods(targetClass, modId + " " + targetClassName.substring(targetClassName.lastIndexOf('.') + 1));
            MultiCore.LOGGER.info("[MultiCore] {}: {} methods take the mod lock (state shared by every machine)", targetClassName, wrapped);
        } else if (mixinClassName.endsWith(".create.LogisticsPauseCarrier")) {
            // The per-tick bookkeeping (now run on every world's thread with the level tick events) only touches Create's
            // own records: the lock is enough. Requests and stock summaries reach inventories: those pause the world.
            // GlobalLogisticsManager never reaches an inventory (every method only reads or writes its network maps; checked
            // in Create 6.0.10 bytecode), so it only locks: factory gauges called getUnloadedLinkCount every tick, and each
            // call stopped every other world thread (spark 2026-11-27: 5-6% of overworld and creative).
            // LogisticsManager.getSummaryOfNetwork is a cache lookup; only a cache miss (createSummaryOfNetwork, still
            // pausing) walks the network's inventories.
            boolean global = targetClassName.endsWith(".GlobalLogisticsManager");
            int wrapped = MethodLockTransformer.wrapAllMethods(targetClass,
                    "create " + targetClassName.substring(targetClassName.lastIndexOf('.') + 1),
                    // Hooks other mods' mixins merged into the class (names with '$', e.g. a profiler's) only lock too.
                    m -> global || m.name.equals("tick") || m.name.contains("$") || LOGISTICS_LOCK_ONLY.contains(m.name)
                            ? "enterShared" : "enterSharedPausing");
            MultiCore.LOGGER.info("[MultiCore] {}: {} methods take the mod lock and pause every other tick thread", targetClassName, wrapped);
        } else if (mixinClassName.endsWith(".create.BrassTunnelStaticsCarrier")) {
            int rewritten = ThreadLocalStaticsTransformer.rewrite(targetClass, ThreadStatics.CREATE_BRASS_TUNNEL, CREATE_BRASS_TUNNEL_FIELDS);
            MultiCore.LOGGER.info("[MultiCore] {}: {} uses of the brass tunnel's scratch collections made per-thread", targetClassName, rewritten);
        } else if (mixinClassName.endsWith(".create.DeployerCaptureCarrier")) {
            int rewritten = ThreadLocalStaticsTransformer.rewrite(targetClass, ThreadStatics.CREATE_DEPLOYER_HANDLER, CREATE_DEPLOYER_FIELDS);
            MultiCore.LOGGER.info("[MultiCore] {}: {} uses of Create's deployer drop capture made per-thread", targetClassName, rewritten);
        }
    }

    private static final Set<String> ENDER_CHANNELS = Set.of(
            "com/forsteri/createendertransmission/blocks/energyTransmitter/EnergyNetwork.channels",
            "com/forsteri/createendertransmission/blocks/MatterTransmitterNetwork.channels");
    /** LogisticsManager methods that do not reach inventories themselves (see the LogisticsPauseCarrier branch). */
    private static final Set<String> LOGISTICS_LOCK_ONLY = Set.of("getSummaryOfNetwork");
    private static final String RAILS_MIXIN_VARIABLES = "com/railwayteam/railways/util/MixinVariables";
    private static final Set<String> RAILS_FIELDS = Set.of("trackEdgeTemporarilyFlipped", "trackEdgeCarriageTravelling",
            "temporarilySkipSwitches", "signalPropagatorCallDepth", "navigationCallDepth", "largeGhastFireballExplosion");
    private static final Set<String> CREATE_BRASS_TUNNEL_FIELDS = Set.of("distributed", "full");
    private static final Set<String> CREATE_DEPLOYER_FIELDS = Set.of("CAPTURED_BLOCK_DROPS", "CAPTURED_BLOCK_DROPS_VIEW");

    private static final String OPAC_SERVER_CORE = "xaero/pac/common/server/core/ServerCore";
    /** State handed from a "before" hook to an "after" hook on the same thread. The *_USABLE kill switches stay global. */
    private static final Set<String> OPAC_CAPTURE_FIELDS = Set.of(
            "DETECTING_ENTITY_BLOCK_COLLISION", "DETECTING_ENTITY_BLOCK_COLLISION_POS", "CAPTURED_TARGET_POS", "CAPTURED_POS_STATE_MAP",
            "CREATE_DISASSEMBLE_SUPER_GLUE_LEVEL", "CREATE_DISASSEMBLE_SUPER_GLUE_ANCHOR", "CREATE_PLACING_CONTRAPTION",
            "CREATE_PLACING_CONTRAPTION_TICK", "ENCHANTMENT_EFFECT_BLOCKSTATE", "ENCHANTMENT_EFFECT_LEVEL", "ENCHANTMENT_EFFECT_ENTITY",
            "ENCHANTMENT_EFFECT_I", "HANDLING_ENCHANTMENT_ON_BLOCK", "HANDLING_ENCHANTMENT_ON_DISK", "FINDING_RAID_SPAWN_POS",
            "RAID_SPAWN_POS_CAPTURE_TICK", "DYING_LIVING", "DYING_LIVING_FROM", "DYING_LIVING_TICK", "DROPPING_LOOT_LIVING",
            "DROPPING_LOOT_LIVING_FROM", "DROPPING_LOOT_LIVING_TICK", "MOB_GRIEFING_IS_FOR_ITEMS", "MOB_GRIEFING_IS_FOR_ITEMS_TICK",
            "BEHAVIOR_UTILS_THROW_ITEM_LIVING", "BEHAVIOR_UTILS_THROW_ITEM_TICK", "RESOURCES_DROP_OWNER", "RESOURCES_DROP_OWNER_TICK",
            "PROJECTILE_HIT", "PROJECTILE_HIT_TICK");
}
