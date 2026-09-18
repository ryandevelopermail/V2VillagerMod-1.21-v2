package dev.sterner.guardvillagers.common.developer;

import java.util.ArrayList;
import java.util.List;

/** Pure sequential batch state used by the server executor and focused tests. */
final class DeveloperV1BatchProgress {
    enum Stage {
        PREPARE_CURRENT,
        WAIT_FOR_PROFESSION,
        COMPLETE
    }

    private final List<Task> tasks;
    private int currentIndex;
    private int successful;
    private int failed;
    private Stage stage;
    private boolean subjectRestrained;

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
        this.stage = tasks.isEmpty() ? Stage.COMPLETE : Stage.PREPARE_CURRENT;
    }

    Stage stage() {
        return stage;
    }

    DeveloperProfession currentProfession() {
        return currentTask().profession();
    }

    Task currentTask() {
        if (stage == Stage.COMPLETE) {
            throw new IllegalStateException("The V1 batch is complete.");
        }
        return tasks.get(currentIndex);
    }

    int currentNumber() {
        return Math.min(currentIndex + 1, tasks.size());
    }

    int total() {
        return tasks.size();
    }

    int processed() {
        return successful + failed;
    }

    int successful() {
        return successful;
    }

    int failed() {
        return failed;
    }

    void markPrepared() {
        if (stage != Stage.PREPARE_CURRENT) {
            throw new IllegalStateException("Cannot wait for a profession from stage " + stage);
        }
        stage = Stage.WAIT_FOR_PROFESSION;
        subjectRestrained = true;
    }

    boolean releaseRequired() {
        return subjectRestrained;
    }

    void markSubjectReleased() {
        subjectRestrained = false;
    }

    void finishCurrent(boolean success) {
        if (stage == Stage.COMPLETE) {
            throw new IllegalStateException("The V1 batch is already complete.");
        }
        if (subjectRestrained) {
            throw new IllegalStateException("The current V1 villager must be released before advancing the batch.");
        }
        if (success) {
            successful++;
        } else {
            failed++;
        }
        currentIndex++;
        stage = currentIndex >= tasks.size() ? Stage.COMPLETE : Stage.PREPARE_CURRENT;
    }

    boolean isComplete() {
        return stage == Stage.COMPLETE;
    }

    List<Task> tasks() {
        return tasks;
    }

    record Task(int index, DeveloperProfession profession, DeveloperV1PlacementGrid.Offset gridSlot) {
    }
}
