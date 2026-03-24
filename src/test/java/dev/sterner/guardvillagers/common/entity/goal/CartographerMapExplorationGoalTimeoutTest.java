package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartographerMapExplorationGoalTimeoutTest {

    @Test
    void hasTimedOut_returnsFalseBeforeThreshold() {
        assertFalse(CartographerMapExplorationGoal.hasTimedOut(199L, 0L, 200));
    }

    @Test
    void hasTimedOut_returnsTrueAtThresholdAndBeyond() {
        assertTrue(CartographerMapExplorationGoal.hasTimedOut(200L, 0L, 200));
        assertTrue(CartographerMapExplorationGoal.hasTimedOut(260L, 10L, 200));
    }
}
