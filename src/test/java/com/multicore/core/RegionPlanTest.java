package com.multicore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegionPlanTest {
    private static RegionPlan plan(int[][] chunks, int maxJobs) {
        int[] x = new int[chunks.length];
        int[] z = new int[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            x[i] = chunks[i][0];
            z[i] = chunks[i][1];
        }
        return RegionPlan.plan(x, z, chunks.length, 3, maxJobs);
    }

    @Test
    void oneGroupIsNotSplit() {
        assertNull(plan(new int[][] {{0, 0}, {1, 1}, {7, 7}, {8, 8}, {15, 0}}, 4));
    }

    @Test
    void nothingOrOneJobIsNotSplit() {
        assertNull(plan(new int[0][], 4));
        assertNull(plan(new int[][] {{0, 0}, {1000, 1000}}, 1));
    }

    @Test
    void farApartGroupsGetTheirOwnJob() {
        RegionPlan p = plan(new int[][] {{0, 0}, {1, 2}, {100, 100}, {101, 100}, {-200, 5}}, 4);
        assertNotNull(p);
        assertEquals(3, p.regions);
        assertEquals(3, p.jobs.length);
        // Items of one group are in the same job, in their original order.
        int jobOf0 = jobOf(p, 0);
        assertEquals(jobOf0, jobOf(p, 1));
        assertEquals(jobOf(p, 2), jobOf(p, 3));
        assertTrue(jobOf0 != jobOf(p, 2) && jobOf0 != jobOf(p, 4) && jobOf(p, 2) != jobOf(p, 4));
        for (int[] job : p.jobs) {
            for (int i = 1; i < job.length; i++) assertTrue(job[i - 1] < job[i]);
        }
    }

    @Test
    void touchingSquaresAreOneRegionEvenDiagonally() {
        // Squares (0,0), (1,1), (2,2) touch diagonally: one region. (5,5) is two squares away: its own.
        RegionPlan p = plan(new int[][] {{0, 0}, {8, 8}, {16, 16}, {40, 40}}, 4);
        assertNotNull(p);
        assertEquals(2, p.regions);
        assertEquals(jobOf(p, 0), jobOf(p, 2));
        assertTrue(jobOf(p, 0) != jobOf(p, 3));
    }

    @Test
    void regionsAreAtLeastOneEmptySquareApart() {
        // Chunk 7 (square 0) and chunk 16 (square 2): square 1 between them is empty, so 8 chunks of gap.
        RegionPlan p = plan(new int[][] {{7, 0}, {16, 0}}, 4);
        assertNotNull(p);
        assertEquals(2, p.regions);
        // Chunk 7 and chunk 15 are in neighbouring squares: one region.
        assertNull(plan(new int[][] {{7, 0}, {15, 0}}, 4));
    }

    @Test
    void negativeCoordinatesWork() {
        RegionPlan p = plan(new int[][] {{-1, -1}, {0, 0}, {-30, -30}}, 4);
        assertNotNull(p);
        assertEquals(2, p.regions);
        assertEquals(jobOf(p, 0), jobOf(p, 1));
    }

    @Test
    void moreRegionsThanThreadsArePackedBiggestFirst() {
        // Region A: 4 items, B: 3, C: 2, D: 1, two jobs -> {A, D} = 5 and {B, C} = 5.
        RegionPlan p = plan(new int[][] {
                {0, 0}, {0, 1}, {1, 0}, {1, 1},
                {100, 0}, {100, 1}, {101, 0},
                {200, 0}, {200, 1},
                {300, 0}}, 2);
        assertNotNull(p);
        assertEquals(4, p.regions);
        assertEquals(2, p.jobs.length);
        assertEquals(5, p.jobs[0].length);
        assertEquals(5, p.jobs[1].length);
        Set<Integer> all = new HashSet<>();
        for (int[] job : p.jobs) for (int i : job) assertTrue(all.add(i));
        assertEquals(10, all.size());
    }

    @Test
    void jobCoversItsSquaresOnly() {
        RegionPlan p = plan(new int[][] {{0, 0}, {100, 100}}, 4);
        assertNotNull(p);
        int a = jobOf(p, 0);
        int b = jobOf(p, 1);
        assertTrue(p.jobCovers(a, 3, 5, 3));
        assertFalse(p.jobCovers(a, 100, 100, 3));
        assertTrue(p.jobCovers(b, 100, 100, 3));
        assertFalse(p.jobCovers(b, 50, 50, 3));
    }

    private static int jobOf(RegionPlan p, int item) {
        for (int j = 0; j < p.jobs.length; j++) {
            for (int i : p.jobs[j]) if (i == item) return j;
        }
        throw new AssertionError("item " + item + " in no job");
    }
}
