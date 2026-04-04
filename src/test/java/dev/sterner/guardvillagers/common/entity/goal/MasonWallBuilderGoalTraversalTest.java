package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonWallBuilderGoalTraversalTest {

    @Test
    void computePerimeterTraversal_includesSamePerimeterCoordinatesAsOppositeFaceOrdering() {
        int minX = 3;
        int minZ = -4;
        int maxX = 8;
        int maxZ = 2;
        int y = 70;

        List<BlockPos> clockwise = MasonWallBuilderGoal.computePerimeterTraversal(minX, minZ, maxX, maxZ, y);
        Set<BlockPos> previousOrdering = new HashSet<>();

        for (int x = minX; x <= maxX; x++) {
            previousOrdering.add(new BlockPos(x, y, minZ));
            previousOrdering.add(new BlockPos(x, y, maxZ));
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            previousOrdering.add(new BlockPos(minX, y, z));
            previousOrdering.add(new BlockPos(maxX, y, z));
        }

        assertEquals(previousOrdering.size(), clockwise.size());
        assertEquals(previousOrdering, new HashSet<>(clockwise));
    }

    @Test
    void computePerimeterTraversal_flatRectangleHasLocalSuccessiveSteps() {
        List<BlockPos> traversal = MasonWallBuilderGoal.computePerimeterTraversal(10, 20, 16, 26, 64);

        for (int i = 1; i < traversal.size(); i++) {
            int distance = manhattan2d(traversal.get(i - 1), traversal.get(i));
            assertTrue(distance <= 1, "Expected neighboring perimeter steps, got distance=" + distance + " at index " + i);
        }
    }

    private static int manhattan2d(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    @Test
    void batchedSorties_reduceAverageTravelAndMeetMinimumBatchWhenLocalCandidatesExist() {
        List<BlockPos> rectanglePerimeter = MasonWallBuilderGoal.computePerimeterTraversal(0, 0, 16, 10, 64);
        List<BlockPos> oppositeFaceOrdering = new java.util.ArrayList<>();
        int half = rectanglePerimeter.size() / 2;
        for (int i = 0; i < half; i++) {
            oppositeFaceOrdering.add(rectanglePerimeter.get(i));
            oppositeFaceOrdering.add(rectanglePerimeter.get(i + half));
        }

        BlockPos start = new BlockPos(0, 64, 0);
        MasonWallBuilderGoal.TraversalSimulationResult sequential =
                MasonWallBuilderGoal.simulateSequentialTraversal(oppositeFaceOrdering, start);
        MasonWallBuilderGoal.TraversalSimulationResult batched =
                MasonWallBuilderGoal.simulateBatchedTraversal(oppositeFaceOrdering, start);

        assertTrue(batched.averageTravelPerPlacement() < sequential.averageTravelPerPlacement(),
                "Expected batching to reduce average travel distance per placement");
        assertEquals(batched.sortiesWithMinCandidates(), batched.sortiesMeetingMinPlacements(),
                "Every sortie with >=3 nearby candidates should place at least 3 segments");
    }

    @Test
    void layerOneFirstLapSelectionPolicy_wrapsMonotonicallyBeforeLapCompletion() {
        List<BlockPos> perimeter = MasonWallBuilderGoal.computePerimeterTraversal(0, 0, 4, 3, 64);
        int startIndex = perimeter.size() - 2;
        int selections = perimeter.size() - 1;

        List<BlockPos> selected = MasonWallBuilderGoal.simulateLayerOneFirstLapAnchorOrder(perimeter, startIndex, selections);

        assertEquals(selections, selected.size(), "Should select each requested layer-one anchor before lap completion");
        for (int i = 1; i < selected.size(); i++) {
            int previousIndex = perimeter.indexOf(selected.get(i - 1));
            int expectedIndex = (previousIndex + 1) % perimeter.size();
            int actualIndex = perimeter.indexOf(selected.get(i));
            assertEquals(expectedIndex, actualIndex,
                    "Layer-one first-lap policy should advance by exactly one perimeter index (with wraparound)");
        }
    }

    @Test
    void layerOneFirstLapSelectionPolicy_skipsIntermittentBlockedSegmentsWithoutRegionPingPong() {
        List<BlockPos> perimeter = MasonWallBuilderGoal.computePerimeterTraversal(0, 0, 5, 4, 64);
        int startIndex = perimeter.size() - 1;
        Set<Integer> temporarilyBlocked = Set.of(0, 2, 5, 9, 13);
        int selections = perimeter.size() - temporarilyBlocked.size();

        List<BlockPos> selected = MasonWallBuilderGoal.simulateLayerOneFirstLapAnchorOrderWithTemporaryBlocks(
                perimeter,
                startIndex,
                temporarilyBlocked,
                selections
        );

        assertEquals(selections, selected.size(), "Should still select all non-blocked first-lap anchors");
        for (int i = 1; i < selected.size(); i++) {
            int previousIndex = perimeter.indexOf(selected.get(i - 1));
            int actualIndex = perimeter.indexOf(selected.get(i));
            int expectedIndex = (previousIndex + 1) % perimeter.size();
            while (temporarilyBlocked.contains(expectedIndex)) {
                expectedIndex = (expectedIndex + 1) % perimeter.size();
            }
            assertEquals(expectedIndex, actualIndex,
                    "Selection must continue forward around the perimeter, skipping blocked slots without region resets");
        }
    }

    @Test
    void pivotAnchorSelection_doesNotEscalateToHigherLayersWhenLayerOnePendingExists() {
        List<Integer> segmentLayers = List.of(1, 2, 3, 2, 3);
        List<Boolean> buildableStates = List.of(false, true, true, true, true);

        int selectedLayer = MasonWallBuilderGoal.simulatePivotAnchorLayerSelection(segmentLayers, buildableStates, 1);

        assertEquals(-1, selectedLayer,
                "Pivot anchor selection must return null-equivalent when active layer has no buildable candidate");
    }
}
