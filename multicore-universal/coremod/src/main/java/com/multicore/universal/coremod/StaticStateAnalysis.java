package com.multicore.universal.coremod;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Finds mixin code that hands information from one hook to another through static state: a "before" hook stores the
 * entity being processed in a static field (or pushes it on a static stack) and an "after" hook, or a hook further
 * down the call, reads it back. With one server thread that is correct; with several dimension threads another thread
 * can overwrite the value in between, even if every hook holds the lock while it runs.
 * <p>
 * The vanilla method such code is injected into must therefore hold the lock from start to end. This check looks at
 * the hook itself and one level into the mod methods it calls: static methods, and methods of a mod object it reads
 * from a static field (a singleton).
 */
public final class StaticStateAnalysis {
    private static final Set<String> MUTATORS = Set.of(
            "add", "addAll", "addFirst", "addLast", "push", "pop", "poll", "pollFirst", "pollLast", "offer", "offerFirst",
            "offerLast", "remove", "removeFirst", "removeLast", "removeIf", "clear", "put", "putAll", "putIfAbsent",
            "compute", "computeIfAbsent", "computeIfPresent", "merge", "replace", "set", "lazySet", "setPlain",
            "setRelease", "getAndSet", "compareAndSet", "weakCompareAndSet", "incrementAndGet", "decrementAndGet",
            "getAndIncrement", "getAndDecrement", "addAndGet", "getAndAdd", "updateAndGet", "getAndUpdate",
            "accumulateAndGet", "getAndAccumulate", "setValue", "increment", "decrement");
    private static final Map<String, Boolean> CALLEE_CACHE = new ConcurrentHashMap<>();

    private StaticStateAnalysis() {
    }

    /** True if the method (or a static method of a locked mod it calls) writes shared static state. */
    public static boolean writesSharedStatic(MethodNode method, @Nullable ModCatalog catalog) {
        return writesSharedStatic("java/lang/Object", method, catalog, Set.of());
    }

