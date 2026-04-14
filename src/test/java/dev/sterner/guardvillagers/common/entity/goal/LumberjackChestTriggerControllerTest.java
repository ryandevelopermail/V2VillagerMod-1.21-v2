package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

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
    void reachableV1MissingChest_stillBlocksV2Placement() {
        boolean blocked = LumberjackChestTriggerController.shouldBlockV2TablePlacement(
                LumberjackChestTriggerController.UpgradeDemand.v2CraftingTable(),
                1
        );

        assertTrue(blocked);
    }

    @Test
    void unreachableV1MissingChest_doesNotBlockV2Forever() {
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

    @Test
    void computeCappedRetryDelay_doublesUntilCap() {
        long base = 600L;
        long cap = 2_400L;
        assertEquals(600L, LumberjackChestTriggerController.computeCappedRetryDelay(base, cap, 1));
        assertEquals(1_200L, LumberjackChestTriggerController.computeCappedRetryDelay(base, cap, 2));
        assertEquals(2_400L, LumberjackChestTriggerController.computeCappedRetryDelay(base, cap, 3));
        assertEquals(2_400L, LumberjackChestTriggerController.computeCappedRetryDelay(base, cap, 5));
    }

    @Test
    void immediateUpgradeBatch_promotesMultipleV1CandidatesInOnePass() {
        AtomicInteger v1Placements = new AtomicInteger();
        AtomicInteger v2Placements = new AtomicInteger();

        boolean placedAny = LumberjackChestTriggerController.runImmediateVillageUpgradePassBatch(
                () -> v1Placements.incrementAndGet() <= 2,
                () -> false,
                () -> {
                    v2Placements.incrementAndGet();
                    return false;
                },
                12
        );

        assertTrue(placedAny);
        assertEquals(3, v1Placements.get(), "V1 should be retried until first non-actionable attempt.");
        assertEquals(1, v2Placements.get(), "V2 should still be evaluated after V1 backlog clears.");
    }

    @Test
    void immediateUpgradeBatch_runsV2PlacementsInSamePassAfterV1BacklogClears() {
        AtomicInteger v1Calls = new AtomicInteger();
        AtomicInteger v2Calls = new AtomicInteger();

        boolean placedAny = LumberjackChestTriggerController.runImmediateVillageUpgradePassBatch(
                () -> v1Calls.incrementAndGet() == 1,
                () -> false,
                () -> v2Calls.incrementAndGet() <= 2,
                12
        );

        assertTrue(placedAny);
        assertEquals(2, v1Calls.get(), "V1 should run until non-actionable before V2 starts.");
        assertEquals(3, v2Calls.get(), "V2 should place repeatedly in the same pass.");
    }

}
