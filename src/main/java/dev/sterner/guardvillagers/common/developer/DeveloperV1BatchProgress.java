package dev.sterner.guardvillagers.common.developer;

import java.util.ArrayList;
import java.util.List;

/** Pure rolling-window batch state used by the concurrent V1 server executor and focused tests. */
final class DeveloperV1BatchProgress {
    private final List<Task> tasks;
    private final boolean[] pendingTasks;
    private int nextTaskIndex;
    private int pending;
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
                    DeveloperV1PlacementGrid.offsetFor(index, expanded.size())));
        }
        this.tasks = List.copyOf(plannedTasks);
        this.pendingTasks = new boolean[tasks.size()];
    }

    boolean canStart(int maxPending) {
        if (maxPending < 1) {
            throw new IllegalArgumentException("V1 pending capacity must be positive.");
        }
        return pending < maxPending && nextTaskIndex < tasks.size();
    }

    Task startNext() {
        if (nextTaskIndex >= tasks.size()) {
            throw new IllegalStateException("Every V1 task has already started.");
        }
        Task task = tasks.get(nextTaskIndex++);
        pendingTasks[task.index()] = true;
        pending++;
        return task;
    }

    void finish(int taskIndex, boolean success) {
        if (taskIndex < 0 || taskIndex >= pendingTasks.length || !pendingTasks[taskIndex]) {
            throw new IllegalStateException("V1 task " + taskIndex + " is not pending.");
        }
        pendingTasks[taskIndex] = false;
        pending--;
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
        return pending;
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

    record Task(int index, DeveloperProfession profession, DeveloperV1PlacementGrid.Offset gridSlot) {
    }
}
