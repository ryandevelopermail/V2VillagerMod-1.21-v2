package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonWallBuilderGoalEarlyCycleAbortPolicyTest {

    @Test
    void earlyCycleBandFirstAbort_appliesLongerCooldownAndImmediateBandQuarantine() {
        MasonWallBuilderGoal.EarlyCycleAbortPolicyDecision decision = MasonWallBuilderGoal.simulateEarlyCycleAbortPolicy(
                true,
                1,
                false
        );

        assertTrue(decision.cooldownTicks() >= 360L, "Expected early-cycle abort to use a longer first cooldown");
        assertTrue(decision.shouldQuarantineBand(), "Expected early-cycle abort to quarantine the band immediately");
    }

    @Test
    void nonEarlyCycleSingleAbort_keepsShortCooldownWithoutImmediateQuarantine() {
        MasonWallBuilderGoal.EarlyCycleAbortPolicyDecision decision = MasonWallBuilderGoal.simulateEarlyCycleAbortPolicy(
                false,
                1,
                false
        );

        assertTrue(decision.cooldownTicks() <= 120L, "Expected non-early first abort to keep baseline cooldown");
        assertFalse(decision.shouldQuarantineBand(), "Expected non-early first abort to avoid immediate quarantine");
    }
}
