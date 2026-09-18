package dev.sterner.guardvillagers.common.developer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure rolling-window batch state used by the concurrent V1 server executor and focused tests. */
final class DeveloperV1BatchProgress {
    private final List<Task> tasks;
    private final Set<Integer> unstartedTaskIndexes = new LinkedHashSet<>();
    private final Set<Integer> pendingTaskIndexes = new LinkedHashSet<>();
    private int successful;
    private int failed;

    DeveloperV1BatchProgress(List<DeveloperProfessionSelection> selections) {
        List<DeveloperProfession> expanded = new ArrayList<>();
        for (DeveloperProfessionSelection selection : selections) {
            for (int count = 0; count < selection.quantity(); count++) {
                expanded.add(selection.profession());
            }
        }
        List<Task> plannedTasks = new ArrayList<>(expanded.size());
        for (int index = 0; index < expanded.size(); index++) {
            plannedTasks.add(new Task(
                    index,
                    expanded.get(index),
                    DeveloperV1PlacementGrid.offsetFor(index, expanded.size()),
                    DeveloperV1PlacementGrid.laneFor(index)));
            unstartedTaskIndexes.add(index);
        }
        this.tasks = List.copyOf(plannedTasks);
    }

    boolean canStart(int maxPending) {
        if (maxPending < 1) {
            throw new IllegalArgumentException("V1 pending capacity must be positive.");
        }
        return pendingTaskIndexes.size() < maxPending && nextAvailableTaskIndex() >= 0;
    }

    Task startNext() {
        int taskIndex = nextAvailableTaskIndex();
        if (taskIndex < 0) {
            throw new IllegalStateException("No V1 task can start until a pending lane is released.");
        }
        unstartedTaskIndexes.remove(taskIndex);
        Task task = tasks.get(taskIndex);
        pendingTaskIndexes.add(task.index());
        return task;
    }

    void finish(int taskIndex, boolean success) {
        if (!pendingTaskIndexes.remove(taskIndex)) {
            throw new IllegalStateException("V1 task " + taskIndex + " is not pending.");
        }
        if (success) {
            successful++;
        } else {
            failed++;
        }
    }

    int total() {
        return tasks.size();
    }

    int processed() {
        return successful + failed;
    }

    int pending() {
        return pendingTaskIndexes.size();
    }

    int successful() {
        return successful;
    }

    int failed() {
        return failed;
    }

    boolean isComplete() {
        return processed() == tasks.size();
    }

    List<Task> tasks() {
        return tasks;
    }

    private int nextAvailableTaskIndex() {
        for (int taskIndex : unstartedTaskIndexes) {
            int lane = tasks.get(taskIndex).lane();
            boolean laneOccupied = pendingTaskIndexes.stream()
                    .map(tasks::get)
                    .anyMatch(task -> task.lane() == lane);
            if (!laneOccupied) {
                return taskIndex;
            }
        }
        return -1;
    }

    record Task(
            int index,
            DeveloperProfession profession,
            DeveloperV1PlacementGrid.Offset gridSlot,
            int lane
    ) {
    }
}
