package com.multicore.universal.coremod;

import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Where the object a method writes to comes from: this, a parameter or a freshly created object (local to the call),
 * a static field (shared by every dimension), or something else (a value returned by another method, a field of
 * another object: unknown, treated as possibly shared). Uses ASM's source analysis, per method.
 */
final class ValueOrigins {
    enum Origin { LOCAL, STATIC, UNKNOWN }

    private final MethodNode method;
    @Nullable
    private final Frame<SourceValue>[] frames;
    private final int paramSlots;

    ValueOrigins(String owner, MethodNode method) {
        this.method = method;
        Frame<SourceValue>[] f;
        try {
            f = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
        } catch (AnalyzerException | RuntimeException e) {
            f = null;
        }
        this.frames = f;
        int slots = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type t : Type.getArgumentTypes(method.desc)) {
            slots += t.getSize();
        }
        this.paramSlots = slots;
    }

    boolean analysed() {
        return this.frames != null;
    }

    /** Origin of the object a write or call at {@code insn} acts on (field owner object, array, or call receiver). */
    Origin targetOf(AbstractInsnNode insn) {
        if (this.frames == null) {
            return Origin.UNKNOWN;
        }
        Frame<SourceValue> frame = this.frames[this.method.instructions.indexOf(insn)];
        if (frame == null) {
            return Origin.LOCAL; // unreachable code
        }
        // How many stack entries lie above the target object. ASM's frames hold one entry per value, long and double
        // included.
        int depth = switch (insn.getOpcode()) {
            case Opcodes.PUTFIELD -> 1;
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE,
                    Opcodes.CASTORE, Opcodes.SASTORE -> 2;
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL ->
                    Type.getArgumentTypes(((MethodInsnNode) insn).desc).length;
            case Opcodes.MONITORENTER -> 0;
            default -> -1;
        };
        if (depth < 0) {
            return Origin.UNKNOWN;
        }
        int index = frame.getStackSize() - 1 - depth;
        if (index < 0) {
            return Origin.UNKNOWN;
        }
        return this.origin(frame.getStack(index), 0, new HashSet<>());
    }

    /**
     * The static field ("owner.name") the object a write or call at {@code insn} acts on was read from, or null if it
     * does not come from exactly that kind of source.
     */
    @Nullable
    String staticFieldOf(AbstractInsnNode insn) {
        if (this.frames == null || this.targetOf(insn) != Origin.STATIC) {
            return null;
        }
        Frame<SourceValue> frame = this.frames[this.method.instructions.indexOf(insn)];
        int depth = switch (insn.getOpcode()) {
            case Opcodes.PUTFIELD -> 1;
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL ->
                    Type.getArgumentTypes(((MethodInsnNode) insn).desc).length;
            default -> 2;
        };
        return this.firstStatic(frame.getStack(frame.getStackSize() - 1 - depth), 0, new HashSet<>());
    }

    @Nullable
    private String firstStatic(SourceValue value, int depth, Set<AbstractInsnNode> seen) {
        for (AbstractInsnNode src : value.insns) {
            if (!seen.add(src) || depth > 6) {
                continue;
            }
            if (src instanceof FieldInsnNode f && src.getOpcode() == Opcodes.GETSTATIC) {
                return f.owner + "." + f.name;
            }
            Frame<SourceValue> f = this.frames[this.method.instructions.indexOf(src)];
            if (f == null) {
                continue;
            }
            String found = null;
            if (src.getOpcode() == Opcodes.ALOAD) {
                for (AbstractInsnNode store : f.getLocal(((VarInsnNode) src).var).insns) {
                    Frame<SourceValue> sf = this.frames[this.method.instructions.indexOf(store)];
                    if (sf != null && sf.getStackSize() > 0) {
                        found = this.firstStatic(sf.getStack(sf.getStackSize() - 1), depth + 1, seen);
                    }
                    if (found != null) {
                        return found;
                    }
                }
            } else if ((src.getOpcode() == Opcodes.CHECKCAST || src.getOpcode() == Opcodes.DUP || src.getOpcode() == Opcodes.GETFIELD)
                    && f.getStackSize() > 0) {
                found = this.firstStatic(f.getStack(f.getStackSize() - 1), depth + 1, seen);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Origin origin(SourceValue value, int depth, Set<AbstractInsnNode> seen) {
        if (value.insns.isEmpty()) {
            return Origin.LOCAL; // a parameter or this, as initially in the frame
        }
        Origin result = Origin.LOCAL;
        for (AbstractInsnNode src : value.insns) {
            Origin o = this.originOf(src, depth, seen);
            if (o == Origin.STATIC) {
                return Origin.STATIC;
            }
            if (o == Origin.UNKNOWN) {
                result = Origin.UNKNOWN;
            }
        }
        return result;
    }

    private Origin originOf(AbstractInsnNode src, int depth, Set<AbstractInsnNode> seen) {
        if (!seen.add(src) || depth > 6) {
            return Origin.UNKNOWN;
        }
        switch (src.getOpcode()) {
            case Opcodes.GETSTATIC:
                return Origin.STATIC;
            case Opcodes.NEW, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY, Opcodes.ACONST_NULL, Opcodes.LDC:
                return Origin.LOCAL;
            case Opcodes.ALOAD: {
                int slot = ((VarInsnNode) src).var;
                Frame<SourceValue> f = this.frames[this.method.instructions.indexOf(src)];
                if (f == null) {
                    return Origin.UNKNOWN;
                }
                SourceValue stored = f.getLocal(slot);
                if (stored.insns.isEmpty()) {
                    return slot < this.paramSlots ? Origin.LOCAL : Origin.UNKNOWN;
                }
                // The value comes from the stores into that local: follow what was stored.
                Origin result = Origin.LOCAL;
                for (AbstractInsnNode store : stored.insns) {
                    Origin o = this.operandOrigin(store, 0, depth + 1, seen);
                    if (o == Origin.STATIC) {
                        return Origin.STATIC;
                    }
                    if (o == Origin.UNKNOWN) {
                        result = Origin.UNKNOWN;
                    }
                }
                return result;
            }
            case Opcodes.CHECKCAST, Opcodes.DUP:
                return this.operandOrigin(src, 0, depth + 1, seen);
            case Opcodes.GETFIELD: {
                // A field of a local object (this, a parameter) is that object's state; anything else is unknown.
                Origin owner = this.operandOrigin(src, 0, depth + 1, seen);
                return owner == Origin.LOCAL ? Origin.LOCAL : owner;
            }
            default:
                return Origin.UNKNOWN;
        }
    }

    /** Origin of the value {@code fromTop} entries below the top of the stack just before {@code insn}. */
    private Origin operandOrigin(AbstractInsnNode insn, int fromTop, int depth, Set<AbstractInsnNode> seen) {
        Frame<SourceValue> f = this.frames[this.method.instructions.indexOf(insn)];
        if (f == null || f.getStackSize() - 1 - fromTop < 0) {
            return Origin.UNKNOWN;
        }
        return this.origin(f.getStack(f.getStackSize() - 1 - fromTop), depth, seen);
    }
}
