package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MasonWallBuilderGoalAdaptiveBudgetPolicyTest {

    @Test
    void noPlacementAndRetrySpike_entersThrottledMode() {
        MasonWallBuilderGoal.AdaptivePerfModeDecision decision = MasonWallBuilderGoal.simulateAdaptiveBudgetPolicy(
                List.of(0, 0, 0, 0, 0, 0, 0),
                List.of(0, 1, 2, 3, 4, 5, 6),
                List.of(0, 1, 2, 3, 3, 4, 4),
                List.of(0, 1, 2, 2, 3, 4, 4),
                6,
                4
        );

        assertEquals(MasonWallBuilderGoal.AdaptivePerfMode.THROTTLED, decision.mode());
    }

    @Test
    void placementsResumeAndCooldownExpires_returnsToNormalMode() {
        MasonWallBuilderGoal.AdaptivePerfModeDecision decision = MasonWallBuilderGoal.simulateAdaptiveBudgetPolicy(
                List.of(0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7),
                List.of(0, 1, 2, 3, 4, 5, 5, 5, 5, 5, 5, 5),
                List.of(0, 1, 2, 3, 4, 4, 4, 4, 4, 4, 4, 4),
                List.of(0, 1, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3),
                8,
                3
        );

        assertEquals(MasonWallBuilderGoal.AdaptivePerfMode.NORMAL, decision.mode());
    }
}
