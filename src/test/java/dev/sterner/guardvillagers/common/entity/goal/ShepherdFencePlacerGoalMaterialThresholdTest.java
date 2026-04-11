package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShepherdFencePlacerGoalMaterialThresholdTest {

    @Test
    void largerFenceStacksStillSatisfyBuildTriggerThreshold() {
        assertTrue(ShepherdFencePlacerGoal.hasRequiredPenMaterials(24, 1));
        assertTrue(ShepherdFencePlacerGoal.hasRequiredPenMaterials(48, 1));
    }

    @Test
    void missingMinimumFenceOrGateStillBlocksBuildTrigger() {
        assertFalse(ShepherdFencePlacerGoal.hasRequiredPenMaterials(23, 1));
        assertFalse(ShepherdFencePlacerGoal.hasRequiredPenMaterials(24, 0));
    }
}
