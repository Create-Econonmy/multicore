package com.multicore.analyzer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Scans every jar in a mods folder (including jar-in-jar) and reports:
 * <ul>
 *   <li>every mixin injection, grouped by the Minecraft class and method it targets,</li>
 *   <li>static collections and random sources that are not thread-safe,</li>
 *   <li>calls that reach into other worlds (getLevel, overworld, changeDimension, teleportTo),</li>
 *   <li>which NeoForge events each mod listens to.</li>
 * </ul>
 * MultiCore uses the report to decide which code must stay in the serial phase.
 */
public final class PackAnalyzer {
    private static final Set<String> UNSAFE_COLLECTIONS = Set.of(
            "java/util/HashMap", "java/util/WeakHashMap", "java/util/IdentityHashMap", "java/util/LinkedHashMap",
            "java/util/TreeMap", "java/util/ArrayList", "java/util/LinkedList", "java/util/HashSet",
            "java/util/LinkedHashSet", "java/util/TreeSet", "java/util/ArrayDeque", "java/util/PriorityQueue",
            "java/util/EnumMap",
            "com/google/common/collect/HashMultimap", "com/google/common/collect/ArrayListMultimap",
            "com/google/common/collect/HashBiMap", "com/google/common/collect/LinkedHashMultimap");
    private static final Pattern FASTUTIL_UNSAFE = Pattern.compile(
            "it/unimi/dsi/fastutil/.*/(\\w+)(OpenHashMap|OpenHashSet|ArrayList|AVLTreeMap|RBTreeMap|ArrayMap|LinkedOpenHashMap|LinkedOpenHashSet|OpenCustomHashMap|ArraySet|ArrayFIFOQueue|HeapPriorityQueue)");
    private static final Map<String, String> UNSAFE_FACTORIES = Map.ofEntries(
            Map.entry("com/google/common/collect/Maps.newHashMap", "java/util/HashMap"),
            Map.entry("com/google/common/collect/Maps.newLinkedHashMap", "java/util/LinkedHashMap"),
            Map.entry("com/google/common/collect/Maps.newIdentityHashMap", "java/util/IdentityHashMap"),
            Map.entry("com/google/common/collect/Maps.newTreeMap", "java/util/TreeMap"),
            Map.entry("com/google/common/collect/Maps.newEnumMap", "java/util/EnumMap"),
            Map.entry("com/google/common/collect/Lists.newArrayList", "java/util/ArrayList"),
            Map.entry("com/google/common/collect/Lists.newLinkedList", "java/util/LinkedList"),
            Map.entry("com/google/common/collect/Sets.newHashSet", "java/util/HashSet"),
            Map.entry("com/google/common/collect/Sets.newLinkedHashSet", "java/util/LinkedHashSet"),
            Map.entry("com/google/common/collect/Sets.newIdentityHashSet", "IdentityHashSet"),
            Map.entry("com/google/common/collect/HashMultimap.create", "com/google/common/collect/HashMultimap"),
            Map.entry("com/google/common/collect/ArrayListMultimap.create", "com/google/common/collect/ArrayListMultimap"));
    private static final Set<String> UNSAFE_RANDOM_FACTORIES = Set.of(
            "net/minecraft/util/RandomSource.create",
            "net/minecraft/util/RandomSource.createNewThreadLocalInstance");
    private static final Set<String> UNSAFE_RANDOM_TYPES = Set.of(
            "net/minecraft/world/level/levelgen/LegacyRandomSource",
            "net/minecraft/world/level/levelgen/XoroshiroRandomSource",
            "net/minecraft/world/level/levelgen/SingleThreadedRandomSource");
    /** Calls that reach into another world, or move things between worlds. owner.name -> label */
    private static final Map<String, String> CROSS_LEVEL_CALLS = Map.ofEntries(
            Map.entry("net/minecraft/server/MinecraftServer.getLevel", "server.getLevel"),
            Map.entry("net/minecraft/server/MinecraftServer.overworld", "server.overworld"),
            Map.entry("net/minecraft/server/MinecraftServer.getAllLevels", "server.getAllLevels"),
            Map.entry("net/minecraft/server/MinecraftServer.levelKeys", "server.levelKeys"),
            Map.entry("net/minecraft/server/MinecraftServer.getWorldArray", "server.getWorldArray"),
            Map.entry("net/minecraft/world/entity/Entity.changeDimension", "entity.changeDimension"),
            Map.entry("net/minecraft/server/level/ServerPlayer.changeDimension", "player.changeDimension"),
            Map.entry("net/minecraft/server/level/ServerPlayer.teleportTo", "player.teleportTo"),
            Map.entry("net/minecraft/world/entity/Entity.teleportTo", "entity.teleportTo"),
            Map.entry("net/minecraft/server/MinecraftServer.execute", "server.execute"),
            Map.entry("net/minecraft/server/MinecraftServer.submit", "server.submit"),
            Map.entry("net/minecraft/server/MinecraftServer.executeBlocking", "server.executeBlocking"),
            Map.entry("net/minecraft/util/thread/BlockableEventLoop.executeBlocking", "eventloop.executeBlocking"),
            Map.entry("net/minecraft/commands/Commands.performPrefixedCommand", "commands.performPrefixedCommand"),
            Map.entry("net/minecraft/commands/Commands.performCommand", "commands.performCommand"));
    private static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SUBSCRIBE = "Lnet/neoforged/bus/api/SubscribeEvent;";
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MIXIN_CONFIG = Pattern.compile("(?ms)\\[\\[mixins]]\\s*config\\s*=\\s*\"([^\"]+)\"");

