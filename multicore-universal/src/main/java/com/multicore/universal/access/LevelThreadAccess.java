package com.multicore.universal.access;

/** Implemented by {@code LevelMixin}: lets MultiCore hand {@code Level.thread} to a dimension thread. */
public interface LevelThreadAccess {
    void mcu$setThread(Thread thread);
}
