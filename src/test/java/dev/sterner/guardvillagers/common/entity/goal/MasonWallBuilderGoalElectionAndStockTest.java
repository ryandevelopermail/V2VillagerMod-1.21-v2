package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonWallBuilderGoalElectionAndStockTest {

    @Test
    void election_excludesHighestStoneCandidateWhenJobPairingMissing_andElectsNextEligibleMason() {
        UUID masonA = UUID.randomUUID();
        UUID masonB = UUID.randomUUID();
        UUID masonC = UUID.randomUUID();
        MasonWallBuilderGoal.ElectionDecision decision = MasonWallBuilderGoal.electBuilderCandidate(List.of(
                new MasonWallBuilderGoal.ElectionCandidateSnapshot(masonA, "mason-a", true, false, 128, 9.0D, 0, 0),
                new MasonWallBuilderGoal.ElectionCandidateSnapshot(masonB, "mason-b", true, true, 64, 4.0D, 0, 0),
                new MasonWallBuilderGoal.ElectionCandidateSnapshot(masonC, "mason-c", true, true, 32, 1.0D, 0, 0)
        ));

        assertEquals(masonB, decision.electedBuilderUuid());
        assertEquals(64, decision.electedStoneCount());
        assertEquals(List.of("mason-a"), decision.excludedCandidates().stream().map(MasonWallBuilderGoal.ElectionCandidateSnapshot::candidateId).toList());
    }

    @Test
    void election_prefersLowestParticipation_thenFallsBackToStoneAndDistance() {
        UUID lowParticipation = UUID.randomUUID();
        UUID tieBreakByStone = UUID.randomUUID();
        UUID tieBreakByDistance = UUID.randomUUID();
        MasonWallBuilderGoal.ElectionDecision decision = MasonWallBuilderGoal.electBuilderCandidate(List.of(
                new MasonWallBuilderGoal.ElectionCandidateSnapshot(tieBreakByStone, "stone-rich", true, true, 90, 25.0D, 6, 3),
                new MasonWallBuilderGoal.ElectionCandidateSnapshot(tieBreakByDistance, "closer", true, true, 90, 9.0D, 6, 3),
                new MasonWallBuilderGoal.ElectionCandidateSnapshot(lowParticipation, "fair-choice", true, true, 20, 49.0D, 1, 1)
        ));
        assertEquals(lowParticipation, decision.electedBuilderUuid());
    }

    @Test
    void waitForStock_withThresholdWallsAndMissingJobPos_advancesToMoveToSegment() {
        MasonWallBuilderGoal.WaitForStockDecision decision = MasonWallBuilderGoal.decideWaitForStockTransition(
                12,
                0,
                8,
                true
        );

        assertEquals(MasonWallBuilderGoal.WaitForStockDecision.MOVE_TO_SEGMENT, decision);
    }

    @Test
    void waitForStock_withFallbackEnabledAndCobbleStock_advancesWithoutWalls() {
        MasonWallBuilderGoal.WaitForStockDecision decision = MasonWallBuilderGoal.decideWaitForStockTransition(
                0,
                4,
                4,
                true
        );

        assertEquals(MasonWallBuilderGoal.WaitForStockDecision.MOVE_TO_SEGMENT, decision);
    }

    @Test
    void election_withNoEligibleBuilder_failsGracefullyAndSignalsLoggingIntent() {
        UUID masonA = UUID.randomUUID();
        UUID masonB = UUID.randomUUID();
        MasonWallBuilderGoal.ElectionDecision decision = MasonWallBuilderGoal.electBuilderCandidate(List.of(
                new MasonWallBuilderGoal.ElectionCandidateSnapshot(masonA, "mason-a", true, false, 80, 1.0D, 0, 0),
                new MasonWallBuilderGoal.ElectionCandidateSnapshot(masonB, "mason-b", false, true, 70, 2.0D, 0, 0)
        ));

        assertNull(decision.electedBuilderUuid());
        assertTrue(decision.shouldLogNoEligibleBuilder());
        assertEquals(2, decision.excludedCandidates().size());
    }

    @Test
    void assignmentStatus_marksStalledOrMissingBuilderAsUnhealthy() {
        UUID builder = UUID.randomUUID();
        MasonWallBuilderGoal.AssignmentStatus stalled = MasonWallBuilderGoal.evaluateAssignmentStatus(
                new dev.sterner.guardvillagers.common.util.VillageWallProjectState.ProjectAssignmentSnapshot(builder, 10L, 20L),
                Map.of(),
                net.minecraft.util.math.BlockPos.ORIGIN,
                400L,
                100L,
                48.0D
        );
        assertEquals(MasonWallBuilderGoal.AssignmentStatusKind.UNHEALTHY, stalled.status());
        assertTrue(stalled.reason().contains("missing"));
    }
}
