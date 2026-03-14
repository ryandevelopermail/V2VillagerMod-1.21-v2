package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LumberjackGuardDepositLogsGoalTest {

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
