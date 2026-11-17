package com.multicore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoAdjustTest {
    private static final AutoAdjust.Settings S = new AutoAdjust.Settings(true, 0.5, 2.0, 10.0, 1.05, 100, 6, 1000, 4000);
    private AutoAdjust auto;
    private long tick;

    @BeforeEach
    void setUp() {
        this.auto = new AutoAdjust();
        this.auto.configure(S);
        this.tick = 0;
    }

    /** One server tick: south busy on its own thread, the end idle, with the given parallel wall and work time. */
    private String tick(double southMs, double endMs, int endPlayers, double wallMs, double workMs) {
        this.auto.recordRegion("south", southMs, 5);
        this.auto.recordRegion("end", endMs, endPlayers);
        if (wallMs > 0) {
            this.auto.recordParallelPhase(wallMs, workMs);
        }
        return this.auto.endTick(this.tick++);
    }

    private String window(double southMs, double endMs, int endPlayers, double wallMs, double workMs) {
        String last = null;
        for (int i = 0; i < S.windowTicks(); i++) {
            String msg = this.tick(southMs, endMs, endPlayers, wallMs, workMs);
            if (msg != null) last = msg;
        }
        return last;
    }

    @Test
    void idleDimensionMovesToMainThreadAfterOneWindow() {
        this.window(20, 0.1, 0, 20, 25);
        assertTrue(this.auto.isIdle("end"));
        assertFalse(this.auto.isIdle("south"), "a dimension with players is never idle");
    }

    @Test
    void idleDimensionGetsItsThreadBackAtOnceWhenAPlayerEnters() {
        this.window(20, 0.1, 0, 20, 25);
        assertTrue(this.auto.isIdle("end"));
        this.tick(20, 0.1, 1, 20, 25);
        assertFalse(this.auto.isIdle("end"));
    }

    @Test
    void idleDimensionGetsItsThreadBackWhenItGetsBusy() {
        this.window(20, 0.1, 0, 20, 25);
        assertTrue(this.auto.isIdle("end"));
        this.window(20, 1.0, 0, 20, 25);
        assertTrue(this.auto.isIdle("end"), "between idleMs and busyMs nothing changes (hysteresis)");
        this.window(20, 5.0, 0, 20, 25);
        assertFalse(this.auto.isIdle("end"));
    }

    @Test
    void noGainUnderLoadFallsBackToOneThreadAndRetriesWithBackoff() {
        for (int w = 0; w < 5; w++) {
            this.window(30, 3, 0, 30, 30);
            assertFalse(this.auto.singleThreadNow(this.tick), "needs 6 bad windows in a row, had " + (w + 1));
        }
        String msg = this.window(30, 3, 0, 30, 30);
        assertNotNull(msg);
        assertTrue(this.auto.singleThreadNow(this.tick));
        assertEquals(999, this.auto.singleThreadTicksLeft(this.tick));

        for (int i = 0; i < 1000; i++) this.auto.endTick(this.tick++);
        assertFalse(this.auto.singleThreadNow(this.tick), "retries after retryTicks");

        for (int w = 0; w < 6; w++) this.window(30, 3, 0, 30, 30);
        assertEquals(1999, this.auto.singleThreadTicksLeft(this.tick), "second failure waits twice as long");
    }

    @Test
    void goodSpeedupResetsTheBackoff() {
        for (int w = 0; w < 6; w++) this.window(30, 3, 0, 30, 30);
        for (int i = 0; i < 1000; i++) this.auto.endTick(this.tick++);
        this.window(30, 3, 0, 20, 33);
        for (int w = 0; w < 6; w++) this.window(30, 3, 0, 30, 30);
        assertEquals(999, this.auto.singleThreadTicksLeft(this.tick));
    }

    @Test
    void aBadWindowInBetweenIsForgottenAfterAGoodOne() {
        for (int w = 0; w < 5; w++) this.window(30, 3, 0, 30, 30);
        this.window(30, 3, 0, 20, 40);
        for (int w = 0; w < 5; w++) this.window(30, 3, 0, 30, 30);
        assertFalse(this.auto.singleThreadNow(this.tick));
    }

    @Test
    void quietServerNeverFallsBack() {
        for (int w = 0; w < 20; w++) this.window(2, 0.1, 0, 2, 1);
        assertFalse(this.auto.singleThreadNow(this.tick));
    }

    @Test
    void disabledChangesNothing() {
        this.auto.configure(new AutoAdjust.Settings(false, 0.5, 2.0, 10.0, 1.05, 100, 6, 1000, 4000));
        for (int w = 0; w < 10; w++) this.window(30, 0.1, 0, 30, 30);
        assertFalse(this.auto.isIdle("end"));
        assertFalse(this.auto.singleThreadNow(this.tick));
    }
}
