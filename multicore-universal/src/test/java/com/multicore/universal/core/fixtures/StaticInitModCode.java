package com.multicore.universal.core.fixtures;

import java.util.HashMap;
import java.util.Map;

/** A mod class whose static initializer calls (woven) mod code. */
public final class StaticInitModCode {
    public static final Map<String, Integer> TABLE = build();

    private StaticInitModCode() {
    }

    private static Map<String, Integer> build() {
        Map<String, Integer> map = new HashMap<>();
        map.put("a", 1);
        return map;
    }

    public static int size() {
        return TABLE.size();
    }
}
