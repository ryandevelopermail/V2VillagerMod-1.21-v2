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
        GuardVillagersConfig.lumberjackMaxConcurrentHeavyScansPerWorld = 0;
        GuardVillagersConfig.lumberjackGovernorHighLoadThresholdPermille = 9999;
        GuardVillagersConfig.lumberjackGovernorLowLoadThresholdPermille = 1;
        GuardVillagersConfig.lumberjackGovernorHighLoadDeferTicks = 1;
        GuardVillagersConfig.lumberjackGovernorMetricsLogInterval = 0;

        GuardVillagersConfig.validateClampedRanges();

        assertEquals(GuardVillagersConfig.MIN_HEAVY_SCAN_RANGE, GuardVillagersConfig.quartermasterScanRange);
        assertEquals(GuardVillagersConfig.MAX_HEAVY_SCAN_RANGE, GuardVillagersConfig.armorerFallbackScanRange);
        assertEquals(GuardVillagersConfig.MIN_OVERFLOW_RECIPIENT_SCAN_RANGE, GuardVillagersConfig.overflowRecipientScanRange);
        assertEquals(GuardVillagersConfig.MIN_HEAVY_SCAN_RANGE, GuardVillagersConfig.overflowFallbackQmSearchRadius);
        assertEquals(GuardVillagersConfig.MIN_LUMBERJACK_MAX_CONCURRENT_HEAVY_SCANS, GuardVillagersConfig.lumberjackMaxConcurrentHeavyScansPerWorld);
        assertEquals(GuardVillagersConfig.MAX_LUMBERJACK_GOVERNOR_THRESHOLD, GuardVillagersConfig.lumberjackGovernorHighLoadThresholdPermille);
        assertEquals(GuardVillagersConfig.MIN_LUMBERJACK_GOVERNOR_THRESHOLD, GuardVillagersConfig.lumberjackGovernorLowLoadThresholdPermille);
        assertEquals(GuardVillagersConfig.MIN_LUMBERJACK_GOVERNOR_DEFER_TICKS, GuardVillagersConfig.lumberjackGovernorHighLoadDeferTicks);
        assertEquals(GuardVillagersConfig.MIN_LUMBERJACK_GOVERNOR_LOG_INTERVAL, GuardVillagersConfig.lumberjackGovernorMetricsLogInterval);
    }
}
