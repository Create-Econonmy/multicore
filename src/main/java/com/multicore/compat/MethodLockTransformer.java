package com.multicore.compat;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes every method of a class that calls into a given (native) class run under the {@link com.multicore.core.ModLock}.
 * <p>
 * Each such method {@code m} is renamed to {@code multicore$locked$m} and replaced by a wrapper with the original
 * name, signature and annotations:
 * <pre>
 * boolean held = ModLock.enterForMod(modId);
 * try { return multicore$locked$m(args); } finally { ModLock.exit(held); }
 * </pre>
 * Used for native libraries that keep global state (Sable's Rapier physics) and therefore must never be entered by
 * two threads at once. Runs from {@link MultiCoreMixinPlugin#postApply}.
 */
public final class MethodLockTransformer {
    private static final String LOCK = "com/multicore/core/ModLock";
    private static final String PREFIX = "multicore$locked$";

    private MethodLockTransformer() {
    }

    public static int wrapMethodsCalling(ClassNode node, String calleeOwner, String modId) {
        List<MethodNode> wrappers = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.name.startsWith("<")
                    || method.name.startsWith(PREFIX) || !calls(method, calleeOwner)) {
                continue;
            }
            wrappers.add(wrap(node, method, modId, "enterForMod"));
        }
        node.methods.addAll(wrappers);
        return wrappers.size();
    }

    /**
     * Makes every method of the class run under the {@link com.multicore.core.ModLock} while a dimension or region thread
     * ticks in parallel, even when the mod itself is thread-safe ({@code ModLock.enterShared}): for the few classes of a
     * thread-safe mod that hold state shared by every dimension and region (a global manager, a static cache).
     * Static initialisers and synthetic lambdas are left alone; lambdas run inside a locked caller anyway.
     */
    public static int wrapAllMethods(ClassNode node, String lockName) {
        return wrapAllMethods(node, lockName, "enterShared");
    }

    /** {@link #wrapAllMethods(ClassNode, String)} with another {@code ModLock} entry ("enterSharedPausing"). */
    public static int wrapAllMethods(ClassNode node, String lockName, String enterMethod) {
        return wrapAllMethods(node, lockName, m -> enterMethod);
    }

    /** Every method, each with the {@code ModLock} entry {@code enterFor} picks for it (null = leave it alone). */
    public static int wrapAllMethods(ClassNode node, String lockName, java.util.function.Function<MethodNode, String> enterFor) {
        List<MethodNode> wrappers = new ArrayList<>();
        for (MethodNode method : List.copyOf(node.methods)) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                    || method.name.startsWith("<") || method.name.startsWith(PREFIX)) {
                continue;
            }
            String enter = enterFor.apply(method);
            if (enter != null) wrappers.add(wrap(node, method, lockName, enter));
        }
        node.methods.addAll(wrappers);
        return wrappers.size();
    }

    /**
     * Wraps every method that reads one of {@code fields} ("owner/Name.field", static) in {@code ModLock.enterShared}:
     * the static guard (see StaticGuardCarrier). Lambdas count too (they are often where the collection is changed);
     * constructors and static initialisers do not.
     */
    public static int wrapMethodsReading(ClassNode node, java.util.Set<String> fields, String lockName) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return 0;
        List<MethodNode> wrappers = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0
                    || method.name.startsWith("<") || method.name.startsWith(PREFIX) || !reads(method, fields)) {
                continue;
            }
            wrappers.add(wrap(node, method, lockName, "enterShared"));
        }
        node.methods.addAll(wrappers);
        return wrappers.size();
    }

    private static boolean reads(MethodNode method, java.util.Set<String> fields) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                    && fields.contains(f.owner + "." + f.name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean calls(MethodNode method, String owner) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode wrap(ClassNode owner, MethodNode original, String modId, String enterMethod) {
        boolean isStatic = (original.access & Opcodes.ACC_STATIC) != 0;
        boolean isInterface = (owner.access & Opcodes.ACC_INTERFACE) != 0;
        String name = original.name;

        MethodNode wrapper = new MethodNode(original.access & ~Opcodes.ACC_SYNCHRONIZED, name, original.desc, original.signature,
                original.exceptions == null ? null : original.exceptions.toArray(new String[0]));
        // Annotations (e.g. @SubscribeEvent, @Override-less reflection lookups) stay on the method with the real name.
        wrapper.visibleAnnotations = original.visibleAnnotations;
        wrapper.invisibleAnnotations = original.invisibleAnnotations;
        wrapper.visibleParameterAnnotations = original.visibleParameterAnnotations;
        wrapper.invisibleParameterAnnotations = original.invisibleParameterAnnotations;
        wrapper.annotationDefault = original.annotationDefault;
        original.visibleAnnotations = null;
        original.invisibleAnnotations = null;
        original.visibleParameterAnnotations = null;
        original.invisibleParameterAnnotations = null;

        original.name = PREFIX + name;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_BRIDGE)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        Type[] args = Type.getArgumentTypes(original.desc);
        Type ret = Type.getReturnType(original.desc);
        int slot = isStatic ? 0 : 1;
        List<Object> frameLocals = new ArrayList<>();
        if (!isStatic) {
            frameLocals.add(owner.name);
        }
        for (Type arg : args) {
            frameLocals.add(frameType(arg));
            slot += arg.getSize();
        }
        int heldSlot = slot;
        frameLocals.add(Opcodes.INTEGER);

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        var insns = wrapper.instructions;

        insns.add(new LdcInsnNode(modId));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, enterMethod, "(Ljava/lang/String;)Z", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, heldSlot));
        insns.add(start);
        int load = 0;
        if (!isStatic) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            load = 1;
        }
        for (Type arg : args) {
            insns.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), load));
            load += arg.getSize();
        }
        insns.add(new MethodInsnNode(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner.name, original.name, original.desc, isInterface));
        insns.add(end);
        insns.add(new VarInsnNode(Opcodes.ILOAD, heldSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, "exit", "(Z)V", false));
        insns.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));

        insns.add(handler);
        insns.add(new FrameNode(Opcodes.F_FULL, frameLocals.size(), frameLocals.toArray(), 1, new Object[]{"java/lang/Throwable"}));
        insns.add(new VarInsnNode(Opcodes.ILOAD, heldSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, "exit", "(Z)V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));

        wrapper.maxLocals = heldSlot + 1;
        wrapper.maxStack = Math.max(slot + 2, 4);
        return wrapper;
    }

    private static Object frameType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.LONG -> Opcodes.LONG;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            case Type.ARRAY -> type.getDescriptor();
            default -> type.getInternalName();
        };
    }
}
