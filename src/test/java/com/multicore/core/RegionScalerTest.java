package com.multicore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegionScalerTest {
    private static RegionScaler scaler(int maxThreads) {
        RegionScaler s = new RegionScaler();
        s.configure(new RegionScaler.Settings(true, 40.0, 15.0, 1, maxThreads, 2, 3, 10));
        return s;
    }

    private static String window(RegionScaler s, String dim, double ms, int regions) {
        String msg = null;
        for (int i = 0; i < 10; i++) {
            s.record(dim, ms, regions);
            String m = s.endTick();
            if (m != null) msg = m;
        }
        return msg;
    }

    @Test
    void slowWorldWithManyRegionsGetsMoreThreadsUpToTheLimit() {
        RegionScaler s = scaler(16);
        assertEquals(1, s.threads("w"), "starts on its shared thread, not split");
        assertNotNull(window(s, "w", 60, 1));
        assertEquals(2, s.threads("w"), "too slow: split over two threads");
        assertNotNull(window(s, "w", 60, 20));
        assertEquals(3, s.threads("w"));
        for (int i = 0; i < 20; i++) window(s, "w", 60, 20);
        assertEquals(16, s.threads("w"), "stops at the limit");
    }

    @Test
    void neverMoreThreadsThanRegions() {
        RegionScaler s = scaler(16);
        for (int i = 0; i < 20; i++) window(s, "w", 60, 5);
        assertEquals(5, s.threads("w"));
    }

    @Test
    void slowWorldWithAThreadPerRegionGetsSmallerSquaresThenStops() {
        RegionScaler s = scaler(16);
        window(s, "w", 60, 1); // split over 2
        assertEquals(3, s.shift("w"));
        window(s, "w", 60, 2);
        assertEquals(2, s.shift("w"));
        window(s, "w", 60, 2);
        assertEquals(2, s.shift("w"), "not below the minimum");
    }

    @Test
    void fastWorldGivesThreadsBackThenGrowsSquaresBack() {
        RegionScaler s = scaler(16);
        for (int i = 0; i < 6; i++) window(s, "w", 60, 20);
        int busy = s.threads("w");
        assertTrue(busy > 2);
        window(s, "w", 5, 20);
        assertEquals(busy - 1, s.threads("w"));
        for (int i = 0; i < 30; i++) window(s, "w", 5, 20);
        assertEquals(1, s.threads("w"), "quiet again: back to its shared thread");
        assertEquals(3, s.shift("w"));
    }

    @Test
    void worldInTheMiddleStaysAsItIs() {
        RegionScaler s = scaler(16);
        window(s, "w", 60, 20);
        int t = s.threads("w");
        assertNull(window(s, "w", 25, 20));
        assertEquals(t, s.threads("w"));
    }

    @Test
    void worldsScaleOnTheirOwn() {
        RegionScaler s = scaler(16);
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 10; k++) {
                s.record("busy", 60, 20);
                s.record("calm", 5, 20);
                s.endTick();
            }
        }
        assertTrue(s.threads("busy") > 2);
        assertEquals(1, s.threads("calm"));
    }
}