    private final List<JarReport> jars = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        Path modsDir = Paths.get(args.length > 0 ? args[0] : "run/server/mods");
        Path outDir = Paths.get(args.length > 1 ? args[1] : "build/pack-report");
        PackAnalyzer analyzer = new PackAnalyzer();
        try (Stream<Path> files = Files.list(modsDir)) {
            for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                analyzer.scanJar(jar.getFileName().toString(), Files.readAllBytes(jar), null);
            }
        }
        Files.createDirectories(outDir);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(outDir.resolve("report.json"), gson.toJson(analyzer.jars));
        Files.writeString(outDir.resolve("mixin-targets.json"), gson.toJson(analyzer.byTarget()));
        Files.writeString(outDir.resolve("report.md"), analyzer.markdown());
        System.out.println("Scanned " + analyzer.jars.size() + " jars -> " + outDir.toAbsolutePath());
    }

    private void scanJar(String name, byte[] bytes, String parent) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<String> nested = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.endsWith(".class") || n.endsWith(".json") || n.endsWith(".toml") || n.equals("META-INF/MANIFEST.MF")
                        || (n.startsWith("META-INF/jarjar/") && n.endsWith(".jar"))) {
                    entries.put(n, zip.readAllBytes());
                    if (n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) nested.add(n);
                }
            }
        }
        JarReport report = new JarReport();
        report.jar = parent == null ? name : parent + "!" + name;
        String toml = text(entries.get("META-INF/neoforge.mods.toml"));
        if (toml == null) toml = text(entries.get("META-INF/mods.toml"));
        Set<String> configs = new LinkedHashSet<>();
        if (toml != null) {
            // Only [[mods]] tables declare mods; [[dependencies.x]] tables also have a modId key.
            for (String table : toml.split("(?m)^\\s*\\[\\[")) {
                if (!table.startsWith("mods]]")) continue;
                Matcher m = MOD_ID.matcher(table);
                if (m.find()) report.modIds.add(m.group(1));
            }
            Matcher c = MIXIN_CONFIG.matcher(toml);
            while (c.find()) configs.add(c.group(1));
        }
        byte[] mf = entries.get("META-INF/MANIFEST.MF");
        if (mf != null) {
            String cfgs = new Manifest(new ByteArrayInputStream(mf)).getMainAttributes().getValue("MixinConfigs");
            if (cfgs != null) for (String s : cfgs.split(",")) if (!s.isBlank()) configs.add(s.trim());
        }
        Map<String, String> mixinClasses = new LinkedHashMap<>(); // internal name -> side
        for (String cfg : configs) {
            String json = text(entries.get(cfg));
            if (json == null) continue;
            report.mixinConfigs.add(cfg);
            try {
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                String pkg = o.has("package") ? o.get("package").getAsString().replace('.', '/') : "";
                for (String side : List.of("mixins", "server", "client")) {
                    if (!o.has(side) || !o.get(side).isJsonArray()) continue;
                    for (JsonElement el : o.getAsJsonArray(side)) {
                        mixinClasses.put(pkg + "/" + el.getAsString().replace('.', '/'), side.equals("mixins") ? "common" : side);
                    }
                }
            } catch (RuntimeException ex) {
                report.notes.add("could not parse mixin config " + cfg + ": " + ex.getMessage());
            }
        }
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            ClassNode cn = new ClassNode();
            try {
                new ClassReader(entry.getValue()).accept(cn, ClassReader.SKIP_FRAMES);
            } catch (RuntimeException ex) {
                continue;
            }
            classes.put(cn.name, cn);
        }
        for (ClassNode cn : classes.values()) {
            String side = mixinClasses.get(cn.name);
            if (side != null) {
                List<String> targets = scanMixin(report, cn, side);
                if (!side.equals("client") && !targets.isEmpty()) scanMixinStatics(report, cn, targets, classes);
            }
            if (side == null || !side.equals("client")) scanHazards(report, cn);
        }
        jars.add(report);
        for (String n : nested) scanJar(n.substring(n.lastIndexOf('/') + 1), entries.get(n), report.jar);
    }

    private static final Set<String> MUTATORS = Set.of("put", "putAll", "remove", "clear", "computeIfAbsent", "compute",
            "computeIfPresent", "merge", "putIfAbsent", "add", "addAll", "removeAll", "removeIf", "retainAll", "set", "offer",
            "poll", "push", "pop", "addFirst", "addLast", "removeFirst", "removeLast", "replaceAll", "sort", "addTo");
    private static final Set<String> COLLECTION_INTERFACES = Set.of("java/util/Map", "java/util/List", "java/util/Set",
            "java/util/Collection", "java/util/Queue", "java/util/Deque", "java/util/SortedMap", "java/util/NavigableMap");
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";

    /**
     * Finds mixin code (which runs inside vanilla methods, so without MultiCore's mod lock) that writes static state:
     * static fields it adds to the target, static fields it assigns, static collections it modifies, directly or one
     * static call deep into the mod's own classes. That is the pattern that breaks with several tick threads.
     */
    private static void scanMixinStatics(JarReport report, ClassNode mixin, List<String> targets, Map<String, ClassNode> classes) {
        String target = targets.get(0);
        for (FieldNode f : mixin.fields) {
            if ((f.access & Opcodes.ACC_STATIC) == 0) continue;
            if (annotations(f.visibleAnnotations, f.invisibleAnnotations).stream().anyMatch(a -> SHADOW.equals(a.desc))) continue;
            Type t = Type.getType(f.desc);
            boolean isFinal = (f.access & Opcodes.ACC_FINAL) != 0;
            boolean collection = t.getSort() == Type.OBJECT && (isUnsafeCollection(t.getInternalName()) || COLLECTION_INTERFACES.contains(t.getInternalName()));
            if (!isFinal || collection) {
                report.mixinStaticState.add(new StaticState(mixin.name, target, "<field>", "adds static " + (isFinal ? "collection " : "field ")
                        + f.name + " : " + t.getClassName()));
            }
        }
        for (MethodNode m : mixin.methods) {
            if (m.name.equals("<clinit>") || m.instructions.size() == 0) continue;
            Set<String> found = new LinkedHashSet<>();
            collectStaticWrites(m, mixin.name, classes, 0, new java.util.HashSet<>(), found);
            for (String what : found) report.mixinStaticState.add(new StaticState(mixin.name, target, m.name, what));
        }
    }

    private static void collectStaticWrites(MethodNode m, String owner, Map<String, ClassNode> classes, int depth,
                                            Set<String> visited, Set<String> out) {
        if (!visited.add(owner + "." + m.name + m.desc)) return;
        String via = depth == 0 ? "" : " (via " + owner + "." + m.name + ")";
        String lastCollection = null;
        int distance = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() < 0) continue;
            distance++;
            if (insn instanceof FieldInsnNode f) {
                if (f.owner.startsWith("java/")) continue;
                if (f.getOpcode() == Opcodes.PUTSTATIC && !(depth > 0 && m.name.equals("<clinit>"))) {
                    out.add("writes " + f.owner + "." + f.name + via);
                } else if (f.getOpcode() == Opcodes.GETSTATIC) {
                    Type t = Type.getType(f.desc);
                    if (t.getSort() == Type.OBJECT && (isUnsafeCollection(t.getInternalName()) || COLLECTION_INTERFACES.contains(t.getInternalName()))) {
                        lastCollection = f.owner + "." + f.name;
                        distance = 0;
                    }
                }
            } else if (insn instanceof MethodInsnNode call) {
                if (lastCollection != null && distance < 12 && MUTATORS.contains(call.name)
                        && (call.owner.startsWith("java/util/") || call.owner.startsWith("it/unimi/") || call.owner.startsWith("com/google/common/"))) {
                    out.add("modifies static collection " + lastCollection + via);
                    lastCollection = null;
                }
                if (depth == 0 && call.getOpcode() == Opcodes.INVOKESTATIC && classes.containsKey(call.owner)) {
                    ClassNode callee = classes.get(call.owner);
                    for (MethodNode cm : callee.methods) {
                        if (cm.name.equals(call.name) && cm.desc.equals(call.desc) && cm.instructions.size() > 0) {
                            collectStaticWrites(cm, callee.name, classes, depth + 1, visited, out);
                        }
                    }
                }
            }
        }
    }

    private static List<String> scanMixin(JarReport report, ClassNode cn, String side) {
        List<String> targets = new ArrayList<>();
        for (AnnotationNode an : annotations(cn.visibleAnnotations, cn.invisibleAnnotations)) {
            if (!MIXIN.equals(an.desc)) continue;
            Object value = value(an, "value");
            if (value instanceof List<?> list) for (Object t : list) if (t instanceof Type type) targets.add(type.getInternalName());
            Object named = value(an, "targets");
            if (named instanceof List<?> list) for (Object t : list) targets.add(String.valueOf(t).replace('.', '/'));
        }
        if (targets.isEmpty()) return targets;
        report.mixinCount++;
        for (MethodNode mn : cn.methods) {
            for (AnnotationNode an : annotations(mn.visibleAnnotations, mn.invisibleAnnotations)) {
                if (OVERWRITE.equals(an.desc)) {
                    for (String t : targets) report.injections.add(new Injection(t, mn.name + mn.desc, "Overwrite", "", cn.name, side));
                } else if (INJECTORS.contains(an.desc)) {
                    String kind = an.desc.substring(an.desc.lastIndexOf('/') + 1, an.desc.length() - 1);
                    List<String> methods = new ArrayList<>();
                    Object m = value(an, "method");
                    if (m instanceof List<?> list) for (Object o : list) methods.add(String.valueOf(o));
                    if (methods.isEmpty()) methods.add(mn.name);
                    String at = describeAt(value(an, "at"));
                    for (String t : targets) for (String method : methods) report.injections.add(new Injection(t, method, kind, at, cn.name, side));
                }
            }
        }
        return targets;
    }

    private static String describeAt(Object at) {
        if (at == null) return "";
        List<AnnotationNode> ats = new ArrayList<>();
        if (at instanceof AnnotationNode a) ats.add(a);
        else if (at instanceof List<?> list) for (Object o : list) if (o instanceof AnnotationNode a) ats.add(a);
        StringBuilder sb = new StringBuilder();
        for (AnnotationNode a : ats) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append(value(a, "value"));
            Object target = value(a, "target");
            if (target != null) sb.append(" ").append(target);
        }
        return sb.toString();
    }

    private static final Set<String> LEVEL_TYPES = Set.of("net/minecraft/server/level/ServerLevel", "net/minecraft/world/level/Level",
            "net/minecraft/server/level/ServerChunkCache");

    private static void scanHazards(JarReport report, ClassNode cn) {
        if (cn.superName != null && LEVEL_TYPES.contains(cn.superName)) {
            report.levelSubclasses.add(cn.name + " extends " + cn.superName);
        }
        // 1) Static fields declared with a non-thread-safe collection type.
        Map<String, FieldNode> statics = new LinkedHashMap<>();
        for (FieldNode fn : cn.fields) {
            if ((fn.access & Opcodes.ACC_STATIC) == 0) continue;
            statics.put(fn.name, fn);
            Type t = Type.getType(fn.desc);
            if (t.getSort() == Type.OBJECT && isUnsafeCollection(t.getInternalName())) {
                report.staticCollections.add(new Hazard(cn.name, fn.name, t.getInternalName(), sig(fn)));
            }
            if (t.getSort() == Type.OBJECT && UNSAFE_RANDOM_TYPES.contains(t.getInternalName())) {
                report.staticRandoms.add(new Hazard(cn.name, fn.name, t.getInternalName(), ""));
            }
        }
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals("<clinit>")) collectRuntimeMutations(report, cn, mn);
            String lastCreated = null;
            boolean callsAddListener = false;
            List<String> lambdaEvents = new ArrayList<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof TypeInsnNode tin && tin.getOpcode() == Opcodes.NEW) {
                    lastCreated = tin.desc;
                } else if (insn instanceof MethodInsnNode min) {
                    String key = min.owner + "." + min.name;
                    String factory = UNSAFE_FACTORIES.get(key);
                    if (factory != null) lastCreated = factory;
                    if (UNSAFE_RANDOM_FACTORIES.contains(key)) lastCreated = "RandomSource.create";
                    String cross = CROSS_LEVEL_CALLS.get(key);
                    if (cross != null) report.crossLevelCalls.add(cross + " in " + cn.name + "." + mn.name);
                    if (min.name.equals("addListener") && min.owner.equals("net/neoforged/bus/api/IEventBus")) callsAddListener = true;
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle h && h.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
                            Type[] params = Type.getArgumentTypes(h.getDesc());
                            if (params.length >= 1 && params[params.length - 1].getSort() == Type.OBJECT
                                    && params[params.length - 1].getInternalName().startsWith("net/neoforged/neoforge/event/")) {
                                lambdaEvents.add(params[params.length - 1].getInternalName());
                            }
                        }
                    }
                } else if (insn instanceof FieldInsnNode fin && fin.getOpcode() == Opcodes.PUTSTATIC && fin.owner.equals(cn.name)) {
                    // 2) Static fields whose declared type is an interface (Map, List, RandomSource) but hold an unsafe instance.
                    if (lastCreated != null && mn.name.equals("<clinit>")) {
                        Type declared = Type.getType(fin.desc);
                        if (!(declared.getSort() == Type.OBJECT && isUnsafeCollection(declared.getInternalName()))) {
                            if (lastCreated.equals("RandomSource.create") || UNSAFE_RANDOM_TYPES.contains(lastCreated)) {
                                report.staticRandoms.add(new Hazard(cn.name, fin.name, lastCreated, ""));
                            } else if (isUnsafeCollection(lastCreated)) {
                                FieldNode fn = statics.get(fin.name);
                                report.staticCollections.add(new Hazard(cn.name, fin.name, lastCreated, fn == null ? "" : sig(fn)));
                            }
                        }
                    }
                    lastCreated = null;
                }
            }
            if (callsAddListener) for (String ev : lambdaEvents) report.events.add(shortEvent(ev) + " <- " + cn.name + "." + mn.name + " (addListener)");
            for (AnnotationNode an : annotations(mn.visibleAnnotations, mn.invisibleAnnotations)) {
                if (!SUBSCRIBE.equals(an.desc)) continue;
                Type[] params = Type.getArgumentTypes(mn.desc);
                if (params.length > 0 && params[0].getSort() == Type.OBJECT) report.events.add(shortEvent(params[0].getInternalName()) + " <- " + cn.name + "." + mn.name);
            }
        }
    }

    /**
     * Static collections that a method (not a static initializer) both reads from the field and mutates: the ones that
     * can really be modified while the server runs, as opposed to registries filled at startup. Approximate: a mutating
     * call anywhere in a method that reads the field counts.
     */
    private static void collectRuntimeMutations(JarReport report, ClassNode cn, MethodNode mn) {
        Set<String> fields = new LinkedHashSet<>();
        boolean mutates = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode fin && fin.getOpcode() == Opcodes.GETSTATIC) {
                Type t = Type.getType(fin.desc);
                if (t.getSort() == Type.OBJECT && (isUnsafeCollection(t.getInternalName()) || COLLECTION_INTERFACES.contains(t.getInternalName()))) {
                    fields.add(fin.owner + "." + fin.name + " : " + t.getInternalName());
                }
            } else if (insn instanceof MethodInsnNode min && MUTATORS.contains(min.name)
                    && (min.owner.startsWith("java/util/") || min.owner.startsWith("it/unimi/"))) {
                mutates = true;
            }
        }
        if (mutates) for (String f : fields) report.runtimeMutated.add(f + " <- " + cn.name + "." + mn.name);
    }

    private static boolean isUnsafeCollection(String internalName) {
        return UNSAFE_COLLECTIONS.contains(internalName) || internalName.equals("IdentityHashSet") || FASTUTIL_UNSAFE.matcher(internalName).matches();
    }

    private static String shortEvent(String internal) {
        return internal.replace("net/neoforged/neoforge/event/", "").replace('/', '.');
    }

    private static String sig(FieldNode fn) {
        return fn.signature == null ? "" : fn.signature;
    }

    @SafeVarargs
    private static List<AnnotationNode> annotations(List<AnnotationNode>... lists) {
        List<AnnotationNode> all = new ArrayList<>();
        for (List<AnnotationNode> l : lists) if (l != null) all.addAll(l);
        return all;
    }

    private static Object value(AnnotationNode an, String key) {
        if (an.values == null) return null;
        for (int i = 0; i + 1 < an.values.size(); i += 2) if (key.equals(an.values.get(i))) return an.values.get(i + 1);
        return null;
    }

    private static String text(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** target class -> target method -> list of "mod: kind @ at (mixin class)" */
    private Map<String, Map<String, List<String>>> byTarget() {
        Map<String, Map<String, List<String>>> out = new TreeMap<>();
        for (JarReport j : jars) {
            String mod = j.modIds.isEmpty() ? j.jar : String.join(",", j.modIds);
            for (Injection in : j.injections) {
                out.computeIfAbsent(in.target, k -> new TreeMap<>())
                        .computeIfAbsent(in.method, k -> new ArrayList<>())
                        .add(mod + ": " + in.kind + (in.at.isEmpty() ? "" : " @ " + in.at) + " (" + in.mixin.substring(in.mixin.lastIndexOf('/') + 1) + ", " + in.side + ")");
            }
        }
        return out;
    }

    private String markdown() {
        StringBuilder sb = new StringBuilder("# Pack thread-safety report\n\n");
        sb.append("| Mod | Mixins | Injections | Static collections | Static randoms | Cross-world calls | Event listeners |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (JarReport j : jars) {
            sb.append("| ").append(j.modIds.isEmpty() ? j.jar : String.join(", ", j.modIds))
                    .append(" | ").append(j.mixinCount).append(" | ").append(j.injections.size())
                    .append(" | ").append(j.staticCollections.size()).append(" | ").append(j.staticRandoms.size())
                    .append(" | ").append(j.crossLevelCalls.size()).append(" | ").append(j.events.size()).append(" |\n");
        }
        sb.append("\n## Static random sources (crash if two threads use them at once)\n\n");
        for (JarReport j : jars) for (Hazard h : j.staticRandoms) sb.append("- ").append(j.jar).append(": `").append(h.owner).append('.').append(h.field).append("` (").append(h.type).append(")\n");
        sb.append("\n## Mixin code that writes static state (runs inside vanilla methods, without the mod lock)\n\n");
        for (JarReport j : jars) {
            for (StaticState s : j.mixinStaticState) {
                sb.append("- ").append(j.modIds.isEmpty() ? j.jar : j.modIds.get(0)).append(": `")
                        .append(s.mixin.substring(s.mixin.lastIndexOf('/') + 1)).append('.').append(s.method).append("` on `")
                        .append(s.target.substring(s.target.lastIndexOf('/') + 1)).append("`: ").append(s.what).append('\n');
            }
        }
        sb.append("\n## Static collections modified at runtime (field : declared type <- method that reads it and mutates)\n\n");
        for (JarReport j : jars) for (String c : j.runtimeMutated) sb.append("- ").append(j.modIds.isEmpty() ? j.jar : j.modIds.get(0)).append(": ").append(c).append('\n');
        sb.append("\n## Cross-world calls\n\n");
        for (JarReport j : jars) for (String c : j.crossLevelCalls) sb.append("- ").append(j.jar).append(": ").append(c).append('\n');
        return sb.toString();
    }

    static final class JarReport {
        String jar;
        List<String> modIds = new ArrayList<>();
        List<String> mixinConfigs = new ArrayList<>();
        int mixinCount;
        List<Injection> injections = new ArrayList<>();
        List<Hazard> staticCollections = new ArrayList<>();
        List<Hazard> staticRandoms = new ArrayList<>();
        Set<String> crossLevelCalls = new LinkedHashSet<>();
        Set<String> events = new LinkedHashSet<>();
        List<String> levelSubclasses = new ArrayList<>();
        Set<String> runtimeMutated = new LinkedHashSet<>();
        List<StaticState> mixinStaticState = new ArrayList<>();
        List<String> notes = new ArrayList<>();
    }

    record Injection(String target, String method, String kind, String at, String mixin, String side) {}

    record Hazard(String owner, String field, String type, String signature) {}

    record StaticState(String mixin, String target, String method, String what) {}
}
