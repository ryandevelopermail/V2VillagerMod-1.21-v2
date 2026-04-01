package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonWallBuilderGoalPathRetryPolicyTest {

    @Test
    void singleTickPathFailure_thenSuccess_doesNotSkipSegment() {
        MasonWallBuilderGoal.PathRetrySimulationResult result = MasonWallBuilderGoal.simulatePathRetryPolicy(
                List.of(false, true),
                4,
                40
        );

        assertFalse(result.skippedAsHardUnreachable());
        assertTrue(result.decisionTick() <= 2, "Expected the successful retry to resolve quickly");
    }

    @Test
    void consistentlyUnreachableSegment_eventuallySkipsWithinBoundedRetries() {
        MasonWallBuilderGoal.PathRetrySimulationResult result = MasonWallBuilderGoal.simulatePathRetryPolicy(
                List.of(false, false, false, false, false, false),
                4,
                40
        );

        assertTrue(result.skippedAsHardUnreachable());
        assertTrue(result.failedAttempts() <= 4, "Expected bounded retry attempts before hard skip");
    }
}