    /**
     * @param owner     the class the method is in (for the data-flow analysis)
     * @param perThread fields ("owner.name") that are made per dimension thread, so writing them is not sharing state
     */
    public static boolean writesSharedStatic(String owner, MethodNode method, @Nullable ModCatalog catalog, Set<String> perThread) {
        if (writesDirectly(owner, method, perThread)) {
            return true;
        }
        if (catalog == null) {
            return false;
        }
        // Mod singletons read from static fields: writing their fields is writing shared state.
        Set<String> singletons = new java.util.HashSet<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.desc.startsWith("L")) {
                String type = f.desc.substring(1, f.desc.length() - 1);
                if (catalog.modIndexOfTarget(type) >= 0) {
                    singletons.add(type);
                }
            }
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && singletons.contains(f.owner)) {
                return true;
            }
            if (!(insn instanceof MethodInsnNode call) || catalog.modIndexOfTarget(call.owner) < 0) {
                continue;
            }
            boolean onSingleton = call.getOpcode() != Opcodes.INVOKESTATIC && singletons.contains(call.owner);
            if ((call.getOpcode() == Opcodes.INVOKESTATIC || onSingleton)
                    && calleeWrites(catalog, call.owner, call.name, call.desc, onSingleton)) {
                return true;
            }
        }
        return false;
    }

    /** PUTSTATIC to a non-JDK field, or a write or mutating call on an object read from a static field. */
    static boolean writesDirectly(MethodNode method) {
        return writesDirectly("java/lang/Object", method, Set.of());
    }

    static boolean writesDirectly(String owner, MethodNode method, Set<String> perThread) {
        ValueOrigins origins = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (insn instanceof FieldInsnNode f && op == Opcodes.PUTSTATIC) {
                if (!f.owner.startsWith("java/") && !perThread.contains(f.owner + "." + f.name)) {
                    return true;
                }
            } else if (op == Opcodes.PUTFIELD || isArrayStore(op)
                    // Only library containers: a mod method called on a static object takes the lock itself, and
                    // whether it writes that object's state is checked one level deep (see writesSharedStatic).
                    || (insn instanceof MethodInsnNode call && op != Opcodes.INVOKESTATIC && isCollectionOwner(call.owner)
                    && MUTATORS.contains(call.name) && !isCounterUpdate(call))) {
                if (origins == null) {
                    origins = new ValueOrigins(owner, method);
                    if (!origins.analysed()) {
                        return roughlyWritesStatic(method, perThread);
                    }
                }
                if (origins.targetOf(insn) == ValueOrigins.Origin.STATIC) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True if a mixin hook itself touches state that may be shared, so it must hold the mod lock while it runs: it
     * writes a static field, enters a monitor, writes into an object that did not come from its own arguments, or
     * uses a collection that did not. Hooks that only read their arguments and call methods do not: every mod method
     * they call takes the lock itself, and vanilla code is MultiCore's own responsibility.
     */
    public static boolean needsOwnLock(String owner, MethodNode method, Set<String> perThread) {
        return needsOwnLock(owner, method, perThread, false);
    }

    /**
     * @param ignoreMonitors leave {@code synchronized} blocks out of the decision (the caller locks just around them,
     *                       see LockWeaver.wrapMonitors)
     */
    public static boolean needsOwnLock(String owner, MethodNode method, Set<String> perThread, boolean ignoreMonitors) {
        ValueOrigins origins = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op == Opcodes.MONITORENTER) {
                if (ignoreMonitors) {
                    continue;
                }
                return true;
            }
            if (insn instanceof FieldInsnNode f && op == Opcodes.PUTSTATIC) {
                if (!f.owner.startsWith("java/") && !perThread.contains(f.owner + "." + f.name)) {
                    return true;
                }
                continue;
            }
            boolean collectionCall = insn instanceof MethodInsnNode call && op != Opcodes.INVOKESTATIC && isCollectionOwner(call.owner)
                    && !isCounterUpdate(call);
            // Vanilla scratch objects (a static MutableBlockPos) are only shared when they come from a static field.
            boolean scratchCall = insn instanceof MethodInsnNode call && op != Opcodes.INVOKESTATIC && LockWeaver.isMutableScratch(call.owner);
            if (op == Opcodes.PUTFIELD || isArrayStore(op) || collectionCall || scratchCall) {
                if (origins == null) {
                    origins = new ValueOrigins(owner, method);
                    if (!origins.analysed()) {
                        return true;
                    }
                }
                ValueOrigins.Origin target = origins.targetOf(insn);
                if (scratchCall ? target == ValueOrigins.Origin.STATIC : target != ValueOrigins.Origin.LOCAL) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Set<String> CLEARING = Set.of("remove", "removeFirst", "removeLast", "pop", "poll", "pollFirst",
            "pollLast", "clear", "removeIf", "removeAll", "retainAll");
    private static final Map<String, HookEffects> CALLEE_EFFECTS = new ConcurrentHashMap<>();

    /**
     * What shared static state a hook sets and clears (field keys "owner.name"), directly or through the mod methods it
     * calls. Two hooks into one vanilla method that set and clear the same state hand data to each other across the
     * vanilla code in between, so that method must hold the lock from start to end.
     *
     * @param directWrite the hook itself assigns a shared static field
     */
    public record HookEffects(boolean directWrite, Set<String> sets, Set<String> clears) {
        static final HookEffects NONE = new HookEffects(false, Set.of(), Set.of());

        /** This hook sets state that {@code other} clears (or the other way round). */
        public boolean pairsWith(HookEffects other) {
            return intersects(this.sets, other.clears) || intersects(other.sets, this.clears);
        }

        private static boolean intersects(Set<String> a, Set<String> b) {
            for (String s : a) {
                if (b.contains(s)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static HookEffects effects(String owner, MethodNode hook, @Nullable ModCatalog catalog, Set<String> perThread) {
        java.util.Set<String> sets = new java.util.HashSet<>();
        java.util.Set<String> clears = new java.util.HashSet<>();
        boolean direct = collect(owner, hook, perThread, sets, clears);
        if (catalog != null) {
            for (AbstractInsnNode insn = hook.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && catalog.modIndexOfTarget(call.owner) >= 0) {
                    HookEffects callee = calleeEffects(catalog, call.owner, call.name, call.desc);
                    sets.addAll(callee.sets());
                    clears.addAll(callee.clears());
                }
            }
        }
        return new HookEffects(direct, sets, clears);
    }

    /** Collects sets and clears of shared static state in one method; returns true if it assigns a static field. */
    private static boolean collect(String owner, MethodNode method, Set<String> perThread, Set<String> sets, Set<String> clears) {
        boolean direct = false;
        ValueOrigins origins = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (insn instanceof FieldInsnNode f && op == Opcodes.PUTSTATIC) {
                String key = f.owner + "." + f.name;
                if (!f.owner.startsWith("java/") && !perThread.contains(key)) {
                    direct = true;
                    (isDefaultValue(insn) ? clears : sets).add(key);
                }
                continue;
            }
            boolean collectionCall = insn instanceof MethodInsnNode call && op != Opcodes.INVOKESTATIC && isCollectionOwner(call.owner)
                    && (MUTATORS.contains(call.name) || CLEARING.contains(call.name)) && !isCounterUpdate(call);
            if (!collectionCall && op != Opcodes.PUTFIELD) {
                continue;
            }
            if (origins == null) {
                origins = new ValueOrigins(owner, method);
            }
            String field = origins.staticFieldOf(insn);
            if (field == null || perThread.contains(field)) {
                continue;
            }
            boolean clearing = collectionCall ? CLEARING.contains(((MethodInsnNode) insn).name)
                    || (((MethodInsnNode) insn).name.endsWith("et") && isDefaultValue(insn)) : isDefaultValue(insn);
            (clearing ? clears : sets).add(field);
        }
        return direct;
    }

    private static HookEffects calleeEffects(ModCatalog catalog, String owner, String name, String desc) {
        String key = owner + "." + name + desc;
        HookEffects cached = CALLEE_EFFECTS.get(key);
        if (cached != null) {
            return cached;
        }
        HookEffects result = HookEffects.NONE;
        byte[] bytes = catalog.readClass(owner);
        if (bytes != null) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            for (MethodNode m : node.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) {
                    java.util.Set<String> sets = new java.util.HashSet<>();
                    java.util.Set<String> clears = new java.util.HashSet<>();
                    collect(owner, m, catalog.perThreadFields().keySet(), sets, clears);
                    result = new HookEffects(false, sets, clears);
                    break;
                }
            }
        }
        CALLEE_EFFECTS.put(key, result);
        return result;
    }

    /** The value stored by a PUTSTATIC/PUTFIELD or passed last to a call is a default constant (0, false, null). */
    private static boolean isDefaultValue(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) {
            p = p.getPrevious();
        }
        if (p == null) {
            return false;
        }
        return switch (p.getOpcode()) {
            case Opcodes.ICONST_0, Opcodes.LCONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0, Opcodes.ACONST_NULL -> true;
            default -> false;
        };
    }

    private static boolean isArrayStore(int op) {
        return op >= Opcodes.IASTORE && op <= Opcodes.SASTORE;
    }

    /** Library container types whose methods are not woven (so calling them does not take the lock). */
    private static boolean isCollectionOwner(String owner) {
        if (owner.startsWith("java/util/")) {
            return !owner.equals("java/util/Optional") && !owner.equals("java/util/UUID") && !owner.startsWith("java/util/function/")
                    && !owner.startsWith("java/util/regex/") && !owner.startsWith("java/util/logging/") && !owner.startsWith("java/util/stream/");
        }
        return owner.equals("java/lang/Iterable") || owner.startsWith("it/unimi/dsi/fastutil/") || owner.startsWith("com/google/common/collect/")
                || owner.startsWith("org/apache/commons/lang3/mutable/");
    }

    /** Fallback when the method cannot be analysed: a mutable static is read and something mutating is called. */
    private static boolean roughlyWritesStatic(MethodNode method, Set<String> perThread) {
        boolean readsMutableStatic = false;
        boolean callsMutator = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f) {
                if (!perThread.isEmpty() && perThread.contains(f.owner + "." + f.name)) {
                    continue;
                }
                if (f.getOpcode() == Opcodes.PUTSTATIC && !f.owner.startsWith("java/")) {
                    return true;
                }
                if (f.getOpcode() == Opcodes.GETSTATIC && isMutableType(f.desc)) {
                    readsMutableStatic = true;
                }
            } else if (insn instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC && MUTATORS.contains(call.name)
                    && !isCounterUpdate(call)) {
                callsMutator = true;
            }
        }
        return readsMutableStatic && callsMutator;
    }

    private static final Set<String> COUNTER_TYPES = Set.of("java/util/concurrent/atomic/AtomicInteger",
            "java/util/concurrent/atomic/AtomicLong", "java/util/concurrent/atomic/LongAdder", "java/util/concurrent/atomic/DoubleAdder",
            "java/util/concurrent/atomic/AtomicIntegerArray", "java/util/concurrent/atomic/AtomicLongArray");
    private static final Set<String> COUNTER_METHODS = Set.of("incrementAndGet", "decrementAndGet", "getAndIncrement",
            "getAndDecrement", "addAndGet", "getAndAdd", "increment", "decrement", "add");

    /** Bumping a thread-safe counter (statistics) is not a hand-over between hooks. */
    private static boolean isCounterUpdate(MethodInsnNode call) {
        return COUNTER_TYPES.contains(call.owner) && COUNTER_METHODS.contains(call.name);
    }

    /** {@code ownFields}: the callee runs on a shared singleton, so writing its own fields also counts. */
    private static boolean calleeWrites(ModCatalog catalog, String owner, String name, String desc, boolean ownFields) {
        String key = owner + "." + name + desc + ownFields;
        Boolean cached = CALLEE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        byte[] bytes = catalog.readClass(owner);
        if (bytes != null) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            for (MethodNode m : node.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) {
                    result = writesDirectly(owner, m, catalog.perThreadFields().keySet()) || (ownFields && writesOwnFields(m, owner));
                    break;
                }
            }
        }
        CALLEE_CACHE.put(key, result);
        return result;
    }

    private static boolean writesOwnFields(MethodNode method, String owner) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(owner)) {
                return true;
            }
        }
        return false;
    }

    /** Collections, atomics, arrays and mod-defined objects; not immutable values or registry objects. */
    static boolean isMutableType(String desc) {
        Type type = Type.getType(desc);
        if (type.getSort() == Type.ARRAY) {
            return true;
        }
        if (type.getSort() != Type.OBJECT) {
            return false;
        }
        String n = type.getInternalName();
        if (n.startsWith("java/util/concurrent/atomic/") || n.startsWith("it/unimi/dsi/fastutil/")
                || n.startsWith("org/apache/commons/lang3/mutable/")) {
            return true;
        }
        if (n.startsWith("java/util/")) {
            return !n.equals("java/util/Optional") && !n.equals("java/util/UUID") && !n.startsWith("java/util/function/")
                    && !n.startsWith("java/util/regex/") && !n.startsWith("java/util/logging/");
        }
        if (n.startsWith("com/google/common/collect/")) {
            return !n.startsWith("com/google/common/collect/Immutable");
        }
        return false;
    }
}
