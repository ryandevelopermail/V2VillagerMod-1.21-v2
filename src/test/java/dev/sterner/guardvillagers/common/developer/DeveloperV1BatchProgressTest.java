package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperV1BatchProgressTest {
    @Test
    void expandsRepeatedAndMixedProfessionsInStableOrder() {
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 2),
                new DeveloperProfessionSelection(DeveloperProfession.FLETCHER, 1)));

        assertEquals(3, progress.total());
        assertEquals(DeveloperProfession.FARMER, progress.currentProfession());
        progress.markPrepared();
        progress.markSubjectReleased();
        progress.finishCurrent(true);
        assertEquals(DeveloperProfession.FARMER, progress.currentProfession());
        progress.markPrepared();
        progress.markSubjectReleased();
        progress.finishCurrent(true);
        assertEquals(DeveloperProfession.FLETCHER, progress.currentProfession());
    }

    @Test
    void failedVillagerDoesNotPreventRestOfBatch() {
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 1),
                new DeveloperProfessionSelection(DeveloperProfession.SHEPHERD, 1)));

        progress.markPrepared();
        assertTrue(progress.releaseRequired());
        progress.markSubjectReleased();
        progress.finishCurrent(false);

        assertFalse(progress.isComplete());
        assertEquals(DeveloperProfession.SHEPHERD, progress.currentProfession());
        progress.markPrepared();
        progress.markSubjectReleased();
        progress.finishCurrent(true);
        assertTrue(progress.isComplete());
        assertEquals(1, progress.successful());
        assertEquals(1, progress.failed());
        assertEquals(2, progress.processed());
    }

    @Test
    void mixedBatchPlacementGridKeepsEveryJobSiteDistinct() {
        int total = 13;
        Set<DeveloperV1PlacementGrid.Offset> offsets = IntStream.range(0, total)
                .mapToObj(index -> DeveloperV1PlacementGrid.offsetFor(index, total))
                .collect(Collectors.toSet());

        assertEquals(total, offsets.size());
        for (DeveloperV1PlacementGrid.Offset first : offsets) {
            for (DeveloperV1PlacementGrid.Offset second : offsets) {
                if (first.equals(second)) {
                    continue;
                }
                int dx = first.x() - second.x();
                int dz = first.z() - second.z();
                assertTrue(dx * dx + dz * dz >= 36);
            }
        }
    }

    @Test
    void selectAllBatchPermanentlyReservesEveryCompletedJobSite() {
        List<DeveloperProfessionSelection> selections = DeveloperProfession.v1Professions().stream()
                .map(profession -> new DeveloperProfessionSelection(profession, 1))
                .toList();
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(selections);
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments =
                new DeveloperV1JobSiteAssignments<>();

        assertEquals(DeveloperProfession.v1Professions().size(), progress.total());
        assertEquals(progress.total(), progress.tasks().size());

        Set<DeveloperV1PlacementGrid.Offset> completedPositions = new java.util.HashSet<>();
        while (!progress.isComplete()) {
            DeveloperV1BatchProgress.Task task = progress.currentTask();
            assertTrue(completedPositions.add(task.gridSlot()), "Every task must have a unique global grid slot.");
            assertTrue(assignments.begin(task, task.gridSlot()));
            progress.markPrepared();
            assignments.completeCurrent(new UUID(0L, task.index() + 1L));
            progress.markSubjectReleased();
            progress.finishCurrent(true);

            assertEquals(progress.processed(), assignments.completedCount());
            assertTrue(assignments.reservedPositions().containsAll(completedPositions));
        }

        assertEquals(DeveloperProfession.v1Professions().size(), assignments.completedCount());
        assertEquals(completedPositions, assignments.reservedPositions());
    }

    @Test
    void completedPositionCannotBeReusedByNextProfession() {
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 1),
                new DeveloperProfessionSelection(DeveloperProfession.FISHERMAN, 1)));
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments =
                new DeveloperV1JobSiteAssignments<>();

        DeveloperV1BatchProgress.Task farmer = progress.currentTask();
        assertTrue(assignments.begin(farmer, farmer.gridSlot()));
        progress.markPrepared();
        assignments.completeCurrent(new UUID(0L, 1L));
        progress.markSubjectReleased();
        progress.finishCurrent(true);

        DeveloperV1BatchProgress.Task fisherman = progress.currentTask();
        assertFalse(assignments.begin(fisherman, farmer.gridSlot()));
        assertTrue(assignments.begin(fisherman, fisherman.gridSlot()));
        assertTrue(assignments.reservedPositions().contains(farmer.gridSlot()));
    }

    @Test
    void rolledBackInProgressPositionCanBeReusedWithoutReleasingCompletedSites() {
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 1),
                new DeveloperProfessionSelection(DeveloperProfession.FISHERMAN, 1)));
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments =
                new DeveloperV1JobSiteAssignments<>();

        DeveloperV1BatchProgress.Task farmer = progress.tasks().get(0);
        DeveloperV1BatchProgress.Task fisherman = progress.tasks().get(1);
        assertTrue(assignments.begin(farmer, farmer.gridSlot()));
        assignments.completeCurrent(new UUID(0L, 1L));
        assertTrue(assignments.begin(fisherman, fisherman.gridSlot()));
        assignments.rollbackCurrent();

        assertTrue(assignments.begin(fisherman, fisherman.gridSlot()));
        assertTrue(assignments.reservedPositions().contains(farmer.gridSlot()));
        assertEquals(1, assignments.completedCount());
    }

    @Test
    void repeatedProfessionAndMixedQuantitiesKeepUniqueTaskSlots() {
        DeveloperV1BatchProgress repeated = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 4)));
        DeveloperV1BatchProgress mixed = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 2),
                new DeveloperProfessionSelection(DeveloperProfession.FLETCHER, 3),
                new DeveloperProfessionSelection(DeveloperProfession.SHEPHERD, 1)));

        assertEquals(4, repeated.total());
        assertTrue(repeated.tasks().stream().allMatch(task -> task.profession() == DeveloperProfession.FARMER));
        assertEquals(4, repeated.tasks().stream().map(DeveloperV1BatchProgress.Task::gridSlot).distinct().count());
        assertEquals(6, mixed.total());
        assertEquals(6, mixed.tasks().stream().map(DeveloperV1BatchProgress.Task::gridSlot).distinct().count());
    }
}
