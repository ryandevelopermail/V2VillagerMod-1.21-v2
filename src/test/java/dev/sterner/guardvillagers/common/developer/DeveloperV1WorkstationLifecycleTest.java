package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.sterner.guardvillagers.common.developer.DeveloperV1JobSiteAssignments.AssignmentState.COMPLETED;
import static dev.sterner.guardvillagers.common.developer.DeveloperV1JobSiteAssignments.AssignmentState.RESERVED;
import static dev.sterner.guardvillagers.common.developer.DeveloperV1JobSiteAssignments.AssignmentState.UNRESOLVED;
import static dev.sterner.guardvillagers.common.developer.DeveloperV1JobSiteAssignments.AssignmentState.VILLAGER_ATTACHED;
import static dev.sterner.guardvillagers.common.developer.DeveloperV1JobSiteAssignments.AssignmentState.WORKSTATION_PLACED;
import static dev.sterner.guardvillagers.common.developer.DeveloperV1JobSiteAssignments.RollbackDecision.ATTACHED_PAIR_PRESERVED;
import static dev.sterner.guardvillagers.common.developer.DeveloperV1JobSiteAssignments.RollbackDecision.ELIGIBLE;
import static dev.sterner.guardvillagers.common.developer.DeveloperV1JobSiteAssignments.RollbackDecision.TASK_POSITION_MISMATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperV1WorkstationLifecycleTest {
    @Test
    void rollbackRequiresPendingOwnershipWithoutSuccessOrCollision() {
        assertTrue(DeveloperV1JobSiteAssignments.shouldRollbackWorkstation(
                WORKSTATION_PLACED, false, true, false));
        assertFalse(DeveloperV1JobSiteAssignments.shouldRollbackWorkstation(
                WORKSTATION_PLACED, true, true, false));
        assertFalse(DeveloperV1JobSiteAssignments.shouldRollbackWorkstation(
                WORKSTATION_PLACED, false, false, false));
        assertFalse(DeveloperV1JobSiteAssignments.shouldRollbackWorkstation(
                WORKSTATION_PLACED, false, true, true));
        assertFalse(DeveloperV1JobSiteAssignments.shouldRollbackWorkstation(
                RESERVED, false, true, false));
    }

    @Test
    void completedWorkstationCannotBeRolledBack() {
        Fixture fixture = fixture(1);
        complete(fixture, 0);

        assertEquals(COMPLETED, fixture.assignments.state(0));
        assertEquals(DeveloperV1JobSiteAssignments.RollbackDecision.COMPLETED,
                fixture.assignments.rollbackDecision(0, position(0)));
        assertFalse(fixture.assignments.rollback(0));
        assertFalse(DeveloperV1JobSiteAssignments.shouldRollbackWorkstation(
                COMPLETED, true, true, false));
    }

    @Test
    void unresolvedPreservedWorkstationCannotBeRolledBack() {
        Fixture fixture = fixture(1);
        reserveAndAttach(fixture, 0);
        assertTrue(fixture.assignments.markUnresolved(0));

        assertEquals(UNRESOLVED, fixture.assignments.state(0));
        assertEquals(DeveloperV1JobSiteAssignments.RollbackDecision.UNRESOLVED,
                fixture.assignments.rollbackDecision(0, position(0)));
        assertFalse(fixture.assignments.rollback(0));
        assertFalse(DeveloperV1JobSiteAssignments.shouldRollbackWorkstation(
                UNRESOLVED, false, true, false));
    }

    @Test
    void failedTaskReleasesOnlyItsOwnWorkstation() {
        Fixture fixture = fixture(2);
        reserveAndPlace(fixture, 0);
        reserveAndAttach(fixture, 1);

        assertEquals(ELIGIBLE, fixture.assignments.rollbackDecision(0, position(0)));
        assertTrue(fixture.assignments.rollback(0));
        assertTrue(fixture.assignments.reservedPositions().contains(position(1)));
        assertEquals(1, fixture.assignments.pendingCount());
    }

    @Test
    void failureOfTaskACannotTargetTaskBWorkstation() {
        Fixture fixture = fixture(2);
        reserveAndPlace(fixture, 0);
        reserveAndAttach(fixture, 1);

        assertEquals(TASK_POSITION_MISMATCH,
                fixture.assignments.rollbackDecision(0, position(1)));
        assertTrue(fixture.assignments.reservedPositions().containsAll(Set.of(position(0), position(1))));
    }

    @Test
    void cleanupPolicyPreservesCompletedAssignment() {
        Fixture fixture = fixture(1);
        complete(fixture, 0);

        assertFalse(fixture.assignments.isPending(0, position(0)));
        assertFalse(fixture.assignments.rollback(0));
        assertTrue(fixture.assignments.reservedPositions().contains(position(0)));
    }

    @Test
    void cleanupPolicyPreservesUnresolvedAssignment() {
        Fixture fixture = fixture(1);
        reserveAndAttach(fixture, 0);
        assertTrue(fixture.assignments.markUnresolved(0));

        assertFalse(fixture.assignments.isPending(0, position(0)));
        assertFalse(fixture.assignments.rollback(0));
        assertTrue(fixture.assignments.reservedPositions().contains(position(0)));
    }

    @Test
    void exactProfessionAndJobSiteCompletionLeavesRollbackEligibility() {
        Fixture fixture = fixture(1);
        reserveAndAttach(fixture, 0);

        assertTrue(fixture.assignments.complete(0, villagerId(0), position(0)));
        DeveloperV1JobSiteAssignments.Assignment<String> completed =
                fixture.assignments.completedAssignments().iterator().next();
        assertTrue(completed.exactPairRecorded());
        assertEquals(COMPLETED, completed.state());
    }

    @Test
    void terrainRelocatedPositionIsTheAuthoritativeOwnedPosition() {
        Fixture fixture = fixture(1);
        String relocated = "actual:14,67,-3";
        DeveloperV1BatchProgress.Task task = fixture.progress.tasks().getFirst();

        assertTrue(fixture.assignments.reserve(task, relocated));
        assertTrue(fixture.assignments.markWorkstationPlaced(task.index(), relocated));
        assertTrue(fixture.assignments.attachVillager(task.index(), villagerId(0)));
        assertTrue(fixture.assignments.complete(task.index(), villagerId(0), relocated));
        assertEquals(Set.of(relocated), fixture.assignments.reservedPositions());
    }

    @Test
    void transientMissingJobSiteMemoryCannotMakeCompletedAssignmentDeletable() {
        Fixture fixture = fixture(1);
        complete(fixture, 0);

        assertFalse(DeveloperV1JobSiteAssignments.shouldRollbackWorkstation(
                fixture.assignments.state(0),
                true,
                true,
                false));
    }

    @Test
    void mixedProfessionBatchKeepsEverySuccessfulPositionReserved() {
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 1),
                new DeveloperProfessionSelection(DeveloperProfession.LIBRARIAN, 1),
                new DeveloperProfessionSelection(DeveloperProfession.SHEPHERD, 1)));
        DeveloperV1JobSiteAssignments<String> assignments = new DeveloperV1JobSiteAssignments<>();
        Fixture fixture = new Fixture(progress, assignments);

        for (int taskIndex = 0; taskIndex < progress.total(); taskIndex++) {
            complete(fixture, taskIndex);
        }

        assertEquals(3, assignments.completedCount());
        assertEquals(Set.of(position(0), position(1), position(2)), assignments.reservedPositions());
    }

    @Test
    void isolationPlacementAndCleanupRejectProtectedTaskPositions() {
        Set<String> protectedPositions = Set.of("job-a", "job-b", "spawn-b");

        assertFalse(DeveloperV1IsolationSafety.avoidsProtectedPositions(
                Set.of("wall", "job-b"),
                Set.of("spawn-a"),
                protectedPositions));
        assertFalse(DeveloperV1IsolationSafety.avoidsProtectedPositions(
                Set.of("wall"),
                Set.of("spawn-b"),
                protectedPositions));
        assertFalse(DeveloperV1IsolationSafety.canRemoveTemporaryPosition("job-a", protectedPositions));
        assertTrue(DeveloperV1IsolationSafety.canRemoveTemporaryPosition("wall", protectedPositions));
    }

    @Test
    void attachedV1PairIsNeverDestructivelyRolledBack() {
        Fixture fixture = fixture(1);
        reserveAndAttach(fixture, 0);

        assertFalse(VILLAGER_ATTACHED.rollbackEligible());
        assertEquals(ATTACHED_PAIR_PRESERVED,
                fixture.assignments.rollbackDecision(0, position(0)));
        assertFalse(fixture.assignments.rollback(0));
        assertEquals(VILLAGER_ATTACHED, fixture.assignments.state(0));
        assertTrue(fixture.assignments.reservedPositions().contains(position(0)));
    }

    @Test
    void onlyPreAttachmentStatesPermitDestructiveRollback() {
        assertTrue(RESERVED.rollbackEligible());
        assertTrue(WORKSTATION_PLACED.rollbackEligible());
        assertFalse(VILLAGER_ATTACHED.rollbackEligible());
        assertFalse(COMPLETED.rollbackEligible());
        assertFalse(UNRESOLVED.rollbackEligible());
    }

    @Test
    void missingIsolationBarrierPreservesAttachedPairAsUnresolved() {
        Fixture fixture = fixture(1);
        reserveAndAttach(fixture, 0);

        assertTrue(fixture.assignments.markUnresolved(0));
        assertEquals(UNRESOLVED, fixture.assignments.state(0));
        assertFalse(fixture.assignments.rollback(0));
        assertTrue(fixture.assignments.reservedPositions().contains(position(0)));
    }

    @Test
    void workstationLossCanBeRepairedOnlyForOwnedAttachedReplaceablePosition() {
        assertTrue(DeveloperV1JobSiteAssignments.canRestoreAttachedWorkstation(
                VILLAGER_ATTACHED, true, true));
        assertFalse(DeveloperV1JobSiteAssignments.canRestoreAttachedWorkstation(
                WORKSTATION_PLACED, true, true));
        assertFalse(DeveloperV1JobSiteAssignments.canRestoreAttachedWorkstation(
                VILLAGER_ATTACHED, false, true));
        assertFalse(DeveloperV1JobSiteAssignments.canRestoreAttachedWorkstation(
                VILLAGER_ATTACHED, true, false));
    }

    @Test
    void sessionFatalFailurePreservesAllAttachedPairs() {
        Fixture fixture = fixture(4);
        for (int taskIndex = 0; taskIndex < 4; taskIndex++) {
            reserveAndAttach(fixture, taskIndex);
        }

        for (int taskIndex = 0; taskIndex < 4; taskIndex++) {
            assertTrue(fixture.assignments.markUnresolved(taskIndex));
        }

        assertEquals(4, fixture.assignments.unresolvedCount());
        assertEquals(Set.of(position(0), position(1), position(2), position(3)),
                fixture.assignments.reservedPositions());
    }

    @Test
    void finalWaveFailurePreservesAllPreviouslySpawnedPairs() {
        Fixture fixture = fixture(21);
        for (int taskIndex = 0; taskIndex < 17; taskIndex++) {
            complete(fixture, taskIndex);
        }
        for (int taskIndex = 17; taskIndex < 21; taskIndex++) {
            reserveAndAttach(fixture, taskIndex);
        }

        assertTrue(fixture.assignments.markUnresolved(19));

        assertEquals(17, fixture.assignments.completedCount());
        assertEquals(1, fixture.assignments.unresolvedCount());
        assertEquals(3, fixture.assignments.pendingCount());
        assertEquals(21, fixture.assignments.reservedPositions().size());
        for (int taskIndex = 0; taskIndex < 21; taskIndex++) {
            assertTrue(fixture.assignments.reservedPositions().contains(position(taskIndex)));
        }
    }

    private static Fixture fixture(int quantity) {
        return new Fixture(
                new DeveloperV1BatchProgress(List.of(
                        new DeveloperProfessionSelection(DeveloperProfession.FARMER, quantity))),
                new DeveloperV1JobSiteAssignments<>());
    }

    private static void complete(Fixture fixture, int taskIndex) {
        reserveAndAttach(fixture, taskIndex);
        assertTrue(fixture.assignments.complete(taskIndex, villagerId(taskIndex), position(taskIndex)));
    }

    private static void reserveAndAttach(Fixture fixture, int taskIndex) {
        reserveAndPlace(fixture, taskIndex);
        assertTrue(fixture.assignments.attachVillager(taskIndex, villagerId(taskIndex)));
    }

    private static void reserveAndPlace(Fixture fixture, int taskIndex) {
        DeveloperV1BatchProgress.Task task = fixture.progress.tasks().get(taskIndex);
        assertTrue(fixture.assignments.reserve(task, position(taskIndex)));
        assertTrue(fixture.assignments.markWorkstationPlaced(taskIndex, position(taskIndex)));
    }

    private static String position(int taskIndex) {
        return "job-" + taskIndex;
    }

    private static UUID villagerId(int taskIndex) {
        return new UUID(7L, taskIndex + 1L);
    }

    private record Fixture(
            DeveloperV1BatchProgress progress,
            DeveloperV1JobSiteAssignments<String> assignments
    ) {
    }
}
