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
        try (InputStream in = open()) {
            if (in == null) {
                org.slf4j.LoggerFactory.getLogger("multicore").error("[MultiCore] multicore-guard.txt not found: the static guard is OFF");
            } else {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("F ")) FIELDS.add(line.substring(2).trim());
                    else if (line.startsWith("S ")) PER_WORLD.add(line.substring(2).trim());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("MultiCore: cannot read multicore-guard.txt", e);
        }
        org.slf4j.LoggerFactory.getLogger("multicore").info("[MultiCore] Static guard list: {} fields, {} per-world classes", FIELDS.size(), PER_WORLD.size());
    }

    private StaticGuard() {
    }

    /** Mixin plugins are loaded by Mixin's own class loader, which may not see the mod jar's resources: try them all. */
    private static InputStream open() {
        ClassLoader[] loaders = {StaticGuard.class.getClassLoader(), Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader()};
        for (ClassLoader l : loaders) {
            if (l == null) continue;
            InputStream in = l.getResourceAsStream("multicore-guard.txt");
            if (in != null) return in;
        }
        InputStream in = StaticGuard.class.getResourceAsStream("/multicore-guard.txt");
        if (in != null) return in;
        // Last resort: next to this class's code source (a jar or the dev build's classes folder).
        try {
            java.net.URL where = StaticGuard.class.getProtectionDomain().getCodeSource().getLocation();
            java.nio.file.Path p = java.nio.file.Path.of(where.toURI());
            java.nio.file.Path file = java.nio.file.Files.isDirectory(p) ? p.resolve("multicore-guard.txt") : null;
            if (file != null && !java.nio.file.Files.exists(file)) {
                // MDG dev layout: build/classes/java/main next to build/resources/main
                file = p.getParent().getParent().getParent().resolve("resources/main/multicore-guard.txt");
            }
            if (file != null && java.nio.file.Files.exists(file)) return java.nio.file.Files.newInputStream(file);
            if (file == null) {
                java.util.jar.JarFile jar = new java.util.jar.JarFile(p.toFile());
                java.util.jar.JarEntry e = jar.getJarEntry("multicore-guard.txt");
                if (e != null) return jar.getInputStream(e);
            }
        } catch (Exception ignored) {
            // not found
        }
        return null;
    }

    static Set<String> fields() {
        return FIELDS;
    }

    static boolean isPerWorldState(String internalName) {
        return PER_WORLD.contains(internalName);
    }

    private static final AtomicInteger CLASSES = new AtomicInteger();

    static int noteWrapped(int n) {
        return WRAPPED.addAndGet(n);
    }

    static int noteClass() {
        return CLASSES.incrementAndGet();
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
