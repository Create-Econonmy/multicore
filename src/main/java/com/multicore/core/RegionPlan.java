package com.multicore.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Splits the things that tick in one dimension (entities, block entities) into regions that can tick on different
 * threads at the same time. Pure Java, no Minecraft classes, so it is unit-tested on its own.
 * <p>
 * The world is cut into squares of {@code 2^shift} chunks. Squares that contain something are joined with their eight
 * neighbours; each group of joined squares is a region. Two regions are therefore always at least one empty square apart
 * (8 chunks = 128 blocks with the default shift of 3), so what ticks in one region does not reach into another.
 * <p>
 * Regions are then packed onto at most {@code maxJobs} jobs (one job = one thread), biggest first onto the least loaded job.
 */
public final class RegionPlan {
    /** Item indices of each job. */
    public final int[][] jobs;
    /** Square keys (see {@link #squareKey}) covered by each job. */
    public final long[][] jobSquares;
    /** Number of regions found (may be more than jobs). */
    public final int regions;

    private RegionPlan(int[][] jobs, long[][] jobSquares, int regions) {
        this.jobs = jobs;
        this.jobSquares = jobSquares;
        this.regions = regions;
    }

    public static long squareKey(int squareX, int squareZ) {
        return ((long) squareX << 32) | (squareZ & 0xFFFFFFFFL);
    }

    /**
     * @param chunkX chunk x of item i
     * @param chunkZ chunk z of item i
     * @param count  number of items
     * @param shift  square size is 2^shift chunks
     * @param maxJobs at most this many jobs
     * @return the plan, or null when there are fewer than two regions (nothing to split)
     */
    @Nullable
    public static RegionPlan plan(int[] chunkX, int[] chunkZ, int count, int shift, int maxJobs) {
        if (count == 0 || maxJobs < 2) {
            return null;
        }
        // 1. Distinct squares.
        Map<Long, Integer> squareIndex = new HashMap<>();
        int[] itemSquare = new int[count];
        List<long[]> squares = new ArrayList<>(); // {key, x, z}
        for (int i = 0; i < count; i++) {
            int sx = chunkX[i] >> shift;
            int sz = chunkZ[i] >> shift;
            long key = squareKey(sx, sz);
            Integer idx = squareIndex.get(key);
            if (idx == null) {
                idx = squares.size();
                squareIndex.put(key, idx);
                squares.add(new long[] {key, sx, sz});
            }
            itemSquare[i] = idx;
        }
        int n = squares.size();
        if (n < 2) {
            return null;
        }
        // 2. Union squares that touch (8-neighbourhood).
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            long[] s = squares.get(i);
            int sx = (int) s[1];
            int sz = (int) s[2];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Integer j = squareIndex.get(squareKey(sx + dx, sz + dz));
                    if (j != null) union(parent, i, j);
                }
            }
        }
        // 3. Regions with their weight (item count).
        int[] regionOfSquare = new int[n];
        int[] rootToRegion = new int[n];
        Arrays.fill(rootToRegion, -1);
        int regions = 0;
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            if (rootToRegion[root] < 0) rootToRegion[root] = regions++;
            regionOfSquare[i] = rootToRegion[root];
        }
        if (regions < 2) {
            return null;
        }
        int[] weight = new int[regions];
        for (int i = 0; i < count; i++) weight[regionOfSquare[itemSquare[i]]]++;
        // 4. Pack regions onto jobs: biggest region first, onto the job with the least work so far.
        Integer[] order = new Integer[regions];
        for (int r = 0; r < regions; r++) order[r] = r;
        Arrays.sort(order, (a, b) -> Integer.compare(weight[b], weight[a]));
        int jobCount = Math.min(regions, maxJobs);
        int[] jobOfRegion = new int[regions];
        long[] jobWeight = new long[jobCount];
        for (Integer r : order) {
            int best = 0;
            for (int j = 1; j < jobCount; j++) if (jobWeight[j] < jobWeight[best]) best = j;
            jobOfRegion[r] = best;
            jobWeight[best] += weight[r];
        }
        // 5. Items and squares per job, items kept in their original order.
        int[] jobSize = new int[jobCount];
        for (int i = 0; i < count; i++) jobSize[jobOfRegion[regionOfSquare[itemSquare[i]]]]++;
        int[][] jobs = new int[jobCount][];
        for (int j = 0; j < jobCount; j++) jobs[j] = new int[jobSize[j]];
        int[] fill = new int[jobCount];
        for (int i = 0; i < count; i++) {
            int j = jobOfRegion[regionOfSquare[itemSquare[i]]];
            jobs[j][fill[j]++] = i;
        }
        int[] squaresPerJob = new int[jobCount];
        for (int i = 0; i < n; i++) squaresPerJob[jobOfRegion[regionOfSquare[i]]]++;
        long[][] jobSquares = new long[jobCount][];
        for (int j = 0; j < jobCount; j++) jobSquares[j] = new long[squaresPerJob[j]];
        int[] sfill = new int[jobCount];
        for (int i = 0; i < n; i++) {
            int j = jobOfRegion[regionOfSquare[i]];
            jobSquares[j][sfill[j]++] = squares.get(i)[0];
        }
        for (long[] s : jobSquares) Arrays.sort(s);
        return new RegionPlan(jobs, jobSquares, regions);
    }

    /** Is the chunk in one of the squares of job {@code job}? */
    public boolean jobCovers(int job, int chunkX, int chunkZ, int shift) {
        return Arrays.binarySearch(this.jobSquares[job], squareKey(chunkX >> shift, chunkZ >> shift)) >= 0;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }
}
