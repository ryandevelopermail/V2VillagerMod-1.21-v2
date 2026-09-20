package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
                FarmerFarmlandScanPlan.priorityOffsets(32, -1, 1);
        FarmerFarmlandScanPlan.Offset nearbyDirt = new FarmerFarmlandScanPlan.Offset(2, 0, 1);

        assertFalse(schedule.shouldWait(1L));
        assertTrue(schedule.consumeImmediateRequest());
        assertTrue(schedule.consumePriorityScanRequest());
        assertTrue(localOffsets.contains(nearbyDirt));
        assertTrue(FarmerHarvestGoal.isLocalPriorityTerritoryCell(false, true, true));
        assertTrue(FarmerHarvestGoal.canPrepareFarmland(true, true));
    }

    @Test
    void immediatePriorityScanFindsHydratedDirtWithinPrimaryRadius() {
        List<FarmerFarmlandScanPlan.Offset> offsets =
                FarmerFarmlandScanPlan.priorityOffsets(32, -1, 1);

        assertTrue(offsets.contains(new FarmerFarmlandScanPlan.Offset(32, 0, 0)));
        assertEquals(32, FarmerHarvestGoal.localPriorityDiscoveryRadius(64));
        assertTrue(FarmerHarvestGoal.isLocalPriorityTerritoryCell(false, true, true));
    }

    @Test
    void hydratedHoeTargetWithinThirtyTwoPreventsFallback() {
        FarmerFarmlandScanPlan.Offset target = new FarmerFarmlandScanPlan.Offset(32, 0, 0);
        boolean targetInPrimary = FarmerFarmlandScanPlan.priorityOffsets(32, -1, 1).contains(target);
        int primaryActionableTargets = FarmerHarvestGoal.shouldQueueHoeTarget(
                targetInPrimary,
                true,
                true,
                true) ? 1 : 0;

        assertFalse(FarmerHarvestGoal.shouldRunLocalPriorityFallback(64, primaryActionableTargets));
        assertEquals(32, FarmerHarvestGoal.localPriorityFinalRadius(64, primaryActionableTargets));
    }

    @Test
    void hydratedHoeTargetInFallbackRingCanBeDiscovered() {
        List<FarmerFarmlandScanPlan.Offset> fallbackOffsets =
                FarmerFarmlandScanPlan.priorityRingOffsets(32, 64, -1, 1);
        FarmerFarmlandScanPlan.Offset target = new FarmerFarmlandScanPlan.Offset(60, 0, 0);

        assertTrue(FarmerHarvestGoal.shouldRunLocalPriorityFallback(64, 0));
        assertEquals(64, FarmerHarvestGoal.localPriorityFinalRadius(64, 0));
        assertTrue(fallbackOffsets.contains(new FarmerFarmlandScanPlan.Offset(33, 0, 0)));
        assertTrue(fallbackOffsets.contains(target));
        assertTrue(FarmerHarvestGoal.shouldQueueHoeTarget(
                fallbackOffsets.contains(target),
                true,
                true,
                true));
    }

    @Test
    void unrelatedWaterInsidePrimaryRadiusDoesNotSuppressFallback() {
        boolean waterPresent = true;
        boolean hoeTargetPresent = false;
        boolean actionableTarget = FarmerHarvestGoal.isLocalPriorityTerritoryCell(
                false,
                hoeTargetPresent,
                waterPresent);

        assertFalse(actionableTarget);
        assertTrue(FarmerHarvestGoal.shouldRunLocalPriorityFallback(64, 0));
    }

    @Test
    void fallbackRingDoesNotRescanInnerCells() {
        List<FarmerFarmlandScanPlan.Offset> primaryOffsets =
                FarmerFarmlandScanPlan.priorityOffsets(32, -1, 1);
        List<FarmerFarmlandScanPlan.Offset> fallbackOffsets =
                FarmerFarmlandScanPlan.priorityRingOffsets(32, 64, -1, 1);
        Set<FarmerFarmlandScanPlan.Offset> allOffsets = new HashSet<>(primaryOffsets);

        assertTrue(allOffsets.stream().noneMatch(fallbackOffsets::contains));
        allOffsets.addAll(fallbackOffsets);
        assertEquals(FarmerFarmlandScanPlan.priorityOffsets(64, -1, 1).size(), allOffsets.size());
    }

    @Test
    void radiusSixtyFiveAndBeyondIsExcluded() {
        List<FarmerFarmlandScanPlan.Offset> fallbackOffsets =
                FarmerFarmlandScanPlan.priorityRingOffsets(32, 64, -1, 1);

        assertFalse(fallbackOffsets.contains(new FarmerFarmlandScanPlan.Offset(65, 0, 0)));
        assertEquals(64, FarmerHarvestGoal.localPriorityFinalRadius(999, 0));
    }

    @Test
    void configuredRadiiBelowSixtyFourAreRespected() {
        assertEquals(24, FarmerHarvestGoal.localPriorityDiscoveryRadius(24));
        assertFalse(FarmerHarvestGoal.shouldRunLocalPriorityFallback(24, 0));
        assertEquals(24, FarmerHarvestGoal.localPriorityFinalRadius(24, 0));
        assertFalse(FarmerFarmlandScanPlan.priorityOffsets(24, -1, 1)
                .contains(new FarmerFarmlandScanPlan.Offset(25, 0, 0)));

        assertEquals(32, FarmerHarvestGoal.localPriorityDiscoveryRadius(48));
        assertTrue(FarmerHarvestGoal.shouldRunLocalPriorityFallback(48, 0));
        assertEquals(48, FarmerHarvestGoal.localPriorityFinalRadius(48, 0));
        assertEquals(32, FarmerHarvestGoal.localPriorityFinalRadius(48, 1));
        assertFalse(FarmerFarmlandScanPlan.priorityRingOffsets(32, 48, -1, 1)
                .contains(new FarmerFarmlandScanPlan.Offset(49, 0, 0)));
    }

    @Test
    void defaultConfiguredFarmlandWorkRadiusIsSixtyFour() {
        assertEquals(64, GuardVillagersConfig.DEFAULT_FARMER_FARMLAND_WORK_RADIUS);
        assertEquals(GuardVillagersConfig.DEFAULT_FARMER_FARMLAND_WORK_RADIUS,
                GuardVillagersConfig.farmerFarmlandWorkRadius);
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
    void primaryPriorityScanIsBoundedAndDoesNotScanFallbackRadius() {
        int localCells = FarmerFarmlandScanPlan.priorityOffsets(32, -1, 1).size();
        int localCellBudget = FarmerHarvestGoal.localPriorityScanCellBudget(32);
        int fullConfiguredCells = FarmerFarmlandScanPlan.totalCells(64, -1, 1);

        assertTrue(localCells > 0);
        assertTrue(localCells < 10_000);
        assertTrue(localCellBudget < fullConfiguredCells);
        assertTrue(localCells < fullConfiguredCells);
    }

    @Test
    void scanAccountingDistinguishesPrimaryOnlyAndFallbackPasses() {
        int primaryOnlyCells = FarmerHarvestGoal.localPriorityScanCellBudget(64, 1);
        int fallbackCells = FarmerHarvestGoal.localPriorityScanCellBudget(64, 0);

        assertEquals(FarmerHarvestGoal.localPriorityScanCellBudget(32), primaryOnlyCells);
        assertEquals(FarmerHarvestGoal.localPriorityScanCellBudget(64), fallbackCells);
        assertTrue(fallbackCells > primaryOnlyCells);
    }

    @Test
    void localPriorityScanVisitsClosestHorizontalCellsFirst() {
        List<FarmerFarmlandScanPlan.Offset> offsets =
                FarmerFarmlandScanPlan.priorityOffsets(32, -1, 1);
        int nearIndex = offsets.indexOf(new FarmerFarmlandScanPlan.Offset(1, 0, 0));
        int farIndex = offsets.indexOf(new FarmerFarmlandScanPlan.Offset(7, 0, 0));

        assertTrue(nearIndex >= 0);
        assertTrue(farIndex > nearIndex);
    }

    @Test
    void discoveryRadiusDoesNotIncreaseSessionHoeTargetCap() {
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = 0; x < 64; x++) {
            candidates.add(new BlockPos(x, 64, 0));
        }

        List<BlockPos> selected = FarmerHarvestGoal.selectCompactHoeTargets(
                candidates, List.of(), new BlockPos(0, 64, 0), 32, 2);

        assertEquals(32, selected.size());
    }

    @Test
    void nearestCompactCandidatesWinOverFarScatteredCandidates() {
        BlockPos origin = new BlockPos(0, 64, 0);
        List<BlockPos> candidates = List.of(
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(12, 64, 8),
                new BlockPos(15, 64, -4));

        List<BlockPos> selected = FarmerHarvestGoal.selectCompactHoeTargets(
                candidates, List.of(), origin, 5, 2);

        assertEquals(List.of(
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0)), selected);
    }

    @Test
    void existingFarmlandReducesNewExpansionBudget() {
        BlockPos origin = new BlockPos(0, 64, 0);
        List<BlockPos> existing = List.of(
                new BlockPos(4, 64, 0),
                new BlockPos(4, 64, 1),
                new BlockPos(4, 64, 2));
        List<BlockPos> candidates = List.of(
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0));

        List<BlockPos> selected = FarmerHarvestGoal.selectCompactHoeTargets(
                candidates, existing, origin, 4, 2);

        assertEquals(1, selected.size());
        assertEquals(new BlockPos(5, 64, 0), selected.getFirst());
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
        FarmerFarmlandScanPlan.Slice first = FarmerFarmlandScanPlan.nextSlice(0, 1200, 64, -1, 1);
        FarmerFarmlandScanPlan.priorityOffsets(32, -1, 1);
        FarmerFarmlandScanPlan.priorityRingOffsets(32, 64, -1, 1);
        FarmerFarmlandScanPlan.Slice second =
                FarmerFarmlandScanPlan.nextSlice(first.nextCursor(), 1200, 64, -1, 1);

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
