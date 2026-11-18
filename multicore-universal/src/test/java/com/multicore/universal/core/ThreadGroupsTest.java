package com.multicore.universal.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ThreadGroupsTest {
    private static final String OW = "minecraft:overworld", NETHER = "minecraft:the_nether", END = "minecraft:the_end",
            HUB = "multiworld:hub", SHOP = "multiworld:shop", NORTH = "multiworld:north", WAR = "multiworld:war",
            CREATIVE = "multiworld:creative";
    private static final ThreadGroups.Settings S = new ThreadGroups.Settings(10, 6, 10.0);

    @Test
    void lightDimensionsShareOneThreadAndAHeavyOneGetsItsOwn() {
        Map<String, Double> cost = Map.of(OW, 30.0, NETHER, 3.0, END, 1.0, HUB, 0.5, SHOP, 0.5);
        var g = ThreadGroups.assign(List.of(OW, NETHER, END, HUB, SHOP), List.of(), S, cost);
        assertEquals(List.of(List.of(OW), List.of(NETHER, END, HUB, SHOP)), g);
    }

    @Test
    void atMostSixDimensionsOnOneThread() {
        var dims = List.of(OW, NETHER, END, HUB, SHOP, NORTH, WAR, CREATIVE);
        Map<String, Double> tiny = dims.stream().collect(Collectors.toMap(d -> d, d -> 0.2));
        var g = ThreadGroups.assign(dims, List.of(), S, tiny);
        assertEquals(2, g.size(), "8 light dimensions: 6 on one thread, 2 on another");
        assertTrue(g.stream().allMatch(t -> t.size() <= 6));
    }

    @Test
    void aThreadIsFilledUpToAboutTheTargetTime() {
        // 4 ms each: two fit in 10 ms, the third goes to the next thread.
        Map<String, Double> cost = Map.of(OW, 4.0, NETHER, 4.0, END, 4.0, HUB, 4.0);
        var g = ThreadGroups.assign(List.of(OW, NETHER, END, HUB), List.of(), S, cost);
        assertEquals(2, g.size());
        assertTrue(g.stream().allMatch(t -> t.size() == 2));
    }

    @Test
    void configuredDimensionsAlwaysShare() {
        Map<String, Double> cost = Map.of(OW, 1.0, NETHER, 20.0, END, 20.0);
        var g = ThreadGroups.assign(List.of(OW, NETHER, END), ThreadGroups.parse(List.of("minecraft:the_nether, minecraft:the_end")), S, cost);
        assertTrue(g.contains(List.of(NETHER, END)), "the Nether and the End together even when both are heavy");
    }

    @Test
    void theThreadLimitIsKept() {
        var dims = List.of(OW, NETHER, END, HUB, SHOP);
        Map<String, Double> heavy = dims.stream().collect(Collectors.toMap(d -> d, d -> 25.0));
        var g = ThreadGroups.assign(dims, List.of(), new ThreadGroups.Settings(2, 6, 10.0), heavy);
        assertEquals(2, g.size());
        assertEquals(Set.copyOf(dims), g.stream().flatMap(List::stream).collect(Collectors.toSet()), "every dimension ticks exactly once");
        assertEquals(5, g.stream().mapToInt(List::size).sum());
    }

    @Test
    void onlyDimensionsThatTickInParallelNowAreGrouped() {
        var g = ThreadGroups.assign(List.of(OW, NETHER), ThreadGroups.parse(List.of("minecraft:the_nether,minecraft:the_end")), S, Map.of(OW, 30.0));
        assertEquals(List.of(List.of(OW), List.of(NETHER)), g);
    }
}
