package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmerImmediateFarmlandDiscoveryTest {
    @Test
    void hoeChestMutationFindsNearbyFarmlandBeforeBackgroundSweepCompletes() {
        FarmerWorkCheckSchedule schedule = new FarmerWorkCheckSchedule();
        schedule.scheduleAt(600L);

        schedule.requestImmediate();
        List<FarmerFarmlandScanPlan.Offset> localOffsets =
                FarmerFarmlandScanPlan.priorityOffsets(16, -1, 1);
        FarmerFarmlandScanPlan.Offset nearbyDirt = new FarmerFarmlandScanPlan.Offset(2, 0, 1);

        assertFalse(schedule.shouldWait(1L));
        assertTrue(schedule.consumeImmediateRequest());
        assertTrue(schedule.consumePriorityScanRequest());
        assertTrue(localOffsets.contains(nearbyDirt));
        assertTrue(FarmerHarvestGoal.isLocalPriorityTerritoryCell(false, true, true));
        assertTrue(FarmerHarvestGoal.canPrepareFarmland(true, true));
    }

    @Test
    void immediatePriorityScanFindsHydratedDirtFifteenBlocksFromJob() {
        List<FarmerFarmlandScanPlan.Offset> offsets =
                FarmerFarmlandScanPlan.priorityOffsets(16, -1, 1);

        assertTrue(offsets.contains(new FarmerFarmlandScanPlan.Offset(15, 0, 0)));
        assertEquals(16, FarmerHarvestGoal.localPriorityDiscoveryRadius(32));
        assertTrue(FarmerHarvestGoal.isLocalPriorityTerritoryCell(false, true, true));
    }

    @Test
    void dirtOutsideSixteenBlocksIsNotInImmediatePriorityScan() {
        List<FarmerFarmlandScanPlan.Offset> offsets =
                FarmerFarmlandScanPlan.priorityOffsets(16, -1, 1);

        assertFalse(offsets.contains(new FarmerFarmlandScanPlan.Offset(17, 0, 0)));
        assertFalse(offsets.contains(new FarmerFarmlandScanPlan.Offset(16, 0, 5)));
    }

    @Test
    void requestImmediateWorkCheckClearsLongScheduledDelay() {
        FarmerWorkCheckSchedule schedule = new FarmerWorkCheckSchedule();
        schedule.scheduleAt(600L);

        schedule.requestImmediate();

        assertEquals(0L, schedule.nextCheckTime());
        assertFalse(schedule.shouldWait(10L));
    }

    @Test
    void debouncedChestWakeStillRequestsPriorityScanAtCoalescedTick() {
        FarmerWorkCheckSchedule schedule = new FarmerWorkCheckSchedule();

        schedule.requestNoSoonerThan(40L);

        assertTrue(schedule.shouldWait(39L));
        assertFalse(schedule.shouldWait(40L));
        assertTrue(schedule.consumePriorityScanRequest());
    }

    @Test
    void repairingFarmerPairingClearsStaleIdleBackoff() {
        FarmerWorkCheckSchedule schedule = new FarmerWorkCheckSchedule();
        schedule.scheduleAt(600L);

        schedule.reset();

        assertEquals(0L, schedule.nextCheckTime());
        assertFalse(schedule.shouldWait(1L));
    }

    @Test
    void incompleteTerritoryWithUnknownActionUsesShortRetry() {
        assertEquals(40, FarmerHarvestGoal.noActionRetryDelay(false));
    }

    @Test
    void completedEmptyTerritoryMayUseLongIdleBackoff() {
        assertEquals(600, FarmerHarvestGoal.noActionRetryDelay(true));
    }

    @Test
    void localPriorityScanIsBoundedAndDoesNotScanConfiguredRadius() {
        int localCells = FarmerFarmlandScanPlan.priorityOffsets(16, -1, 1).size();
        int localCellBudget = FarmerHarvestGoal.localPriorityScanCellBudget(16);
        int fullConfiguredCells = FarmerFarmlandScanPlan.totalCells(32, -1, 1);

        assertTrue(localCells > 0);
        assertTrue(localCells < 2500);
        assertTrue(localCellBudget < fullConfiguredCells);
        assertTrue(localCells < fullConfiguredCells);
    }

    @Test
    void localPriorityScanVisitsClosestHorizontalCellsFirst() {
        List<FarmerFarmlandScanPlan.Offset> offsets =
                FarmerFarmlandScanPlan.priorityOffsets(16, -1, 1);
        int nearIndex = offsets.indexOf(new FarmerFarmlandScanPlan.Offset(1, 0, 0));
        int farIndex = offsets.indexOf(new FarmerFarmlandScanPlan.Offset(7, 0, 0));

        assertTrue(nearIndex >= 0);
        assertTrue(farIndex > nearIndex);
    }

    @Test
    void discoveryRadiusDoesNotIncreaseSessionHoeTargetCap() {
        List<net.minecraft.util.math.BlockPos> candidates = new java.util.ArrayList<>();
        for (int x = 0; x < 64; x++) {
            candidates.add(new net.minecraft.util.math.BlockPos(x, 64, 0));
        }

        List<net.minecraft.util.math.BlockPos> selected = FarmerHarvestGoal.selectCompactHoeTargets(
                candidates, List.of(), new net.minecraft.util.math.BlockPos(0, 64, 0), 32, 2);

        assertEquals(32, selected.size());
    }

    @Test
    void nearestCompactCandidatesWinOverFarScatteredCandidates() {
        net.minecraft.util.math.BlockPos origin = new net.minecraft.util.math.BlockPos(0, 64, 0);
        List<net.minecraft.util.math.BlockPos> candidates = List.of(
                new net.minecraft.util.math.BlockPos(4, 64, 0),
                new net.minecraft.util.math.BlockPos(5, 64, 0),
                new net.minecraft.util.math.BlockPos(6, 64, 0),
                new net.minecraft.util.math.BlockPos(12, 64, 8),
                new net.minecraft.util.math.BlockPos(15, 64, -4));

        List<net.minecraft.util.math.BlockPos> selected = FarmerHarvestGoal.selectCompactHoeTargets(
                candidates, List.of(), origin, 5, 2);

        assertEquals(List.of(
                new net.minecraft.util.math.BlockPos(4, 64, 0),
                new net.minecraft.util.math.BlockPos(5, 64, 0),
                new net.minecraft.util.math.BlockPos(6, 64, 0)), selected);
    }

    @Test
    void existingFarmlandReducesNewExpansionBudget() {
        net.minecraft.util.math.BlockPos origin = new net.minecraft.util.math.BlockPos(0, 64, 0);
        List<net.minecraft.util.math.BlockPos> existing = List.of(
                new net.minecraft.util.math.BlockPos(4, 64, 0),
                new net.minecraft.util.math.BlockPos(4, 64, 1),
                new net.minecraft.util.math.BlockPos(4, 64, 2));
        List<net.minecraft.util.math.BlockPos> candidates = List.of(
                new net.minecraft.util.math.BlockPos(5, 64, 0),
                new net.minecraft.util.math.BlockPos(6, 64, 0),
                new net.minecraft.util.math.BlockPos(7, 64, 0));

        List<net.minecraft.util.math.BlockPos> selected = FarmerHarvestGoal.selectCompactHoeTargets(
                candidates, existing, origin, 4, 2);

        assertEquals(1, selected.size());
        assertEquals(new net.minecraft.util.math.BlockPos(5, 64, 0), selected.getFirst());
    }

    @Test
    void localHydratedDirtBecomesEligibleAndQueueable() {
        assertTrue(FarmerHarvestGoal.isLocalPriorityTerritoryCell(false, true, true));
        assertTrue(FarmerHarvestGoal.shouldQueueHoeTarget(true, true, true, true));
        assertFalse(FarmerHarvestGoal.shouldQueueHoeTarget(true, true, false, true));
    }

    @Test
    void localDiscoveryInvalidatesFarmlandCoverageCache() {
        assertTrue(FarmerHarvestGoal.shouldInvalidateCoverageCache(true));
        assertFalse(FarmerHarvestGoal.shouldInvalidateCoverageCache(false));
    }

    @Test
    void backgroundCursorContinuesAfterIndependentLocalPlan() {
        FarmerFarmlandScanPlan.Slice first = FarmerFarmlandScanPlan.nextSlice(0, 1200, 32, -1, 1);
        FarmerFarmlandScanPlan.priorityOffsets(16, -1, 1);
        FarmerFarmlandScanPlan.Slice second =
                FarmerFarmlandScanPlan.nextSlice(first.nextCursor(), 1200, 32, -1, 1);

        assertEquals(1200, first.nextCursor());
        assertEquals(2400, second.nextCursor());
        assertFalse(FarmerFarmlandScanPlan.completeAfterSlice(false, first));
    }

    @Test
    void wrappedBackgroundSliceMarksTerritoryComplete() {
        int total = FarmerFarmlandScanPlan.totalCells(8, -1, 1);
        FarmerFarmlandScanPlan.Slice last =
                FarmerFarmlandScanPlan.nextSlice(total - 1, 1200, 8, -1, 1);

        assertTrue(FarmerFarmlandScanPlan.completeAfterSlice(false, last));
        assertTrue(FarmerFarmlandScanPlan.completeAfterSlice(true,
                FarmerFarmlandScanPlan.nextSlice(0, 1, 8, -1, 1)));
    }

    @Test
    void vanillaHydrationAcceptsSameLevelAndOneAboveWater() {
        assertTrue(FarmerHarvestGoal.isWithinVanillaHydrationRange(4, 0, 4));
        assertTrue(FarmerHarvestGoal.isWithinVanillaHydrationRange(-4, 1, -4));
    }

    @Test
    void vanillaHydrationRejectsOutsideHorizontalOrVerticalRange() {
        assertFalse(FarmerHarvestGoal.isWithinVanillaHydrationRange(5, 0, 0));
        assertFalse(FarmerHarvestGoal.isWithinVanillaHydrationRange(0, 0, -5));
        assertFalse(FarmerHarvestGoal.isWithinVanillaHydrationRange(0, 2, 0));
    }

    @Test
    void waterSeveralBlocksBelowDoesNotHydrateCandidate() {
        assertFalse(FarmerHarvestGoal.isWithinVanillaHydrationRange(0, -1, 0));
        assertFalse(FarmerHarvestGoal.isWithinVanillaHydrationRange(0, -3, 0));
    }

    @Test
    void seedAndPlantingFlowRemainsReachableAfterHoeing() {
        assertTrue(FarmerHarvestGoal.shouldContinueSeedFlowAfterHoeing(true, true));
        assertFalse(FarmerHarvestGoal.shouldContinueSeedFlowAfterHoeing(true, false));
        assertFalse(FarmerHarvestGoal.shouldContinueSeedFlowAfterHoeing(false, true));
    }
}
