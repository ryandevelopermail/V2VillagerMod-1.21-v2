package dev.sterner.guardvillagers.common.developer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tracks the one in-progress site separately from immutable completed V1 task assignments. */
final class DeveloperV1JobSiteAssignments<P> {
    private final Map<Integer, Assignment<P>> completedByTask = new LinkedHashMap<>();
    private Assignment<P> current;

    boolean begin(DeveloperV1BatchProgress.Task task, P position) {
        if (current != null
                || completedByTask.containsKey(task.index())
                || completedByTask.values().stream().anyMatch(assignment -> assignment.position().equals(position))) {
            return false;
        }
        current = new Assignment<>(task, position, null);
        return true;
    }

    void completeCurrent(UUID villagerId) {
        if (current == null || villagerId == null) {
            throw new IllegalStateException("Cannot complete a V1 site without an in-progress assignment and villager.");
        }
        Assignment<P> completed = new Assignment<>(current.task(), current.position(), villagerId);
        if (completedByTask.putIfAbsent(completed.task().index(), completed) != null) {
            throw new IllegalStateException("V1 task " + completed.task().index() + " already owns a completed site.");
        }
        current = null;
    }

    void rollbackCurrent() {
        current = null;
    }

    boolean isCurrent(P position) {
        return current != null && current.position().equals(position);
    }

    Set<P> reservedPositions() {
        Set<P> positions = new HashSet<>();
        for (Assignment<P> assignment : completedByTask.values()) {
            positions.add(assignment.position());
        }
        if (current != null) {
            positions.add(current.position());
        }
        return Set.copyOf(positions);
    }

    Collection<Assignment<P>> completedAssignments() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(completedByTask.values()));
    }

    int completedCount() {
        return completedByTask.size();
    }

    record Assignment<P>(
            DeveloperV1BatchProgress.Task task,
            P position,
            UUID villagerId
    ) {
    }
}
