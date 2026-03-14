package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackChestTriggerControllerTest {

    @Test
    void isEligibleV2MissingCraftingTableForStage_existingChestMissingTableForEligibleV2AllowsPlacementAfterDelay() {
        boolean beforeDelay = LumberjackChestTriggerController.isEligibleV2MissingCraftingTableForStage(
                LumberjackChestTriggerController.UpgradeStage.CHEST_PAIRED,
                true,
                false,
                true,
                100L,
                80L
        );

        boolean afterDelay = LumberjackChestTriggerController.isEligibleV2MissingCraftingTableForStage(
                LumberjackChestTriggerController.UpgradeStage.CHEST_PAIRED,
                true,
                false,
                true,
                130L,
                80L
        );

        assertFalse(beforeDelay);
        assertTrue(afterDelay);
    }
}
