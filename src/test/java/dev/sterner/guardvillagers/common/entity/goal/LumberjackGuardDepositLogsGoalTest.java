package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.LumberjackDemandPlanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LumberjackGuardDepositLogsGoalTest {

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
}
