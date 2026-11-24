package com.multicore.compat;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-thread storage for static fields that {@link ThreadLocalStaticsTransformer} redirected. Each thread starts from
 * the field's real (initial) value, read once by reflection; the real field is never written again.
 * Keys are {@code "owner/internal/Name.field"}.
 */
public final class ThreadStatics {
    static final String CREATE_DEPLOYER_HANDLER = "com/simibubi/create/content/kinetics/deployer/DeployerHandler";
    static final String CREATE_CAPTURED_DROPS = CREATE_DEPLOYER_HANDLER + ".CAPTURED_BLOCK_DROPS";
    static final String CREATE_CAPTURED_DROPS_VIEW = CREATE_DEPLOYER_HANDLER + ".CAPTURED_BLOCK_DROPS_VIEW";
    static final String CREATE_BRASS_TUNNEL = "com/simibubi/create/content/logistics/tunnel/BrassTunnelBlockEntity";
    private static final Object UNSET = new Object();
    private static final ThreadLocal<HashMap<String, Object>> VALUES = ThreadLocal.withInitial(HashMap::new);
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    private ThreadStatics() {
    }

    public static Object getObject(String key) {
        HashMap<String, Object> values = VALUES.get();
        Object value = values.getOrDefault(key, UNSET);
        if (value == UNSET) {
            value = initialValue(key);
            values.put(key, value);
        }
        return value;
    }

    public static void setObject(Object value, String key) {
        VALUES.get().put(key, value);
    }

    public static boolean getBoolean(String key) {
        return getObject(key) instanceof Boolean b && b;
    }

    public static void setBoolean(boolean value, String key) {
        setObject(value, key);
    }

    public static int getInt(String key) {
        return getObject(key) instanceof Number n ? n.intValue() : 0;
    }

    public static void setInt(int value, String key) {
        setObject(value, key);
    }

    public static long getLong(String key) {
        return getObject(key) instanceof Number n ? n.longValue() : 0L;
    }

    public static void setLong(long value, String key) {
        setObject(value, key);
    }

    public static float getFloat(String key) {
        return getObject(key) instanceof Number n ? n.floatValue() : 0F;
    }

    public static void setFloat(float value, String key) {
        setObject(value, key);
    }

    public static double getDouble(String key) {
        return getObject(key) instanceof Number n ? n.doubleValue() : 0D;
    }

    public static void setDouble(double value, String key) {
        setObject(value, key);
    }

    /**
     * Fields that start out holding a mutable object need a new object per thread, not a shared reference to the real
     * one. Keyed like the fields themselves.
     */
    private static final Map<String, Supplier<Object>> FRESH_VALUES = Map.of(
            CREATE_BRASS_TUNNEL + ".distributed", java.util.IdentityHashMap::new,
            CREATE_BRASS_TUNNEL + ".full", java.util.HashSet::new,
            CREATE_CAPTURED_DROPS, HashMap::new,
            CREATE_CAPTURED_DROPS_VIEW, () -> Collections.unmodifiableMap((Map<?, ?>) getObject(CREATE_CAPTURED_DROPS)));

    private static Object initialValue(String key) {
        Supplier<Object> fresh = FRESH_VALUES.get(key);
        if (fresh != null) {
            return fresh.get();
        }
        Field field = FIELDS.computeIfAbsent(key, ThreadStatics::findField);
        try {
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read initial value of " + key, e);
        }
    }

    private static Field findField(String key) {
        int dot = key.lastIndexOf('.');
        String owner = key.substring(0, dot).replace('/', '.');
        String name = key.substring(dot + 1);
        try {
            Field field = Class.forName(owner, false, ThreadStatics.class.getClassLoader()).getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot find redirected static field " + key, e);
        }
    }
}
