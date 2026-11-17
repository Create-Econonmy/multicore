package com.multicore.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

/** Which mod a class belongs to, from the module (mod jar) it was loaded from. */
public final class ModOwnership {
    private static final Map<Class<?>, String> BY_CLASS = new ConcurrentHashMap<>();
    private static volatile Map<String, String> moduleToMod;

    private ModOwnership() {
    }

    /** The owning mod id, the module name if the module is not a mod, or "?" if unknown. */
    public static String modIdOf(Class<?> type) {
        return BY_CLASS.computeIfAbsent(type, ModOwnership::lookup);
    }

    private static String lookup(Class<?> type) {
        String module = type.getModule().getName();
        if (module == null) {
            return "?";
        }
        return moduleToMod().getOrDefault(module, module);
    }

    private static Map<String, String> moduleToMod() {
        Map<String, String> map = moduleToMod;
        if (map == null) {
            map = new HashMap<>();
            for (IModFileInfo file : ModList.get().getModFiles()) {
                try {
                    String module = file.getFile().getSecureJar().name();
                    for (IModInfo mod : file.getMods()) {
                        map.putIfAbsent(module, mod.getModId());
                    }
                } catch (RuntimeException ignored) {
                    // A mod file that cannot be inspected; its classes are treated as unknown (unsafe).
                }
            }
            moduleToMod = map;
        }
        return map;
    }
}
