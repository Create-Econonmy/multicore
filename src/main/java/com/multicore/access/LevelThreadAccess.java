package com.multicore.access;

/** Implemented by {@code LevelMixin}: lets MultiCore hand {@code Level.thread} to a dimension thread. */
public interface LevelThreadAccess {
    void multicore$setThread(Thread thread);
}
