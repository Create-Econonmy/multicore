package com.multicore.compat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The list behind {@code StaticGuardCarrier} (multicore-guard.txt, generated from the pack analyzer): shared static
 * collections that mods change while the game runs ("F owner/Class.field") and mods' per-world state classes ("S").
 * With every mod unlocked, these are what two tick threads could otherwise break at the same time.
 */
public final class StaticGuard {
    private static final Set<String> FIELDS = new HashSet<>();
    private static final Set<String> PER_WORLD = new HashSet<>();
    private static final AtomicInteger WRAPPED = new AtomicInteger();

    static {
        try (InputStream in = StaticGuard.class.getResourceAsStream("/multicore-guard.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("F ")) FIELDS.add(line.substring(2).trim());
                    else if (line.startsWith("S ")) PER_WORLD.add(line.substring(2).trim());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("MultiCore: cannot read multicore-guard.txt", e);
        }
    }

    private StaticGuard() {
    }

    static Set<String> fields() {
        return FIELDS;
    }

    static boolean isPerWorldState(String internalName) {
        return PER_WORLD.contains(internalName);
    }

    static void noteWrapped(int n) {
        WRAPPED.addAndGet(n);
    }

    /** Methods wrapped so far (classes are patched as they load). */
    public static int wrapped() {
        return WRAPPED.get();
    }

    public static int fieldCount() {
        return FIELDS.size();
    }

    public static int perWorldCount() {
        return PER_WORLD.size();
    }
}
