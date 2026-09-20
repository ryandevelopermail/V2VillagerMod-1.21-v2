package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static dev.sterner.guardvillagers.common.entity.goal.MasonMiningRecoveryPolicy.Action.ABORT;
import static dev.sterner.guardvillagers.common.entity.goal.MasonMiningRecoveryPolicy.Action.REROUTE;
import static dev.sterner.guardvillagers.common.entity.goal.MasonMiningRecoveryPolicy.Action.RETRY_STEP;
import static dev.sterner.guardvillagers.common.entity.goal.MasonMiningRecoveryPolicy.FailureSubtype.BLOCK_BREAK_FAILED;
import static dev.sterner.guardvillagers.common.entity.goal.MasonMiningRecoveryPolicy.FailureSubtype.JOB_BLOCK_EXCLUSION;
import static dev.sterner.guardvillagers.common.entity.goal.MasonMiningRecoveryPolicy.FailureSubtype.NAVIGATION_FAILED;
import static dev.sterner.guardvillagers.common.entity.goal.MasonMiningRecoveryPolicy.FailureSubtype.STEP_CLEAR_FAILED;
import static dev.sterner.guardvillagers.common.entity.goal.MasonMiningRecoveryPolicy.FailureSubtype.TOOL_LOST;
import static dev.sterner.guardvillagers.common.entity.goal.MasonMiningRecoveryPolicy.FailureSubtype.WATER_HAZARD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonMiningRecoveryPolicyTest {
    @Test
    void normalStaircaseAdvancesUntilBatchTarget() {
        BlockPos origin = new BlockPos(10, 70, 20);
        for (int completed = 0; completed < 12; completed++) {
            assertFalse(MasonMiningRecoveryPolicy.batchComplete(completed, 12));
            assertEquals(
                    new BlockPos(10, 69 - completed, 19 - completed),
                    MasonMiningStairGoal.computeStepTarget(origin, Direction.NORTH, completed));
        }
        assertTrue(MasonMiningRecoveryPolicy.batchComplete(12, 12));
    }

    @Test
    void oneRecoverableObstructionRetriesInsteadOfEndingSession() {
        MasonMiningRecoveryPolicy.Decision decision =
                MasonMiningRecoveryPolicy.decide(STEP_CLEAR_FAILED, 0, false);
        assertEquals(RETRY_STEP, decision.action());
        assertEquals(1, decision.nextRetryCount());
    }

    @Test
    void successfulRepairCanResetRetryBudgetForFollowingStep() {
        MasonMiningRecoveryPolicy.Decision firstFailure =
                MasonMiningRecoveryPolicy.decide(BLOCK_BREAK_FAILED, 0, false);
        MasonMiningRecoveryPolicy.Decision laterIndependentFailure =
                MasonMiningRecoveryPolicy.decide(BLOCK_BREAK_FAILED, 0, false);
        assertEquals(1, firstFailure.nextRetryCount());
        assertEquals(1, laterIndependentFailure.nextRetryCount());
    }

    @Test
    void retryBudgetIsBoundedAndThenReroutesWhenPossible() {
        for (int attempts = 0; attempts < MasonMiningRecoveryPolicy.MAX_LOCAL_RETRIES; attempts++) {
            assertEquals(RETRY_STEP,
                    MasonMiningRecoveryPolicy.decide(STEP_CLEAR_FAILED, attempts, false).action());
        }
        assertEquals(REROUTE,
                MasonMiningRecoveryPolicy.decide(
                        STEP_CLEAR_FAILED,
                        MasonMiningRecoveryPolicy.MAX_LOCAL_RETRIES,
                        true).action());
    }

    @Test
    void repeatedUnrecoverableFailureEndsCleanlyWhenNoRerouteExists() {
        assertEquals(ABORT,
                MasonMiningRecoveryPolicy.decide(
                        STEP_CLEAR_FAILED,
                        MasonMiningRecoveryPolicy.MAX_LOCAL_RETRIES,
                        false).action());
    }

    @Test
    void failureSubtypeKeepsConcreteDiagnosticCode() {
        assertEquals("block_break_failed", BLOCK_BREAK_FAILED.reasonCode());
        assertEquals("navigation_failed", NAVIGATION_FAILED.reasonCode());
    }

    @Test
    void failedRouteCanRerouteInsteadOfRejoiningPermanentTarget() {
        assertEquals(REROUTE,
                MasonMiningRecoveryPolicy.decide(
                        BLOCK_BREAK_FAILED,
                        MasonMiningRecoveryPolicy.MAX_LOCAL_RETRIES,
                        true).action());
    }

    @Test
    void waterRemainsImmediateHardStop() {
        assertEquals(ABORT, MasonMiningRecoveryPolicy.decide(WATER_HAZARD, 0, true).action());
    }

    @Test
    void jobBlockExclusionNeverRetriesExcavation() {
        assertEquals(REROUTE, MasonMiningRecoveryPolicy.decide(JOB_BLOCK_EXCLUSION, 0, true).action());
        assertEquals(ABORT, MasonMiningRecoveryPolicy.decide(JOB_BLOCK_EXCLUSION, 0, false).action());
    }

    @Test
    void toolLossEndsSessionAndUsesShortRecheckBackoff() {
        assertEquals(ABORT, MasonMiningRecoveryPolicy.decide(TOOL_LOST, 0, true).action());
        assertEquals(MasonMiningRecoveryPolicy.TOOL_RECHECK_TICKS,
                MasonMiningRecoveryPolicy.failureBackoffTicks(TOOL_LOST, 1));
    }

    @Test
    void repeatedCannotAdvanceBackoffIsShortAndCapped() {
        assertEquals(20 * 20,
                MasonMiningRecoveryPolicy.failureBackoffTicks(STEP_CLEAR_FAILED, 1));
        assertEquals(20 * 30,
                MasonMiningRecoveryPolicy.failureBackoffTicks(STEP_CLEAR_FAILED, 2));
        assertEquals(20 * 40,
                MasonMiningRecoveryPolicy.failureBackoffTicks(STEP_CLEAR_FAILED, 99));
    }

    @Test
    void successfulBatchTargetEndsNormally() {
        assertTrue(MasonMiningRecoveryPolicy.batchComplete(20, 20));
        assertTrue(MasonMiningRecoveryPolicy.batchComplete(21, 20));
    }

    @Test
    void returnPathRetriesThenFallsBackToChest() {
        assertEquals(MasonMiningRecoveryPolicy.ReturnPathAction.RETRY_STAIR,
                MasonMiningRecoveryPolicy.returnPathAction(0));
        assertEquals(MasonMiningRecoveryPolicy.ReturnPathAction.FALL_BACK_TO_CHEST,
                MasonMiningRecoveryPolicy.returnPathAction(MasonMiningRecoveryPolicy.MAX_LOCAL_RETRIES));
    }
}
