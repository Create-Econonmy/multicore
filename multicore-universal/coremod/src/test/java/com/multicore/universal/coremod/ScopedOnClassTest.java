package com.multicore.universal.coremod;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Diagnostic: -PscopedClass=path/to/Some.class prints which static fields count as scoped flags. */
class ScopedOnClassTest {
    @Test
    void classify() throws IOException {
        String path = System.getProperty("scopedClass");
        assumeTrue(path != null, "set -DscopedClass to run");
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(path))).accept(node, 0);
        Set<String> hooks = new HashSet<>();
        node.methods.forEach(m -> hooks.add(m.name + m.desc));
        System.out.println("fields: " + node.fields.stream().map(f -> f.name + "/" + f.access).toList());
        System.out.println("scoped: " + ScopedStatics.find(node, Set.of(), hooks));
        node.methods.forEach(m -> System.out.println("  " + m.name + " writesDirectly=" + StaticStateAnalysis.writesDirectly(m)));
    }
}
