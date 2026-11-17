package com.multicore.universal.compat;

import com.multicore.universal.MultiCore;
import com.multicore.universal.coremod.LockWeaver;
import com.multicore.universal.coremod.ModCatalog;
import com.multicore.universal.coremod.ScopedStatics;
import com.multicore.universal.coremod.StaticStateAnalysis;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/**
 * Runs after Mixin has applied every mixin to a class and makes the code other mods merged into it take the mod lock:
 * <ul>
 * <li>every merged method (injection handlers, overwrites, merged helpers and lambdas) of a mod that is not built in
 * holds the lock while it runs, exactly like that mod's own classes (see the coremod);</li>
 * <li>if such a hook stores state in static fields for another hook to read back (see {@link StaticStateAnalysis}),
 * the whole method it is injected into holds the lock, so no other dimension thread can overwrite that state between
 * the two hooks;</li>
 * <li>in a mod's own class (already woven by the coremod) every method with injected hooks is wrapped once more, so
 * hooks injected at its start or end also run inside the lock.</li>
 * </ul>
 */
public final class MixinLockExtension implements IExtension {
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    /** Mixins of a mod MultiCore cannot identify: locked under an index no mod has. */
    private static final int UNKNOWN_MOD = -100;
    /**
     * Methods that contain (almost) a whole dimension's tick. Holding the lock for all of them would serialize the
     * dimensions completely, so hooks there are only locked individually (and logged).
     */
    private static final Set<String> NEVER_WHOLE = Set.of(
            "net/minecraft/server/level/ServerLevel.tick(Ljava/util/function/BooleanSupplier;)V",
            "net/minecraft/server/level/ServerLevel.tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",
            "net/minecraft/server/level/ServerLevel.tickCustomSpawners(ZZ)V",
            "net/minecraft/world/level/Level.tickBlockEntities()V",
            "net/minecraft/server/level/ServerChunkCache.tick(Ljava/util/function/BooleanSupplier;Z)V",
            "net/minecraft/server/level/ServerChunkCache.tickChunks()V",
            "net/minecraft/server/level/ChunkMap.tick(Ljava/util/function/BooleanSupplier;)V",
            "net/minecraft/server/MinecraftServer.tickServer(Ljava/util/function/BooleanSupplier;)V",
            "net/minecraft/server/MinecraftServer.tickChildren(Ljava/util/function/BooleanSupplier;)V",
            "net/minecraft/server/MinecraftServer.runServer()V");
    private static final MixinLockExtension INSTANCE = new MixinLockExtension();
    private static final AtomicInteger HANDLERS = new AtomicInteger();
    private static final AtomicInteger WHOLE = new AtomicInteger();
    private static final AtomicInteger REWRAPPED = new AtomicInteger();
    /** Hooks left unlocked because wrapping them would need classes that do not exist on this side. */
    private static final AtomicInteger UNSAFE_TO_WRAP = new AtomicInteger();
    private static final AtomicInteger SCOPED_FIELDS = new AtomicInteger();
    /** Hooks whose synchronized blocks take the lock, rather than the whole hook. */
    private static final AtomicInteger MONITOR_ONLY = new AtomicInteger();
    /** Returns added by mixins inside woven mod methods that needed the missing exit. */
    private static final AtomicInteger REPAIRED_EXITS = new AtomicInteger();
    private static final Map<String, String> NOT_WHOLE = new ConcurrentHashMap<>();
    private static volatile boolean registered;

    private MixinLockExtension() {
    }

