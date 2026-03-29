package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.LumberjackDemandPlanner;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.village.VillagerProfession;

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
    void clampTransferForRecipient_limitsButcherLogTransfersToOne() {
        int transfer = LumberjackGuardDepositLogsGoal.clampTransferForRecipient(
                LumberjackDemandPlanner.MaterialType.LOGS,
                VillagerProfession.BUTCHER,
                6
        );

        assertEquals(1, transfer);
    }

    @Test
    void clampTransferForRecipient_keepsCharcoalTransfersUnchangedForButcher() {
        int transfer = LumberjackGuardDepositLogsGoal.clampTransferForRecipient(
                LumberjackDemandPlanner.MaterialType.CHARCOAL,
                VillagerProfession.BUTCHER,
                6
        );

        assertEquals(6, transfer);
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

    @Test
    void collectCandidates_materialDetectionRecognizesFenceAndGateStacksForShepherdRouting() {
        assertEquals(LumberjackDemandPlanner.MaterialType.FENCES, LumberjackDemandPlanner.MaterialType.fromStack(new ItemStack(Items.OAK_FENCE)));
        assertEquals(LumberjackDemandPlanner.MaterialType.FENCE_GATES, LumberjackDemandPlanner.MaterialType.fromStack(new ItemStack(Items.OAK_FENCE_GATE)));
    }

    @Test
    void clampTransferForRecipient_keepsFenceTransfersForShepherdUnchanged() {
        int transfer = LumberjackGuardDepositLogsGoal.clampTransferForRecipient(
                LumberjackDemandPlanner.MaterialType.FENCES,
                VillagerProfession.SHEPHERD,
                4
        );

        assertEquals(4, transfer);
    }

}
