package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonWallBuilderGoalElectionAndStockTest {

    @Test
    void election_excludesHighestStoneCandidateWhenJobPairingMissing_andElectsNextEligibleMason() {
        MasonWallBuilderGoal.ElectionDecision decision = MasonWallBuilderGoal.electBuilderCandidate(List.of(
                new MasonWallBuilderGoal.ElectionCandidateSnapshot("mason-a", true, false, 128),
                new MasonWallBuilderGoal.ElectionCandidateSnapshot("mason-b", true, true, 64),
                new MasonWallBuilderGoal.ElectionCandidateSnapshot("mason-c", true, true, 32)
        ));

        assertEquals("mason-b", decision.electedCandidateId());
        assertEquals(64, decision.electedStoneCount());
        assertEquals(List.of("mason-a"), decision.excludedCandidates().stream().map(MasonWallBuilderGoal.ElectionCandidateSnapshot::candidateId).toList());
    }

    @Test
    void waitForStock_withThresholdWallsAndMissingJobPos_advancesToMoveToSegment() {
        MasonWallBuilderGoal.WaitForStockDecision decision = MasonWallBuilderGoal.decideWaitForStockTransition(
                12,
                12,
                false
        );

        assertEquals(MasonWallBuilderGoal.WaitForStockDecision.MOVE_TO_SEGMENT, decision);
    }

    @Test
    void election_withNoEligibleBuilder_failsGracefullyAndSignalsLoggingIntent() {
        MasonWallBuilderGoal.ElectionDecision decision = MasonWallBuilderGoal.electBuilderCandidate(List.of(
                new MasonWallBuilderGoal.ElectionCandidateSnapshot("mason-a", true, false, 80),
                new MasonWallBuilderGoal.ElectionCandidateSnapshot("mason-b", false, true, 70)
        ));

        assertNull(decision.electedCandidateId());
        assertTrue(decision.shouldLogNoEligibleBuilder());
        assertEquals(2, decision.excludedCandidates().size());
    }
}
