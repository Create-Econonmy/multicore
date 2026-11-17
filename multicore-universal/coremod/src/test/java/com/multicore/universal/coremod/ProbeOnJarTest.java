package com.multicore.universal.coremod;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

/**
 * Diagnostic: runs the frame-computation probe over every class of a real mod jar and reports failures that are not
 * missing classes. Only runs with -DprobeJar=path/to/mod.jar.
 */
class ProbeOnJarTest {
    @Test
    void probeEveryClassOfAJar() throws IOException {
        String jar = System.getProperty("probeJar");
        assumeTrue(jar != null, "set -DprobeJar to run");
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")) {
                    classes.put(e.getName().substring(0, e.getName().length() - 6), zip.getInputStream(e).readAllBytes());
                }
            }
        }
        ModCatalog catalog = ModCatalog.forTest(classes, Map.of(), List.of());
        int ok = 0;
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            try {
                new ClassReader(e.getValue()).accept(new ModCatalog.ProbeWriter(catalog), ClassReader.SKIP_FRAMES);
                ok++;
            } catch (ModCatalog.MissingClassException missing) {
                ok++;
            } catch (Throwable t) {
                if (failures.size() < 3) {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    t.printStackTrace(new java.io.PrintWriter(sw));
                    failures.add(e.getKey() + ": " + sw.toString().lines().limit(12).reduce("", (a, b) -> a + "\n" + b));
                } else {
                    failures.add(e.getKey());
                }
            }
        }
        System.out.println("probe: " + ok + " ok, " + failures.size() + " failed");
        failures.stream().limit(3).forEach(System.out::println);
        org.junit.jupiter.api.Assertions.assertTrue(failures.isEmpty(), failures.size() + " probe failures");
    }
}
