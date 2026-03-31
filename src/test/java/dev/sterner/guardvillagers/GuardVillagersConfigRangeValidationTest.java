package dev.sterner.guardvillagers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardVillagersConfigRangeValidationTest {

    @Test
    void clampsHeavyAndOverflowRangeSettings() {
        GuardVillagersConfig.quartermasterScanRange = -1;
        GuardVillagersConfig.armorerFallbackScanRange = 9000;
        GuardVillagersConfig.overflowRecipientScanRange = 2;
        GuardVillagersConfig.overflowFallbackQmSearchRadius = 0;

        GuardVillagersConfig.validateClampedRanges();

        assertEquals(GuardVillagersConfig.MIN_HEAVY_SCAN_RANGE, GuardVillagersConfig.quartermasterScanRange);
        assertEquals(GuardVillagersConfig.MAX_HEAVY_SCAN_RANGE, GuardVillagersConfig.armorerFallbackScanRange);
        assertEquals(GuardVillagersConfig.MIN_OVERFLOW_RECIPIENT_SCAN_RANGE, GuardVillagersConfig.overflowRecipientScanRange);
        assertEquals(GuardVillagersConfig.MIN_HEAVY_SCAN_RANGE, GuardVillagersConfig.overflowFallbackQmSearchRadius);
    }
}
