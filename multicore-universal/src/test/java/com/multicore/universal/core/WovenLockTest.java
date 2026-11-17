package com.multicore.universal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.multicore.universal.coremod.LockWeaver;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Weaves a deliberately thread-unsafe class exactly like the coremod weaves a mod, loads it, and hammers it from
 * several dimension threads. Without the lock the shared HashMap and counters break; with it every update survives.
 */
class WovenLockTest {
    private static final String FIXTURE = "com.multicore.universal.core.fixtures.UnsafeModCode";
    private static final int THREADS = 4;
    private static final int ITERATIONS = 100_000;
    private static Class<?> woven;

    @BeforeAll
    static void weave() throws Exception {
        // Must be set before ModPolicy is initialized: one woven mod, index 0.
        System.setProperty(ModPolicy.MODS_PROPERTY, "testmod");
        System.setProperty(ModPolicy.STATUS_PROPERTY, "active");
        byte[] original;
        try (InputStream in = WovenLockTest.class.getClassLoader().getResourceAsStream(FIXTURE.replace('.', '/') + ".class")) {
            original = in.readAllBytes();
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        int wrapped = LockWeaver.weaveClass(node, 0);
        assertTrue(wrapped >= 8, "woven methods: " + wrapped);
        // Frames as written by the weaver (no recomputation): the JVM verifies them when the class is defined.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        woven = new ClassLoader(WovenLockTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(FIXTURE)) {
                    synchronized (this) {
                        Class<?> c = this.findLoadedClass(name);
                        return c != null ? c : this.defineClass(name, bytes, 0, bytes.length);
                    }
                }
                return super.loadClass(name, resolve);
            }
        }.loadClass(FIXTURE);
    }

    @BeforeEach
    void parallelPhase() throws Exception {
        ModPolicy.configure(true, List.of());
        LockState.parallel = true;
        call("reset");
    }

    @AfterEach
    void serialPhase() {
        LockState.parallel = false;
        LockState.serverThread = null;
        ModPolicy.configure(true, List.of());
    }

    private static Object call(String name, Object... args) throws Exception {
        for (Method m : woven.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                try {
                    return m.invoke(null, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause() instanceof Exception ex ? ex : new RuntimeException(e.getCause());
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static final class DimensionThread extends TickThread {
        private final Runnable body;
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile int depthAfter = -1;

        DimensionThread(String name, Runnable body) {
            super(Thread.currentThread().getThreadGroup(), name);
            this.body = body;
        }

        @Override
        public boolean pollOwnTasks() {
            return false;
        }

        @Override
        public void run() {
            try {
                this.body.run();
            } catch (Throwable t) {
                this.error.set(t);
            }
            this.depthAfter = this.lockDepth;
        }
    }

    private interface Body {
        void run() throws Exception;
    }

    private static Runnable unchecked(Body body) {
        return () -> {
            try {
                body.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static List<DimensionThread> runAll(Body body) throws InterruptedException {
        List<DimensionThread> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            threads.add(new DimensionThread("dim-" + i, unchecked(body)));
        }
        threads.forEach(Thread::start);
        for (DimensionThread t : threads) {
            t.join(TimeUnit.MINUTES.toMillis(2));
            assertFalse(t.isAlive(), t.getName() + " did not finish");
        }
        return threads;
    }

    @Test
    void wovenCodeIsMutuallyExclusive() throws Exception {
        List<DimensionThread> threads = runAll(() -> {
            for (int i = 0; i < ITERATIONS; i++) {
                call("count", i & 15);
                if ((i & 1023) == 0) {
                    call("nested", 20);
                    call("syncIncrement");
                    assertEquals(i & 15, call("catches", i & 15));
                    assertEquals(i & 15, ((IntSupplier) call("lambda", i & 15)).getAsInt());
                }
            }
        });
        for (DimensionThread t : threads) {
            assertEquals(null, t.error.get(), t.getName() + " failed");
            assertEquals(0, t.depthAfter, t.getName() + " left the lock depth unbalanced");
        }
        int extra = (ITERATIONS + 1023) / 1024;
        @SuppressWarnings("unchecked")
        Map<Integer, Integer> counts = (Map<Integer, Integer>) woven.getField("COUNTS").get(null);
        int sum = counts.values().stream().mapToInt(Integer::intValue).sum();
        // count() per iteration, plus nested(), syncIncrement(), catches() (via throwing()) and the lambda.
        int expected = THREADS * (ITERATIONS + 4 * extra);
        assertEquals(expected, sum, "lost HashMap updates");
        assertEquals(expected, woven.getField("plainCounter").getInt(null), "lost counter updates");
        assertEquals(THREADS * extra, call("syncCounter"));
        assertFalse(ModLock.heldByCurrentThread());
    }

    @Test
    void withoutTheLockTheSameCodeRaces() throws Exception {
        LockState.serialize = false;
        try {
            runAll(() -> {
                for (int i = 0; i < ITERATIONS * 4; i++) {
                    try {
                        call("count", i & 15);
                    } catch (RuntimeException e) {
                        // A corrupted HashMap may throw; the counters tell the story either way.
                    }
                }
            });
        } finally {
            LockState.serialize = true;
        }
        int counter = woven.getField("plainCounter").getInt(null);
        assertTrue(counter < THREADS * ITERATIONS * 4, "expected lost updates without the lock (control), got " + counter);
    }

    @Test
    void exceptionsReleaseTheLock() throws Exception {
        List<DimensionThread> threads = runAll(() -> {
            for (int i = 0; i < 1000; i++) {
                try {
                    call("throwing", 1);
                } catch (IllegalStateException expected) {
                    // thrown through the woven frame
                }
            }
        });
        for (DimensionThread t : threads) {
            assertEquals(null, t.error.get());
            assertEquals(0, t.depthAfter);
        }
        assertEquals(THREADS * 1000, woven.getField("plainCounter").getInt(null));
    }

    @Test
    void lockIsHeldWhileWovenCodeRunsAndOnlyThen() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DimensionThread holder = new DimensionThread("holder", unchecked(() -> call("park", entered, release)));
        holder.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        // Another dimension thread must wait for the holder.
        DimensionThread waiter = new DimensionThread("waiter", unchecked(() -> call("count", 7)));
        waiter.start();
        waiter.join(300);
        assertTrue(waiter.isAlive(), "a second dimension thread ran mod code while another held the lock");

        // A thread that is not a tick thread (world generation, network) never takes the lock.
        Thread worker = new Thread(unchecked(() -> call("count", 8)));
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive(), "a worker thread waited for the mod lock");

        // Neither does a trivial method on a dimension thread.
        DimensionThread trivial = new DimensionThread("trivial", unchecked(() -> call("trivialGetter")));
        trivial.start();
        trivial.join(5000);
        assertFalse(trivial.isAlive(), "a trivial getter waited for the mod lock");

        release.countDown();
        holder.join(5000);
        waiter.join(5000);
        assertFalse(waiter.isAlive());
        assertEquals(0, holder.depthAfter);
        assertEquals(0, waiter.depthAfter);
    }

    @Test
    void threadSafeModsAndSerialPhaseSkipTheLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DimensionThread holder = new DimensionThread("holder", unchecked(() -> call("park", entered, release)));
        holder.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        try {
            ModPolicy.configure(true, List.of("testmod"));
            DimensionThread safe = new DimensionThread("safe", unchecked(() -> call("count", 1)));
            safe.start();
            safe.join(5000);
            assertFalse(safe.isAlive(), "a thread-safe mod's code waited for the lock");
        } finally {
            release.countDown();
            holder.join(5000);
        }
        ModPolicy.configure(true, List.of());
        LockState.parallel = false;
        DimensionThread serial = new DimensionThread("serial", unchecked(() -> call("count", 2)));
        serial.start();
        serial.join(5000);
        assertEquals(0, serial.depthAfter, "the lock was taken outside the parallel phase");
    }

    @Test
    void mainThreadTakesTheLockDuringTheParallelPhase() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DimensionThread holder = new DimensionThread("holder", unchecked(() -> call("park", entered, release)));
        holder.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        Thread main = new Thread(unchecked(() -> call("count", 3)), "Server thread");
        LockState.serverThread = main;
        main.start();
        main.join(300);
        assertTrue(main.isAlive(), "the main thread ran mod code while a dimension thread held the lock");
        release.countDown();
        main.join(5000);
        holder.join(5000);
        assertFalse(main.isAlive());
    }

    /**
     * A class initialized on a dimension thread while another thread holds the lock must not wait for it inside its
     * static initializer (the holder could need the same class, and both would wait forever).
     */
    @Test
    void staticInitializersNeverWaitForTheLock() throws Exception {
        String name = "com.multicore.universal.core.fixtures.StaticInitModCode";
        byte[] original;
        try (InputStream in = WovenLockTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            original = in.readAllBytes();
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        LockWeaver.weaveClass(node, 0);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        ClassLoader loader = new ClassLoader(WovenLockTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (n.equals(name)) {
                    synchronized (this) {
                        Class<?> c = this.findLoadedClass(n);
                        return c != null ? c : this.defineClass(n, bytes, 0, bytes.length);
                    }
                }
                return super.loadClass(n, resolve);
            }
        };
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DimensionThread holder = new DimensionThread("holder", unchecked(() -> call("park", entered, release)));
        holder.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        try {
            // Initializing the class runs woven build() from its static initializer: it must finish while the lock
            // is held elsewhere.
            DimensionThread init = new DimensionThread("init", unchecked(() -> Class.forName(name, true, loader)));
            init.start();
            init.join(5000);
            assertFalse(init.isAlive(), "the static initializer waited for the mod lock");
            assertEquals(null, init.error.get());
            assertEquals(0, init.depthAfter);
            // Ordinary mod code afterwards does wait.
            DimensionThread later = new DimensionThread("later", unchecked(() -> Class.forName(name, true, loader).getMethod("size").invoke(null)));
            later.start();
            later.join(300);
            assertTrue(later.isAlive(), "size() should wait for the lock");
            release.countDown();
            later.join(5000);
            assertFalse(later.isAlive());
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    /**
     * Mixin puts {@code @At("RETURN")} hooks after the woven lock exit, right before the return. If such a hook throws,
     * the woven catch-all handler must not release a second time: the caller still holds the lock and relies on it.
     */
    @Test
    void returnHooksThatThrowLeaveTheCallersLockAlone() throws Exception {
        for (boolean rewrapped : new boolean[]{false, true}) {
            Class<?> hooked = wovenWithReturnHooks(rewrapped);
            for (String method : List.of("value", "syncValue")) {
                Method m = hooked.getMethod(method, int.class);
                List<DimensionThread> threads = runAll(() -> {
                    ModLock.acquire(LockSource.of("caller"));
                    try {
                        assertEquals(method.equals("value") ? 4 : 6, m.invoke(null, 2));
                        try {
                            m.invoke(null, -1);
                        } catch (InvocationTargetException e) {
                            assertEquals(IllegalStateException.class, e.getCause().getClass(), "the hook's own exception");
                        }
                        assertTrue(ModLock.heldByCurrentThread(), "the caller lost the lock (" + method + ", rewrapped=" + rewrapped + ")");
                        assertEquals(1, ((TickThread) Thread.currentThread()).lockDepth, method + ", rewrapped=" + rewrapped);
                        // The cancellable injection's early return leaves the lock and, for syncValue, the monitor.
                        assertEquals(-5, m.invoke(null, 99));
                        assertEquals(1, ((TickThread) Thread.currentThread()).lockDepth, method + " early return, rewrapped=" + rewrapped);
                        assertFalse(Thread.holdsLock(hooked), method + " kept the class monitor after its early return");
                    } finally {
                        ModLock.release();
                    }
                });
                for (DimensionThread t : threads) {
                    assertEquals(null, t.error.get(), t.getName() + ": " + method + ", rewrapped=" + rewrapped);
                    assertEquals(0, t.depthAfter);
                }
            }
        }
    }

    /** Weaves TailHookModCode, then adds a hook call before every return as Mixin does, and the mixin extension's repair. */
    private static Class<?> wovenWithReturnHooks(boolean rewrapped) throws Exception {
        String name = "com.multicore.universal.core.fixtures.TailHookModCode";
        String internal = name.replace('.', '/');
        byte[] original;
        try (InputStream in = WovenLockTest.class.getClassLoader().getResourceAsStream(internal + ".class")) {
            original = in.readAllBytes();
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        LockWeaver.weaveClass(node, 0);
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (!m.name.equals("value") && !m.name.equals("syncValue")) {
                continue;
            }
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == org.objectweb.asm.Opcodes.IRETURN) {
                    org.objectweb.asm.tree.InsnList hook = new org.objectweb.asm.tree.InsnList();
                    hook.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ILOAD, 0));
                    hook.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, internal, "tailHook", "(I)V", false));
                    m.instructions.insertBefore(insn, hook);
                }
            }
            // A cancellable injection at the first call: "if (x == 99) return -5;" without any exit, as Mixin adds it.
            org.objectweb.asm.tree.AbstractInsnNode firstCall = null;
            for (var insn = m.instructions.getFirst(); insn != null && firstCall == null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("count")) {
                    firstCall = insn;
                }
            }
            org.objectweb.asm.tree.LabelNode go = new org.objectweb.asm.tree.LabelNode();
            org.objectweb.asm.tree.InsnList cancel = new org.objectweb.asm.tree.InsnList();
            cancel.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ILOAD, 0));
            cancel.add(new org.objectweb.asm.tree.IntInsnNode(org.objectweb.asm.Opcodes.BIPUSH, 99));
            cancel.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.IF_ICMPNE, go));
            cancel.add(new org.objectweb.asm.tree.IntInsnNode(org.objectweb.asm.Opcodes.BIPUSH, -5));
            cancel.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IRETURN));
            cancel.add(go);
            m.instructions.insert(firstCall, cancel);
            LockWeaver.repairExits(m);
            if (rewrapped) {
                LockWeaver.wrapAgain(node, m, 0);
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(WovenLockTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (n.equals(name)) {
                    synchronized (this) {
                        Class<?> c = this.findLoadedClass(n);
                        return c != null ? c : this.defineClass(n, bytes, 0, bytes.length);
                    }
                }
                return super.loadClass(n, resolve);
            }
        }.loadClass(name);
    }

    /** Scoped flags the main thread sets while dimension threads tick stay its own (it runs serial dimensions' tasks). */
    @Test
    void mainThreadScopedFlagsStayOnTheMainThread() throws Exception {
        int slot = 5;
        AtomicReference<Throwable> mainError = new AtomicReference<>();
        CountDownLatch set = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(1);
        Thread main = new Thread(() -> {
            try {
                assertTrue(StaticOverlay.setZ(true, slot), "the main thread's write must go to its own copy");
                assertTrue(StaticOverlay.getZ(false, slot));
                set.countDown();
                assertTrue(checked.await(10, TimeUnit.SECONDS));
                LockState.parallel = false;
                StaticOverlay.clearMain();
                assertFalse(StaticOverlay.getZ(false, slot), "outside the parallel phase the real field counts");
                assertFalse(StaticOverlay.setZ(true, slot), "outside the parallel phase writes go to the real field");
            } catch (Throwable t) {
                mainError.set(t);
            }
        }, "Server thread");
        LockState.serverThread = main;
        main.start();
        assertTrue(set.await(10, TimeUnit.SECONDS));
        List<DimensionThread> threads = runAll(() -> assertFalse(StaticOverlay.getZ(false, slot), "a dimension thread saw the main thread's flag"));
        checked.countDown();
        main.join(10_000);
        assertEquals(null, mainError.get());
        for (DimensionThread t : threads) {
            assertEquals(null, t.error.get(), t.getName());
        }
    }

    @Test
    void suspendAndRestoreKeepTheDepth() throws Exception {
        List<DimensionThread> threads = runAll(() -> {
            ModLock.acquire(LockSource.of("test"));
            ModLock.acquire(LockSource.of("test"));
            int depth = ModLock.suspend();
            assertEquals(2, depth);
            assertFalse(ModLock.heldByCurrentThread());
            call("count", 4);
            ModLock.restore(depth);
            assertTrue(ModLock.heldByCurrentThread());
            ModLock.release();
            ModLock.release();
            assertFalse(ModLock.heldByCurrentThread());
        });
        for (DimensionThread t : threads) {
            assertEquals(null, t.error.get());
            assertEquals(0, t.depthAfter);
        }
    }
}
