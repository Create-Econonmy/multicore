package com.multicore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoAdjustTest {
    /** 5 s windows; acts after 30 s (600 ticks) of server ticks over 50 ms. */
    private static final AutoAdjust.Settings S = new AutoAdjust.Settings(true, 0.5, 2.0, 50.0, 1.05, 100, 600, 1000, 4000);
    private static final double LAGGING = 60;
    private static final double FINE = 30;
    private AutoAdjust auto;
    private long tick;

    @BeforeEach
    void setUp() {
        this.auto = new AutoAdjust();
        this.auto.configure(S);
        this.tick = 0;
    }

    /** One server tick: south busy on its own thread, the end idle, with the given parallel wall and work time. */
    private String tick(double serverMs, double endMs, int endPlayers, double wallMs, double workMs) {
        this.auto.recordRegion("south", 20, 5);
        this.auto.recordRegion("end", endMs, endPlayers);
        if (wallMs > 0) {
            this.auto.recordParallelPhase(wallMs, workMs);
        }
        return this.auto.endTick(this.tick++, serverMs);
    }

    private String window(double serverMs, double endMs, int endPlayers, double wallMs, double workMs) {
        String last = null;
        for (int i = 0; i < S.windowTicks(); i++) {
            String msg = this.tick(serverMs, endMs, endPlayers, wallMs, workMs);
            if (msg != null) last = msg;
        }
        return last;
    }

    private void windows(int n, double serverMs, double wallMs, double workMs) {
        for (int w = 0; w < n; w++) this.window(serverMs, 3, 0, wallMs, workMs);
    }

    @Test
    void nothingChangesWhileTpsIsFine() {
        for (int w = 0; w < 50; w++) this.window(FINE, 0.1, 0, 30, 30);
        assertFalse(this.auto.isIdle("end"), "TPS 20: idle dimensions stay where they are");
        assertFalse(this.auto.singleThreadNow(this.tick), "TPS 20: no fallback even without a speedup");
    }

    @Test
    void idleDimensionMovesToMainThreadOnlyAfter30SecondsUnder20Tps() {
        for (int w = 0; w < 5; w++) {
            this.window(LAGGING, 0.1, 0, 40, 60);
            assertFalse(this.auto.isIdle("end"), "only " + (w + 1) * 5 + " s of low TPS");
        }
        this.window(LAGGING, 0.1, 0, 40, 60);
        assertTrue(this.auto.isIdle("end"));
        assertFalse(this.auto.isIdle("south"), "a dimension with players is never idle");
    }

    @Test
    void aGoodWindowRestartsThe30Seconds() {
        for (int w = 0; w < 5; w++) this.window(LAGGING, 0.1, 0, 40, 60);
        this.window(FINE, 0.1, 0, 40, 60);
        for (int w = 0; w < 5; w++) this.window(LAGGING, 0.1, 0, 40, 60);
        assertFalse(this.auto.isIdle("end"));
        this.window(LAGGING, 0.1, 0, 40, 60);
        assertTrue(this.auto.isIdle("end"));
    }

    @Test
    void idleDimensionGetsItsThreadBackAtOnceWhenAPlayerEnters() {
        for (int w = 0; w < 6; w++) this.window(LAGGING, 0.1, 0, 40, 60);
        assertTrue(this.auto.isIdle("end"));
        this.tick(FINE, 0.1, 1, 40, 60);
        assertFalse(this.auto.isIdle("end"));
    }

    @Test
    void idleDimensionGetsItsThreadBackWhenItGetsBusyEvenAtGoodTps() {
        for (int w = 0; w < 6; w++) this.window(LAGGING, 0.1, 0, 40, 60);
        assertTrue(this.auto.isIdle("end"));
        this.window(FINE, 1.0, 0, 40, 60);
        assertTrue(this.auto.isIdle("end"), "between idleMs and busyMs nothing changes (hysteresis)");
        this.window(FINE, 5.0, 0, 40, 60);
        assertFalse(this.auto.isIdle("end"));
    }

    @Test
    void noGainFor30SecondsUnder20TpsFallsBackAndRetriesWithBackoff() {
        for (int w = 0; w < 5; w++) {
            this.window(LAGGING, 3, 0, 40, 40);
            assertFalse(this.auto.singleThreadNow(this.tick), "needs 30 s under 20 TPS, had " + (w + 1) * 5);
        }
        String msg = this.window(LAGGING, 3, 0, 40, 40);
        assertNotNull(msg);
        assertTrue(msg.contains("TPS under 20 for 30 s"), msg);
        assertTrue(this.auto.singleThreadNow(this.tick));
        assertEquals(999, this.auto.singleThreadTicksLeft(this.tick));

        for (int i = 0; i < 1000; i++) this.auto.endTick(this.tick++, LAGGING);
        assertFalse(this.auto.singleThreadNow(this.tick), "retries after retryTicks");

        this.windows(5, LAGGING, 40, 40);
        assertFalse(this.auto.singleThreadNow(this.tick), "after a retry it waits another 30 s of low TPS");
        this.windows(1, LAGGING, 40, 40);
        assertEquals(999 * 2 + 1, this.auto.singleThreadTicksLeft(this.tick), "second failure waits twice as long");
    }

    @Test
    void goodSpeedupUnderLoadNeverFallsBackAndResetsTheBackoff() {
        this.windows(6, LAGGING, 40, 40);
        for (int i = 0; i < 1000; i++) this.auto.endTick(this.tick++, LAGGING);
        this.windows(6, LAGGING, 40, 60);
        assertFalse(this.auto.singleThreadNow(this.tick), "1.5x faster: keep the threads");
        this.window(FINE, 3, 0, 40, 40);
        this.windows(6, LAGGING, 40, 40);
        assertEquals(999, this.auto.singleThreadTicksLeft(this.tick), "the good stretch reset the wait");
    }

    @Test
    void slowParallelTickWithGoodTpsNeverFallsBack() {
        // The old rule looked at the parallel phase alone; now only the server's real TPS counts.
        this.windows(20, 45, 45, 45);
        assertFalse(this.auto.singleThreadNow(this.tick));
    }

    @Test
    void disabledChangesNothing() {
        this.auto.configure(new AutoAdjust.Settings(false, 0.5, 2.0, 50.0, 1.05, 100, 600, 1000, 4000));
        for (int w = 0; w < 10; w++) this.window(LAGGING, 0.1, 0, 40, 40);
        assertFalse(this.auto.isIdle("end"));
        assertFalse(this.auto.singleThreadNow(this.tick));
    }
}
