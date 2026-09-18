package dev.sterner.guardvillagers.common.developer;

/** Pure timing and terminal-state model for one pending V1 villager/workstation pair. */
final class DeveloperV1PendingPairProgress {
    enum Status {
        PENDING,
        COMPLETE,
        TIMED_OUT
    }

    private final int restraintTicks;
    private final int timeoutTicks;
    private int elapsedTicks;
    private Status status = Status.PENDING;

    DeveloperV1PendingPairProgress(int restraintTicks, int timeoutTicks) {
        if (restraintTicks < 0 || timeoutTicks < 1 || restraintTicks >= timeoutTicks) {
            throw new IllegalArgumentException("V1 restraint must be non-negative and shorter than its timeout.");
        }
        this.restraintTicks = restraintTicks;
        this.timeoutTicks = timeoutTicks;
    }

    void advanceTick() {
        if (status == Status.PENDING) {
            elapsedTicks++;
        }
    }

    boolean shouldRestrain() {
        return status == Status.PENDING && elapsedTicks <= restraintTicks;
    }

    boolean completeIfExactPair(boolean exactPair) {
        if (status != Status.PENDING || !exactPair) {
            return false;
        }
        status = Status.COMPLETE;
        return true;
    }

    boolean timeOutIfExpired() {
        if (status != Status.PENDING || elapsedTicks < timeoutTicks) {
            return false;
        }
        status = Status.TIMED_OUT;
        return true;
    }

    int elapsedTicks() {
        return elapsedTicks;
    }

    Status status() {
        return status;
    }
}
