package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonWallBuilderGoalWatchdogRecoveryAndLogRateLimitTest {

    @Test
    void noPlacementForWatchdogCycles_forcesDeterministicRecoveryIndexShift() {
        int initialIndex = 2;
        int shiftedIndex = MasonWallBuilderGoal.simulateDeterministicRecoveryIndexShift(
                6,
                initialIndex,
                Set.of(3)
        );

        assertNotEquals(initialIndex, shiftedIndex,
                "Expected watchdog recovery to force a deterministic target/index change after repeated no-progress cycles");
        assertEquals(4, shiftedIndex,
                "Expected deterministic forward rotation to skip blocked indices and pick the next eligible segment");
    }

    @Test
    void repeatedIdenticalRetryCondition_capsInfoEmissions() {
        int emissions = MasonWallBuilderGoal.simulateInfoLogEmissionsForRepeatedState(
                List.of(0L, 10L, 20L, 30L, 40L, 50L, 60L),
                200L,
                List.of("same-retry", "same-retry", "same-retry", "same-retry", "same-retry", "same-retry", "same-retry")
        );

        assertTrue(emissions <= 1,
                "Expected repeated identical retry conditions within the interval to avoid unbounded info logs");
    }
}
