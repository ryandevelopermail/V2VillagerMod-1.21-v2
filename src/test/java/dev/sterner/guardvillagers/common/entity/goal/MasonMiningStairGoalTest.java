package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonMiningStairGoalTest {

    @Test
    void hasRepairSummaryData_onlyReportsWhenThereAreRepairEvents() {
        assertFalse(MasonMiningStairGoal.hasRepairSummaryData(0, 0));
        assertTrue(MasonMiningStairGoal.hasRepairSummaryData(1, 0));
        assertTrue(MasonMiningStairGoal.hasRepairSummaryData(0, 1));
    }
}
