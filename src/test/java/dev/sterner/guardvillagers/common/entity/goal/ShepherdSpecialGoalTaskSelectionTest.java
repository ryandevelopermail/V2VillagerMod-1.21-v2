package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShepherdSpecialGoalTaskSelectionTest {

    @Test
    void selectTaskTypeByAvailability_bannerPresentWithoutPlacementTargetAndGatherPen_prefersWheatGather() {
        ShepherdSpecialGoal.TaskType selectedTask = ShepherdSpecialGoal.selectTaskTypeByAvailability(
                true,
                false,
                false,
                true,
                true
        );

        assertEquals(ShepherdSpecialGoal.TaskType.WHEAT_GATHER, selectedTask);
    }
}
