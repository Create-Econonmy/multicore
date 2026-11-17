package com.multicore.universal.coremod.fixtures;

import java.util.List;

/** An enum like Create's RollingMode, which NeoForge's enum extender may rewrite. */
public enum Mode {
    FIRST, SECOND, THIRD;

    public int count(int x) {
        return List.of(x, x).size() + this.ordinal();
    }
}
