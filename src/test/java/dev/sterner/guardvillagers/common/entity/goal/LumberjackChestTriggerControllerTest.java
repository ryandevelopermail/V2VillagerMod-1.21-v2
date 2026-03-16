package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackChestTriggerControllerTest {

    @Test
    void nearbyChestWithUnpairedStageDoesNotPermitTablePlacement() {
        boolean allowed = LumberjackChestTriggerController.isEligibleV2MissingCraftingTableForStage(
                LumberjackChestTriggerController.UpgradeStage.UNPAIRED,
                true,
                false,
                true,
                130L,
                80L
        );

        assertFalse(allowed);
    }

    @Test
    void tablePlacementPermittedOnlyAfterChestPairedTransitionAndDelay() {
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
    void restartHydrationPathDoesNotInventChestPairedFromUnpairedPersistence() {
        assertFalse(LumberjackChestTriggerController.canHydrateFromPersistedStage(
                LumberjackChestTriggerController.UpgradeStage.UNPAIRED
        ));
        assertTrue(LumberjackChestTriggerController.canHydrateFromPersistedStage(
                LumberjackChestTriggerController.UpgradeStage.CHEST_PAIRED
        ));
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
