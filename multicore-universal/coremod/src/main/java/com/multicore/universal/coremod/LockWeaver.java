package com.multicore.universal.coremod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes a method hold MultiCore's mod lock while it runs, in place (same name, same locals, same annotations):
 * <pre>
 * ModLock.enter(modIndex);
 * try { original body } finally { ModLock.exit(); }
 * </pre>
 * {@code enter} only takes the lock on a dimension thread during the parallel phase, for mods not marked thread-safe,
 * and is re-entrant through a per-thread depth counter, so the common cases cost a type check or a field increment.
 * {@code exit} undoes exactly what the matching {@code enter} did, so the pair needs no local variable; this keeps
 * local variable indexes (which other mods' mixins may refer to) unchanged.
 * <p>
 * A {@code synchronized} method takes the mod lock before its monitor instead of after, so the lock order is always
 * "mod lock, then mod monitors" and the two can never deadlock.
 */
public final class LockWeaver {
    public static final String LOCK = "com/multicore/universal/core/ModLock";
    /** Bytecode size above which a method is left alone (the JVM limit is 65535 bytes). */
    private static final int MAX_INSNS = 20_000;
    private static final int TRIVIAL_MAX_INSNS = 64;
    private static final Set<String> PURE_OWNERS = Set.of(
            "java/lang/Math", "java/lang/StrictMath", "net/minecraft/util/Mth", "java/lang/Objects",
            "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double", "java/lang/Boolean",
            "java/lang/Short", "java/lang/Byte", "java/lang/Character",
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable");

    private LockWeaver() {
    }

    /** Wraps every method of a mod class that can be wrapped and is not trivial. Returns how many were wrapped. */
    public static int weaveClass(ClassNode node, int modIndex) {
        return weaveClass(node, modIndex, owner -> !owner.startsWith("java/") && !owner.startsWith("net/minecraft/"));
    }

    /**
     * @param isModClass whether calling a method of that class runs mod code (which takes the lock itself)
     */
    public static int weaveClass(ClassNode node, int modIndex, java.util.function.Predicate<String> isModClass) {
        if (isMixinPlugin(node) || isExtensibleEnum(node)) {
            return 0;
        }
        boolean isEnum = (node.access & Opcodes.ACC_ENUM) != 0;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (isEnum && isEnumMachinery(method)) {
                continue;
            }
            if (canWrap(method) && !isTrivial(method) && !isReadOnlyLeaf(node, method, isModClass) && wrap(node, method, modIndex)) {
                count++;
            }
        }
        if (!isEnum) {
            guardStaticInit(node);
        }
        return count;
    }

    /**
     * Enums NeoForge can extend at runtime (IExtensibleEnum): its RuntimeEnumExtender rewrites their static
     * initializer and {@code $values()} after this transformer, finding its spots by their exact compiled shape (the
     * array size before ANEWARRAY, inserting before the method's last instruction). Any change there breaks it, so
     * such classes are never touched.
     */
    public static boolean isExtensibleEnum(ClassNode node) {
        return (node.access & Opcodes.ACC_ENUM) != 0 && node.interfaces != null
                && node.interfaces.contains("net/neoforged/fml/common/asm/enumextension/IExtensibleEnum");
    }

    /** Compiler-generated enum methods: they only build or read the constants array. */
    private static boolean isEnumMachinery(MethodNode method) {
        return method.name.equals("$values") || method.name.equals("values") || method.name.equals("valueOf");
    }

    /**
     * A method that writes nothing itself (no field, array or static writes, no monitors, no use of library
     * collections) and calls no mod code can only read, and call vanilla or library code that has its own rules (mod
     * overrides reached through vanilla calls are woven themselves). It needs no lock of its own: when a mod method
     * calls it, that caller already holds the lock.
     */
    static boolean isReadOnlyLeaf(ClassNode owner, MethodNode method, java.util.function.Predicate<String> isModClass) {
        if ((method.access & Opcodes.ACC_SYNCHRONIZED) != 0) {
            return false;
        }
        // An iterator belongs to one loop on one thread: moving its own cursor is not shared state, and the class it
        // iterates (its outer class) is usually a plain container.
        boolean iterator = (method.access & Opcodes.ACC_STATIC) == 0 && isIterator(owner);
        int dollar = owner.name.lastIndexOf('$');
        String outer = dollar > 0 ? owner.name.substring(0, dollar) : null;
        List<AbstractInsnNode> scratchCalls = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            switch (op) {
                case Opcodes.PUTFIELD -> {
                    if (!(iterator && ((org.objectweb.asm.tree.FieldInsnNode) insn).owner.equals(owner.name))) {
                        return false;
                    }
                }
                case Opcodes.PUTSTATIC, Opcodes.MONITORENTER, Opcodes.MONITOREXIT,
                        Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE,
                        Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                    return false;
                }
                default -> {
                }
            }
            if (insn instanceof MethodInsnNode call) {
                String o = call.owner;
                if (call.name.equals("<init>") && !isModClass.test(o)) {
                    continue; // building a new object (an exception, a builder) is local to this call
                }
                if (iterator && (o.equals(owner.name) || o.equals(outer))) {
                    continue;
                }
                if (o.equals(owner.name) || isModClass.test(o) || isLibraryContainer(o)) {
                    return false;
                }
                if (call.getOpcode() != Opcodes.INVOKESTATIC && isMutableScratch(o)) {
                    if (scratchCalls == null) {
                        scratchCalls = new ArrayList<>();
                    }
                    scratchCalls.add(call);
                }
            }
        }
        // Calling vanilla or library code is fine, unless it changes an object every dimension shares: a scratch object
        // kept in a static field ("static final MutableBlockPos POS", a static DecimalFormat).
        return scratchCalls == null || !anyOnStaticObject(owner.name, method, scratchCalls);
    }

    /**
     * Vanilla and library types that are mutable and not thread-safe, and that mods commonly keep one instance of in a
     * static field to reuse. Calling their methods on such a shared instance needs the lock.
     */
    private static final List<String> MUTABLE_SCRATCH = List.of("net/minecraft/core/BlockPos$MutableBlockPos", "org/joml/",
            "java/lang/StringBuilder", "java/text/", "net/minecraft/nbt/", "net/minecraft/world/SimpleContainer",
            "net/minecraft/core/NonNullList", "net/neoforged/neoforge/items/ItemStackHandler",
            "net/neoforged/neoforge/fluids/capability/templates/", "com/mojang/blaze3d/vertex/PoseStack");

    static boolean isMutableScratch(String owner) {
        for (String prefix : MUTABLE_SCRATCH) {
            if (owner.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** True if one of the writes or calls acts on an object that may come from a static field (or cannot be analysed). */
    private static boolean anyOnStaticObject(String owner, MethodNode method, List<AbstractInsnNode> insns) {
        ValueOrigins origins = new ValueOrigins(owner, method);
        if (!origins.analysed()) {
            return true;
        }
        for (AbstractInsnNode insn : insns) {
            if (origins.targetOf(insn) == ValueOrigins.Origin.STATIC) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIterator(ClassNode node) {
        if (node.interfaces == null) {
            return false;
        }
        for (String i : node.interfaces) {
            if (i.equals("java/util/Iterator") || i.equals("java/util/ListIterator") || i.equals("java/util/Enumeration")
                    || i.startsWith("java/util/PrimitiveIterator") || i.startsWith("it/unimi/dsi/fastutil/") && i.endsWith("Iterator")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLibraryContainer(String owner) {
        return (owner.startsWith("java/util/") && !owner.equals("java/util/Objects") && !owner.equals("java/util/Optional")
                && !owner.startsWith("java/util/function/")) || owner.equals("java/lang/Iterable") || owner.startsWith("it/unimi/")
                || owner.startsWith("com/google/common/collect/") || owner.startsWith("java/lang/ref/") || owner.startsWith("java/lang/reflect/");
    }

    /** Mixin config plugins run inside the class transformer; they must never wait for a lock. */
    private static boolean isMixinPlugin(ClassNode node) {
        return node.interfaces != null && (node.interfaces.contains("org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin")
                || node.interfaces.contains("org/spongepowered/asm/mixin/extensibility/IMixinErrorHandler"));
    }

    public static boolean canWrap(MethodNode method) {
        return canWrapIgnoringExisting(method) && !isWrapped(method);
    }

    /** Like {@link #canWrap}, but also true for a method that already holds the lock (for an outer layer). */
    public static boolean canWrapIgnoringExisting(MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0) {
            return false;
        }
        if (method.name.equals("<init>") || method.name.equals("<clinit>") || method.instructions.size() == 0) {
            return false;
        }
        return method.instructions.size() < MAX_INSNS && fitsAfterWeaving(method);
    }

    /**
     * For code whose only reason to hold the mod lock is a {@code synchronized} block (often in a rarely taken branch):
     * takes the lock right before each monitor enter and drops it right after each monitor exit, so the lock order
     * stays "mod lock, then monitor" without locking the whole method on every call. javac emits one monitor exit per
     * path out of a synchronized block (normal and exceptional), so each enter is matched by exactly one exit.
     * Returns the number of synchronized blocks wrapped.
     */
    public static int wrapMonitors(ClassNode owner, MethodNode method, int modIndex) {
        if ((owner.version & 0xFFFF) < Opcodes.V1_7) {
            return 0;
        }
        List<AbstractInsnNode> enters = new ArrayList<>();
        List<AbstractInsnNode> exits = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.MONITORENTER) {
                enters.add(insn);
            } else if (insn.getOpcode() == Opcodes.MONITOREXIT) {
                exits.add(insn);
            }
        }
        if (enters.isEmpty() || exits.isEmpty()) {
            return 0;
        }
        for (AbstractInsnNode enter : enters) {
            method.instructions.insertBefore(enter, new org.objectweb.asm.tree.InvokeDynamicInsnNode("enter", "()V", ENTER_BOOTSTRAP, modIndex));
        }
        for (AbstractInsnNode exit : exits) {
            method.instructions.insert(exit, new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, "exit", "()V", false));
        }
        return enters.size();
    }

    /**
     * The coremod wraps mod methods before Mixin runs, and Mixin then changes them in two ways the wrapper must follow:
     * <ul>
     * <li>Hooks at a return ({@code @At("RETURN")}, {@code "TAIL"}, MixinExtras' return value wrappers) are inserted
     * between the wrapper's exit and the return. If such a hook threw, the catch-all handler would run the exit a
     * second time and release a lock the calling mod code still relies on (and, for a synchronized method, exit the
     * monitor twice). Those exits are moved down to the return, so the hook runs inside the lock and the monitor, as it
     * would inside the original method.</li>
     * <li>A cancellable injection in the middle of the method adds a new {@code return} after the enter call but without
     * the exit (nor, for a synchronized method, the monitor exit), so the lock would leak.</li>
     * </ul>
     * Afterwards every return after the first enter call whose own straight-line code (back to the previous jump target)
     * does not already call exit gets one. Returns before the enter call (injected at the method's head) are left alone.
     * Returns the number of exits added.
     */
    public static int repairExits(MethodNode method) {
        int added = 0;
        added += repairExits(method, "enter", "exit");
        added += repairExits(method, "enterStaticInit", "exitStaticInit");
        return added;
    }

    private static int repairExits(MethodNode method, String enterName, String exitName) {
        AbstractInsnNode firstEnter = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (isLockCall(insn, enterName)) {
                firstEnter = insn;
                break;
            }
        }
        if (firstEnter == null) {
            return 0;
        }
        // A woven synchronized method enters its monitor right after the lock: "enter; this/Class; MONITORENTER".
        AbstractInsnNode monitor = nextReal(firstEnter);
        if (monitor == null || !isMonitorObject(monitor) || nextReal(monitor) == null || nextReal(monitor).getOpcode() != Opcodes.MONITORENTER) {
            monitor = null;
        }
        List<AbstractInsnNode> displaced = new ArrayList<>();
        for (AbstractInsnNode insn = firstEnter.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode && isLockCall(insn, exitName) && !endsTheMethod(nextReal(insn))) {
                displaced.add(insn);
            }
        }
        for (AbstractInsnNode exit : displaced) {
            AbstractInsnNode monitorExit = previousReal(exit);
            if (monitor != null && monitorExit != null && monitorExit.getOpcode() == Opcodes.MONITOREXIT
                    && previousReal(monitorExit) != null && isMonitorObject(previousReal(monitorExit))) {
                method.instructions.remove(previousReal(monitorExit));
                method.instructions.remove(monitorExit);
            }
            method.instructions.remove(exit);
        }
        Set<LabelNode> targets = jumpTargets(method);
        List<AbstractInsnNode> missing = new ArrayList<>();
        for (AbstractInsnNode insn = firstEnter.getNext(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op < Opcodes.IRETURN || op > Opcodes.RETURN) {
                continue;
            }
            boolean covered = false;
            for (AbstractInsnNode p = insn.getPrevious(); p != null && p != firstEnter; p = p.getPrevious()) {
                if (isLockCall(p, exitName)) {
                    covered = true;
                    break;
                }
                if (p instanceof LabelNode label && targets.contains(label)) {
                    break;
                }
                int pop = p.getOpcode();
                if ((pop >= Opcodes.IRETURN && pop <= Opcodes.RETURN) || pop == Opcodes.ATHROW || pop == Opcodes.GOTO
                        || pop == Opcodes.TABLESWITCH || pop == Opcodes.LOOKUPSWITCH) {
                    break;
                }
            }
            if (!covered) {
                missing.add(insn);
            }
        }
        for (AbstractInsnNode ret : missing) {
            if (monitor != null) {
                method.instructions.insertBefore(ret, monitor.clone(null));
                method.instructions.insertBefore(ret, new InsnNode(Opcodes.MONITOREXIT));
            }
            method.instructions.insertBefore(ret, new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, exitName, "()V", false));
        }
        return missing.size();
    }

    /** Where a woven exit belongs: right before a return, the rethrow of the catch-all handler, or an outer layer's exit. */
    private static boolean endsTheMethod(AbstractInsnNode next) {
        if (next == null) {
            return true;
        }
        int op = next.getOpcode();
        return (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) || op == Opcodes.ATHROW
                || isLockCall(next, "exit") || isLockCall(next, "exitStaticInit");
    }

    /** The monitor object load of a woven synchronized method: {@code this}, or the class for a static method. */
    private static boolean isMonitorObject(AbstractInsnNode insn) {
        return (insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0)
                || (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type);
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode n = insn.getNext();
        while (n != null && n.getOpcode() < 0) {
            n = n.getNext();
        }
        return n;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) {
            p = p.getPrevious();
        }
        return p;
    }

    /** A call to ModLock.{@code name}, direct or through the invokedynamic enter. */
    static boolean isLockCall(AbstractInsnNode insn, String name) {
        if (insn instanceof MethodInsnNode call) {
            return call.owner.equals(LOCK) && call.name.equals(name);
        }
        return insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy && indy.name.equals(name) && indy.bsm.getOwner().equals(LOCK);
    }

    private static Set<LabelNode> jumpTargets(MethodNode method) {
        Set<LabelNode> out = new java.util.HashSet<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof org.objectweb.asm.tree.JumpInsnNode j) {
                out.add(j.label);
            } else if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode t) {
                out.add(t.dflt);
                out.addAll(t.labels);
            } else if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode l) {
                out.add(l.dflt);
                out.addAll(l.labels);
            }
        }
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
                out.add(tcb.handler);
            }
        }
        return out;
    }

    /** JVM methods are limited to 65535 bytes of code; wrapping adds a few bytes per return. */
    static boolean fitsAfterWeaving(MethodNode method) {
        int size = 0;
        int returns = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            size += maxSize(insn);
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                returns++;
            }
        }
        return size + 32 + returns * 8L < 65_000;
    }

    /** Upper bound of an instruction's encoded size. */
    private static int maxSize(AbstractInsnNode insn) {
        if (insn.getOpcode() < 0) {
            return 0;
        }
        return switch (insn.getType()) {
            case AbstractInsnNode.INSN -> 1;
            case AbstractInsnNode.INT_INSN -> 3;
            case AbstractInsnNode.VAR_INSN -> 4;
            case AbstractInsnNode.TYPE_INSN, AbstractInsnNode.FIELD_INSN, AbstractInsnNode.LDC_INSN -> 3;
            case AbstractInsnNode.METHOD_INSN, AbstractInsnNode.INVOKE_DYNAMIC_INSN, AbstractInsnNode.JUMP_INSN -> 5;
            case AbstractInsnNode.IINC_INSN -> 6;
            case AbstractInsnNode.MULTIANEWARRAY_INSN -> 4;
            case AbstractInsnNode.TABLESWITCH_INSN ->
                    16 + 4 * ((org.objectweb.asm.tree.TableSwitchInsnNode) insn).labels.size();
            case AbstractInsnNode.LOOKUPSWITCH_INSN ->
                    12 + 8 * ((org.objectweb.asm.tree.LookupSwitchInsnNode) insn).labels.size();
            default -> 5;
        };
    }

    /** Already starts with a call to ModLock.enter (woven by the coremod, then given to the mixin extension). */
    public static boolean isWrapped(MethodNode method) {
        int seen = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null && seen < 4; insn = insn.getNext()) {
            if (insn.getOpcode() < 0) {
                continue;
            }
            seen++;
            if (isLockCall(insn, "enter")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Small methods that call nothing (getters, setters, constant returns, arithmetic) cannot reach shared state
     * beyond a single field and are left unwrapped, so the JIT can keep inlining them. Unless that field belongs to an
     * object read from a static field ({@code INSTANCE.count++}): every dimension shares it, and the update must not
     * interleave with another thread's.
     */
    public static boolean isTrivial(MethodNode method) {
        if ((method.access & Opcodes.ACC_SYNCHRONIZED) != 0) {
            return false;
        }
        int real = 0;
        List<AbstractInsnNode> fieldWrites = null;
        boolean readsStaticObject = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op < 0) {
                continue;
            }
            if (++real > TRIVIAL_MAX_INSNS) {
                return false;
            }
            if (op == Opcodes.PUTFIELD) {
                if (fieldWrites == null) {
                    fieldWrites = new ArrayList<>();
                }
                fieldWrites.add(insn);
            } else if (op == Opcodes.GETSTATIC) {
                char kind = ((FieldInsnNode) insn).desc.charAt(0);
                readsStaticObject |= kind == 'L' || kind == '[';
            }
            switch (op) {
                case Opcodes.INVOKEDYNAMIC, Opcodes.MONITORENTER, Opcodes.MONITOREXIT, Opcodes.PUTSTATIC,
                        Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE,
                        Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE, Opcodes.ATHROW -> {
                    return false;
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
                    if (!PURE_OWNERS.contains(((MethodInsnNode) insn).owner)) {
                        return false;
                    }
                }
                default -> {
                }
            }
        }
        return fieldWrites == null || !readsStaticObject || !anyOnStaticObject("java/lang/Object", method, fieldWrites);
    }

    /** Wraps one method. Returns false (and leaves it untouched) if it cannot be wrapped. */
    public static boolean wrap(ClassNode owner, MethodNode method, int modIndex) {
        return canWrap(method) && weave(owner, method, modIndex);
    }

    /**
     * Wraps a method even if it already takes the lock, as an outer layer: used when mixins injected hooks into a
     * woven method outside its lock (at its very start or right before its returns), and when a whole vanilla method
     * must hold the lock. The inner layer then only counts depth.
     */
    public static boolean wrapAgain(ClassNode owner, MethodNode method, int modIndex) {
        return canWrapIgnoringExisting(method) && weave(owner, method, modIndex);
    }

    /** ModLock.bootstrapEnter: links an {@code enter()} call site to {@code ModLock.enter(modIndex)}. */
    static final org.objectweb.asm.Handle ENTER_BOOTSTRAP = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, LOCK, "bootstrapEnter",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;", false);

    /**
     * The mod index travels as an invokedynamic bootstrap argument rather than as an int constant in the code: other
     * mods' mixins into this method may match int constants ({@code @ModifyConstant}, constant expressions) by value
     * and position, and an extra constant would change which one they hit.
     */
    private static boolean weave(ClassNode owner, MethodNode method, int modIndex) {
        InsnList enter = new InsnList();
        if ((owner.version & 0xFFFF) >= Opcodes.V1_7) {
            enter.add(new org.objectweb.asm.tree.InvokeDynamicInsnNode("enter", "()V", ENTER_BOOTSTRAP, modIndex));
        } else {
            enter.add(pushInt(modIndex));
            enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, "enter", "(I)V", false));
        }
        return weave(owner, method, enter, "exit");
    }

    /**
     * Marks a mod class's static initializer, so that code it runs never waits for the mod lock. A thread initializing
     * a class holds that class's initialization lock; if it then waited for the mod lock while the holder of the mod
     * lock needed the same class, both would wait forever. Static initializers run once, mostly at startup.
     */
    public static boolean guardStaticInit(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals("<clinit>") && method.instructions.size() > 0 && fitsAfterWeaving(method)) {
                InsnList enter = new InsnList();
                enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, "enterStaticInit", "()V", false));
                return weave(owner, method, enter, "exitStaticInit");
            }
        }
        return false;
    }

    private static boolean weave(ClassNode owner, MethodNode method, InsnList enter, String exitName) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        boolean sync = (method.access & Opcodes.ACC_SYNCHRONIZED) != 0;
        boolean frames = (owner.version & 0xFFFF) >= Opcodes.V1_6;
        if (sync) {
            method.access &= ~Opcodes.ACC_SYNCHRONIZED;
        }

        InsnList code = method.instructions;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();

        InsnList prologue = new InsnList();
        prologue.add(enter);
        if (sync) {
            prologue.add(monitorObject(owner, isStatic));
            prologue.add(new InsnNode(Opcodes.MONITORENTER));
        }
        prologue.add(start);

        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode insn = code.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                returns.add(insn);
            }
        }
        for (AbstractInsnNode ret : returns) {
            code.insertBefore(ret, exitSequence(owner, isStatic, sync, exitName));
        }
        code.insert(prologue);
        code.add(end);
        code.add(handler);
        if (frames) {
            Object[] locals = sync && !isStatic ? new Object[]{owner.name} : new Object[0];
            code.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 1, new Object[]{"java/lang/Throwable"}));
        }
        code.add(exitSequence(owner, isStatic, sync, exitName));
        code.add(new InsnNode(Opcodes.ATHROW));
        // Last in the table, so the method's own handlers keep catching what they caught before.
        if (method.tryCatchBlocks == null) {
            method.tryCatchBlocks = new ArrayList<>();
        }
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        method.maxStack = Math.max(method.maxStack, 2);
        return true;
    }

    private static InsnList exitSequence(ClassNode owner, boolean isStatic, boolean sync, String exitName) {
        InsnList list = new InsnList();
        if (sync) {
            list.add(monitorObject(owner, isStatic));
            list.add(new InsnNode(Opcodes.MONITOREXIT));
        }
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, exitName, "()V", false));
        return list;
    }

    /** The object a synchronized method locks: {@code this}, or the class for a static method. */
    private static AbstractInsnNode monitorObject(ClassNode owner, boolean isStatic) {
        return isStatic ? new LdcInsnNode(Type.getObjectType(owner.name)) : new VarInsnNode(Opcodes.ALOAD, 0);
    }

    static AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    /** True if the method carries the given annotation (visible or invisible). */
    public static boolean hasAnnotation(MethodNode method, String desc) {
        return findAnnotation(method, desc) != null;
    }

    public static AnnotationNode findAnnotation(MethodNode method, String desc) {
        for (List<AnnotationNode> list : List.of(
                method.visibleAnnotations == null ? List.<AnnotationNode>of() : method.visibleAnnotations,
                method.invisibleAnnotations == null ? List.<AnnotationNode>of() : method.invisibleAnnotations)) {
            for (AnnotationNode a : list) {
                if (a.desc.equals(desc)) {
                    return a;
                }
            }
        }
        return null;
    }

    /** Value of an annotation attribute, or null. */
    public static Object annotationValue(AnnotationNode a, String key) {
        if (a.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (key.equals(a.values.get(i))) {
                return a.values.get(i + 1);
            }
        }
        return null;
    }

    static boolean isFieldWrite(AbstractInsnNode insn) {
        return insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC;
    }
}
