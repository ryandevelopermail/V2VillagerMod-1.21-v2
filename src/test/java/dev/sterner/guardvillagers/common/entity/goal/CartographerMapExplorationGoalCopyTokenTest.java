package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CartographerMapExplorationGoalCopyTokenTest {

    @Test
    void canonicalSourceSet_remainsImmutableAfterFirstLock() {
        List<String> canonical = List.of("slot-1", "slot-2", "slot-3", "slot-4");
        List<String> newerDuplicates = List.of("dup-1", "dup-2", "dup-3", "dup-4");

        List<String> locked = CartographerMapExplorationGoal.lockCanonicalBase(canonical, newerDuplicates, 4);

        assertSame(canonical, locked);
        assertIterableEquals(List.of("slot-1", "slot-2", "slot-3", "slot-4"), locked);
    }

    @Test
    void copyTokens_oneTwoThreeFollowStableRoundRobinSlots() {
        assertEquals(List.of(0), CartographerMapExplorationGoal.plannedCopySlots(1, 0, 4));
        assertEquals(List.of(0, 1), CartographerMapExplorationGoal.plannedCopySlots(2, 0, 4));
        assertEquals(List.of(0, 1, 2), CartographerMapExplorationGoal.plannedCopySlots(3, 0, 4));
    }

    @Test
    void copyTokens_continueCompletingSetWhenMoreTokensArriveLater() {
        List<Integer> firstRun = CartographerMapExplorationGoal.plannedCopySlots(3, 0, 4);
        assertEquals(List.of(0, 1, 2), firstRun);

        int cursorAfterFirstRun = 3;
        List<Integer> secondRun = CartographerMapExplorationGoal.plannedCopySlots(2, cursorAfterFirstRun, 4);
        assertEquals(List.of(3, 0), secondRun);
    }
}
