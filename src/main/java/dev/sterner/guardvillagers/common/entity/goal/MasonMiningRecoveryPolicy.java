package dev.sterner.guardvillagers.common.entity.goal;

/** Pure bounded retry/reroute policy for one Mason staircase continuation. */
final class MasonMiningRecoveryPolicy {
    static final int MAX_LOCAL_RETRIES = 3;
    static final int LOCAL_RETRY_DELAY_TICKS = 5;
    static final int CANNOT_ADVANCE_BACKOFF_BASE_TICKS = 20 * 20;
    static final int NAVIGATION_BACKOFF_TICKS = 20 * 30;
    static final int TOOL_RECHECK_TICKS = 20 * 10;

    private MasonMiningRecoveryPolicy() {
    }

    static Decision decide(FailureSubtype failure, int completedRetries, boolean rerouteAvailable) {
        if (failure == null || failure == FailureSubtype.NONE) {
            throw new IllegalArgumentException("A concrete mining failure subtype is required.");
        }
        int boundedRetries = Math.max(0, completedRetries);
        if (failure == FailureSubtype.WATER_HAZARD || failure == FailureSubtype.TOOL_LOST) {
            return new Decision(Action.ABORT, boundedRetries);
        }
        if (failure == FailureSubtype.JOB_BLOCK_EXCLUSION
                || failure == FailureSubtype.TARGET_PROTECTED) {
            return new Decision(rerouteAvailable ? Action.REROUTE : Action.ABORT, boundedRetries);
        }
        if (boundedRetries < MAX_LOCAL_RETRIES) {
            return new Decision(Action.RETRY_STEP, boundedRetries + 1);
        }
        return new Decision(rerouteAvailable ? Action.REROUTE : Action.ABORT, boundedRetries);
    }

    static int failureBackoffTicks(FailureSubtype failure, int consecutiveFailures) {
        int repeats = Math.max(1, consecutiveFailures);
        return switch (failure) {
            case JOB_BLOCK_EXCLUSION, TARGET_PROTECTED -> 20;
            case NAVIGATION_FAILED, RETURN_PATH_FAILED -> NAVIGATION_BACKOFF_TICKS;
            case TOOL_LOST -> TOOL_RECHECK_TICKS;
            case WATER_HAZARD -> 20 * 60;
            case NONE -> CANNOT_ADVANCE_BACKOFF_BASE_TICKS;
            default -> CANNOT_ADVANCE_BACKOFF_BASE_TICKS
                    + Math.min(2, repeats - 1) * (20 * 10);
        };
    }

    static boolean batchComplete(int completedSteps, int targetSteps) {
        return targetSteps > 0 && completedSteps >= targetSteps;
    }

    static ReturnPathAction returnPathAction(int completedRetries) {
        return completedRetries < MAX_LOCAL_RETRIES
                ? ReturnPathAction.RETRY_STAIR
                : ReturnPathAction.FALL_BACK_TO_CHEST;
    }

    enum Action {
        RETRY_STEP,
        REROUTE,
        ABORT
    }

    enum ReturnPathAction {
        RETRY_STAIR,
        FALL_BACK_TO_CHEST
    }

    enum FailureSubtype {
        NONE("none"),
        TARGET_PROTECTED("target_protected"),
        HEAD_CLEAR_FAILED("head_clear_failed"),
        STEP_CLEAR_FAILED("step_clear_failed"),
        TRANSITION_REPAIR_FAILED("transition_repair_failed"),
        NAVIGATION_FAILED("navigation_failed"),
        SUPPORT_FAILED("support_failed"),
        UNMINEABLE_BLOCK("unmineable_block"),
        BLOCK_BREAK_FAILED("block_break_failed"),
        JOB_BLOCK_EXCLUSION("job_block_exclusion"),
        WATER_HAZARD("water_hazard"),
        TOOL_LOST("tool_lost"),
        RETURN_PATH_FAILED("return_path_failed");

        private final String reasonCode;

        FailureSubtype(String reasonCode) {
            this.reasonCode = reasonCode;
        }

        String reasonCode() {
            return reasonCode;
        }
    }

    record Decision(Action action, int nextRetryCount) {
    }
}
