package dev.sterner.guardvillagers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardVillagersConfigRangeValidationTest {

    @Test
    void clampsHeavyAndOverflowRangeSettings() {
        GuardVillagersConfig.quartermasterScanRange = -1;
        GuardVillagersConfig.armorerFallbackScanRange = 9000;
        GuardVillagersConfig.overflowRecipientScanRange = 2;
        GuardVillagersConfig.professionalRecipientScanRange = 1;
        GuardVillagersConfig.professionalRecipientWideScanRange = 9999;
        GuardVillagersConfig.overflowFallbackQmSearchRadius = 0;
        GuardVillagersConfig.lumberjackMaxConcurrentHeavyScansPerWorld = 0;
        GuardVillagersConfig.lumberjackGovernorHighLoadThresholdPermille = 9999;
        GuardVillagersConfig.lumberjackGovernorLowLoadThresholdPermille = 1;
        GuardVillagersConfig.lumberjackGovernorHighLoadDeferTicks = 1;
        GuardVillagersConfig.lumberjackGovernorMetricsLogInterval = 0;
        GuardVillagersConfig.farmerAdaptiveThrottleLoadThreshold = 0;
        GuardVillagersConfig.farmerAdaptiveThrottleDeferTicks = 0;
        GuardVillagersConfig.farmerAdaptiveThrottleJitterTicks = 999999;
        GuardVillagersConfig.farmerAdaptiveSummaryLogIntervalSessions = 0;
        GuardVillagersConfig.weaponsmithAdaptiveThrottleLoadThreshold = 999999;
        GuardVillagersConfig.weaponsmithAdaptiveThrottleDeferTicks = 0;
        GuardVillagersConfig.weaponsmithAdaptiveThrottleJitterTicks = 999999;
        GuardVillagersConfig.weaponsmithAdaptiveSummaryLogIntervalSessions = 0;
        GuardVillagersConfig.shepherdFenceBatchMin = 0;
        GuardVillagersConfig.shepherdFenceBatchMax = 999;

        GuardVillagersConfig.validateClampedRanges();

        assertEquals(GuardVillagersConfig.MIN_HEAVY_SCAN_RANGE, GuardVillagersConfig.quartermasterScanRange);
        assertEquals(GuardVillagersConfig.MAX_HEAVY_SCAN_RANGE, GuardVillagersConfig.armorerFallbackScanRange);
        assertEquals(GuardVillagersConfig.MIN_OVERFLOW_RECIPIENT_SCAN_RANGE, GuardVillagersConfig.overflowRecipientScanRange);
        assertEquals(GuardVillagersConfig.MIN_PROFESSIONAL_RECIPIENT_SCAN_RANGE, GuardVillagersConfig.professionalRecipientScanRange);
        assertEquals(GuardVillagersConfig.MAX_PROFESSIONAL_RECIPIENT_SCAN_RANGE, GuardVillagersConfig.professionalRecipientWideScanRange);
        assertEquals(GuardVillagersConfig.MIN_HEAVY_SCAN_RANGE, GuardVillagersConfig.overflowFallbackQmSearchRadius);
        assertEquals(GuardVillagersConfig.MIN_LUMBERJACK_MAX_CONCURRENT_HEAVY_SCANS, GuardVillagersConfig.lumberjackMaxConcurrentHeavyScansPerWorld);
        assertEquals(GuardVillagersConfig.MAX_LUMBERJACK_GOVERNOR_THRESHOLD, GuardVillagersConfig.lumberjackGovernorHighLoadThresholdPermille);
        assertEquals(GuardVillagersConfig.MIN_LUMBERJACK_GOVERNOR_THRESHOLD, GuardVillagersConfig.lumberjackGovernorLowLoadThresholdPermille);
        assertEquals(GuardVillagersConfig.MIN_LUMBERJACK_GOVERNOR_DEFER_TICKS, GuardVillagersConfig.lumberjackGovernorHighLoadDeferTicks);
        assertEquals(GuardVillagersConfig.MIN_LUMBERJACK_GOVERNOR_LOG_INTERVAL, GuardVillagersConfig.lumberjackGovernorMetricsLogInterval);
        assertEquals(GuardVillagersConfig.MIN_PROFESSION_THROTTLE_THRESHOLD, GuardVillagersConfig.farmerAdaptiveThrottleLoadThreshold);
        assertEquals(GuardVillagersConfig.MIN_PROFESSION_THROTTLE_DEFER_TICKS, GuardVillagersConfig.farmerAdaptiveThrottleDeferTicks);
        assertEquals(GuardVillagersConfig.MAX_PROFESSION_THROTTLE_JITTER_TICKS, GuardVillagersConfig.farmerAdaptiveThrottleJitterTicks);
        assertEquals(GuardVillagersConfig.MIN_PROFESSION_SUMMARY_LOG_INTERVAL, GuardVillagersConfig.farmerAdaptiveSummaryLogIntervalSessions);
        assertEquals(GuardVillagersConfig.MAX_PROFESSION_THROTTLE_THRESHOLD, GuardVillagersConfig.weaponsmithAdaptiveThrottleLoadThreshold);
        assertEquals(GuardVillagersConfig.MIN_PROFESSION_THROTTLE_DEFER_TICKS, GuardVillagersConfig.weaponsmithAdaptiveThrottleDeferTicks);
        assertEquals(GuardVillagersConfig.MAX_PROFESSION_THROTTLE_JITTER_TICKS, GuardVillagersConfig.weaponsmithAdaptiveThrottleJitterTicks);
        assertEquals(GuardVillagersConfig.MIN_PROFESSION_SUMMARY_LOG_INTERVAL, GuardVillagersConfig.weaponsmithAdaptiveSummaryLogIntervalSessions);
        assertEquals(GuardVillagersConfig.MIN_SHEPHERD_FENCE_BATCH, GuardVillagersConfig.shepherdFenceBatchMin);
        assertEquals(GuardVillagersConfig.MAX_SHEPHERD_FENCE_BATCH, GuardVillagersConfig.shepherdFenceBatchMax);
    }
}
