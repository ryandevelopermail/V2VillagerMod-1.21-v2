package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmerFarmlandScanPlanTest {
    @Test
    void eligibleGroundInsideRangeIsVisited() {
        assertTrue(visitFullSweep(8).contains(new FarmerFarmlandScanPlan.Offset(2, 0, 3)));
    }

    @Test
    void configuredBoundaryIsIncluded() {
        assertTrue(FarmerFarmlandScanPlan.isInsideHorizontalRadius(
                new FarmerFarmlandScanPlan.Offset(8, 0, 0), 8));
    }

    @Test
    void outsideConfiguredRangeIsExcluded() {
        assertFalse(FarmerFarmlandScanPlan.isInsideHorizontalRadius(
                new FarmerFarmlandScanPlan.Offset(9, 0, 0), 8));
    }

    @Test
    void largeRangeRequiresMultipleBudgetedPasses() {
        FarmerFarmlandScanPlan.Slice first = FarmerFarmlandScanPlan.nextSlice(0, 100, 32, -1, 1);
        assertEquals(100, first.offsets().size());
        assertEquals(100, first.nextCursor());
        assertFalse(first.wrapped());
    }

    @Test
    void completedSweepWrapsToStart() {
        int total = FarmerFarmlandScanPlan.totalCells(4, -1, 1);
        FarmerFarmlandScanPlan.Slice last = FarmerFarmlandScanPlan.nextSlice(total - 7, 100, 4, -1, 1);
        assertEquals(7, last.offsets().size());
        assertEquals(0, last.nextCursor());
        assertTrue(last.wrapped());
    }

    @Test
    void fullSweepVisitsEveryCellExactlyOnce() {
        int radius = 5;
        assertEquals(FarmerFarmlandScanPlan.totalCells(radius, -1, 1), visitFullSweep(radius).size());
    }

    @Test
    void separatedHydratedPatchesRemainDiscoverable() {
        Set<FarmerFarmlandScanPlan.Offset> visited = visitFullSweep(16);
        assertTrue(visited.contains(new FarmerFarmlandScanPlan.Offset(-12, 0, -4)));
        assertTrue(visited.contains(new FarmerFarmlandScanPlan.Offset(13, 0, 2)));
    }

    @Test
    void timedOutTargetIsRediscoveredOnNextSweep() {
        FarmerFarmlandScanPlan.Offset target = new FarmerFarmlandScanPlan.Offset(3, 0, 3);
        assertTrue(visitFullSweep(8).contains(target));
        assertTrue(visitFullSweep(8).contains(target));
    }

    @Test
    void perPassWorkNeverExceedsBudget() {
        int budget = 37;
        int cursor = 0;
        do {
            FarmerFarmlandScanPlan.Slice slice = FarmerFarmlandScanPlan.nextSlice(cursor, budget, 20, -1, 1);
            assertTrue(slice.offsets().size() <= budget);
            cursor = slice.nextCursor();
        } while (cursor != 0);
    }

    @Test
    void expandedWorkRangeAddsOuterCoverageWithoutUsingHarvestRadius() {
        assertTrue(FarmerFarmlandScanPlan.totalCells(32, -1, 1)
                > FarmerFarmlandScanPlan.totalCells(16, -1, 1));
        assertTrue(FarmerFarmlandScanPlan.isInsideHorizontalRadius(
                new FarmerFarmlandScanPlan.Offset(32, 0, 0), 32));
    }

    @Test
    void hoeingReadinessDoesNotDependOnSeedsOrSeedSources() {
        assertTrue(FarmerHarvestGoal.canPrepareFarmland(true, true));
        assertFalse(FarmerHarvestGoal.canPrepareFarmland(false, true));
        assertFalse(FarmerHarvestGoal.canPrepareFarmland(true, false));
    }

    @Test
    void vanillaHydrationIncludesDiagonalBoundaryWater() {
        assertTrue(FarmerHarvestGoal.isWithinHydrationRange(4, 4, 4));
        assertFalse(FarmerHarvestGoal.isWithinHydrationRange(5, 0, 4));
    }

    private static Set<FarmerFarmlandScanPlan.Offset> visitFullSweep(int radius) {
        Set<FarmerFarmlandScanPlan.Offset> visited = new HashSet<>();
        int cursor = 0;
        do {
            FarmerFarmlandScanPlan.Slice slice = FarmerFarmlandScanPlan.nextSlice(cursor, 73, radius, -1, 1);
            visited.addAll(slice.offsets());
            cursor = slice.nextCursor();
        } while (cursor != 0);
        return visited;
    }
}
