package com.multicore.universal.coremod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Finds static fields in a mod's own classes that the mod's mixin hooks use as scoped flags: an {@code @Inject} hook
 * (or a static helper of the mod it calls) sets the field when a vanilla method starts, and another hook into the same
 * vanilla method clears it again (Open Parties and Claims marks "this mob is griefing for items" around
 * {@code Mob.aiStep} this way). Fields written together with such a flag by the same helper ("captured in tick N")
 * belong to it. They are then kept per dimension thread everywhere (see ScopedStatics and MultiCore's StaticOverlay),
 * so the vanilla method does not have to hold the mod lock from start to end.
 * <p>
 * Fields with a real initial value, fields the mod sets anywhere else, and fields that are never cleared by a hook are
 * left shared.
 */
final class ModScopedStatics {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    private final ModCatalog catalog;
    private final Map<String, ClassNode> nodes = new HashMap<>();

    ModScopedStatics(ModCatalog catalog) {
        this.catalog = catalog;
    }

    /** One write of a static field: which field, and whether the value is the type's default (a "clear"). */
    private record Write(String field, boolean clear, String method) {
    }

    /**
     * @param mixinClasses the mod's mixin classes
     * @param ownClasses   the mod's other classes
     * @return per-thread fields as "owner/Internal.name"
     */
    Set<String> find(List<String> mixinClasses, List<String> ownClasses) {
        Set<String> own = new HashSet<>(ownClasses);
        // Group key "target.method" -> per field: does some hook set it, does some hook clear it.
        Map<String, Map<String, boolean[]>> groups = new HashMap<>();
        Map<String, Set<String>> setterMethods = new HashMap<>();   // field -> methods ("owner.name+desc") that set it
        Map<String, Set<String>> companionsOf = new HashMap<>();    // setter method -> fields it sets
        for (String name : mixinClasses) {
            ClassNode mixin = this.node(name);
            if (mixin == null) {
                continue;
            }
            List<String> targets = mixinTargets(mixin);
            if (targets.isEmpty()) {
                continue;
            }
            for (MethodNode m : mixin.methods) {
                AnnotationNode inject = LockWeaver.findAnnotation(m, INJECT);
                if (inject == null) {
                    continue;
                }
                List<Write> writes = this.writesOf(m, mixin.name, own);
                if (writes.isEmpty()) {
                    continue;
                }
                for (String target : targets) {
                    for (String method : injectedMethods(inject)) {
                        Map<String, boolean[]> group = groups.computeIfAbsent(target + "." + method, k -> new HashMap<>());
                        for (Write w : writes) {
                            group.computeIfAbsent(w.field(), k -> new boolean[2])[w.clear() ? 1 : 0] = true;
                        }
                    }
                }
                for (Write w : writes) {
                    if (!w.clear()) {
                        setterMethods.computeIfAbsent(w.field(), k -> new HashSet<>()).add(w.method());
                        companionsOf.computeIfAbsent(w.method(), k -> new HashSet<>()).add(w.field());
                    }
                }
            }
        }
        // A flag: in every group where a hook sets it, a hook also clears it.
        Set<String> flags = new HashSet<>();
        Set<String> setAnywhere = new HashSet<>();
        Set<String> unpaired = new HashSet<>();
        for (Map<String, boolean[]> group : groups.values()) {
            for (Map.Entry<String, boolean[]> e : group.entrySet()) {
                if (e.getValue()[0]) {
                    setAnywhere.add(e.getKey());
                    if (!e.getValue()[1]) {
                        unpaired.add(e.getKey());
                    }
                }
            }
        }
        for (String field : setAnywhere) {
            if (!unpaired.contains(field)) {
                flags.add(field);
            }
        }
        if (flags.isEmpty()) {
            return Set.of();
        }
        // Fields set together with a flag by the same method travel with it.
        Set<String> candidates = new HashSet<>(flags);
        for (String flag : flags) {
            for (String setter : setterMethods.getOrDefault(flag, Set.of())) {
                candidates.addAll(companionsOf.getOrDefault(setter, Set.of()));
            }
        }
        // Drop fields that are final, start with a real value, or are set to a real value anywhere but those setters.
        Set<String> allowedSetters = new HashSet<>();
        candidates.forEach(f -> allowedSetters.addAll(setterMethods.getOrDefault(f, Set.of())));
        Set<String> result = new HashSet<>();
        for (String field : candidates) {
            if (this.isPlainStatic(field)) {
                result.add(field);
            }
        }
        if (result.isEmpty()) {
            return Set.of();
        }
        Map<String, Set<String>> accessors = new HashMap<>();
        for (String name : ownClasses) {
            ClassNode node = this.node(name);
            if (node == null) {
                continue;
            }
            for (MethodNode m : node.methods) {
                String key = node.name + "." + m.name + m.desc;
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof FieldInsnNode f) || (f.getOpcode() != Opcodes.PUTSTATIC && f.getOpcode() != Opcodes.GETSTATIC)) {
                        continue;
                    }
                    String field = f.owner + "." + f.name;
                    if (!result.contains(field)) {
                        continue;
                    }
                    accessors.computeIfAbsent(field, k -> new HashSet<>()).add(node.name);
                    if (f.getOpcode() == Opcodes.PUTSTATIC && !isDefault(insn)
                            && (m.name.equals("<clinit>") || !allowedSetters.contains(key))) {
                        result.remove(field);
                    }
                }
            }
        }
        // Every class of the mod that touches the field must be rewritten, or reads and writes would disagree.
        result.removeIf(field -> !accessors.getOrDefault(field, Set.of()).stream().allMatch(c -> this.catalog.modIndexOfTarget(c) >= 0));
        return result;
    }

    /** Direct writes of the hook plus writes of the mod's static methods it calls, to static fields of the mod. */
    private List<Write> writesOf(MethodNode hook, String mixinName, Set<String> own) {
        List<Write> out = new ArrayList<>();
        String hookKey = mixinName + "." + hook.name + hook.desc;
        for (AbstractInsnNode insn = hook.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && own.contains(f.owner)) {
                out.add(new Write(f.owner + "." + f.name, isDefault(insn), hookKey));
            } else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && own.contains(call.owner)) {
                MethodNode callee = this.method(call.owner, call.name, call.desc);
                if (callee == null) {
                    continue;
                }
                String calleeKey = call.owner + "." + call.name + call.desc;
                for (AbstractInsnNode c = callee.instructions.getFirst(); c != null; c = c.getNext()) {
                    if (c instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && own.contains(f.owner)) {
                        out.add(new Write(f.owner + "." + f.name, isDefault(c), calleeKey));
                    }
                }
            }
        }
        return out;
    }

    private boolean isPlainStatic(String field) {
        int dot = field.lastIndexOf('.');
        ClassNode owner = this.node(field.substring(0, dot));
        if (owner == null) {
            return false;
        }
        for (FieldNode f : owner.fields) {
            if (f.name.equals(field.substring(dot + 1))) {
                return (f.access & Opcodes.ACC_STATIC) != 0 && (f.access & Opcodes.ACC_FINAL) == 0 && f.value == null;
            }
        }
        return false;
    }

    private static boolean isDefault(AbstractInsnNode put) {
        AbstractInsnNode p = put.getPrevious();
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

    private ClassNode node(String name) {
        return this.nodes.computeIfAbsent(name, n -> {
            byte[] bytes = this.catalog.readClass(n);
            if (bytes == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            return node;
        });
    }

    private MethodNode method(String owner, String name, String desc) {
        ClassNode node = this.node(owner);
        if (node == null) {
            return null;
        }
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    /** Internal names of the classes a mixin applies to (from {@code value} and {@code targets}). */
    @SuppressWarnings("unchecked")
    static List<String> mixinTargets(ClassNode mixin) {
        List<String> out = new ArrayList<>();
        List<AnnotationNode> all = new ArrayList<>();
        if (mixin.invisibleAnnotations != null) {
            all.addAll(mixin.invisibleAnnotations);
        }
        if (mixin.visibleAnnotations != null) {
            all.addAll(mixin.visibleAnnotations);
        }
        for (AnnotationNode a : all) {
            if (!a.desc.equals(MIXIN)) {
                continue;
            }
            Object value = LockWeaver.annotationValue(a, "value");
            if (value instanceof List<?> types) {
                for (Object t : types) {
                    if (t instanceof Type type) {
                        out.add(type.getInternalName());
                    }
                }
            }
            Object targets = LockWeaver.annotationValue(a, "targets");
            if (targets instanceof List<?> names) {
                for (Object n : names) {
                    if (n instanceof String s) {
                        out.add(s.replace('.', '/'));
                    }
                }
            }
        }
        return out;
    }

    /** Method names an {@code @Inject} targets ("aiStep", "aiStep()V" and "Lowner;aiStep()V" all give "aiStep"). */
    static List<String> injectedMethods(AnnotationNode inject) {
        List<String> out = new ArrayList<>();
        Object value = LockWeaver.annotationValue(inject, "method");
        if (value instanceof List<?> names) {
            for (Object n : names) {
                if (n instanceof String s) {
                    String name = s;
                    int semi = name.indexOf(';');
                    if (semi >= 0 && name.startsWith("L")) {
                        name = name.substring(semi + 1);
                    }
                    int paren = name.indexOf('(');
                    if (paren >= 0) {
                        name = name.substring(0, paren);
                    }
                    out.add(name);
                }
            }
        }
        return out;
    }
}
