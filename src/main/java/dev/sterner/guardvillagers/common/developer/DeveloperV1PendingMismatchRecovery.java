package dev.sterner.guardvillagers.common.developer;

/** Pure bounded-retry policy for transient V1 villager profession/POI cross-claims. */
final class DeveloperV1PendingMismatchRecovery {
    static final int MAX_RECOVERY_ATTEMPTS = 4;

    private int recoveryAttempts;

    Action observe(boolean wrongJobSite, boolean wrongProfession) {
        if (!wrongJobSite && !wrongProfession) {
            return Action.NONE;
        }
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            return Action.WAIT_FOR_TIMEOUT;
        }
        recoveryAttempts++;
        return Action.RECOVER;
    }

    int recoveryAttempts() {
        return recoveryAttempts;
    }

    static boolean canResetTemporaryProfession(boolean wrongProfession, int level, int experience) {
        return wrongProfession && level <= 1 && experience == 0;
    }

    enum Action {
        NONE,
        RECOVER,
        WAIT_FOR_TIMEOUT
    }
}
