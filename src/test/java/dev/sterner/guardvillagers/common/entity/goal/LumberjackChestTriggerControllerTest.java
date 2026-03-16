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

    @Test
    void shouldBlockV2TablePlacement_whenNextDemandIsV1Chest() {
        boolean blocked = LumberjackChestTriggerController.shouldBlockV2TablePlacement(
                LumberjackChestTriggerController.UpgradeDemand.v1Chest(),
                0
        );

        assertTrue(blocked);
    }

    @Test
    void shouldBlockV2TablePlacement_whenAnyEligibleV1VillagerStillMissingChest() {
        boolean blocked = LumberjackChestTriggerController.shouldBlockV2TablePlacement(
                LumberjackChestTriggerController.UpgradeDemand.v2CraftingTable(),
                1
        );

        assertTrue(blocked);
    }

    @Test
    void shouldNotBlockV2TablePlacement_whenNoUnresolvedV1ChestDemandExists() {
        boolean blocked = LumberjackChestTriggerController.shouldBlockV2TablePlacement(
                LumberjackChestTriggerController.UpgradeDemand.v2CraftingTable(),
                0
        );

        assertFalse(blocked);
    }

}
