package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperV1BatchProgressTest {
    @Test
    void allSupportedProfessionsArePreplannedWithCompactUniqueSites() {
        List<DeveloperProfessionSelection> selections = DeveloperProfession.vanillaV1Professions().stream()
                .map(profession -> new DeveloperProfessionSelection(profession, 1))
                .toList();
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(selections);
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments =
                new DeveloperV1JobSiteAssignments<>();
        Set<DeveloperV1PlacementGrid.Offset> plannedPositions = new HashSet<>();
        for (DeveloperV1BatchProgress.Task task : progress.tasks()) {
            assertTrue(plannedPositions.add(task.gridSlot()));
        }

        while (progress.canStart(DeveloperV1PlacementGrid.MAX_CONCURRENT)) {
            DeveloperV1BatchProgress.Task task = progress.startNext();
            assertTrue(assignments.reserve(task, task.gridSlot()));
            assertTrue(assignments.attachVillager(task.index(), villagerId(task.index())));
        }

        assertEquals(13, progress.total());
        assertEquals(DeveloperProfession.vanillaV1Professions().size(), progress.total());
        assertEquals(DeveloperV1PlacementGrid.MAX_CONCURRENT, progress.pending());
        assertEquals(progress.pending(), assignments.pendingCount());
        assertTrue(plannedPositions.stream().allMatch(position -> Math.abs(position.x()) <= 6));
        assertTrue(plannedPositions.stream().allMatch(position -> Math.abs(position.z()) <= 6));
        assertEquals(4, progress.tasks().get(1).gridSlot().x() - progress.tasks().get(0).gridSlot().x());
        for (DeveloperV1PlacementGrid.Offset first : plannedPositions) {
            for (DeveloperV1PlacementGrid.Offset second : plannedPositions) {
                if (first.equals(second)) {
                    continue;
                }
                int dx = first.x() - second.x();
                int dz = first.z() - second.z();
                assertTrue(dx * dx + dz * dz >= 4 * 4);
            }
        }
    }

    @Test
    void pendingTasksCanCompleteInArbitraryOrder() {
        DeveloperV1BatchProgress progress = progress(3);
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments = startAll(progress);

        complete(progress, assignments, 2);
        complete(progress, assignments, 0);
        assertEquals(1, progress.pending());
        assertFalse(progress.isComplete());
        complete(progress, assignments, 1);

        assertTrue(progress.isComplete());
        assertEquals(3, progress.successful());
        assertEquals(0, progress.failed());
    }

    @Test
    void oneTimedOutPairDoesNotBlockTheRollingWindow() {
        DeveloperV1BatchProgress progress = progress(4);
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments =
                new DeveloperV1JobSiteAssignments<>();
        List<DeveloperV1BatchProgress.Task> firstWindow = new ArrayList<>();
        while (progress.canStart(3)) {
            DeveloperV1BatchProgress.Task task = progress.startNext();
            reserve(assignments, task);
            firstWindow.add(task);
        }

        DeveloperV1BatchProgress.Task timedOut = firstWindow.get(1);
        assertTrue(assignments.markUnresolved(timedOut.index()));
        progress.finish(timedOut.index(), false);
        assertTrue(progress.canStart(3));

        DeveloperV1BatchProgress.Task replacement = progress.startNext();
        reserve(assignments, replacement);
        complete(progress, assignments, firstWindow.get(2).index());
        complete(progress, assignments, firstWindow.get(0).index());
        complete(progress, assignments, replacement.index());

        assertTrue(progress.isComplete());
        assertEquals(3, progress.successful());
        assertEquals(1, progress.failed());
    }

    @Test
    void rollingSchedulerKeepsMaximumBatchCompact() {
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 16),
                new DeveloperProfessionSelection(DeveloperProfession.FLETCHER, 16),
                new DeveloperProfessionSelection(DeveloperProfession.SHEPHERD, 16),
                new DeveloperProfessionSelection(DeveloperProfession.LIBRARIAN, 16)));
        List<DeveloperV1BatchProgress.Task> active = new ArrayList<>();

        assertEquals(64, progress.total());
        assertEquals(64, progress.tasks().stream().map(DeveloperV1BatchProgress.Task::gridSlot).distinct().count());
        assertTrue(progress.tasks().stream().allMatch(task -> Math.abs(task.gridSlot().x()) <= 6));
        assertTrue(progress.tasks().stream().allMatch(task -> Math.abs(task.gridSlot().z()) <= 30));

        while (!progress.isComplete()) {
            while (progress.canStart(DeveloperV1PlacementGrid.MAX_CONCURRENT)) {
                active.add(progress.startNext());
            }
            assertTrue(active.size() <= DeveloperV1PlacementGrid.MAX_CONCURRENT);
            DeveloperV1BatchProgress.Task finished = active.remove(active.size() - 1);
            progress.finish(finished.index(), true);
        }

        assertEquals(64, progress.successful());
    }

    @Test
    void completedSitesStayReservedWhileOtherPairsRemainPending() {
        DeveloperV1BatchProgress progress = progress(3);
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments = startAll(progress);
        DeveloperV1BatchProgress.Task completed = progress.tasks().get(1);

        complete(progress, assignments, completed.index());

        assertTrue(assignments.reservedPositions().contains(completed.gridSlot()));
        assertFalse(assignments.reserve(progress.tasks().get(0), completed.gridSlot()));
        assertEquals(1, assignments.completedCount());
        assertEquals(2, assignments.pendingCount());
    }

    @Test
    void timedOutPairPreservesBothVillagerAndWorkstationForInspection() {
        DeveloperV1BatchProgress progress = progress(2);
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments = startAll(progress);
        DeveloperV1BatchProgress.Task timedOut = progress.tasks().get(1);

        assertTrue(assignments.markUnresolved(timedOut.index()));
        progress.finish(timedOut.index(), false);

        DeveloperV1JobSiteAssignments.Assignment<DeveloperV1PlacementGrid.Offset> unresolved =
                assignments.unresolvedAssignments().iterator().next();
        assertEquals(timedOut.gridSlot(), unresolved.position());
        assertEquals(villagerId(timedOut.index()), unresolved.villagerId());
        assertTrue(assignments.reservedPositions().contains(timedOut.gridSlot()));
        assertFalse(assignments.rollback(timedOut.index()));
        assertEquals(1, assignments.unresolvedCount());
    }

    @Test
    void anotherTasksTimeoutCannotDeleteCompletedOrPendingWorkstations() {
        DeveloperV1BatchProgress progress = progress(3);
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments = startAll(progress);
        DeveloperV1BatchProgress.Task completed = progress.tasks().get(0);
        DeveloperV1BatchProgress.Task timedOut = progress.tasks().get(1);
        DeveloperV1BatchProgress.Task stillPending = progress.tasks().get(2);

        complete(progress, assignments, completed.index());
        assertTrue(assignments.markUnresolved(timedOut.index()));
        progress.finish(timedOut.index(), false);

        assertFalse(assignments.rollback(completed.index()));
        assertTrue(assignments.reservedPositions().contains(completed.gridSlot()));
        assertTrue(assignments.reservedPositions().contains(timedOut.gridSlot()));
        assertTrue(assignments.reservedPositions().contains(stillPending.gridSlot()));
        assertEquals(1, assignments.completedCount());
        assertEquals(1, assignments.unresolvedCount());
        assertEquals(1, assignments.pendingCount());
    }

    @Test
    void taskCannotCompleteAgainstAnotherTasksWorkstation() {
        DeveloperV1BatchProgress progress = progress(2);
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments = startAll(progress);
        DeveloperV1BatchProgress.Task first = progress.tasks().get(0);
        DeveloperV1BatchProgress.Task second = progress.tasks().get(1);

        assertFalse(assignments.complete(first.index(), villagerId(first.index()), second.gridSlot()));
        assertEquals(2, assignments.pendingCount());
        assertEquals(0, assignments.completedCount());
        assertTrue(assignments.complete(first.index(), villagerId(first.index()), first.gridSlot()));
    }

    @Test
    void failedPendingSiteCanBeReusedWithoutReleasingCompletedSites() {
        DeveloperV1BatchProgress progress = progress(2);
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments = startAll(progress);
        DeveloperV1BatchProgress.Task completed = progress.tasks().get(0);
        DeveloperV1BatchProgress.Task failed = progress.tasks().get(1);

        complete(progress, assignments, completed.index());
        assertTrue(assignments.rollback(failed.index()));
        assertTrue(assignments.reserve(failed, failed.gridSlot()));

        assertTrue(assignments.reservedPositions().contains(completed.gridSlot()));
        assertEquals(1, assignments.completedCount());
    }

    @Test
    void repeatedProfessionAndMixedQuantitiesKeepUniqueGlobalSlots() {
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

    private static DeveloperV1BatchProgress progress(int quantity) {
        return new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, quantity)));
    }

    private static DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> startAll(
            DeveloperV1BatchProgress progress
    ) {
        DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments =
                new DeveloperV1JobSiteAssignments<>();
        while (progress.canStart(progress.total())) {
            reserve(assignments, progress.startNext());
        }
        return assignments;
    }

    private static void reserve(
            DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments,
            DeveloperV1BatchProgress.Task task
    ) {
        assertTrue(assignments.reserve(task, task.gridSlot()));
        assertTrue(assignments.attachVillager(task.index(), villagerId(task.index())));
    }

    private static void complete(
            DeveloperV1BatchProgress progress,
            DeveloperV1JobSiteAssignments<DeveloperV1PlacementGrid.Offset> assignments,
            int taskIndex
    ) {
        DeveloperV1BatchProgress.Task task = progress.tasks().get(taskIndex);
        assertTrue(assignments.complete(task.index(), villagerId(task.index()), task.gridSlot()));
        progress.finish(task.index(), true);
    }

    private static UUID villagerId(int taskIndex) {
        return new UUID(0L, taskIndex + 1L);
    }
}
