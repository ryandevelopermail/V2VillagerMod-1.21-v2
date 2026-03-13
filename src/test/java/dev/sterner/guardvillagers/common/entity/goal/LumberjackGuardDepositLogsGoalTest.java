package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.LumberjackDemandPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LumberjackGuardDepositLogsGoalTest {

    @Test
    void determinePickaxeBatchTransferAmount_sticksWhenTwoMissing() {
        int transfer = LumberjackGuardDepositLogsGoal.determinePickaxeBatchTransferAmountForCounts(
                LumberjackDemandPlanner.MaterialType.STICK,
                3,
                0,
                0,
                2
        );

        assertEquals(2, transfer);
    }

    @Test
    void determineToolsmithBatchTransferAmount_sticksWhenTwoMissing() {
        int transfer = LumberjackGuardDepositLogsGoal.determineToolsmithPickaxeBatchTransferAmountForCounts(
                LumberjackDemandPlanner.MaterialType.STICK,
                3,
                0,
                0,
                2
        );

        assertEquals(2, transfer);
    }

    @Test
    void determineToolsmithBatchTransferAmount_planksWhenThreeMissing() {
        int transfer = LumberjackGuardDepositLogsGoal.determineToolsmithPickaxeBatchTransferAmountForCounts(
                LumberjackDemandPlanner.MaterialType.PLANKS,
                0,
                2,
                3,
                0
        );

        assertEquals(3, transfer);
    }

    @Test
    void determineToolsmithBatchTransferAmount_returnsZeroWithoutComplementarySourceMaterials() {
        int plankTransfer = LumberjackGuardDepositLogsGoal.determineToolsmithPickaxeBatchTransferAmountForCounts(
                LumberjackDemandPlanner.MaterialType.PLANKS,
                0,
                0,
                3,
                0
        );
        int stickTransfer = LumberjackGuardDepositLogsGoal.determineToolsmithPickaxeBatchTransferAmountForCounts(
                LumberjackDemandPlanner.MaterialType.STICK,
                0,
                0,
                0,
                2
        );

        assertEquals(0, plankTransfer);
        assertEquals(0, stickTransfer);
    }

    @Test
    void resolveTransferAmount_returnsZeroWhenRequestedAmountIsZero() {
        int transfer = LumberjackGuardDepositLogsGoal.resolveTransferAmount(0, 16);

        assertEquals(0, transfer);
    }

    @Test
    void resolveTransferAmount_returnsZeroWhenRequestedAmountIsNegative() {
        int transfer = LumberjackGuardDepositLogsGoal.resolveTransferAmount(-2, 16);

        assertEquals(0, transfer);
    }

    @Test
    void resolveTransferAmount_clampsOnlyToSourceStackCount() {
        int transfer = LumberjackGuardDepositLogsGoal.resolveTransferAmount(64, 5);

        assertEquals(5, transfer);
    }

    @Test
    void firstActionableCandidateIndex_skipsZeroTransferAndSelectsNextCandidate() {
        int candidateIndex = LumberjackGuardDepositLogsGoal.firstActionableCandidateIndexForCounts(
                List.of(0, 3),
                List.of(32, 32)
        );

        assertEquals(1, candidateIndex);
    }

    @Test
    void firstActionableCandidateIndex_returnsNoCandidateWhenAllTransfersAreZero() {
        int candidateIndex = LumberjackGuardDepositLogsGoal.firstActionableCandidateIndexForCounts(
                List.of(0, -2, 0),
                List.of(16, 16, 16)
        );

        assertEquals(-1, candidateIndex);
    }

}
