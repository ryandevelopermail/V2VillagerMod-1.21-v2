package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperTreeGrowthDriverTest {
    @Test
    void advancesSaplingThroughVanillaStageBeforeAcceptingMatureTree() {
        AtomicBoolean saplingPresent = new AtomicBoolean(true);
        AtomicBoolean oakLogPresent = new AtomicBoolean(false);
        AtomicInteger growthCalls = new AtomicInteger();
        AtomicBoolean cleanupCalled = new AtomicBoolean(false);

        boolean result = DeveloperTreeGrowthDriver.growToMaturity(
                3,
                saplingPresent::get,
                () -> !saplingPresent.get() && oakLogPresent.get(),
                () -> {
                    if (growthCalls.incrementAndGet() == 2) {
                        saplingPresent.set(false);
                        oakLogPresent.set(true);
                    }
                },
                () -> cleanupCalled.set(true)
        );

        assertTrue(result);
        assertEquals(2, growthCalls.get());
        assertFalse(cleanupCalled.get());
    }

    @Test
    void removesLeftoverSaplingAfterBoundedGrowthFailure() {
        AtomicBoolean saplingPresent = new AtomicBoolean(true);
        AtomicInteger growthCalls = new AtomicInteger();
        AtomicBoolean cleanupCalled = new AtomicBoolean(false);

        boolean result = DeveloperTreeGrowthDriver.growToMaturity(
                3,
                saplingPresent::get,
                () -> false,
                growthCalls::incrementAndGet,
                () -> {
                    cleanupCalled.set(true);
                    saplingPresent.set(false);
                }
        );

        assertFalse(result);
        assertEquals(3, growthCalls.get());
        assertTrue(cleanupCalled.get());
        assertFalse(saplingPresent.get());
    }

    @Test
    void doesNotAcceptSaplingDisappearanceWithoutOakLogs() {
        AtomicBoolean saplingPresent = new AtomicBoolean(true);
        AtomicBoolean cleanupCalled = new AtomicBoolean(false);

        boolean result = DeveloperTreeGrowthDriver.growToMaturity(
                3,
                saplingPresent::get,
                () -> false,
                () -> saplingPresent.set(false),
                () -> cleanupCalled.set(true)
        );

        assertFalse(result);
        assertFalse(cleanupCalled.get());
    }
}
