package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void resolveV3PenDemand_mixedFenceTypesMeetingThreshold_doesNotRequestRedundantFenceCrafting() {
        SimpleInventory chestInventory = new SimpleInventory(3);
        chestInventory.setStack(0, new ItemStack(Items.OAK_FENCE, 10));
        chestInventory.setStack(1, new ItemStack(Items.SPRUCE_FENCE, 10));
        chestInventory.setStack(2, new ItemStack(Items.OAK_FENCE_GATE, 1));

        LumberjackChestTriggerController.UpgradeDemand demand =
                LumberjackChestTriggerController.resolveV3PenDemand(chestInventory);

        assertNull(demand);
    }

    @Test
    void resolveV3PenDemand_mixedFenceGateTypesMeetingThreshold_doesNotRequestRedundantGateCrafting() {
        SimpleInventory chestInventory = new SimpleInventory(4);
        chestInventory.setStack(0, new ItemStack(Items.OAK_FENCE, 20));
        chestInventory.setStack(1, new ItemStack(Items.SPRUCE_FENCE_GATE, 1));
        chestInventory.setStack(2, new ItemStack(Items.BIRCH_FENCE_GATE, 1));

        LumberjackChestTriggerController.UpgradeDemand demand =
                LumberjackChestTriggerController.resolveV3PenDemand(chestInventory);

        assertNull(demand);
    }

    @Test
    void resolveV3PenDemand_belowFenceThresholdAcrossMixedTypes_requestsFenceDemand() {
        SimpleInventory chestInventory = new SimpleInventory(3);
        chestInventory.setStack(0, new ItemStack(Items.OAK_FENCE, 8));
        chestInventory.setStack(1, new ItemStack(Items.SPRUCE_FENCE, 11));
        chestInventory.setStack(2, new ItemStack(Items.OAK_FENCE_GATE, 1));

        LumberjackChestTriggerController.UpgradeDemand demand =
                LumberjackChestTriggerController.resolveV3PenDemand(chestInventory);

        assertEquals(LumberjackChestTriggerController.UpgradeDemand.v3Fence(), demand);
    }

    @Test
    void resolveV3PenDemand_missingGateDespiteFenceThreshold_requestsFenceGateDemand() {
        SimpleInventory chestInventory = new SimpleInventory(2);
        chestInventory.setStack(0, new ItemStack(Items.OAK_FENCE, 12));
        chestInventory.setStack(1, new ItemStack(Items.SPRUCE_FENCE, 8));

        LumberjackChestTriggerController.UpgradeDemand demand =
                LumberjackChestTriggerController.resolveV3PenDemand(chestInventory);

        assertEquals(LumberjackChestTriggerController.UpgradeDemand.v3FenceGate(), demand);
    }

}
