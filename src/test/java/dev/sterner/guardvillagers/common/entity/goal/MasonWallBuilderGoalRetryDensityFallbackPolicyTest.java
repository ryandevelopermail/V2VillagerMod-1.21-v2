package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonWallBuilderGoalRetryDensityFallbackPolicyTest {

    @Test
    void highRetryDensityWithLowPlacementDelta_triggersFallback() {
        MasonWallBuilderGoal.RetryDensityFallbackDecision decision = MasonWallBuilderGoal.simulateRetryDensityFallbackPolicy(
                List.of(0, 0, 0, 1, 1),
                List.of(0, 1, 2, 4, 5),
                List.of(0, 0, 1, 1, 1),
                200
        );

        assertTrue(decision.fallbackTriggered());
        assertTrue(decision.retryDensity() >= 2.0D);
    }

    @Test
    void steadyPlacementProgress_doesNotTriggerFallback() {
        MasonWallBuilderGoal.RetryDensityFallbackDecision decision = MasonWallBuilderGoal.simulateRetryDensityFallbackPolicy(
                List.of(0, 1, 2, 3, 4),
                List.of(0, 1, 2, 3, 4),
                List.of(0, 0, 0, 0, 0),
                200
        );

        assertFalse(decision.fallbackTriggered());
    }
}
