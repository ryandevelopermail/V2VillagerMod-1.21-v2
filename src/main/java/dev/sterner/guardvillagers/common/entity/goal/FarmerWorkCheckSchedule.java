package dev.sterner.guardvillagers.common.entity.goal;

/** Pure wake/backoff state for Farmer chest-triggered and periodic work checks. */
final class FarmerWorkCheckSchedule {
    private long nextCheckTime;
    private boolean immediateRunRequested;
    private boolean priorityScanRequested;

    void reset() {
        nextCheckTime = 0L;
        immediateRunRequested = false;
        priorityScanRequested = false;
    }

    void requestImmediate() {
        immediateRunRequested = true;
        priorityScanRequested = true;
        nextCheckTime = 0L;
    }

    void requestNoSoonerThan(long targetTick) {
        priorityScanRequested = true;
        if (nextCheckTime == 0L || nextCheckTime > targetTick) {
            nextCheckTime = targetTick;
        }
    }

    boolean shouldWait(long currentTick) {
        return !immediateRunRequested && currentTick < nextCheckTime;
    }

    boolean consumeImmediateRequest() {
        boolean requested = immediateRunRequested;
        immediateRunRequested = false;
        return requested;
    }

    boolean consumePriorityScanRequest() {
        boolean requested = priorityScanRequested;
        priorityScanRequested = false;
        return requested;
    }

    void scheduleAt(long targetTick) {
        nextCheckTime = targetTick;
    }

    void ensureNotBefore(long targetTick) {
        nextCheckTime = Math.max(nextCheckTime, targetTick);
    }

    long nextCheckTime() {
        return nextCheckTime;
    }
}
