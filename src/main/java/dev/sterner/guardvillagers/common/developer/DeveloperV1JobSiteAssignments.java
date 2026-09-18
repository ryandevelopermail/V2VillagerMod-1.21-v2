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
    private final Map<Integer, Assignment<P>> completedByTask = new LinkedHashMap<>();

    boolean reserve(DeveloperV1BatchProgress.Task task, P position) {
        if (task == null
                || position == null
                || pendingByTask.containsKey(task.index())
                || completedByTask.containsKey(task.index())
                || reservedPositions().contains(position)) {
            return false;
        }
        pendingByTask.put(task.index(), new Assignment<>(task, position, null));
        return true;
    }

    boolean attachVillager(int taskIndex, UUID villagerId) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        if (pending == null || villagerId == null || pending.villagerId() != null || ownsVillager(villagerId)) {
            return false;
        }
        pendingByTask.put(taskIndex, new Assignment<>(pending.task(), pending.position(), villagerId));
        return true;
    }

    boolean complete(int taskIndex, UUID villagerId, P claimedPosition) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        if (pending == null
                || pending.villagerId() == null
                || !pending.villagerId().equals(villagerId)
                || !pending.position().equals(claimedPosition)) {
            return false;
        }
        pendingByTask.remove(taskIndex);
        completedByTask.put(taskIndex, pending);
        return true;
    }

    boolean rollback(int taskIndex) {
        return pendingByTask.remove(taskIndex) != null;
    }

    boolean isPending(int taskIndex, P position) {
        Assignment<P> pending = pendingByTask.get(taskIndex);
        return pending != null && pending.position().equals(position);
    }

    Set<P> reservedPositions() {
        Set<P> positions = new HashSet<>();
        pendingByTask.values().forEach(assignment -> positions.add(assignment.position()));
        completedByTask.values().forEach(assignment -> positions.add(assignment.position()));
        return Set.copyOf(positions);
    }

    Collection<Assignment<P>> completedAssignments() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(completedByTask.values()));
    }

    int pendingCount() {
        return pendingByTask.size();
    }

    int completedCount() {
        return completedByTask.size();
    }

    private boolean ownsVillager(UUID villagerId) {
        return pendingByTask.values().stream().anyMatch(assignment -> villagerId.equals(assignment.villagerId()))
                || completedByTask.values().stream().anyMatch(assignment -> villagerId.equals(assignment.villagerId()));
    }

    record Assignment<P>(
            DeveloperV1BatchProgress.Task task,
            P position,
            UUID villagerId
    ) {
    }
}
