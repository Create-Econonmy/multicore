package com.multicore.universal.coremod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Finds static fields that mixins added to a class and use as scoped flags, and gives each dimension thread its own
 * copy of them (MultiCore's StaticOverlay).
 * <p>
 * A field counts as a scoped flag when every piece of code that sets it to a non-default value also clears it again
 * for the same operation: either the same method resets it (a try/finally around some work), or the method that sets
 * it is a hook injected into a vanilla method that also calls a hook which clears it (a HEAD hook storing the entity
 * and a TAIL hook clearing it). Fields set in one operation and consumed in a later one (a "dirty" flag read by the
 * main thread) do not match and are left shared; the hooks that write them make their whole vanilla method hold the
 * mod lock instead.
 */
public final class ScopedStatics {
    public static final String OVERLAY = "com/multicore/universal/core/StaticOverlay";

    private ScopedStatics() {
    }

    /**
     * @param node      the class after Mixin applied every mixin
     * @param original  names of the fields the class had before (mixin-added fields are the others)
     * @param hookNames name+desc of the methods merged from mixins
     * @return the scoped flag fields (by name)
     */
    public static Set<String> find(ClassNode node, Set<String> original, Set<String> hookNames) {
        Map<String, FieldNode> candidates = new HashMap<>();
        for (FieldNode f : node.fields) {
            if ((f.access & Opcodes.ACC_STATIC) != 0 && (f.access & Opcodes.ACC_FINAL) == 0 && !original.contains(f.name)
                    && !f.name.startsWith("mcu$")) {
                candidates.put(f.name, f);
            }
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }
        // Per field: methods that set it to a non-default value, methods that clear it.
        Map<String, Set<String>> setters = new HashMap<>();
        Map<String, Set<String>> clearers = new HashMap<>();
        Set<String> rejected = new HashSet<>();
        for (MethodNode m : node.methods) {
            String key = m.name + m.desc;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && f.owner.equals(node.name)
                        && candidates.containsKey(f.name)) {
                    boolean clear = isDefaultConstant(previousReal(insn));
                    if (m.name.equals("<clinit>")) {
                        if (!clear) {
                            rejected.add(f.name); // starts with a real value: shared state, not a flag
                        }
                        continue;
                    }
                    (clear ? clearers : setters).computeIfAbsent(f.name, k -> new HashSet<>()).add(key);
                }
            }
        }
        Set<String> scoped = new HashSet<>();
        for (String field : candidates.keySet()) {
            Set<String> sets = setters.getOrDefault(field, Set.of());
            Set<String> clears = clearers.getOrDefault(field, Set.of());
            if (rejected.contains(field) || sets.isEmpty() || clears.isEmpty()) {
                continue;
            }
            boolean ok = true;
            for (String setter : sets) {
                if (clears.contains(setter)) {
                    continue;
                }
                if (!hookNames.contains(setter) || !pairedInSomeMethod(node, setter, clears)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                scoped.add(field);
            }
        }
        return scoped;
    }

    /** True if some method of the class calls the setter hook and also one of the clearing hooks. */
    private static boolean pairedInSomeMethod(ClassNode node, String setter, Set<String> clearers) {
        for (MethodNode m : node.methods) {
            boolean callsSetter = false;
            boolean callsClearer = false;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    String key = call.name + call.desc;
                    callsSetter |= key.equals(setter);
                    callsClearer |= clearers.contains(key);
                }
            }
            if (callsSetter && callsClearer) {
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) {
            p = p.getPrevious();
        }
        return p;
    }

    private static boolean isDefaultConstant(AbstractInsnNode insn) {
        if (insn == null) {
            return false;
        }
        return switch (insn.getOpcode()) {
            case Opcodes.ICONST_0, Opcodes.LCONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0, Opcodes.ACONST_NULL -> true;
            default -> false;
        };
    }

    /**
     * Rewrites every access to the given fields, outside the static initializer, to go through StaticOverlay.
     * Returns the number of accesses rewritten.
     */
    public static int rewrite(ClassNode node, Set<String> fields, IntSupplier newSlot) {
        Map<String, Integer> slots = new HashMap<>();
        for (String f : fields) {
            slots.put(node.name + "." + f, newSlot.getAsInt());
        }
        return rewriteFields(node, slots);
    }

    /**
     * Rewrites every access (outside static initializers) to the given fields, keyed "owner/Internal.name", in any
     * class. Returns the number of accesses rewritten.
     */
    public static int rewriteFields(ClassNode node, Map<String, Integer> slots) {
        return rewriteFields(node, slots, m -> true);
    }

    /** As above, limited to the methods {@code which} accepts. */
    public static int rewriteFields(ClassNode node, Map<String, Integer> slots, java.util.function.Predicate<MethodNode> which) {
        if (slots.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (MethodNode m : node.methods) {
            if (m.name.equals("<clinit>") || !which.test(m)) {
                continue;
            }
            boolean changed = false;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                Integer slotOf = insn instanceof FieldInsnNode fi ? slots.get(fi.owner + "." + fi.name) : null;
                if (slotOf != null && (insn.getOpcode() == Opcodes.GETSTATIC || insn.getOpcode() == Opcodes.PUTSTATIC)) {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    int slot = slotOf;
                    if (f.getOpcode() == Opcodes.GETSTATIC) {
                        m.instructions.insert(f, readSequence(f.desc, slot));
                    } else {
                        m.instructions.insert(f, writeSequence(f, slot));
                        m.instructions.remove(f);
                    }
                    changed = true;
                    count++;
                }
                insn = next;
            }
            if (changed) {
                m.maxStack += 4;
            }
        }
        return count;
    }

    private static String suffix(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "Z";
            case Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> "I";
            case Type.LONG -> "J";
            case Type.FLOAT -> "F";
            case Type.DOUBLE -> "D";
            default -> "A";
        };
    }

    private static String stackDesc(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "Z";
            case Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> "I";
            case Type.LONG -> "J";
            case Type.FLOAT -> "F";
            case Type.DOUBLE -> "D";
            default -> "Ljava/lang/Object;";
        };
    }

    /** After GETSTATIC (real value on the stack): replace it with this thread's copy if there is one. */
    private static InsnList readSequence(String desc, int slot) {
        Type type = Type.getType(desc);
        String s = stackDesc(type);
        InsnList list = new InsnList();
        list.add(LockWeaver.pushInt(slot));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OVERLAY, "get" + suffix(type), "(" + s + "I)" + s, false));
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            list.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
        } else if (type.getSort() == Type.BYTE) {
            list.add(new InsnNode(Opcodes.I2B));
        } else if (type.getSort() == Type.SHORT) {
            list.add(new InsnNode(Opcodes.I2S));
        } else if (type.getSort() == Type.CHAR) {
            list.add(new InsnNode(Opcodes.I2C));
        }
        return list;
    }

    /** Replaces PUTSTATIC: store in this thread's copy, or in the real field if this is not a dimension thread. */
    private static InsnList writeSequence(FieldInsnNode put, int slot) {
        Type type = Type.getType(put.desc);
        boolean wide = type.getSize() == 2;
        String s = stackDesc(type);
        LabelNode stored = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList list = new InsnList();
        list.add(new InsnNode(wide ? Opcodes.DUP2 : Opcodes.DUP));
        list.add(LockWeaver.pushInt(slot));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OVERLAY, "set" + suffix(type), "(" + s + "I)Z", false));
        list.add(new JumpInsnNode(Opcodes.IFNE, stored));
        list.add(new FieldInsnNode(Opcodes.PUTSTATIC, put.owner, put.name, put.desc));
        list.add(new JumpInsnNode(Opcodes.GOTO, done));
        list.add(stored);
        list.add(new InsnNode(wide ? Opcodes.POP2 : Opcodes.POP));
        list.add(done);
        return list;
    }

    static List<String> describe(Set<String> fields) {
        return List.copyOf(fields);
    }
}
