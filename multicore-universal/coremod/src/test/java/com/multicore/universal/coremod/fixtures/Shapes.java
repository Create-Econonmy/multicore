package com.multicore.universal.coremod.fixtures;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Method shapes the weaver must handle; verified with ASM's analyzer after weaving. */
public class Shapes implements Comparable<Shapes> {
    public static Object captured;
    public static final Deque<Object> STACK = new ArrayDeque<>();
    public static final Shapes INSTANCE = new Shapes();
    private long wide;
    private double wider;
    private int field;
    public Object shared;

    public int getField() {
        return this.field;
    }

    public void setField(int value) {
        this.field = value;
    }

    public synchronized long syncWide(long a, double b) {
        this.wide += a;
        this.wider += b;
        return this.wide + (long) this.wider;
    }

    public static synchronized int syncStatic(int x) {
        return x * 2 + List.of(x).size();
    }

    public int loops(int[] values) {
        int sum = 0;
        for (int v : values) {
            if (v < 0) {
                return -1;
            }
            sum += v;
        }
        return sum;
    }

    public String tryCatch(String s) {
        try {
            return s.trim();
        } catch (NullPointerException e) {
            return "null";
        } finally {
            this.field++;
        }
    }

    /** A lazily built value guarded by a synchronized block in a rarely taken branch (like ModernFix's caches). */
    public int lazy(Object o) {
        if (o == null) {
            synchronized (Shapes.class) {
                this.field++;
            }
        }
        return this.field;
    }

    public void throwsAlways() {
        throw new UnsupportedOperationException();
    }

    public Runnable lambda() {
        return () -> this.field++;
    }

    @Override
    public int compareTo(Shapes o) {
        return Integer.compare(this.field, o.field);
    }

    // ---- static state, for StaticStateAnalysis ----

    public static void writesStatic(Object o) {
        captured = o;
    }

    public static void pushesStatic(Object o) {
        STACK.push(o);
    }

    public static Object readsStatic() {
        return captured;
    }

    public static void callsWriter(Object o) {
        writesStatic(o);
    }

    public static void writesSingleton(Object o) {
        INSTANCE.shared = o;
    }

    // ---- shared objects changed without a field write of the method's own ----

    public static final StringBuilder SCRATCH = new StringBuilder();
    private int count;

    /** Reuses one scratch object for every caller, like "static final MutableBlockPos POS". */
    public static String formatShared(int x) {
        SCRATCH.setLength(0);
        return SCRATCH.append(x).toString();
    }

    public static String formatLocal(int x) {
        StringBuilder sb = new StringBuilder();
        return sb.append(x).toString();
    }

    public static void bumpsSingleton() {
        INSTANCE.count++;
    }

    public void bumpsOwn() {
        this.count++;
    }

    public interface WithDefault {
        default int twice(int x) {
            return Math.max(x, 0) * 2 + String.valueOf(x).length();
        }
    }
}