    /**
     * Mixin selects its active extensions before it loads config plugins (which is where this is called from), so the
     * extension is also added to the active list directly, the same way MixinExtras registers its own.
     */
    @SuppressWarnings("unchecked")
    public static synchronized void register() {
        if (registered) {
            return;
        }
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (!(transformer instanceof IMixinTransformer mixinTransformer)) {
                MultiCore.LOGGER.error("[MultiCore] No active Mixin transformer; other mods' mixins will not take the mod lock");
                return;
            }
            IExtensionRegistry registry = mixinTransformer.getExtensions();
            if (!(registry instanceof Extensions extensions)) {
                MultiCore.LOGGER.error("[MultiCore] Unknown Mixin extension registry {}; other mods' mixins will not take the mod lock",
                        registry.getClass());
                return;
            }
            extensions.add(INSTANCE);
            Field active = Extensions.class.getDeclaredField("activeExtensions");
            active.setAccessible(true);
            List<IExtension> list = new ArrayList<>((List<IExtension>) active.get(extensions));
            if (!list.contains(INSTANCE)) {
                list.add(INSTANCE);
                active.set(extensions, Collections.unmodifiableList(list));
            }
            registered = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MultiCore.LOGGER.error("[MultiCore] Could not register the Mixin extension; other mods' mixins will not take the mod lock", e);
        }
    }

    public static boolean isRegistered() {
        return registered;
    }

    public static void logSummary() {
        MultiCore.LOGGER.info("[MultiCore] Mixin code from other mods: {} hooks lock only around their synchronized blocks",
                MONITOR_ONLY.get());
        MultiCore.LOGGER.info("[MultiCore] Mixin code from other mods: {} hooks take the mod lock, {} vanilla methods run "
                + "entirely under it (their hooks pass state through static fields), {} mod methods re-wrapped around injected hooks, "
                + "{} hooks left unlocked (their code uses classes that do not exist on this side), {} mixin flags made per "
                + "dimension thread, {} early returns from mixins into mod methods given their missing lock exit",
                HANDLERS.get(), WHOLE.get(), REWRAPPED.get(), UNSAFE_TO_WRAP.get(), SCOPED_FIELDS.get(), REPAIRED_EXITS.get());
        NOT_WHOLE.forEach((method, mixin) -> MultiCore.LOGGER.warn("[MultiCore] {} stores state in static fields inside {}, "
                + "which is too large to lock as a whole; only its hooks are locked. If that mod misbehaves with MultiCore, "
                + "report it.", mixin, method));
    }

    public static int handlerCount() {
        return HANDLERS.get();
    }

    public static int wholeMethodCount() {
        return WHOLE.get();
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    /** Field names of classes about to receive mixins, to tell mixin-added fields apart afterwards. */
    private static final Map<String, Set<String>> ORIGINAL_FIELDS = new ConcurrentHashMap<>();

    @Override
    public void preApply(ITargetClassContext context) {
        ClassNode node = context.getClassNode();
        Set<String> names = new java.util.HashSet<>();
        node.fields.forEach(f -> names.add(f.name));
        ORIGINAL_FIELDS.put(node.name, names);
    }

    @Override
    public void postApply(ITargetClassContext context) {
        ClassNode node = context.getClassNode();
        Set<String> original = ORIGINAL_FIELDS.remove(node.name);
        ModCatalog catalog = ModCatalog.get();
        if (catalog == null) {
            return;
        }
        try {
            process(catalog, node, original);
        } catch (Throwable t) {
            MultiCore.LOGGER.error("[MultiCore] Could not lock the mixin code merged into {}", node.name, t);
        }
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
    }

    private record Hook(int mod, StaticStateAnalysis.HookEffects effects, String mixin) {
    }

    static void process(ModCatalog catalog, ClassNode node, Set<String> originalFields) {
        if (LockWeaver.isExtensibleEnum(node)) {
            return; // NeoForge's enum extender rewrites these by their exact shape; see LockWeaver.isExtensibleEnum
        }
        // Static flags that mixins added and that are set and cleared within one operation become per dimension thread
        // (rewritten at the end), so the analysis below does not count them as shared state.
        Set<String> scoped = Set.of();
        Set<String> perThread = Set.of();
        if (originalFields != null) {
            Set<String> mergedMethods = new java.util.HashSet<>();
            for (MethodNode m : node.methods) {
                if (LockWeaver.findAnnotation(m, MERGED) != null) {
                    mergedMethods.add(m.name + m.desc);
                }
            }
            scoped = ScopedStatics.find(node, originalFields, mergedMethods);
            Set<String> keys = new java.util.HashSet<>(catalog.perThreadFields().keySet());
            for (String f : scoped) {
                keys.add(node.name + "." + f);
            }
            perThread = keys;
        } else {
            perThread = catalog.perThreadFields().keySet();
        }
        // Mods' own scoped flags (see the coremod's ModScopedStatics) read or written by merged hook code. The mod's own
        // classes were already rewritten by the coremod, so only merged methods are rewritten in them.
        boolean modClass = catalog.modIndexOfTarget(node.name) >= 0;
        ScopedStatics.rewriteFields(node, catalog.perThreadFields(), m -> !modClass || LockWeaver.findAnnotation(m, MERGED) != null);
        // A mod class was woven before Mixin ran; cancellable injections may have added returns that skip the exit.
        if (catalog.modIndexOfTarget(node.name) >= 0) {
            int repaired = 0;
            for (MethodNode m : node.methods) {
                repaired += LockWeaver.repairExits(m);
            }
            if (repaired > 0) {
                REPAIRED_EXITS.addAndGet(repaired);
            }
        }
        Map<String, Hook> hooks = new HashMap<>();
        for (MethodNode m : node.methods) {
            AnnotationNode merged = LockWeaver.findAnnotation(m, MERGED);
            if (merged == null) {
                continue;
            }
            Object mixin = LockWeaver.annotationValue(merged, "mixin");
            int mod = mixin instanceof String s ? catalog.modIndexOfMixin(s) : -2;
            if (mod == -1) {
                continue; // built in (vanilla, NeoForge, MultiCore) or excluded
            }
            if (mod == -2) {
                mod = UNKNOWN_MOD;
            }
            hooks.put(m.name + m.desc, new Hook(mod, StaticStateAnalysis.effects(node.name, m, catalog, perThread), String.valueOf(mixin)));
            if (!LockWeaver.isTrivial(m) && LockWeaver.canWrap(m) && StaticStateAnalysis.needsOwnLock(node.name, m, perThread)) {
                if (!StaticStateAnalysis.needsOwnLock(node.name, m, perThread, true) && LockWeaver.wrapMonitors(node, m, mod) > 0) {
                    // Only its synchronized blocks need the lock (ModernFix's lazy caches): lock just those.
                    MONITOR_ONLY.incrementAndGet();
                } else if (catalog.wrapIsSafe(node, m) && LockWeaver.wrap(node, m, mod)) {
                    HANDLERS.incrementAndGet();
                } else {
                    UNSAFE_TO_WRAP.incrementAndGet();
                }
            }
        }
        if (!scoped.isEmpty()) {
            int accesses = ScopedStatics.rewrite(node, scoped, ModCatalog::newOverlaySlot);
            SCOPED_FIELDS.addAndGet(scoped.size());
            MultiCore.LOGGER.debug("[MultiCore] {}: mixin flags {} are per dimension thread ({} accesses)", node.name, scoped, accesses);
        }
        if (hooks.isEmpty()) {
            return;
        }
        int classMod = catalog.modIndexOfTarget(node.name);
        for (MethodNode m : node.methods) {
            if (LockWeaver.findAnnotation(m, MERGED) != null || !LockWeaver.canWrapIgnoringExisting(m)) {
                continue;
            }
            java.util.List<Hook> called = new java.util.ArrayList<>();
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                Hook hook = null;
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    hook = hooks.get(call.name + call.desc);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle h && h.getOwner().equals(node.name) && hooks.containsKey(h.getName() + h.getDesc())) {
                            hook = hooks.get(h.getName() + h.getDesc());
                        }
                    }
                }
                if (hook != null && !called.contains(hook)) {
                    called.add(hook);
                }
            }
            if (called.isEmpty()) {
                continue;
            }
            // Whole-method lock when the hooks run a protocol across the vanilla code between them: a hook assigns a
            // shared static field itself, or one hook sets shared state that another hook clears.
            Hook writer = null;
            for (int i = 0; i < called.size() && writer == null; i++) {
                Hook a = called.get(i);
                if (a.effects().directWrite()) {
                    writer = a;
                }
                for (int j = i + 1; j < called.size() && writer == null; j++) {
                    if (a.effects().pairsWith(called.get(j).effects())) {
                        writer = a;
                    }
                }
            }
            if (classMod >= 0) {
                if (catalog.wrapIsSafe(node, m) && LockWeaver.wrapAgain(node, m, classMod)) {
                    REWRAPPED.incrementAndGet();
                }
            } else if (writer != null) {
                String key = node.name + "." + m.name + m.desc;
                if (NEVER_WHOLE.contains(key)) {
                    NOT_WHOLE.putIfAbsent(key, writer.mixin());
                } else if (!catalog.wrapIsSafe(node, m)) {
                    UNSAFE_TO_WRAP.incrementAndGet();
                } else if (LockWeaver.wrapAgain(node, m, writer.mod())) {
                    WHOLE.incrementAndGet();
                    MultiCore.LOGGER.info("[MultiCore] {} runs entirely under the mod lock: {} passes state between hooks there "
                            + "through static fields", key, writer.mixin());
                }
            }
        }
    }
}
