package com.multicore.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegionGovernorTest {
    private static final String DIM = "minecraft:overworld";

    @BeforeEach
    void setUp() {
        RegionGovernor.setEnabled(true);
    }

    /** Runs one tick the way RegionTicker does and returns whether it split. */
    private boolean tick(long serialNanosPerItem, long splitNanosPerItem) {
        boolean split = RegionGovernor.allowSplit(DIM, false);
        RegionGovernor.record(DIM, false, split, (split ? splitNanosPerItem : serialNanosPerItem) * 1000, 1000);
        return split;
    }

    @Test
    void slowerSplitFallsBackToOneThreadFast() {
        // Live South: 30 ms on one thread, 148 ms split.
        for (int i = 0; i < 40; i++) assertFalse(tick(30, 148), "measures one thread first");
        int splitTicks = 0;
        for (int i = 0; i < 200; i++) if (tick(30, 148)) splitTicks++;
        assertTrue(splitTicks <= 5, "gave up on the slow split after the early check, split " + splitTicks + " times");
        assertTrue(RegionGovernor.describe().values().iterator().next().startsWith("one thread"));
    }

    @Test
    void fasterSplitIsKept() {
        for (int i = 0; i < 40; i++) tick(30, 10);
        int splitTicks = 0;
        for (int i = 0; i < 500; i++) if (tick(30, 10)) splitTicks++;
        assertTrue(splitTicks == 500, "kept splitting, split " + splitTicks);
    }

    @Test
    void barelyFasterSplitIsNotKept() {
        for (int i = 0; i < 40; i++) tick(30, 28);
        for (int i = 0; i < 40; i++) tick(30, 28);
        for (int i = 0; i < 100; i++) assertFalse(tick(30, 28));
    }

    @Test
    void offMeansAlwaysAllowed() {
        RegionGovernor.setEnabled(false);
        for (int i = 0; i < 100; i++) assertTrue(tick(30, 148));
    }
}
