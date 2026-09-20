package dev.sterner.guardvillagers.common.developer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tracks multiple pending sites separately from immutable completed V1 task assignments. */
final class DeveloperV1JobSiteAssignments<P> {
    private final Map<Integer, Assignment<P>> pendingByTask = new LinkedHashMap<>();
    private final Map<Integer, Assignment<P>> unresolvedByTask = new LinkedHashMap<>();
    private final Map<Integer, Assignment<P>> completedByTask = new LinkedHashMap<>();

    boolean reserve(DeveloperV1BatchProgress.Task task, P position) {
        if (task == null
                || position == null
                || pendingByTask.containsKey(task.index())
                || unresolvedByTask.containsKey(task.index())
                || completedByTask.containsKey(task.index())
                || reservedPositions().contains(position)) {
            return false;
        }
        pendingByTask.put(task.index(), new Assignment<>(
                task, position, null, AssignmentState.RESERVED, false));
        return true;
    }

    boolean markWorkstationPlaced(int taskIndex, P position) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        if (pending == null
                || pending.state() != AssignmentState.RESERVED
                || !pending.position().equals(position)) {
            return false;
        }
        pendingByTask.put(taskIndex, pending.withState(AssignmentState.WORKSTATION_PLACED));
        return true;
    }

    boolean attachVillager(int taskIndex, UUID villagerId) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        if (pending == null
                || pending.state() != AssignmentState.WORKSTATION_PLACED
                || villagerId == null
                || pending.villagerId() != null
                || ownsVillager(villagerId)) {
            return false;
        }
        pendingByTask.put(taskIndex, new Assignment<>(
                pending.task(),
                pending.position(),
                villagerId,
                AssignmentState.VILLAGER_ATTACHED,
                false));
        return true;
    }

    boolean complete(int taskIndex, UUID villagerId, P claimedPosition) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        if (pending == null
                || pending.state() != AssignmentState.VILLAGER_ATTACHED
                || pending.villagerId() == null
                || !pending.villagerId().equals(villagerId)
                || !pending.position().equals(claimedPosition)) {
            return false;
        }
        pendingByTask.remove(taskIndex);
        completedByTask.put(taskIndex, new Assignment<>(
                pending.task(),
                pending.position(),
                pending.villagerId(),
                AssignmentState.COMPLETED,
                true));
        return true;
    }

    boolean markUnresolved(int taskIndex) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        if (pending == null
                || pending.state() != AssignmentState.VILLAGER_ATTACHED
                || pending.villagerId() == null) {
            return false;
        }
        pendingByTask.remove(taskIndex);
        unresolvedByTask.put(taskIndex, pending.withState(AssignmentState.UNRESOLVED));
        return true;
    }

    boolean rollback(int taskIndex) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        if (pending == null || !pending.state().rollbackEligible()) {
            return false;
        }
        pendingByTask.remove(taskIndex);
        return true;
    }

    boolean isPending(int taskIndex, P position) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        return pending != null && pending.position().equals(position);
    }

    RollbackDecision rollbackDecision(int taskIndex, P position) {
        Assignment<P> completed = completedByTask.get(taskIndex);
        if (completed != null) {
            return RollbackDecision.COMPLETED;
        }
        Assignment<P> unresolved = unresolvedByTask.get(taskIndex);
        if (unresolved != null) {
            return RollbackDecision.UNRESOLVED;
        }
        Assignment<P> pending = pendingByTask.get(taskIndex);
        if (pending == null) {
            return RollbackDecision.ASSIGNMENT_MISSING;
        }
        if (!pending.position().equals(position)) {
            return RollbackDecision.TASK_POSITION_MISMATCH;
        }
        boolean positionOwnedByAnotherTask = assignments().stream()
                .anyMatch(assignment -> assignment.task().index() != taskIndex
                        && assignment.position().equals(position));
        if (positionOwnedByAnotherTask) {
            return RollbackDecision.POSITION_RESERVED_BY_ANOTHER_TASK;
        }
        if (pending.exactPairRecorded()) {
            return RollbackDecision.EXACT_PAIR_RECORDED;
        }
        if (pending.state() == AssignmentState.VILLAGER_ATTACHED) {
            return RollbackDecision.ATTACHED_PAIR_PRESERVED;
        }
        if (!pending.state().workstationPlaced()) {
            return RollbackDecision.WORKSTATION_NOT_PLACED;
        }
        return shouldRollbackWorkstation(
                pending.state(),
                pending.exactPairRecorded(),
                true,
                false)
                ? RollbackDecision.ELIGIBLE
                : RollbackDecision.NOT_PENDING;
    }

    AssignmentState state(int taskIndex) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        if (pending != null) {
            return pending.state();
        }
        Assignment<P> unresolved = unresolvedByTask.get(taskIndex);
        if (unresolved != null) {
            return unresolved.state();
        }
        Assignment<P> completed = completedByTask.get(taskIndex);
        return completed == null ? null : completed.state();
    }

    static boolean shouldRollbackWorkstation(
            AssignmentState state,
            boolean exactPairRecorded,
            boolean failingTaskOwnsPosition,
            boolean positionReservedByAnotherTask
    ) {
        return state != null
                && state.rollbackEligible()
                && state.workstationPlaced()
                && !exactPairRecorded
                && failingTaskOwnsPosition
                && !positionReservedByAnotherTask;
    }

    static boolean canRestoreAttachedWorkstation(
            AssignmentState state,
            boolean taskOwnsPosition,
            boolean currentBlockReplaceable
    ) {
        return state == AssignmentState.VILLAGER_ATTACHED
                && taskOwnsPosition
                && currentBlockReplaceable;
    }

    Set<P> reservedPositions() {
        Set<P> positions = new HashSet<>();
        pendingByTask.values().forEach(assignment -> positions.add(assignment.position()));
        unresolvedByTask.values().forEach(assignment -> positions.add(assignment.position()));
        completedByTask.values().forEach(assignment -> positions.add(assignment.position()));
        return Set.copyOf(positions);
    }

    Collection<Assignment<P>> completedAssignments() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(completedByTask.values()));
    }

    Collection<Assignment<P>> unresolvedAssignments() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(unresolvedByTask.values()));
    }

    int pendingCount() {
        return pendingByTask.size();
    }

    int completedCount() {
        return completedByTask.size();
    }

    int unresolvedCount() {
        return unresolvedByTask.size();
    }

    private boolean ownsVillager(UUID villagerId) {
        return pendingByTask.values().stream().anyMatch(assignment -> villagerId.equals(assignment.villagerId()))
                || unresolvedByTask.values().stream().anyMatch(assignment -> villagerId.equals(assignment.villagerId()))
                || completedByTask.values().stream().anyMatch(assignment -> villagerId.equals(assignment.villagerId()));
    }

    private Collection<Assignment<P>> assignments() {
        ArrayList<Assignment<P>> assignments = new ArrayList<>();
        assignments.addAll(pendingByTask.values());
        assignments.addAll(unresolvedByTask.values());
        assignments.addAll(completedByTask.values());
        return assignments;
    }

    enum AssignmentState {
        RESERVED(false, true),
        WORKSTATION_PLACED(true, true),
        VILLAGER_ATTACHED(true, false),
        COMPLETED(true, false),
        UNRESOLVED(true, false);

        private final boolean workstationPlaced;
        private final boolean rollbackEligible;

        AssignmentState(boolean workstationPlaced, boolean rollbackEligible) {
            this.workstationPlaced = workstationPlaced;
            this.rollbackEligible = rollbackEligible;
        }

        boolean workstationPlaced() {
            return workstationPlaced;
        }

        boolean rollbackEligible() {
            return rollbackEligible;
        }
    }

    enum RollbackDecision {
        ELIGIBLE,
        ASSIGNMENT_MISSING,
        TASK_POSITION_MISMATCH,
        WORKSTATION_NOT_PLACED,
        ATTACHED_PAIR_PRESERVED,
        EXACT_PAIR_RECORDED,
        COMPLETED,
        UNRESOLVED,
        POSITION_RESERVED_BY_ANOTHER_TASK,
        NOT_PENDING
    }

    record Assignment<P>(
            DeveloperV1BatchProgress.Task task,
            P position,
            UUID villagerId,
            AssignmentState state,
            boolean exactPairRecorded
    ) {
        Assignment<P> withState(AssignmentState replacement) {
            return new Assignment<>(task, position, villagerId, replacement, exactPairRecorded);
        }
    }
}
