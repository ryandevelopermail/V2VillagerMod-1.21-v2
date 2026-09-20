package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperV1TerrainPlannerTest {
    @Test
    void flatTerrainKeepsPreferredCompactGrid() {
        List<DeveloperV1TerrainPlanner.Site> reserved = new ArrayList<>();
        DeveloperV1BatchProgress progress = progress(9, DeveloperProfession.FARMER);

        for (DeveloperV1BatchProgress.Task task : progress.tasks()) {
            DeveloperV1TerrainPlanner.SearchResult<String> result = planTask(
                    task, 0, 0, 64, reserved, (x, z) -> candidate(x, 64, z));
            DeveloperV1TerrainPlanner.Site selected = result.selection().orElseThrow().site();
            assertEquals(task.gridSlot().x(), selected.x());
            assertEquals(task.gridSlot().z(), selected.z());
            reserved.add(selected);
        }

        assertTrue(reserved.stream().allMatch(site -> Math.abs(site.x()) <= 4));
        assertTrue(reserved.stream().allMatch(site -> Math.abs(site.z()) <= 4));
    }

    @Test
    void invalidPreferredAnchorRelocatesNearby() {
        Set<String> invalid = Set.of("0,0");
        DeveloperV1TerrainPlanner.SearchResult<String> result = plan(
                0, 0, 64, List.of(),
                (x, z) -> invalid.contains(x + "," + z) ? Optional.empty() : candidate(x, 64, z));

        assertEquals(new DeveloperV1TerrainPlanner.Site(-1, 64, 0),
                result.selection().orElseThrow().site());
    }

    @Test
    void neighboringProfessionSitesMayUseDifferentSurfaceHeights() {
        List<DeveloperV1TerrainPlanner.Site> reserved = new ArrayList<>();
        DeveloperV1TerrainPlanner.Selection<String> first = plan(
                0, 0, 64, reserved, (x, z) -> candidate(x, 64, z)).selection().orElseThrow();
        reserved.add(first.site());
        DeveloperV1TerrainPlanner.Selection<String> second = plan(
                4, 0, 64, reserved, (x, z) -> candidate(x, x >= 4 ? 65 : 64, z))
                .selection().orElseThrow();

        assertEquals(64, first.site().y());
        assertEquals(65, second.site().y());
        assertEquals(4, second.site().x());
    }

    @Test
    void gentleSteppedTerrainSupportsMultiplePairs() {
        DeveloperV1BatchProgress progress = progress(6, DeveloperProfession.FARMER);
        List<DeveloperV1TerrainPlanner.Site> reserved = new ArrayList<>();
        for (DeveloperV1BatchProgress.Task task : progress.tasks()) {
            DeveloperV1TerrainPlanner.SearchResult<String> result = planTask(
                    task, 20, 30, 70, reserved,
                    (x, z) -> candidate(x, 70 + Math.floorDiv(x - 20, 4), z));
            reserved.add(result.selection().orElseThrow().site());
        }

        assertEquals(6, reserved.size());
        assertTrue(reserved.stream().map(DeveloperV1TerrainPlanner.Site::y).distinct().count() > 1);
    }

    @Test
    void oneInvalidGridCellDoesNotFailWholeBatch() {
        DeveloperV1BatchProgress progress = progress(4, DeveloperProfession.FARMER);
        List<DeveloperV1TerrainPlanner.Site> reserved = new ArrayList<>();
        for (DeveloperV1BatchProgress.Task task : progress.tasks()) {
            int invalidX = task.index() == 1 ? task.gridSlot().x() : Integer.MIN_VALUE;
            int invalidZ = task.index() == 1 ? task.gridSlot().z() : Integer.MIN_VALUE;
            DeveloperV1TerrainPlanner.SearchResult<String> result = planTask(
                    task, 0, 0, 64, reserved,
                    (x, z) -> x == invalidX && z == invalidZ ? Optional.empty() : candidate(x, 64, z));
            reserved.add(result.selection().orElseThrow().site());
        }

        assertEquals(4, reserved.size());
        assertEquals(4, Set.copyOf(reserved).size());
    }

    @Test
    void occupiedAndObstructedCellsAreSkipped() {
        Set<String> blocked = Set.of("0,0", "-1,0", "0,-1", "0,1");
        DeveloperV1TerrainPlanner.SearchResult<String> result = plan(
                0, 0, 64, List.of(),
                (x, z) -> blocked.contains(x + "," + z) ? Optional.empty() : candidate(x, 64, z));

        assertEquals(new DeveloperV1TerrainPlanner.Site(1, 64, 0),
                result.selection().orElseThrow().site());
    }

    @Test
    void reservedJobSiteSpacingIsPreservedAfterRelocation() {
        DeveloperV1TerrainPlanner.Site occupied = new DeveloperV1TerrainPlanner.Site(0, 64, 0);
        DeveloperV1TerrainPlanner.SearchResult<String> result = plan(
                0, 0, 64, List.of(occupied), (x, z) -> candidate(x, 64, z));
        DeveloperV1TerrainPlanner.Site selected = result.selection().orElseThrow().site();
        long dx = selected.x() - occupied.x();
        long dz = selected.z() - occupied.z();

        assertTrue(dx * dx + dz * dz >= 9);
        assertEquals(new DeveloperV1TerrainPlanner.Site(-3, 64, 0), selected);
    }

    @Test
    void unsuccessfulSearchIsBounded() {
        AtomicInteger calls = new AtomicInteger();
        DeveloperV1TerrainPlanner.SearchResult<String> result = plan(
                0, 0, 64, List.of(), (x, z) -> {
                    calls.incrementAndGet();
                    return Optional.empty();
                });

        int side = DeveloperV1TerrainPlanner.MAX_SEARCH_RADIUS * 2 + 1;
        assertTrue(result.selection().isEmpty());
        assertEquals(side * side, result.candidateLimit());
        assertEquals(result.candidateLimit(), result.evaluatedCandidates());
        assertEquals(result.candidateLimit(), calls.get());
    }

    @Test
    void candidateOrderingAndTieBreakingAreDeterministic() {
        List<DeveloperV1TerrainPlanner.Offset> first =
                DeveloperV1TerrainPlanner.orderedOffsets(4);
        List<DeveloperV1TerrainPlanner.Offset> second =
                DeveloperV1TerrainPlanner.orderedOffsets(4);
        assertEquals(first, second);

        DeveloperV1TerrainPlanner.Site expected = plan(
                0, 0, 64, List.of(),
                (x, z) -> x == 0 && z == 0 ? Optional.empty() : candidate(x, 64, z))
                .selection().orElseThrow().site();
        for (int repeat = 0; repeat < 5; repeat++) {
            assertEquals(expected, plan(
                    0, 0, 64, List.of(),
                    (x, z) -> x == 0 && z == 0 ? Optional.empty() : candidate(x, 64, z))
                    .selection().orElseThrow().site());
        }
    }

    @Test
    void equalDistanceCandidatesPreferLessAdjustmentThenCloserElevation() {
        DeveloperV1TerrainPlanner.Selection<String> lowerAdjustment = plan(
                0, 0, 64, List.of(),
                (x, z) -> {
                    if (x == -1 && z == 0) {
                        return candidate(x, 64, z, 2);
                    }
                    if (x == 1 && z == 0) {
                        return candidate(x, 70, z, 0);
                    }
                    return Optional.empty();
                }).selection().orElseThrow();
        assertEquals(new DeveloperV1TerrainPlanner.Site(1, 70, 0), lowerAdjustment.site());

        DeveloperV1TerrainPlanner.Selection<String> closerElevation = plan(
                0, 0, 64, List.of(),
                (x, z) -> {
                    if (x == -1 && z == 0) {
                        return candidate(x, 70, z, 0);
                    }
                    if (x == 1 && z == 0) {
                        return candidate(x, 65, z, 0);
                    }
                    return Optional.empty();
                }).selection().orElseThrow();
        assertEquals(new DeveloperV1TerrainPlanner.Site(1, 65, 0), closerElevation.site());
    }

    @Test
    void moreVillagersTaskUsesSameTerrainPlanner() {
        DeveloperV1BatchProgress.Task task = progress(1, DeveloperProfession.OCEANOGRAPHER).tasks().getFirst();
        DeveloperV1TerrainPlanner.Selection<String> selection = planTask(
                task, 10, 10, 72, List.of(), (x, z) -> candidate(x, 72, z))
                .selection().orElseThrow();

        assertEquals(DeveloperProfession.OCEANOGRAPHER, task.profession());
        assertEquals(new DeveloperV1TerrainPlanner.Site(10, 72, 10), selection.site());
    }

    @Test
    void everyRequestedTaskEndsExplicitlyAccountedFor() {
        DeveloperV1BatchProgress progress = progress(5, DeveloperProfession.FARMER);
        Set<Integer> forcedFailures = Set.of(2);
        while (progress.canStart(DeveloperV1PlacementGrid.MAX_CONCURRENT)) {
            DeveloperV1BatchProgress.Task task = progress.startNext();
            progress.finish(task.index(), !forcedFailures.contains(task.index()));
        }
        while (progress.canStart(DeveloperV1PlacementGrid.MAX_CONCURRENT)) {
            DeveloperV1BatchProgress.Task task = progress.startNext();
            progress.finish(task.index(), !forcedFailures.contains(task.index()));
        }

        assertTrue(progress.isComplete());
        assertEquals(progress.total(), progress.successful() + progress.failed());
        assertEquals(4, progress.successful());
        assertEquals(1, progress.failed());
    }

    private static DeveloperV1BatchProgress progress(int quantity, DeveloperProfession profession) {
        return new DeveloperV1BatchProgress(List.of(new DeveloperProfessionSelection(profession, quantity)));
    }

    private static DeveloperV1TerrainPlanner.SearchResult<String> planTask(
            DeveloperV1BatchProgress.Task task,
            int originX,
            int originZ,
            int preferredY,
            List<DeveloperV1TerrainPlanner.Site> reserved,
            BiFunction<Integer, Integer, Optional<DeveloperV1TerrainPlanner.Candidate<String>>> resolver
    ) {
        return plan(
                originX + task.gridSlot().x(),
                originZ + task.gridSlot().z(),
                preferredY,
                reserved,
                resolver);
    }

    private static DeveloperV1TerrainPlanner.SearchResult<String> plan(
            int anchorX,
            int anchorZ,
            int preferredY,
            List<DeveloperV1TerrainPlanner.Site> reserved,
            BiFunction<Integer, Integer, Optional<DeveloperV1TerrainPlanner.Candidate<String>>> resolver
    ) {
        return DeveloperV1TerrainPlanner.select(
                anchorX, anchorZ, preferredY, reserved, resolver::apply);
    }

    private static Optional<DeveloperV1TerrainPlanner.Candidate<String>> candidate(int x, int y, int z) {
        return candidate(x, y, z, 0);
    }

    private static Optional<DeveloperV1TerrainPlanner.Candidate<String>> candidate(
            int x,
            int y,
            int z,
            int terrainAdjustmentCost
    ) {
        return Optional.of(new DeveloperV1TerrainPlanner.Candidate<>(
                new DeveloperV1TerrainPlanner.Site(x, y, z), terrainAdjustmentCost, "valid"));
    }
}
