package com.multicore.universal.coremod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.annotation.Nullable;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Everything the coremod and the mixin extension need to know about the installed mods: which classes belong to which
 * mod, where their bytes are, which packages hold their mixins, and which classes must be left alone.
 * <p>
 * Built once, before any game class is loaded.
 */
public final class ModCatalog {
    /** Never locked: vanilla, NeoForge and MultiCore itself are handled by MultiCore's own patches. */
    public static final Set<String> BUILT_IN_SAFE = Set.of("minecraft", "neoforge", "multicore_universal");
    private static final Pattern PACKAGE_FIELD = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PLUGIN_FIELD = Pattern.compile("\"plugin\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern EXCLUSIONS = Pattern.compile("transformExclusions\\s*=\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    @Nullable
    private static volatile ModCatalog instance;

    private final List<String> modIds = new ArrayList<>();
    /** Classes the transformer rewrites, internal name to mod index. */
    private final Map<String, Integer> targets = new HashMap<>();
    /** Every class in every mod file (including vanilla and NeoForge), for reading bytecode later. */
    private final Map<String, Path> classFiles = new HashMap<>();
    /** Internal package prefix ("com/example/mixin/") to mod index, -1 for mods that are not locked. */
    private final List<Map.Entry<String, Integer>> mixinPackages = new ArrayList<>();
    /** Packages of libraries (JDK, launcher layers, nested jars) whose classes are assumed present. */
    private final Set<String> libraryPackages = new HashSet<>();
    private final Map<String, Module> modulePackages = new HashMap<>();
    /** Package of a nested or loose library jar to that jar, for reading class headers on demand. */
    private final Map<String, Path> libraryJars = new HashMap<>();
    private final Map<String, Integer> skippedPerMod = new ConcurrentHashMap<>();
    private int skipped;

    private ModCatalog() {
    }

    @Nullable
    public static ModCatalog get() {
        return instance;
    }

    private static final java.util.concurrent.atomic.AtomicInteger OVERLAY_SLOTS = new java.util.concurrent.atomic.AtomicInteger();
    /** Static fields of mods' own classes kept per dimension thread ("owner/Name.field" to overlay slot). */
    private final Map<String, Integer> perThreadFields = new ConcurrentHashMap<>();

    /** A new slot in MultiCore's StaticOverlay (one per per-thread field). */
    public static int newOverlaySlot() {
        return OVERLAY_SLOTS.getAndIncrement();
    }

    public Map<String, Integer> perThreadFields() {
        return this.perThreadFields;
    }

    private final Set<String> duckInterfaces = new HashSet<>();

    /**
     * True if calling a method of {@code owner} runs woven mod code. Interfaces that mixins add to vanilla classes do
     * not count: their methods are implemented by merged code in the vanilla class (accessors, mostly).
     */
    public boolean isWovenModCode(String owner) {
        return this.modIndexOfTarget(owner) >= 0 && !this.duckInterfaces.contains(owner);
    }

    static void setInstance(ModCatalog catalog) {
        instance = catalog;
    }

    public List<String> modIds() {
        return Collections.unmodifiableList(this.modIds);
    }

    public int targetCount() {
        return this.targets.size();
    }

    public int skippedCount() {
        return this.skipped;
    }

    Set<String> targetNames() {
        return this.targets.keySet();
    }

    /** Mod index of a class the transformer rewrites, or -1. */
    public int modIndexOfTarget(String internalName) {
        Integer index = this.targets.get(internalName);
        return index == null ? -1 : index;
    }

    /**
     * Mod index of the mod that owns a mixin class (dotted or internal name), -1 if that mod is never locked, or -2 if
     * the mixin is not from any known mod.
     */
    public int modIndexOfMixin(String mixinClass) {
        String name = mixinClass.replace('.', '/');
        for (Map.Entry<String, Integer> e : this.mixinPackages) {
            if (name.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return -2;
    }

    /** Bytecode of a class from a mod jar (including vanilla and NeoForge), or null. */
    @Nullable
    public byte[] readClass(String internalName) {
        Path path = this.classFiles.get(internalName);
        if (path == null) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    static ModCatalog build() throws IOException {
        ModCatalog catalog = new ModCatalog();
        Set<String> exclusions = readExclusions();
        catalog.indexModuleLayers();

        List<ModFileInfo> files = LoadingModList.get().getModFiles();
        Map<String, List<String>> candidates = new HashMap<>();
        Map<String, List<String>> mixinClassesByMod = new HashMap<>();
        Set<Path> modPaths = new HashSet<>();
        for (ModFileInfo info : files) {
            ModFile file = info.getFile();
            modPaths.add(file.getFilePath().toAbsolutePath().normalize());
            Path root = file.getSecureJar().getRootPath();
            List<String> ids = info.getMods().stream().map(IModInfo::getModId).toList();
            List<String> classes = listClasses(root, catalog.classFiles);
            catalog.indexNestedJars(root);
            if (file.getType() != IModFile.Type.MOD || ids.isEmpty()) {
                continue;
            }
            boolean locked = ids.stream().noneMatch(id -> BUILT_IN_SAFE.contains(id) || exclusions.contains(id));
            int index = -1;
            if (locked) {
                index = catalog.modIds.size();
                catalog.modIds.add(ids.get(0));
            }
            List<String> mixinPrefixes = new ArrayList<>();
            for (var config : file.getMixinConfigs()) {
                String json = readMixinConfig(root, config.config());
                String pkg = field(json, PACKAGE_FIELD);
                if (pkg != null) {
                    String prefix = pkg.replace('.', '/') + "/";
                    mixinPrefixes.add(prefix);
                    catalog.mixinPackages.add(Map.entry(prefix, index));
                }
                String plugin = field(json, PLUGIN_FIELD);
                if (plugin != null) {
                    catalog.mixinPlugins.add(plugin.replace('.', '/'));
                }
            }
            if (locked && Files.exists(root.resolve("module-info.class"))) {
                // An explicit Java module only reads the modules it declares, so woven calls into MultiCore would fail
                // with IllegalAccessError. Its mixin code (merged into vanilla classes) is still locked.
                UniversalCoreMod.LOGGER.warn("[MultiCore Universal] {} is an explicit Java module; its own classes are not "
                        + "locked (only its mixins are)", ids.get(0));
                continue;
            }
            if (locked) {
                List<String> mine = new ArrayList<>();
                List<String> mixins = new ArrayList<>();
                for (String name : classes) {
                    if (mixinPrefixes.stream().noneMatch(name::startsWith)) {
                        mine.add(name);
                    } else {
                        mixins.add(name);
                    }
                }
                candidates.put(ids.get(0), mine);
                mixinClassesByMod.put(ids.get(0), mixins);
            }
        }
        catalog.indexLooseLibraryJars(modPaths);
        // Longest prefix first, so nested mixin packages resolve to the right mod.
        catalog.mixinPackages.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        // Classes whose frames cannot be computed (they reference classes that are not installed, typically optional
        // integrations) would fail to load if they were transformed, so they are left alone and logged.
        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < catalog.modIds.size(); i++) {
            indexOf.put(catalog.modIds.get(i), i);
        }
        Map<String, Integer> targets = new ConcurrentHashMap<>();
        candidates.entrySet().parallelStream().forEach(e -> {
            int index = indexOf.get(e.getKey());
            for (String name : e.getValue()) {
                if (catalog.isSafeToTransform(name)) {
                    targets.put(name, index);
                } else {
                    catalog.skippedPerMod.merge(e.getKey(), 1, Integer::sum);
                }
            }
        });
        catalog.targets.putAll(targets);
        int loaderCode = catalog.excludeLoaderCode(candidates);
        if (loaderCode > 0) {
            UniversalCoreMod.LOGGER.info("[MultiCore Universal] {} classes run inside class loading (launch plugins, transformers, "
                    + "mixin plugins and their helpers) and are left unlocked: {}", loaderCode, catalog.loaderCodeRoots);
        }
        // Interfaces mixins add to vanilla classes ("duck" interfaces): calling one runs vanilla-side accessor code.
        for (List<String> mixins : mixinClassesByMod.values()) {
            for (String mixin : mixins) {
                try {
                    for (String iface : catalog.header(mixin).interfaces()) {
                        if (catalog.classFiles.containsKey(iface) && !iface.startsWith("net/minecraft/")) {
                            catalog.duckInterfaces.add(iface);
                        }
                    }
                } catch (MissingClassException ignored) {
                    // not readable: nothing to learn from it
                }
            }
        }
        // Flags in mods' own classes that their hooks set and clear around a vanilla method: per dimension thread.
        for (Map.Entry<String, List<String>> e : mixinClassesByMod.entrySet()) {
            try {
                Set<String> fields = new ModScopedStatics(catalog).find(e.getValue(), candidates.getOrDefault(e.getKey(), List.of()));
                for (String field : new java.util.TreeSet<>(fields)) {
                    catalog.perThreadFields.put(field, newOverlaySlot());
                }
                if (!fields.isEmpty()) {
                    UniversalCoreMod.LOGGER.info("[MultiCore Universal] {}: {} static fields its hooks use as scoped flags are kept "
                            + "per dimension thread", e.getKey(), fields.size());
                }
            } catch (RuntimeException ex) {
                UniversalCoreMod.LOGGER.warn("[MultiCore Universal] Could not analyse the static fields of {}", e.getKey(), ex);
            }
        }
        catalog.skipped = catalog.skippedPerMod.values().stream().mapToInt(Integer::intValue).sum();
        if (catalog.skipped > 0) {
            UniversalCoreMod.LOGGER.info("[MultiCore Universal] Classes left unlocked per mod (they use classes that do not exist "
                    + "on this side, usually client-only code or optional integrations): {}", new java.util.TreeMap<>(catalog.skippedPerMod));
            UniversalCoreMod.LOGGER.info("[MultiCore Universal] Most common missing packages: {}", catalog.topMissingPackages(20));
            if (catalog.firstUnexpected != null) {
                UniversalCoreMod.LOGGER.warn("[MultiCore Universal] First unexpected probe failure", catalog.firstUnexpected);
            }
        }
        if (!exclusions.isEmpty()) {
            UniversalCoreMod.LOGGER.info("[MultiCore Universal] Not transformed (transformExclusions): {}", exclusions);
        }
        return catalog;
    }

    /**
     * Types whose implementations run inside class loading and transformation (ModLauncher launch plugins and
     * transformers, Mixin plugins and extensions, ASM visitors, FML loading services). Such code must never wait for
     * the mod lock: the waiting thread may hold class loading locks another thread needs, and a lock wait can itself
     * load classes, which would run the same code again.
     */
    private static final List<String> LOADER_PREFIXES = List.of("cpw/mods/modlauncher/", "cpw/mods/cl/", "cpw/mods/jarhandling/",
            "org/spongepowered/asm/", "org/objectweb/asm/", "net/neoforged/fml/loading/", "net/neoforged/neoforgespi/",
            "net/neoforged/coremod/");
    final List<String> loaderCodeRoots = new ArrayList<>();
    /** Config plugin classes named in mods' mixin configs ("plugin"). */
    private final Set<String> mixinPlugins = new HashSet<>();

    /** Removes loader code (see {@link #LOADER_PREFIXES}) and every class of the same mod it refers to from the targets. */
    int excludeLoaderCode(Map<String, List<String>> candidates) {
        int removed = 0;
        for (Map.Entry<String, List<String>> e : candidates.entrySet()) {
            Set<String> own = new HashSet<>(e.getValue());
            List<String> todo = new ArrayList<>();
            for (String name : e.getValue()) {
                if (this.targets.containsKey(name) && (this.mixinPlugins.contains(name) || this.isLoaderCode(name))) {
                    todo.add(name);
                    this.loaderCodeRoots.add(name.substring(name.lastIndexOf('/') + 1));
                }
            }
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String name = todo.remove(todo.size() - 1);
                if (!seen.add(name)) {
                    continue;
                }
                byte[] bytes = this.readClass(name);
                if (bytes == null) {
                    continue;
                }
                for (String ref : referencedClasses(bytes)) {
                    if (own.contains(ref) && !seen.contains(ref)) {
                        todo.add(ref);
                    }
                }
            }
            for (String name : seen) {
                if (this.targets.remove(name) != null) {
                    removed++;
                    this.skippedPerMod.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }
        return removed;
    }

    /**
     * True if any super class or interface, at any depth, is a loader type: mods often extend a library's or their own
     * base plugin ({@code SableMixinPlugin extends AbstractSableMixinPlugin implements IMixinConfigPlugin}).
     */
    boolean isLoaderCode(String name) {
        Set<String> seen = new HashSet<>();
        List<String> todo = new ArrayList<>();
        todo.add(name);
        while (!todo.isEmpty()) {
            String type = todo.remove(todo.size() - 1);
            if (!seen.add(type)) {
                continue;
            }
            if (!type.equals(name) && LOADER_PREFIXES.stream().anyMatch(type::startsWith)) {
                return true;
            }
            if (type.startsWith("java/")) {
                continue;
            }
            try {
                Header h = this.header(type);
                if (h.superName() != null) {
                    todo.add(h.superName());
                }
                Collections.addAll(todo, h.interfaces());
            } catch (MissingClassException ignored) {
                // not readable here: its own super types stay unknown
            }
        }
        return false;
    }

    /** Names of all CONSTANT_Class entries (array element types included). */
    static Set<String> referencedClasses(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        char[] buf = new char[Math.max(reader.getMaxStringLength(), 16)];
        Set<String> out = new HashSet<>();
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset != 0 && reader.readByte(offset - 1) == 7) {
                String name = reader.readUTF8(offset, buf);
                if (name.startsWith("[")) {
                    int l = name.indexOf('L');
                    if (l < 0) {
                        continue;
                    }
                    name = name.substring(l + 1, name.length() - 1);
                }
                out.add(name);
            }
        }
        return out;
    }

    private static List<String> listClasses(Path root, Map<String, Path> classFiles) throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.endsWith(".class") || rel.startsWith("META-INF/") || rel.endsWith("module-info.class")
                        || rel.endsWith("package-info.class")) {
                    return;
                }
                String name = rel.substring(0, rel.length() - ".class".length());
                classFiles.putIfAbsent(name, p);
                names.add(name);
            });
        }
        return names;
    }

    @Nullable
    private static String readMixinConfig(Path root, String config) {
        try {
            Path json = root.resolve(config);
            return Files.exists(json) ? Files.readString(json, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    static String field(@Nullable String json, Pattern field) {
        if (json == null) {
            return null;
        }
        Matcher m = field.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static Set<String> readExclusions() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Path config = FMLPaths.CONFIGDIR.get().resolve("multicore_universal-common.toml");
            if (Files.exists(config)) {
                Matcher m = EXCLUSIONS.matcher(Files.readString(config, StandardCharsets.UTF_8));
                if (m.find()) {
                    Matcher q = QUOTED.matcher(m.group(1));
                    while (q.find()) {
                        if (!q.group(1).isBlank()) {
                            out.add(q.group(1).trim());
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            UniversalCoreMod.LOGGER.warn("[MultiCore Universal] Could not read transformExclusions", e);
        }
        String prop = System.getProperty("multicore_universal.transformExclusions");
        if (prop != null) {
            for (String id : prop.split(",")) {
                if (!id.isBlank()) {
                    out.add(id.trim());
                }
            }
        }
        return out;
    }

    /** The JDK and the launcher's library layers (ours and its parents). */
    private void indexModuleLayers() {
        Set<ModuleLayer> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<ModuleLayer> todo = new ArrayList<>();
        ModuleLayer own = ModCatalog.class.getModule().getLayer();
        todo.add(own != null ? own : ModuleLayer.boot());
        todo.add(ModuleLayer.boot());
        while (!todo.isEmpty()) {
            ModuleLayer layer = todo.remove(todo.size() - 1);
            if (!seen.add(layer)) {
                continue;
            }
            for (Module module : layer.modules()) {
                for (String pkg : module.getPackages()) {
                    String internal = pkg.replace('.', '/');
                    this.libraryPackages.add(internal);
                    this.modulePackages.putIfAbsent(internal, module);
                }
            }
            todo.addAll(layer.parents());
        }
    }

    /** Libraries shipped inside mod jars (jar-in-jar); they end up in the game layer. */
    private void indexNestedJars(Path root) {
        Path dir = root.resolve("META-INF/jarjar");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> p.toString().endsWith(".jar")).forEach(this::indexLibraryJar);
        } catch (IOException ignored) {
            // Unreadable: its classes count as missing, which only makes the check stricter.
        }
    }

    /** Jars in the mods folder that are libraries rather than mods. */
    private void indexLooseLibraryJars(Set<Path> modPaths) {
        try {
            Path mods = FMLPaths.MODSDIR.get();
            if (!Files.isDirectory(mods)) {
                return;
            }
            try (Stream<Path> list = Files.list(mods)) {
                list.filter(p -> p.toString().endsWith(".jar") && !modPaths.contains(p.toAbsolutePath().normalize()))
                        .forEach(this::indexLibraryJar);
            }
        } catch (IOException | RuntimeException ignored) {
            // As above.
        }
    }

    private void indexLibraryJar(Path jar) {
        try (InputStream in = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                    int slash = name.lastIndexOf('/');
                    if (slash > 0) {
                        String pkg = name.substring(0, slash);
                        this.libraryPackages.add(pkg);
                        this.libraryJars.putIfAbsent(pkg, jar);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // As above.
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private boolean isKnown(String internalName) {
        if (this.classFiles.containsKey(internalName)) {
            return !this.isOtherDistOnly(internalName) && this.hierarchyAvailable(internalName);
        }
        int slash = internalName.lastIndexOf('/');
        if (slash > 0 && this.libraryPackages.contains(internalName.substring(0, slash))) {
            return true;
        }
        // Libraries on the plain classpath (development environments).
        return this.classpathClasses.computeIfAbsent(internalName,
                n -> ClassLoader.getSystemClassLoader().getResource(n + ".class") != null);
    }

    private final Map<String, Boolean> classpathClasses = new ConcurrentHashMap<>();
    private final Map<String, Boolean> otherDistOnly = new ConcurrentHashMap<>();
    /** On a dedicated server, client classes are stripped (production jars do not even contain them). */
    private final boolean dedicatedServer = isDedicatedServer();

    private static boolean isDedicatedServer() {
        try {
            // Reflection keeps the compile classpath free of the distmarker API.
            Object dist = net.neoforged.fml.loading.FMLLoader.class.getMethod("getDist").invoke(null);
            return !"CLIENT".equals(String.valueOf(dist));
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * True for classes that do not exist in this environment although their bytes are in a jar: Minecraft's client
     * packages and classes marked {@code @OnlyIn} for the other side. NeoForge refuses to load them, so frame
     * computation that needs them fails.
     */
    private boolean isOtherDistOnly(String internalName) {
        if (this.dedicatedServer && (internalName.startsWith("net/minecraft/client/") || internalName.startsWith("com/mojang/blaze3d/"))) {
            return true;
        }
        return this.otherDistOnly.computeIfAbsent(internalName, n -> {
            Path path = this.classFiles.get(n);
            if (path == null) {
                return false;
            }
            try {
                return markedForOtherDist(Files.readAllBytes(path), this.dedicatedServer);
            } catch (IOException | RuntimeException e) {
                return false;
            }
        });
    }

    static boolean markedForOtherDist(byte[] bytes, boolean dedicatedServer) {
        String other = dedicatedServer ? "CLIENT" : "DEDICATED_SERVER";
        boolean[] found = {false};
        new ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!desc.equals("Lnet/neoforged/api/distmarker/OnlyIn;")) {
                    return null;
                }
                return new org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public void visitEnum(String name, String enumDesc, String value) {
                        if ("value".equals(name) && other.equals(value)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /**
     * True unless the class refers to classes that are not installed. Those classes are run through the same frame
     * computation ModLauncher performs on transformed classes; if it cannot resolve a type there, the class would fail
     * to load, so it is not transformed.
     */
    boolean isSafeToTransform(String name) {
        byte[] bytes = this.readClass(name);
        if (bytes == null || this.isOtherDistOnly(name)) {
            this.noteMissing("(not loadable here)");
            return false;
        }
        // ModLauncher resolves the class's own super types as soon as frame computation compares two types.
        if (!this.hierarchyAvailable(name)) {
            this.noteMissing("(extends a class that does not exist here)");
            return false;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            if (this.allReferencesKnown(reader)) {
                return true;
            }
            // Probe the woven class: the lock's catch-all handler adds merge points the original code does not have.
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            reader.accept(node, ClassReader.SKIP_FRAMES);
            // Every class of a mod counts as mod code here (the target list is not known yet): weaves at least as
            // much as the real transformer will, so the probe is never less strict.
            LockWeaver.weaveClass(node, 0, owner -> this.classFiles.containsKey(owner) && !owner.startsWith("net/minecraft/")
                    && !owner.startsWith("net/neoforged/"));
            node.accept(new ProbeWriter(this));
            return true;
        } catch (MissingClassException e) {
            this.noteMissing(e.getMessage());
            return false;
        } catch (Throwable t) {
            this.noteMissing("(" + t.getClass().getSimpleName() + ")");
            if (this.firstUnexpected == null) {
                this.firstUnexpected = new IllegalStateException("probing " + name, t);
            }
            return false;
        }
    }

    /**
     * For the mixin extension: true if wrapping {@code method} of {@code owner} in one more lock layer keeps the class's
     * frames computable. The catch-all handler merges the types of every local, which may include classes that do not
     * exist on this side (client-only code in a common mixin); ModLauncher would then fail to write the class.
     */
    public boolean wrapIsSafe(org.objectweb.asm.tree.ClassNode owner, org.objectweb.asm.tree.MethodNode method) {
        try {
            org.objectweb.asm.tree.ClassNode mini = new org.objectweb.asm.tree.ClassNode();
            mini.version = owner.version;
            mini.access = owner.access;
            mini.name = owner.name;
            mini.superName = owner.superName;
            mini.interfaces = owner.interfaces;
            org.objectweb.asm.tree.MethodNode copy = copyOf(method);
            mini.methods.add(copy);
            LockWeaver.wrapAgain(mini, copy, 0);
            mini.accept(new ProbeWriter(this));
            return true;
        } catch (MissingClassException e) {
            this.noteMissing(e.getMessage());
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Deep copy of a method's code (labels are cloned, so the original is never touched). */
    static org.objectweb.asm.tree.MethodNode copyOf(org.objectweb.asm.tree.MethodNode method) {
        org.objectweb.asm.tree.MethodNode copy = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ASM9, method.access,
                method.name, method.desc, method.signature, method.exceptions == null ? null : method.exceptions.toArray(new String[0]));
        Map<org.objectweb.asm.tree.LabelNode, org.objectweb.asm.tree.LabelNode> labels = new HashMap<>();
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof org.objectweb.asm.tree.LabelNode label) {
                labels.put(label, new org.objectweb.asm.tree.LabelNode());
            }
        }
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            copy.instructions.add(insn.clone(labels));
        }
        if (method.tryCatchBlocks != null) {
            for (var tcb : method.tryCatchBlocks) {
                copy.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(labels.get(tcb.start), labels.get(tcb.end),
                        labels.get(tcb.handler), tcb.type));
            }
        }
        copy.maxStack = method.maxStack;
        copy.maxLocals = method.maxLocals;
        return copy;
    }

    private final Map<String, Integer> missingPackages = new ConcurrentHashMap<>();
    private final Map<String, Boolean> hierarchyOk = new ConcurrentHashMap<>();
    @Nullable
    volatile Throwable firstUnexpected;

    /** True if the class and all of its super classes and interfaces can be loaded here. */
    boolean hierarchyAvailable(String name) {
        Boolean cached = this.hierarchyOk.get(name);
        if (cached != null) {
            return cached;
        }
        boolean ok;
        try {
            this.supersOf(name);
            ok = true;
        } catch (MissingClassException e) {
            ok = false;
        }
        this.hierarchyOk.put(name, ok);
        return ok;
    }

    /** The class itself plus every super class and interface, transitively. */
    Set<String> supersOf(String name) {
        Set<String> out = new HashSet<>();
        List<String> todo = new ArrayList<>();
        todo.add(name);
        while (!todo.isEmpty()) {
            String n = todo.remove(todo.size() - 1);
            if (!out.add(n)) {
                continue;
            }
            Header h = this.header(n);
            if (h.superName() != null) {
                todo.add(h.superName());
            }
            Collections.addAll(todo, h.interfaces());
        }
        return out;
    }

    private void noteMissing(String name) {
        int slash = name.lastIndexOf('/');
        this.missingPackages.merge(slash > 0 ? name.substring(0, slash) : name, 1, Integer::sum);
    }

    /** "package (count)" for the packages that made classes unsafe to transform, most frequent first. */
    List<String> topMissingPackages(int limit) {
        return this.missingPackages.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .toList();
    }

    private boolean allReferencesKnown(ClassReader reader) {
        char[] buf = new char[Math.max(reader.getMaxStringLength(), 16)];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0) {
                continue;
            }
            int tag = reader.readByte(offset - 1);
            if (tag == 7) {
                String name = reader.readUTF8(offset, buf); // CONSTANT_Class: u2 index of its name
                if (name.startsWith("[") ? !descriptorTypesKnown(name) : !this.isKnown(name)) {
                    return false;
                }
            } else if (tag == 1) {
                String s = readUtf8(reader, offset);
                if (!s.isEmpty() && (s.charAt(0) == '(' || s.charAt(0) == 'L' || s.charAt(0) == '[') && !descriptorTypesKnown(s)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Checks every {@code Lname;} (or {@code Lname<}) in a descriptor or signature. */
    private boolean descriptorTypesKnown(String s) {
        int i = 0;
        while ((i = s.indexOf('L', i)) >= 0) {
            int end = i + 1;
            while (end < s.length() && s.charAt(end) != ';' && s.charAt(end) != '<') {
                char c = s.charAt(end);
                if (c == '(' || c == ')' || c == ' ' || c == '[' || c == ':' || c == '.') {
                    break;
                }
                end++;
            }
            if (end < s.length() && (s.charAt(end) == ';' || s.charAt(end) == '<') && end > i + 1) {
                String name = s.substring(i + 1, end);
                if (name.indexOf('/') > 0 && !this.isKnown(name)) {
                    return false;
                }
            }
            i = end;
        }
        return true;
    }

    private static String readUtf8(ClassReader reader, int offset) {
        int length = reader.readUnsignedShort(offset);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int b = reader.readByte(offset + 2 + i) & 0xFF;
            sb.append(b < 0x80 ? (char) b : '?');
        }
        return sb.toString();
    }

    // ---- class hierarchy, for the frame computation probe ----

    private final Map<String, Header> headers = new ConcurrentHashMap<>();

    record Header(String superName, String[] interfaces, boolean isInterface) {
    }

    static final class MissingClassException extends RuntimeException {
        MissingClassException(String name) {
            super(name, null, false, false);
        }
    }

    Header header(String name) {
        Header h = this.headers.get(name);
        if (h != null) {
            return h;
        }
        if (this.classFiles.containsKey(name) && this.isOtherDistOnly(name)) {
            throw new MissingClassException(name);
        }
        byte[] bytes = this.readClass(name);
        if (bytes == null) {
            bytes = this.readLibraryClass(name);
        }
        if (bytes == null) {
            throw new MissingClassException(name);
        }
        ClassReader r = new ClassReader(bytes);
        h = new Header(r.getSuperName(), r.getInterfaces(), (r.getAccess() & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0);
        this.headers.put(name, h);
        return h;
    }

    @Nullable
    private byte[] readLibraryClass(String name) {
        int slash = name.lastIndexOf('/');
        String pkg = slash > 0 ? name.substring(0, slash) : "";
        Module module = this.modulePackages.get(pkg);
        if (module != null) {
            try (InputStream in = module.getResourceAsStream(name + ".class")) {
                if (in != null) {
                    return in.readAllBytes();
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        if (pkg.startsWith("java/") || pkg.startsWith("javax/") || pkg.startsWith("jdk/") || pkg.startsWith("sun/")) {
            try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
                if (in != null) {
                    return in.readAllBytes();
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        try (InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream(name + ".class")) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignored) {
            // fall through
        }
        Path jar = this.libraryJars.get(pkg);
        if (jar != null) {
            try (InputStream in = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.getName().equals(name + ".class")) {
                        return zip.readAllBytes();
                    }
                }
            } catch (IOException ignored) {
                // missing
            }
        }
        return null;
    }

    /** Same algorithm as ModLauncher's frame computation, but failing (instead of crashing) on missing classes. */
    static final class ProbeWriter extends ClassWriter {
        private final ModCatalog catalog;

        ProbeWriter(ModCatalog catalog) {
            super(ClassWriter.COMPUTE_FRAMES);
            this.catalog = catalog;
        }

        @Override
        protected String getCommonSuperClass(String a, String b) {
            Set<String> supersOfB = this.supers(b);
            if (supersOfB.contains(a)) {
                return a;
            }
            Set<String> supersOfA = this.supers(a);
            if (supersOfA.contains(b)) {
                return b;
            }
            if (this.catalog.header(a).isInterface() || this.catalog.header(b).isInterface()) {
                return "java/lang/Object";
            }
            String c = a;
            while (c != null && !supersOfB.contains(c)) {
                c = this.catalog.header(c).superName();
            }
            return c == null ? "java/lang/Object" : c;
        }

        private Set<String> supers(String name) {
            return this.catalog.supersOf(name);
        }

        @Override
        protected ClassLoader getClassLoader() {
            throw new UnsupportedOperationException("never loads classes");
        }
    }

    /** For tests: a catalog over explicit class bytes. */
    static ModCatalog forTest(Map<String, byte[]> classes, Map<String, Integer> targets, List<String> modIds) {
        ModCatalog catalog = new ModCatalog();
        catalog.modIds.addAll(modIds);
        catalog.targets.putAll(targets);
        catalog.indexModuleLayers();
        classes.forEach((name, bytes) -> {
            try {
                Path tmp = Files.createTempFile("mcu", ".class");
                Files.write(tmp, bytes);
                tmp.toFile().deleteOnExit();
                catalog.classFiles.put(name, tmp);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return catalog;
    }

    static boolean probeForTest(ModCatalog catalog, byte[] bytes) {
        try {
            new ClassReader(new ByteArrayInputStream(bytes).readAllBytes()).accept(new ProbeWriter(catalog), ClassReader.SKIP_FRAMES);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
