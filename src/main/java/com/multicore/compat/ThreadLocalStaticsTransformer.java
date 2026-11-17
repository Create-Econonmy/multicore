package com.multicore.compat;

import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Rewrites reads and writes of chosen static fields into per-thread storage ({@link ThreadStatics}).
 * <p>
 * Mods often pass information from a "before" hook to an "after" hook through a static field (the entity that is
 * dying, the projectile that is hitting). That is correct with one server thread and wrong with several: another
 * dimension thread overwrites it in between. Making the field per-thread restores the original meaning.
 * The owner's static initializer is left alone so the real field keeps the initial value each thread starts from.
 */
public final class ThreadLocalStaticsTransformer {
    private static final String STATICS = "com/multicore/compat/ThreadStatics";

    private ThreadLocalStaticsTransformer() {
    }

    public static int rewrite(ClassNode node, String owner, Set<String> fields) {
        int count = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && node.name.equals(owner)) {
                continue;
            }
            int inMethod = 0;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof FieldInsnNode f && f.owner.equals(owner) && fields.contains(f.name)
                        && (f.getOpcode() == Opcodes.GETSTATIC || f.getOpcode() == Opcodes.PUTSTATIC)) {
                    method.instructions.insert(insn, replacement(f));
                    method.instructions.remove(insn);
                    inMethod++;
                }
                insn = next;
            }
            if (inMethod > 0) {
                method.maxStack += 2;
                count += inMethod;
            }
        }
        return count;
    }

    private static InsnList replacement(FieldInsnNode f) {
        Type type = Type.getType(f.desc);
        String kind = kind(type);
        String key = f.owner + "." + f.name;
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(key));
        if (f.getOpcode() == Opcodes.GETSTATIC) {
            String returned = descriptorFor(kind, type);
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STATICS, "get" + kind, "(Ljava/lang/String;)" + returned, false));
            if (kind.equals("Object") && !f.desc.equals("Ljava/lang/Object;")) {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
            }
        } else {
            String taken = descriptorFor(kind, type);
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STATICS, "set" + kind, "(" + taken + "Ljava/lang/String;)V", false));
        }
        return list;
    }

    /** byte, short and char live on the stack as int, so they share the int accessors. */
    private static String descriptorFor(String kind, Type type) {
        return switch (kind) {
            case "Object" -> "Ljava/lang/Object;";
            case "Int" -> "I";
            default -> type.getDescriptor();
        };
    }

    private static String kind(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "Boolean";
            case Type.INT, Type.SHORT, Type.BYTE, Type.CHAR -> "Int";
            case Type.LONG -> "Long";
            case Type.FLOAT -> "Float";
            case Type.DOUBLE -> "Double";
            default -> "Object";
        };
    }
}
