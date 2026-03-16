package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackGuardCraftingGoalTest {

    @Test
    void craftBootstrapChestAndAttemptPlacementIfNeeded_noChestAndEnoughPlanks_craftsChestAndAttemptsPlacement() {
        AtomicInteger craftCalls = new AtomicInteger();
        AtomicInteger placeCalls = new AtomicInteger();

        boolean acted = LumberjackGuardCraftingGoal.craftBootstrapChestAndAttemptPlacementIfNeeded(
                true,
                () -> {
                    craftCalls.incrementAndGet();
                    return true;
                },
                () -> {
                    placeCalls.incrementAndGet();
                    return false;
                }
        );

        assertTrue(acted);
        assertEquals(1, craftCalls.get());
        assertEquals(1, placeCalls.get());
    }

    @Test
    void craftBootstrapChestAndAttemptPlacementIfNeeded_craftFails_doesNotAttemptPlacement() {
        AtomicBoolean placementAttempted = new AtomicBoolean(false);

        boolean acted = LumberjackGuardCraftingGoal.craftBootstrapChestAndAttemptPlacementIfNeeded(
                true,
                () -> false,
                () -> {
                    placementAttempted.set(true);
                    return true;
                }
        );

        assertFalse(acted);
        assertFalse(placementAttempted.get());
    }
}
