package com.multicore.universal.coremod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.multicore.universal.coremod.fixtures.Shapes;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

class LockWeaverTest {
    static ClassNode read(Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(Type.getInternalName(type) + ".class")) {
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    /** Runs ASM's bytecode verifier over the class; fails with its report. */
    static void verify(ClassNode node, boolean recomputeFrames) {
        ClassWriter writer = new ClassWriter(recomputeFrames ? ClassWriter.COMPUTE_FRAMES : 0);
        node.accept(writer);
        StringWriter report = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(writer.toByteArray()), LockWeaverTest.class.getClassLoader(), false, new PrintWriter(report));
        assertEquals("", report.toString(), "verifier report");
    }

    @Test
    void wovenClassVerifiesWithAndWithoutRecomputedFrames() throws IOException {
        for (Class<?> type : List.of(Shapes.class, Shapes.WithDefault.class)) {
            ClassNode node = read(type);
            // Every call counts as mod code, so no method is skipped as a read-only leaf.
            int wrapped = LockWeaver.weaveClass(node, 3, o -> true);
            assertTrue(wrapped > 0, type + ": nothing woven");
            verify(node, false);
            ClassNode again = read(type);
            LockWeaver.weaveClass(again, 3, o -> true);
            verify(again, true);
        }
    }

    @Test
    void synchronizedMethodsTakeTheModLockFirst() throws IOException {
        ClassNode node = read(Shapes.class);
        LockWeaver.weaveClass(node, 1);
        for (String name : List.of("syncWide", "syncStatic")) {
            MethodNode m = method(node, name);
            assertEquals(0, m.access & Opcodes.ACC_SYNCHRONIZED, name + " is still ACC_SYNCHRONIZED");
            int enter = -1;
            int monitor = -1;
            int i = 0;
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext(), i++) {
                if (enter < 0 && LockWeaver.isLockCall(insn, "enter")) {
                    enter = i;
                }
                if (monitor < 0 && insn.getOpcode() == Opcodes.MONITORENTER) {
                    monitor = i;
                }
            }
            assertTrue(enter >= 0 && monitor > enter, name + ": mod lock must be taken before the monitor");
        }
    }

    @Test
    void trivialMethodsAreLeftAlone() throws IOException {
        ClassNode node = read(Shapes.class);
        assertTrue(LockWeaver.isTrivial(method(node, "getField")));
        assertTrue(LockWeaver.isTrivial(method(node, "setField")));
        assertTrue(LockWeaver.isTrivial(method(node, "readsStatic")));
        assertFalse(LockWeaver.isTrivial(method(node, "writesStatic")), "PUTSTATIC is not trivial");
        assertTrue(LockWeaver.isTrivial(method(node, "loops")), "reads an array it was given, calls nothing");
        assertFalse(LockWeaver.isTrivial(method(node, "tryCatch")));
        assertFalse(LockWeaver.isTrivial(method(node, "syncStatic")));
        assertFalse(LockWeaver.isTrivial(method(node, "lambda")), "creates a lambda");
    }

    /** Objects every dimension shares, changed through vanilla or library calls, or through a single field write. */
    @Test
    void sharedScratchObjectsAndSingletonFieldsTakeTheLock() throws IOException {
        ClassNode node = read(Shapes.class);
        java.util.function.Predicate<String> noModCode = o -> false;
        assertFalse(LockWeaver.isReadOnlyLeaf(node, method(node, "formatShared"), noModCode), "changes a StringBuilder every thread shares");
        assertTrue(LockWeaver.isReadOnlyLeaf(node, method(node, "formatLocal"), noModCode), "changes only its own StringBuilder");
        assertFalse(LockWeaver.isTrivial(method(node, "bumpsSingleton")), "INSTANCE.count++ updates a shared object");
        assertTrue(LockWeaver.isTrivial(method(node, "bumpsOwn")));
        assertTrue(LockWeaver.isTrivial(method(node, "setField")));
        assertTrue(LockWeaver.weaveClass(node, 0, o -> false) > 0);
        assertTrue(LockWeaver.isWrapped(method(node, "formatShared")));
        assertTrue(LockWeaver.isWrapped(method(node, "bumpsSingleton")));
        assertFalse(LockWeaver.isWrapped(method(node, "formatLocal")));
        verify(node, true);
    }

    @Test
    void wrappingTwiceAddsAnOuterLayerOnlyWhenAsked() throws IOException {
        ClassNode node = read(Shapes.class);
        MethodNode m = method(node, "tryCatch");
        assertTrue(LockWeaver.wrap(node, m, 0));
        assertTrue(LockWeaver.isWrapped(m));
        assertFalse(LockWeaver.wrap(node, m, 0), "wrap() must not wrap twice");
        assertTrue(LockWeaver.wrapAgain(node, m, 0));
        long enters = countCalls(m, "enter");
        long exitsPerReturn = countCalls(m, "exit");
        assertEquals(2, enters);
        assertTrue(exitsPerReturn >= 4);
        verify(node, false);
    }

    @Test
    void methodsNearTheSizeLimitAreLeftAlone() {
        MethodNode huge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "huge", "()V", null, null);
        for (int i = 0; i < 13_000; i++) {
            huge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "a/B", "c", "()V", false));
        }
        huge.instructions.add(new InsnNode(Opcodes.RETURN));
        assertFalse(LockWeaver.canWrap(huge), "65 KB of calls must not be wrapped");
        MethodNode small = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "small", "()V", null, null);
        small.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "a/B", "c", "()V", false));
        small.instructions.add(new InsnNode(Opcodes.RETURN));
        assertTrue(LockWeaver.canWrap(small));
    }

    @Test
    void wrapProbeUsesACopyAndRejectsMissingTypes() throws IOException {
        ClassNode node = read(Shapes.class);
        ModCatalog catalog = ModCatalog.forTest(Map.of(Type.getInternalName(Shapes.class), bytesOf(Shapes.class)), Map.of(), List.of());
        MethodNode m = method(node, "tryCatch");
        int before = m.instructions.size();
        assertTrue(catalog.wrapIsSafe(node, m));
        assertEquals(before, m.instructions.size(), "the probe changed the real method");
        ClassNode merge = new ClassNode();
        new ClassReader(classMergingMissingTypes()).accept(merge, 0);
        assertFalse(catalog.wrapIsSafe(merge, method(merge, "pick")));
    }

    @Test
    void scopedFlagsAreFoundAndOtherStaticsAreNot() throws IOException {
        ClassNode node = read(com.multicore.universal.coremod.fixtures.Flags.class);
        java.util.Set<String> hooks = new java.util.HashSet<>();
        node.methods.forEach(m -> hooks.add(m.name + m.desc));
        java.util.Set<String> scoped = ScopedStatics.find(node, java.util.Set.of(), hooks);
        assertEquals(java.util.Set.of("building", "captured"), scoped);
        int[] next = {0};
        int rewritten = ScopedStatics.rewrite(node, scoped, () -> next[0]++);
        assertTrue(rewritten >= 6, "rewritten accesses: " + rewritten);
        assertEquals(2, next[0]);
        verify(node, true);
    }

    @Test
    void hooksOnlyNeedTheLockWhenTheyTouchSharedStateThemselves() throws IOException {
        ClassNode node = read(Shapes.class);
        String owner = node.name;
        java.util.Set<String> none = java.util.Set.of();
        assertTrue(StaticStateAnalysis.needsOwnLock(owner, method(node, "pushesStatic"), none), "mutates a static deque");
        assertTrue(StaticStateAnalysis.needsOwnLock(owner, method(node, "writesStatic"), none), "writes a static field");
        assertTrue(StaticStateAnalysis.needsOwnLock(owner, method(node, "writesSingleton"), none), "writes into a static singleton");
        assertFalse(StaticStateAnalysis.needsOwnLock(owner, method(node, "setField"), none), "writes this object's own field");
        assertFalse(StaticStateAnalysis.needsOwnLock(owner, method(node, "loops"), none), "reads an array it was given");
        assertFalse(StaticStateAnalysis.needsOwnLock(owner, method(node, "tryCatch"), none), "only calls methods");
        assertTrue(StaticStateAnalysis.needsOwnLock(owner, method(node, "formatShared"), none), "changes a static scratch object");
        assertFalse(StaticStateAnalysis.needsOwnLock(owner, method(node, "formatLocal"), none), "changes its own scratch object");
        assertFalse(StaticStateAnalysis.needsOwnLock(owner, method(node, "writesStatic"), java.util.Set.of(owner + ".captured")),
                "a per-thread field is not shared");
        // Hand-over detection follows the written object to where it came from.
        assertTrue(StaticStateAnalysis.writesDirectly(method(node, "pushesStatic")));
        assertTrue(StaticStateAnalysis.writesDirectly(method(node, "writesSingleton")), "field write into a static singleton");
        assertFalse(StaticStateAnalysis.writesDirectly(method(node, "setField")), "own field, not static state");
    }

    @Test
    void monitorOnlyHooksLockJustTheirSynchronizedBlock() throws IOException {
        ClassNode node = read(Shapes.class);
        MethodNode m = method(node, "lazy");
        assertTrue(StaticStateAnalysis.needsOwnLock(node.name, m, java.util.Set.of()), "has a monitor");
        assertFalse(StaticStateAnalysis.needsOwnLock(node.name, m, java.util.Set.of(), true), "nothing else is shared");
        assertEquals(1, LockWeaver.wrapMonitors(node, m, 2));
        assertEquals(1, countCalls(m, "enter"));
        assertEquals(2, countCalls(m, "exit"), "one exit per monitor exit (normal and exceptional path)");
        // The enter call sits right before the monitor enter, the exits right after the monitor exits.
        for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.MONITORENTER) {
                var prev = insn.getPrevious();
                assertTrue(LockWeaver.isLockCall(prev, "enter"));
            } else if (insn.getOpcode() == Opcodes.MONITOREXIT) {
                assertTrue(LockWeaver.isLockCall(insn.getNext(), "exit"));
            }
        }
        verify(node, true);
    }

    @Test
    void counterUpdatesAreNotHandOvers() throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(classWith(mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "x/Stats", "COUNT", "Ljava/util/concurrent/atomic/AtomicLong;");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicLong", "incrementAndGet", "()J", false);
            mv.visitInsn(Opcodes.POP2);
        })).accept(node, 0);
        assertFalse(StaticStateAnalysis.writesDirectly(method(node, "m")));
        ClassNode capture = new ClassNode();
        new ClassReader(classWith(mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "x/Stats", "CURRENT", "Ljava/util/concurrent/atomic/AtomicReference;");
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicReference", "set", "(Ljava/lang/Object;)V", false);
        })).accept(capture, 0);
        assertTrue(StaticStateAnalysis.writesDirectly(method(capture, "m")));
    }

    private static byte[] classWith(java.util.function.Consumer<org.objectweb.asm.MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/Body", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A cancellable injection in the middle of a woven method adds a return that must get its own exit. */
    @Test
    void earlyReturnsAddedAfterWeavingGetAnExit() throws IOException {
        ClassNode node = read(Shapes.class);
        MethodNode m = method(node, "loops");
        assertTrue(LockWeaver.wrapAgain(node, m, 0));
        assertEquals(0, LockWeaver.repairExits(m), "freshly woven returns are all covered");
        // Simulate Mixin: before the first ILOAD after the enter call, insert "if (flag) return 0;" ...
        org.objectweb.asm.tree.AbstractInsnNode anchor = null;
        boolean afterEnter = false;
        for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (LockWeaver.isLockCall(insn, "enter")) {
                afterEnter = true;
            } else if (afterEnter && insn.getOpcode() == Opcodes.ICONST_0) {
                anchor = insn;
                break;
            }
        }
        assertTrue(anchor != null);
        org.objectweb.asm.tree.LabelNode skip = new org.objectweb.asm.tree.LabelNode();
        org.objectweb.asm.tree.InsnList injected = new org.objectweb.asm.tree.InsnList();
        injected.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, Type.getInternalName(Shapes.class), "captured", "Ljava/lang/Object;"));
        injected.add(new JumpInsnNode(Opcodes.IFNULL, skip));
        injected.add(new InsnNode(Opcodes.ICONST_0));
        injected.add(new InsnNode(Opcodes.IRETURN));
        injected.add(skip);
        m.instructions.insertBefore(anchor, injected);
        // ... and at the head, before the enter call, a return that must stay without an exit.
        org.objectweb.asm.tree.InsnList head = new org.objectweb.asm.tree.InsnList();
        org.objectweb.asm.tree.LabelNode go = new org.objectweb.asm.tree.LabelNode();
        head.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, Type.getInternalName(Shapes.class), "captured", "Ljava/lang/Object;"));
        head.add(new JumpInsnNode(Opcodes.IFNONNULL, go));
        head.add(new InsnNode(Opcodes.ICONST_1));
        head.add(new InsnNode(Opcodes.IRETURN));
        head.add(go);
        m.instructions.insert(head);
        assertEquals(1, LockWeaver.repairExits(m), "exactly the mid-method return needs an exit");
        assertEquals(0, LockWeaver.repairExits(m), "repair is idempotent");
        verify(node, true);
    }

    /** HEAD and TAIL hooks into one method call helpers that set and clear a flag of the mod's own class. */
    @Test
    void modFlagsSetAndClearedAroundAMethodAreFound() throws IOException {
        String helper = Type.getInternalName(com.multicore.universal.coremod.fixtures.CaptureHelper.class);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/mixin/MobMixin", null, "java/lang/Object", null);
        org.objectweb.asm.AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        org.objectweb.asm.AnnotationVisitor values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType("net/minecraft/world/entity/Mob"));
        values.visitEnd();
        mixin.visitEnd();
        for (String[] hook : new String[][]{{"onAiStepPre", "pre", "(I)V"}, {"onAiStepPost", "post", "()V"}}) {
            org.objectweb.asm.MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, hook[0], "()V", null, null);
            org.objectweb.asm.AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
            org.objectweb.asm.AnnotationVisitor methods = inject.visitArray("method");
            methods.visit(null, "aiStep()V");
            methods.visitEnd();
            inject.visitEnd();
            mv.visitCode();
            if (hook[2].equals("(I)V")) {
                mv.visitInsn(Opcodes.ICONST_5);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, helper, hook[1], hook[2], false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        byte[] mixinBytes = cw.toByteArray();
        ModCatalog catalog = ModCatalog.forTest(Map.of("probe/mixin/MobMixin", mixinBytes, helper,
                bytesOf(com.multicore.universal.coremod.fixtures.CaptureHelper.class)), Map.of(helper, 0), List.of("fixture"));
        java.util.Set<String> fields = new ModScopedStatics(catalog).find(List.of("probe/mixin/MobMixin"), List.of(helper));
        assertEquals(java.util.Set.of(helper + ".griefing", helper + ".griefingTick"), fields,
                "usable starts true and dirty is never cleared by a hook; both must stay shared");
    }

    /** NeoForge's enum extender rewrites $values() and the static initializer by their exact shape afterwards. */
    @Test
    void enumMachineryIsNeverWoven() throws IOException {
        ClassNode node = read(com.multicore.universal.coremod.fixtures.Mode.class);
        assertEquals(1, LockWeaver.weaveClass(node, 0, o -> true), "only count() is woven");
        for (String name : List.of("$values", "values", "valueOf", "<clinit>")) {
            assertEquals(0, countCalls(method(node, name), "enter") + countCalls(method(node, name), "enterStaticInit"), name);
        }
        verify(node, true);
        ClassNode extensible = read(com.multicore.universal.coremod.fixtures.Mode.class);
        extensible.interfaces.add("net/neoforged/fml/common/asm/enumextension/IExtensibleEnum");
        assertEquals(0, LockWeaver.weaveClass(extensible, 0, o -> true), "extensible enums are left alone");
    }

    /** Plugins that extend a base plugin (Sable's, Veil's) run inside class loading just like the base. */
    @Test
    void indirectLoaderCodeIsNeverATarget() {
        Map<String, byte[]> classes = Map.of(
                "probe/plugin/BasePlugin", classBytes("probe/plugin/BasePlugin", "java/lang/Object", "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin", null),
                "probe/plugin/SubPlugin", classBytes("probe/plugin/SubPlugin", "probe/plugin/BasePlugin", null, "probe/plugin/Helper"),
                "probe/plugin/Helper", classBytes("probe/plugin/Helper", "java/lang/Object", null, null),
                "probe/other/Normal", classBytes("probe/other/Normal", "java/lang/Object", null, null));
        Map<String, Integer> targets = new java.util.HashMap<>();
        classes.keySet().forEach(n -> targets.put(n, 0));
        ModCatalog catalog = ModCatalog.forTest(classes, targets, List.of("fixture"));
        assertTrue(catalog.isLoaderCode("probe/plugin/SubPlugin"), "extends a class that implements IMixinConfigPlugin");
        assertFalse(catalog.isLoaderCode("probe/other/Normal"));
        assertEquals(3, catalog.excludeLoaderCode(Map.of("fixture", List.copyOf(classes.keySet()))));
        assertEquals(-1, catalog.modIndexOfTarget("probe/plugin/SubPlugin"));
        assertEquals(-1, catalog.modIndexOfTarget("probe/plugin/Helper"), "classes the plugin uses run inside class loading too");
        assertEquals(0, catalog.modIndexOfTarget("probe/other/Normal"));
    }

    /** A class with one optional interface and one optional field type. */
    private static byte[] classBytes(String name, String superName, String iface, String fieldType) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, superName, iface == null ? null : new String[]{iface});
        if (fieldType != null) {
            cw.visitField(Opcodes.ACC_PRIVATE, "helper", "L" + fieldType + ";", null, null).visitEnd();
            cw.newClass(fieldType); // as code using the type would reference it
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void mixinPluginsAreNeverWoven() throws IOException {
        ClassNode node = read(Shapes.class);
        node.interfaces.add("org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin");
        assertEquals(0, LockWeaver.weaveClass(node, 0));
    }

    @Test
    void staticStateWritersAreFound() throws IOException {
        ClassNode node = read(Shapes.class);
        assertTrue(StaticStateAnalysis.writesDirectly(method(node, "writesStatic")));
        assertTrue(StaticStateAnalysis.writesDirectly(method(node, "pushesStatic")));
        assertFalse(StaticStateAnalysis.writesDirectly(method(node, "readsStatic")));
        assertFalse(StaticStateAnalysis.writesDirectly(method(node, "getField")));

        // One level deep through the catalog: a static call into a mod method that writes, and a singleton's field.
        byte[] bytes = bytesOf(Shapes.class);
        ModCatalog catalog = ModCatalog.forTest(Map.of(Type.getInternalName(Shapes.class), bytes),
                Map.of(Type.getInternalName(Shapes.class), 0), List.of("fixture"));
        assertTrue(StaticStateAnalysis.writesSharedStatic(method(node, "callsWriter"), catalog));
        assertTrue(StaticStateAnalysis.writesSharedStatic(method(node, "writesSingleton"), catalog));
        assertFalse(StaticStateAnalysis.writesSharedStatic(method(node, "loops"), catalog));
    }

    @Test
    void classesWithOnlyKnownReferencesAreSafe() throws IOException {
        String shapes = Type.getInternalName(Shapes.class);
        String inner = Type.getInternalName(Shapes.WithDefault.class);
        ModCatalog catalog = ModCatalog.forTest(Map.of(shapes, bytesOf(Shapes.class), inner, bytesOf(Shapes.WithDefault.class)),
                Map.of(), List.of());
        assertTrue(catalog.isSafeToTransform(shapes));
        assertTrue(catalog.isSafeToTransform(inner));
        assertEquals(null, catalog.firstUnexpected);
    }

    @Test
    void probeRejectsFramesThatNeedMissingClasses() throws IOException {
        ModCatalog catalog = ModCatalog.forTest(Map.of(Type.getInternalName(Shapes.class), bytesOf(Shapes.class)), Map.of(), List.of());
        assertTrue(ModCatalog.probeForTest(catalog, bytesOf(Shapes.class)), "a normal class must pass");
        // Object x = flag ? new missing.A() : new missing.B();  -> needs the common super class of two missing classes
        assertFalse(ModCatalog.probeForTest(catalog, classMergingMissingTypes()), "merging missing types must fail");
    }

    private static byte[] classMergingMissingTypes() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "probe/Merge";
        node.superName = "java/lang/Object";
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(Z)Ljava/lang/Object;", null, null);
        LabelNode other = new LabelNode();
        LabelNode join = new LabelNode();
        InsnList code = m.instructions;
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new JumpInsnNode(Opcodes.IFEQ, other));
        code.add(new TypeInsnNode(Opcodes.NEW, "missing/A"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "missing/A", "<init>", "()V", false));
        code.add(new JumpInsnNode(Opcodes.GOTO, join));
        code.add(other);
        code.add(new TypeInsnNode(Opcodes.NEW, "missing/B"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "missing/B", "<init>", "()V", false));
        code.add(join);
        code.add(new InsnNode(Opcodes.ARETURN));
        m.maxStack = 2;
        m.maxLocals = 1;
        node.methods.add(m);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(Type.getInternalName(type) + ".class")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    private static long countCalls(MethodNode m, String name) {
        long n = 0;
        for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (LockWeaver.isLockCall(insn, name)) {
                n++;
            }
        }
        return n;
    }
}
