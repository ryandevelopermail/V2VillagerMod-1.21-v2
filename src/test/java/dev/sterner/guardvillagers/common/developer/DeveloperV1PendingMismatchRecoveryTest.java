package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperV1PendingMismatchRecoveryTest {
    @Test
    void wrongJobSiteClaimRetriesWithoutDeletingIntendedWorkstation() {
        Fixture fixture = fixture(1);

        assertEquals(DeveloperV1PendingMismatchRecovery.Action.RECOVER,
                fixture.recoveries.getFirst().observe(true, false));
        assertTrue(fixture.assignments.reservedPositions().contains("job-0"));
        assertEquals(1, fixture.assignments.pendingCount());
    }

    @Test
    void temporaryWrongProfessionRetriesWithoutDeletingIntendedWorkstation() {
        Fixture fixture = fixture(1);

        assertEquals(DeveloperV1PendingMismatchRecovery.Action.RECOVER,
                fixture.recoveries.getFirst().observe(false, true));
        assertTrue(fixture.assignments.reservedPositions().contains("job-0"));
        assertTrue(DeveloperV1PendingMismatchRecovery.canResetTemporaryProfession(true, 1, 0));
    }

    @Test
    void crossClaimRecoveryPreservesBothTasksWorkstations() {
        Fixture fixture = fixture(2);

        assertEquals(DeveloperV1PendingMismatchRecovery.Action.RECOVER,
                fixture.recoveries.getFirst().observe(true, true));
        assertEquals(Set.of("job-0", "job-1"), fixture.assignments.reservedPositions());
        assertEquals(2, fixture.assignments.pendingCount());
    }

    @Test
    void recoveryCannotRollBackAnotherTasksWorkstation() {
        Fixture fixture = fixture(2);
        fixture.recoveries.getFirst().observe(true, false);

        assertEquals(DeveloperV1JobSiteAssignments.RollbackDecision.TASK_POSITION_MISMATCH,
                fixture.assignments.rollbackDecision(0, "job-1"));
        assertTrue(fixture.assignments.reservedPositions().contains("job-1"));
    }

    @Test
    void recoveryAttemptsAreBounded() {
        DeveloperV1PendingMismatchRecovery recovery = new DeveloperV1PendingMismatchRecovery();

        for (int attempt = 1; attempt <= DeveloperV1PendingMismatchRecovery.MAX_RECOVERY_ATTEMPTS; attempt++) {
            assertEquals(DeveloperV1PendingMismatchRecovery.Action.RECOVER,
                    recovery.observe(true, false));
            assertEquals(attempt, recovery.recoveryAttempts());
        }
        assertEquals(DeveloperV1PendingMismatchRecovery.Action.WAIT_FOR_TIMEOUT,
                recovery.observe(true, false));
        assertEquals(DeveloperV1PendingMismatchRecovery.MAX_RECOVERY_ATTEMPTS,
                recovery.recoveryAttempts());
    }

    @Test
    void repeatedMismatchFallsBackToPreservedUnresolvedAssignment() {
        Fixture fixture = fixture(1);
        DeveloperV1PendingMismatchRecovery recovery = fixture.recoveries.getFirst();
        DeveloperV1PendingPairProgress progress = new DeveloperV1PendingPairProgress(0, 5);
        for (int attempt = 0; attempt < DeveloperV1PendingMismatchRecovery.MAX_RECOVERY_ATTEMPTS; attempt++) {
            progress.advanceTick();
            assertEquals(DeveloperV1PendingMismatchRecovery.Action.RECOVER,
                    recovery.observe(true, true));
        }

        progress.advanceTick();
        assertEquals(DeveloperV1PendingMismatchRecovery.Action.WAIT_FOR_TIMEOUT,
                recovery.observe(true, true));
        assertTrue(progress.timeOutIfExpired());
        assertTrue(fixture.assignments.markUnresolved(0));
        assertTrue(fixture.assignments.reservedPositions().contains("job-0"));
        assertEquals(1, fixture.assignments.unresolvedCount());
        assertFalse(fixture.assignments.rollback(0));
    }

    @Test
    void exactPairCanCompleteAfterPriorMismatch() {
        Fixture fixture = fixture(1);
        fixture.recoveries.getFirst().observe(true, true);

        assertEquals(DeveloperV1PendingMismatchRecovery.Action.NONE,
                fixture.recoveries.getFirst().observe(false, false));
        assertTrue(fixture.assignments.complete(0, villagerId(0), "job-0"));
        assertEquals(1, fixture.assignments.completedCount());
        assertTrue(fixture.assignments.reservedPositions().contains("job-0"));
    }

    @Test
    void onlyFreshNoviceWrongProfessionsAreResettable() {
        assertTrue(DeveloperV1PendingMismatchRecovery.canResetTemporaryProfession(true, 1, 0));
        assertFalse(DeveloperV1PendingMismatchRecovery.canResetTemporaryProfession(false, 1, 0));
        assertFalse(DeveloperV1PendingMismatchRecovery.canResetTemporaryProfession(true, 2, 0));
        assertFalse(DeveloperV1PendingMismatchRecovery.canResetTemporaryProfession(true, 1, 1));
    }

    @Test
    void fourConcurrentRecoveriesPreserveEveryOwnedWorkstation() {
        Fixture fixture = fixture(DeveloperV1PlacementGrid.MAX_CONCURRENT);

        for (int task = 0; task < DeveloperV1PlacementGrid.MAX_CONCURRENT; task++) {
            assertEquals(DeveloperV1PendingMismatchRecovery.Action.RECOVER,
                    fixture.recoveries.get(task).observe(true, task % 2 == 0));
        }

        assertEquals(4, fixture.assignments.pendingCount());
        assertEquals(Set.of("job-0", "job-1", "job-2", "job-3"),
                fixture.assignments.reservedPositions());
    }

    private static Fixture fixture(int quantity) {
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, quantity)));
        DeveloperV1JobSiteAssignments<String> assignments = new DeveloperV1JobSiteAssignments<>();
        List<DeveloperV1PendingMismatchRecovery> recoveries = new ArrayList<>();
        for (DeveloperV1BatchProgress.Task task : progress.tasks()) {
            String position = "job-" + task.index();
            assertTrue(assignments.reserve(task, position));
            assertTrue(assignments.markWorkstationPlaced(task.index(), position));
            assertTrue(assignments.attachVillager(task.index(), villagerId(task.index())));
            recoveries.add(new DeveloperV1PendingMismatchRecovery());
        }
        return new Fixture(assignments, List.copyOf(recoveries));
    }

    private static UUID villagerId(int taskIndex) {
        return new UUID(11L, taskIndex + 1L);
    }

    private record Fixture(
            DeveloperV1JobSiteAssignments<String> assignments,
            List<DeveloperV1PendingMismatchRecovery> recoveries
    ) {
    }
}
