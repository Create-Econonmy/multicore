package com.multicore.universal.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Which mods' code takes the {@link ModLock}. The coremod numbers every mod it wove and publishes the list in a system
 * property; woven code passes that number to {@link ModLock#enter(int)}. Mods listed in threadSafeMods keep their
 * number but skip the lock, so the list can change at runtime without reweaving.
 */
public final class ModPolicy {
    public static final String MODS_PROPERTY = "multicore_universal.coremod.mods";
    public static final String STATUS_PROPERTY = "multicore_universal.coremod.status";

    private static final String[] MOD_IDS = parse(System.getProperty(MODS_PROPERTY));
    private static final LockSource[] SOURCES = new LockSource[MOD_IDS.length];
    private static final LockSource UNKNOWN = LockSource.of("code of an unknown mod");
    private static volatile boolean[] safe = new boolean[MOD_IDS.length];
    private static volatile Set<String> safeMods = Set.of("minecraft", "neoforge", "multicore_universal");

    static {
        for (int i = 0; i < MOD_IDS.length; i++) {
            SOURCES[i] = LockSource.of("mod " + MOD_IDS[i]);
        }
    }

    private ModPolicy() {
    }

    private static String[] parse(String value) {
        return value == null || value.isBlank() ? new String[0] : value.split(",");
    }

    /** True if the coremod loaded and wove the installed mods. Without it, mod code is unprotected. */
    public static boolean coremodActive() {
        return "active".equals(System.getProperty(STATUS_PROPERTY));
    }

    public static String coremodStatus() {
        String status = System.getProperty(STATUS_PROPERTY);
        return status == null ? "not loaded" : status;
    }

    /** Mods whose code is woven, in index order. */
    public static List<String> wovenMods() {
        return List.of(MOD_IDS);
    }

    public static void configure(boolean serialize, Collection<? extends String> threadSafeMods) {
        Set<String> set = Set.copyOf(threadSafeMods);
        boolean[] flags = new boolean[MOD_IDS.length];
        for (int i = 0; i < MOD_IDS.length; i++) {
            flags[i] = set.contains(MOD_IDS[i]);
        }
        safeMods = set;
        safe = flags;
        LockState.serialize = serialize;
    }

    public static Set<String> safeMods() {
        return safeMods;
    }

    public static boolean serializeUnsafe() {
        return LockState.serialize;
    }

    /** Out-of-range indexes (a mismatch that should not happen) count as unsafe. */
    static boolean isSafe(int index) {
        boolean[] flags = safe;
        return index >= 0 && index < flags.length && flags[index];
    }

    static LockSource source(int index) {
        return index >= 0 && index < SOURCES.length ? SOURCES[index] : UNKNOWN;
    }

    public static String modId(int index) {
        return index >= 0 && index < MOD_IDS.length ? MOD_IDS[index] : "?";
    }

    static int count() {
        return MOD_IDS.length;
    }

    static String describe() {
        return Arrays.toString(MOD_IDS);
    }
}
