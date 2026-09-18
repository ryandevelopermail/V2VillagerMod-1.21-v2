package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperV1PendingPairProgressTest {
    @Test
    void restraintEndsAfterTwentyTicks() {
        DeveloperV1PendingPairProgress progress = progress();

        for (int tick = 1; tick <= DeveloperSetupManager.V1_INITIAL_RESTRAINT_TICKS; tick++) {
            progress.advanceTick();
            assertTrue(progress.shouldRestrain(), "Pair should remain restrained through tick " + tick);
        }
        progress.advanceTick();

        assertEquals(21, progress.elapsedTicks());
        assertFalse(progress.shouldRestrain());
        assertEquals(DeveloperV1PendingPairProgress.Status.PENDING, progress.status());
    }

    @Test
    void releasingMovementDoesNotCompletePairing() {
        DeveloperV1PendingPairProgress progress = progress();
        advance(progress, 21);

        assertFalse(progress.shouldRestrain());
        assertFalse(progress.completeIfExactPair(false));
        assertEquals(DeveloperV1PendingPairProgress.Status.PENDING, progress.status());
    }

    @Test
    void exactPairingCanCompleteAfterRestraintEnds() {
        DeveloperV1PendingPairProgress progress = progress();
        advance(progress, 25);

        assertFalse(progress.shouldRestrain());
        assertTrue(progress.completeIfExactPair(true));
        assertEquals(DeveloperV1PendingPairProgress.Status.COMPLETE, progress.status());
        assertFalse(progress.timeOutIfExpired());
    }

    @Test
    void timeoutRemainsIndependentFromRestraintWindow() {
        DeveloperV1PendingPairProgress progress = progress();
        advance(progress, 99);

        assertFalse(progress.shouldRestrain());
        assertFalse(progress.timeOutIfExpired());
        progress.advanceTick();

        assertTrue(progress.timeOutIfExpired());
        assertEquals(DeveloperV1PendingPairProgress.Status.TIMED_OUT, progress.status());
    }

    private static DeveloperV1PendingPairProgress progress() {
        return new DeveloperV1PendingPairProgress(DeveloperSetupManager.V1_INITIAL_RESTRAINT_TICKS, 100);
    }

    private static void advance(DeveloperV1PendingPairProgress progress, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            progress.advanceTick();
        }
    }
}
