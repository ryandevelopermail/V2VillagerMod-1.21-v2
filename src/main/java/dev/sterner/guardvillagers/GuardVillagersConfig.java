package dev.sterner.guardvillagers;

import eu.midnightdust.lib.config.MidnightConfig;

import java.util.ArrayList;
import java.util.List;

public class GuardVillagersConfig extends MidnightConfig {
    public static final int MIN_HEAVY_SCAN_RANGE = 32;
    public static final int MAX_HEAVY_SCAN_RANGE = 512;
    public static final int MIN_OVERFLOW_RECIPIENT_SCAN_RANGE = 16;
    public static final int MAX_OVERFLOW_RECIPIENT_SCAN_RANGE = 256;
    public static final int MIN_PROFESSIONAL_RECIPIENT_SCAN_RANGE = 16;
    public static final int MAX_PROFESSIONAL_RECIPIENT_SCAN_RANGE = 256;
    public static final int MIN_MASON_WALL_FOOTPRINT_RADIUS = 32;
    public static final int MAX_MASON_WALL_FOOTPRINT_RADIUS = 256;
    public static final int MIN_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET = 250;
    public static final int MAX_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET = 32000;
    public static final int MIN_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET = 500;
    public static final int MAX_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET = 128000;
    public static final int MIN_LUMBERJACK_MAX_CONCURRENT_HEAVY_SCANS = 1;
    public static final int MAX_LUMBERJACK_MAX_CONCURRENT_HEAVY_SCANS = 32;
    public static final int MIN_LUMBERJACK_GOVERNOR_THRESHOLD = 100;
    public static final int MAX_LUMBERJACK_GOVERNOR_THRESHOLD = 5000;
    public static final int MIN_LUMBERJACK_GOVERNOR_DEFER_TICKS = 20;
    public static final int MAX_LUMBERJACK_GOVERNOR_DEFER_TICKS = 20 * 60 * 10;
    public static final int MIN_LUMBERJACK_GOVERNOR_LOG_INTERVAL = 1;
    public static final int MAX_LUMBERJACK_GOVERNOR_LOG_INTERVAL = 500;
    public static final int MIN_LUMBERJACK_GOVERNOR_BACKPRESSURE_PERMILLE = 0;
    public static final int MAX_LUMBERJACK_GOVERNOR_BACKPRESSURE_PERMILLE = 900;
    public static final int MIN_LUMBERJACK_BASE_TREE_SEARCH_RADIUS = 8;
    public static final int MAX_LUMBERJACK_BASE_TREE_SEARCH_RADIUS = 40;
    public static final int MIN_PROFESSION_THROTTLE_THRESHOLD = 1;
    public static final int MAX_PROFESSION_THROTTLE_THRESHOLD = 500000;
    public static final int MIN_PROFESSION_THROTTLE_DEFER_TICKS = 20;
    public static final int MAX_PROFESSION_THROTTLE_DEFER_TICKS = 20 * 60 * 10;
    public static final int MIN_PROFESSION_THROTTLE_JITTER_TICKS = 0;
    public static final int MAX_PROFESSION_THROTTLE_JITTER_TICKS = 20 * 60;
    public static final int MIN_PROFESSION_SUMMARY_LOG_INTERVAL = 1;
    public static final int MAX_PROFESSION_SUMMARY_LOG_INTERVAL = 1000;
    public static final int MIN_SHEPHERD_FENCE_BATCH = 1;
    public static final int MAX_SHEPHERD_FENCE_BATCH = 16;
    public static final int MIN_QUARTERMASTER_NATURAL_VILLAGE_POI_SCAN_RADIUS = 16;
    public static final int MAX_QUARTERMASTER_NATURAL_VILLAGE_POI_SCAN_RADIUS = 128;
    public static final int MIN_QUARTERMASTER_NATURAL_VILLAGE_CHEST_LOCAL_POI_RADIUS = 1;
    public static final int MAX_QUARTERMASTER_NATURAL_VILLAGE_CHEST_LOCAL_POI_RADIUS = 64;
    public static final int MIN_FORESTER_BONEMEAL_SCAN_RADIUS = 24;
    public static final int MAX_FORESTER_BONEMEAL_SCAN_RADIUS = 96;
    public static final int MIN_FARMER_BONEMEAL_SCAN_RADIUS = 4;
    public static final int MAX_FARMER_BONEMEAL_SCAN_RADIUS = 96;
    public static final int MIN_BONEMEAL_APPLICATIONS_PER_SESSION = 1;
    public static final int MAX_BONEMEAL_APPLICATIONS_PER_SESSION = 128;

    public enum MasonWallPoiMode {
        JOB_SITES_ONLY,
        JOBS_AND_BEDS,
        ALL_POIS
    }

    @Entry
    public static int reputationRequirementToBeAttacked = -100;
    @Entry
    public static int reputationRequirement = 15;
    @Entry
    public static boolean guardEntitysRunFromPolarBears = false;
    @Entry
    public static boolean guardEntitysOpenDoors = true;
    @Entry
    public static boolean guardEntityFormation = true;
    @Entry
    public static boolean clericHealing = true;
    @Entry
    public static boolean armorerRepairGuardEntityArmor = true;
    @Entry
    public static boolean attackAllMobs = false;
    @Entry
    public static boolean guardAlwaysShield = false;
    @Entry
    public static boolean friendlyFire = true;
    @Entry
    public static List<String> mobBlackList = new ArrayList<>();
    @Entry
    public static float amountOfHealthRegenerated = 1F;
    @Entry
    public static boolean followHero = true;
    @Entry
    public static double healthModifier = 20D;
    @Entry
    public static double speedModifier = 0.5D;
    @Entry
    public static double followRangeModifier = 20D;
    @Entry
    public static boolean giveGuardStuffHotv = false;
    @Entry
    public static boolean setGuardPatrolHotv = false;
    @Entry
    public static float chanceToDropEquipment = 100F;
    @Entry
    public static boolean useSteveModel = false;
    @Entry
    public static boolean raidAnimals = false;
    @Entry
    public static boolean witchesVillager = true;
    @Entry
    public static boolean blackSmithHealing = true;
    @Entry
    public static boolean convertVillagerIfHaveHotv = false;
    @Entry
    public static double guardVillagerHelpRange = 50;
    @Entry
    public static boolean illagersRunFromPolarBears = true;
    @Entry
    public static boolean villagersRunFromPolarBears = true;
    @Entry
    public static boolean guardArrowsHurtVillagers = true;
    @Entry
    public static float spawnChancePerVillager = 0.5f;
    @Entry
    public static boolean villagerConversionFallbackSweepEnabled = false;
    @Entry(min=20)
    public static int villagerConversionCandidateMarkIntervalTicks = 1200;
    @Entry(min=20)
    public static int villagerConversionExecutionIntervalTicks = 40;
    @Entry
    public static int bellReportChestFallbackRadius = 6;
    @Entry
    public static boolean bellChestRequireNearbyBed = false;
    @Entry
    public static int bellChestBedSearchRadius = 6;
    @Entry(min=1)
    public static int masonTableDailyCraftLimit = 4;
    @Entry(min=1)
    public static int shepherdFenceBatchMin = 2;
    @Entry(min=1)
    public static int shepherdFenceBatchMax = 3;
    @Entry
    public static boolean shepherdAllowPenRebuildIfMissing = false;
    @Entry
    public static MasonWallPoiMode masonWallPoiMode = MasonWallPoiMode.JOBS_AND_BEDS;
    @Entry(min=0)
    public static int masonWallExpandBlocks = 5;
    @Entry(min=0)
    public static int masonWallPostExpansionBuffer = 1;
    @Entry(min=0)
    public static int masonWallMaxWidth = 0;
    @Entry(min=0)
    public static int masonWallMaxDepth = 0;
    @Entry(min=0)
    public static int masonWallBootstrapMaxSpan = 48;
    @Entry(min=32)
    public static int masonWallFootprintRadius = 80;
    @Entry(min=0)
    public static int masonWallStagingMaxBelowSurfaceDelta = 3;
    @Entry(min=40)
    public static int masonWallBuilderAssignmentStallTicks = 200;
    @Entry
    public static boolean masonWallVerboseLogging = false;
    @Entry(min=1)
    public static int farmerWheatSeedReserveCap = 64;
    @Entry(min=0)
    public static int farmerWheatSeedBootstrapFloor = 0;
    @Entry
    public static boolean foresterBonemealEnabled = true;
    @Entry(min=1)
    public static int foresterBonemealMaxApplicationsPerSession = 16;
    @Entry(min=24)
    public static int foresterBonemealScanRadius = 80;
    @Entry
    public static boolean farmerBonemealEnabled = true;
    @Entry(min=1)
    public static int farmerBonemealMaxApplicationsPerSession = 16;
    @Entry(min=4)
    public static int farmerBonemealScanRadius = 24;
    @Entry(min=0)
    public static int lumberjackVillageMin = 1;
    @Entry(min=0)
    public static int lumberjackVillageMinLargeVillage = 2;
    @Entry(min=1)
    public static int lumberjackVillageMinLargeVillagePopulation = 12;
     @Entry(min=20)
     public static int lumberjackSpawnRetryIntervalTicks = 1200;
    @Entry(min=1)
    public static int lumberjackNaturalLeafSearchRadius = 3;
    @Entry(min=1)
    public static int lumberjackNaturalRequiredLeafCount = 4;
    @Entry(min=1)
    public static int lumberjackStructureProximityRadius = 2;
    @Entry(min=0)
    public static int lumberjackHousePoiProtectionRadius = 3;
    // Dense-village tuning: larger radius finds more candidates but increases scan cost unless governor throttling is active.
    @Entry(min=8)
    public static int lumberjackBaseTreeSearchRadius = 28;
    @Entry(min=250)
    public static int lumberjackTreeScanPerGuardBudgetCap = 8000;
    @Entry(min=500)
    public static int lumberjackTreeScanWorldSharedBudgetCap = 24000;
    @Entry(min=1)
    public static int lumberjackMaxConcurrentHeavyScansPerWorld = 2;
    @Entry(min=100)
    public static int lumberjackGovernorHighLoadThresholdPermille = 1350;
    @Entry(min=100)
    public static int lumberjackGovernorLowLoadThresholdPermille = 700;
    @Entry(min=20)
    public static int lumberjackGovernorHighLoadDeferTicks = 20 * 15;
    @Entry(min=1)
    public static int lumberjackGovernorMetricsLogInterval = 10;
    @Entry(min=0)
    public static int lumberjackGovernorDeferBackpressureThresholdPermille = 700;
    @Entry(min=0)
    public static int lumberjackGovernorPassiveBackpressureDecayPermillePerSlice = 35;
    @Entry(min=1)
    public static int lumberjackGovernorPassiveBackpressureDecayIntervalTicks = 20 * 10;
    @Entry(min=20)
    public static int lumberjackGovernorForcedProbeIntervalTicks = 20 * 45;
    @Entry(min=0)
    public static int lumberjackGovernorBackpressureDeferLogMinIntervalTicks = 20 * 20;
    @Entry(min=1)
    public static int lumberjackGovernorConsecutiveBackpressureDefersSnapshotThreshold = 3;
    @Entry
    public static boolean lumberjackVerboseLogging = false;
    @Entry(min=1)
    public static int lumberjackAdaptiveThrottleLoadThreshold = 26000;
    @Entry(min=20)
    public static int lumberjackAdaptiveThrottleDeferTicks = 20 * 8;
    @Entry(min=0)
    public static int lumberjackAdaptiveThrottleJitterTicks = 40;
    @Entry(min=1)
    public static int lumberjackAdaptiveSummaryLogIntervalSessions = 8;
    @Entry(min=0)
    public static int quartermasterLumberjackReserveLogs = 64;
    @Entry(min=0)
    public static int quartermasterLumberjackReservePlanks = 32;
    @Entry(min=0)
    public static int quartermasterLumberjackReserveCharcoal = 16;
    @Entry(min=0)
    public static int quartermasterLumberjackFurnaceFuelReserve = 8;
    @Entry(min=20)
    public static int quartermasterLumberjackDrainSweepIntervalTicks = 12_000;
    @Entry
    public static boolean quartermasterLumberjackDrainEnabled = false;
    @Entry
    public static boolean quartermasterLumberjackDrainProtectUpgradeRecipeInputs = true;
    @Entry(min=0.0, max=1.0)
    public static double quartermasterLumberjackReclaimQmChestFullnessThreshold = 0.85D;
    @Entry(min=1)
    public static int masonAdaptiveThrottleLoadThreshold = 180;
    @Entry(min=20)
    public static int masonAdaptiveThrottleDeferTicks = 20 * 6;
    @Entry(min=0)
    public static int masonAdaptiveThrottleJitterTicks = 60;
    @Entry(min=1)
    public static int masonAdaptiveSummaryLogIntervalSessions = 8;
    @Entry(min=20)
    public static int masonWaterBailoutBlacklistDurationTicks = 20 * 60 * 10;
    @Entry(min=20)
    public static int masonWaterBailoutBlacklistRadius = 20;
    @Entry(min=1)
    public static int farmerAdaptiveThrottleLoadThreshold = 32000;
    @Entry(min=20)
    public static int farmerAdaptiveThrottleDeferTicks = 20 * 8;
    @Entry(min=0)
    public static int farmerAdaptiveThrottleJitterTicks = 60;
    @Entry(min=1)
    public static int farmerAdaptiveSummaryLogIntervalSessions = 10;
    @Entry(min=1)
    public static int weaponsmithAdaptiveThrottleLoadThreshold = 420;
    @Entry(min=20)
    public static int weaponsmithAdaptiveThrottleDeferTicks = 20 * 5;
    @Entry(min=0)
    public static int weaponsmithAdaptiveThrottleJitterTicks = 50;
    @Entry(min=1)
    public static int weaponsmithAdaptiveSummaryLogIntervalSessions = 10;
    @Entry
    public static boolean fishermanRequireSkyVisibleWater = true;
    @Entry
    public static boolean fishermanAllowSurfaceQualifiedWater = true;
    @Entry(min=20)
    public static int fishermanInvalidWaterRescanCooldownTicks = 20 * 15;
    @Entry
    public static int quartermasterScanRange = 128;
    @Entry
    public static int armorerFallbackScanRange = 128;
    @Entry
    public static int overflowRecipientScanRange = 96;
    @Entry(min=16)
    public static int professionalRecipientScanRange = 32;
    @Entry(min=16)
    public static int professionalRecipientWideScanRange = 64;
    @Entry
    public static int overflowFallbackQmSearchRadius = 128;
    @Entry(min=16)
    public static int quartermasterNaturalVillagePoiScanRadius = 64;
    @Entry(min=1)
    public static int quartermasterNaturalVillageChestLocalPoiRadius = 20;

    public static void validateClampedRanges() {
        quartermasterScanRange = clamp(quartermasterScanRange, MIN_HEAVY_SCAN_RANGE, MAX_HEAVY_SCAN_RANGE);
        armorerFallbackScanRange = clamp(armorerFallbackScanRange, MIN_HEAVY_SCAN_RANGE, MAX_HEAVY_SCAN_RANGE);
        overflowRecipientScanRange = clamp(overflowRecipientScanRange, MIN_OVERFLOW_RECIPIENT_SCAN_RANGE, MAX_OVERFLOW_RECIPIENT_SCAN_RANGE);
        professionalRecipientScanRange = clamp(professionalRecipientScanRange, MIN_PROFESSIONAL_RECIPIENT_SCAN_RANGE, MAX_PROFESSIONAL_RECIPIENT_SCAN_RANGE);
        professionalRecipientWideScanRange = clamp(professionalRecipientWideScanRange, MIN_PROFESSIONAL_RECIPIENT_SCAN_RANGE, MAX_PROFESSIONAL_RECIPIENT_SCAN_RANGE);
        if (professionalRecipientWideScanRange < professionalRecipientScanRange) {
            professionalRecipientWideScanRange = professionalRecipientScanRange;
        }
        overflowFallbackQmSearchRadius = clamp(overflowFallbackQmSearchRadius, MIN_HEAVY_SCAN_RANGE, MAX_HEAVY_SCAN_RANGE);
        quartermasterNaturalVillagePoiScanRadius = clamp(
                quartermasterNaturalVillagePoiScanRadius,
                MIN_QUARTERMASTER_NATURAL_VILLAGE_POI_SCAN_RADIUS,
                MAX_QUARTERMASTER_NATURAL_VILLAGE_POI_SCAN_RADIUS);
        quartermasterNaturalVillageChestLocalPoiRadius = clamp(
                quartermasterNaturalVillageChestLocalPoiRadius,
                MIN_QUARTERMASTER_NATURAL_VILLAGE_CHEST_LOCAL_POI_RADIUS,
                MAX_QUARTERMASTER_NATURAL_VILLAGE_CHEST_LOCAL_POI_RADIUS);
        masonWallFootprintRadius = clamp(masonWallFootprintRadius, MIN_MASON_WALL_FOOTPRINT_RADIUS, MAX_MASON_WALL_FOOTPRINT_RADIUS);
        masonWallStagingMaxBelowSurfaceDelta = clamp(masonWallStagingMaxBelowSurfaceDelta, 0, 16);
        masonWallBuilderAssignmentStallTicks = clamp(masonWallBuilderAssignmentStallTicks, 40, 20 * 60 * 10);
        lumberjackTreeScanPerGuardBudgetCap = clamp(
                lumberjackTreeScanPerGuardBudgetCap,
                MIN_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET,
                MAX_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET);
        lumberjackTreeScanWorldSharedBudgetCap = clamp(
                lumberjackTreeScanWorldSharedBudgetCap,
                MIN_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET,
                MAX_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET);
        lumberjackMaxConcurrentHeavyScansPerWorld = clamp(
                lumberjackMaxConcurrentHeavyScansPerWorld,
                MIN_LUMBERJACK_MAX_CONCURRENT_HEAVY_SCANS,
                MAX_LUMBERJACK_MAX_CONCURRENT_HEAVY_SCANS);
        lumberjackGovernorHighLoadThresholdPermille = clamp(
                lumberjackGovernorHighLoadThresholdPermille,
                MIN_LUMBERJACK_GOVERNOR_THRESHOLD,
                MAX_LUMBERJACK_GOVERNOR_THRESHOLD);
        lumberjackGovernorLowLoadThresholdPermille = clamp(
                lumberjackGovernorLowLoadThresholdPermille,
                MIN_LUMBERJACK_GOVERNOR_THRESHOLD,
                MAX_LUMBERJACK_GOVERNOR_THRESHOLD);
        lumberjackGovernorHighLoadDeferTicks = clamp(
                lumberjackGovernorHighLoadDeferTicks,
                MIN_LUMBERJACK_GOVERNOR_DEFER_TICKS,
                MAX_LUMBERJACK_GOVERNOR_DEFER_TICKS);
        lumberjackGovernorMetricsLogInterval = clamp(
                lumberjackGovernorMetricsLogInterval,
                MIN_LUMBERJACK_GOVERNOR_LOG_INTERVAL,
                MAX_LUMBERJACK_GOVERNOR_LOG_INTERVAL);
        lumberjackGovernorDeferBackpressureThresholdPermille = clamp(
                lumberjackGovernorDeferBackpressureThresholdPermille,
                MIN_LUMBERJACK_GOVERNOR_BACKPRESSURE_PERMILLE,
                MAX_LUMBERJACK_GOVERNOR_BACKPRESSURE_PERMILLE);
        lumberjackGovernorPassiveBackpressureDecayPermillePerSlice = clamp(
                lumberjackGovernorPassiveBackpressureDecayPermillePerSlice,
                MIN_LUMBERJACK_GOVERNOR_BACKPRESSURE_PERMILLE,
                MAX_LUMBERJACK_GOVERNOR_BACKPRESSURE_PERMILLE);
        lumberjackGovernorPassiveBackpressureDecayIntervalTicks = clamp(
                lumberjackGovernorPassiveBackpressureDecayIntervalTicks,
                1,
                MAX_LUMBERJACK_GOVERNOR_DEFER_TICKS);
        lumberjackGovernorForcedProbeIntervalTicks = clamp(
                lumberjackGovernorForcedProbeIntervalTicks,
                MIN_LUMBERJACK_GOVERNOR_DEFER_TICKS,
                MAX_LUMBERJACK_GOVERNOR_DEFER_TICKS);
        lumberjackGovernorBackpressureDeferLogMinIntervalTicks = clamp(
                lumberjackGovernorBackpressureDeferLogMinIntervalTicks,
                0,
                MAX_LUMBERJACK_GOVERNOR_DEFER_TICKS);
        lumberjackGovernorConsecutiveBackpressureDefersSnapshotThreshold = clamp(
                lumberjackGovernorConsecutiveBackpressureDefersSnapshotThreshold,
                1,
                MAX_LUMBERJACK_GOVERNOR_LOG_INTERVAL * 10);
        lumberjackBaseTreeSearchRadius = clamp(
                lumberjackBaseTreeSearchRadius,
                MIN_LUMBERJACK_BASE_TREE_SEARCH_RADIUS,
                MAX_LUMBERJACK_BASE_TREE_SEARCH_RADIUS);
        lumberjackAdaptiveThrottleLoadThreshold = clamp(lumberjackAdaptiveThrottleLoadThreshold, MIN_PROFESSION_THROTTLE_THRESHOLD, MAX_PROFESSION_THROTTLE_THRESHOLD);
        lumberjackAdaptiveThrottleDeferTicks = clamp(lumberjackAdaptiveThrottleDeferTicks, MIN_PROFESSION_THROTTLE_DEFER_TICKS, MAX_PROFESSION_THROTTLE_DEFER_TICKS);
        lumberjackAdaptiveThrottleJitterTicks = clamp(lumberjackAdaptiveThrottleJitterTicks, MIN_PROFESSION_THROTTLE_JITTER_TICKS, MAX_PROFESSION_THROTTLE_JITTER_TICKS);
        lumberjackAdaptiveSummaryLogIntervalSessions = clamp(lumberjackAdaptiveSummaryLogIntervalSessions, MIN_PROFESSION_SUMMARY_LOG_INTERVAL, MAX_PROFESSION_SUMMARY_LOG_INTERVAL);
        masonAdaptiveThrottleLoadThreshold = clamp(masonAdaptiveThrottleLoadThreshold, MIN_PROFESSION_THROTTLE_THRESHOLD, MAX_PROFESSION_THROTTLE_THRESHOLD);
        masonAdaptiveThrottleDeferTicks = clamp(masonAdaptiveThrottleDeferTicks, MIN_PROFESSION_THROTTLE_DEFER_TICKS, MAX_PROFESSION_THROTTLE_DEFER_TICKS);
        masonAdaptiveThrottleJitterTicks = clamp(masonAdaptiveThrottleJitterTicks, MIN_PROFESSION_THROTTLE_JITTER_TICKS, MAX_PROFESSION_THROTTLE_JITTER_TICKS);
        masonAdaptiveSummaryLogIntervalSessions = clamp(masonAdaptiveSummaryLogIntervalSessions, MIN_PROFESSION_SUMMARY_LOG_INTERVAL, MAX_PROFESSION_SUMMARY_LOG_INTERVAL);
        masonWaterBailoutBlacklistDurationTicks = clamp(
                masonWaterBailoutBlacklistDurationTicks,
                MIN_PROFESSION_THROTTLE_DEFER_TICKS,
                MAX_PROFESSION_THROTTLE_DEFER_TICKS);
        masonWaterBailoutBlacklistRadius = clamp(masonWaterBailoutBlacklistRadius, 20, 128);
        farmerAdaptiveThrottleLoadThreshold = clamp(farmerAdaptiveThrottleLoadThreshold, MIN_PROFESSION_THROTTLE_THRESHOLD, MAX_PROFESSION_THROTTLE_THRESHOLD);
        farmerAdaptiveThrottleDeferTicks = clamp(farmerAdaptiveThrottleDeferTicks, MIN_PROFESSION_THROTTLE_DEFER_TICKS, MAX_PROFESSION_THROTTLE_DEFER_TICKS);
        farmerAdaptiveThrottleJitterTicks = clamp(farmerAdaptiveThrottleJitterTicks, MIN_PROFESSION_THROTTLE_JITTER_TICKS, MAX_PROFESSION_THROTTLE_JITTER_TICKS);
        farmerAdaptiveSummaryLogIntervalSessions = clamp(farmerAdaptiveSummaryLogIntervalSessions, MIN_PROFESSION_SUMMARY_LOG_INTERVAL, MAX_PROFESSION_SUMMARY_LOG_INTERVAL);
        weaponsmithAdaptiveThrottleLoadThreshold = clamp(weaponsmithAdaptiveThrottleLoadThreshold, MIN_PROFESSION_THROTTLE_THRESHOLD, MAX_PROFESSION_THROTTLE_THRESHOLD);
        weaponsmithAdaptiveThrottleDeferTicks = clamp(weaponsmithAdaptiveThrottleDeferTicks, MIN_PROFESSION_THROTTLE_DEFER_TICKS, MAX_PROFESSION_THROTTLE_DEFER_TICKS);
        weaponsmithAdaptiveThrottleJitterTicks = clamp(weaponsmithAdaptiveThrottleJitterTicks, MIN_PROFESSION_THROTTLE_JITTER_TICKS, MAX_PROFESSION_THROTTLE_JITTER_TICKS);
        weaponsmithAdaptiveSummaryLogIntervalSessions = clamp(weaponsmithAdaptiveSummaryLogIntervalSessions, MIN_PROFESSION_SUMMARY_LOG_INTERVAL, MAX_PROFESSION_SUMMARY_LOG_INTERVAL);
        fishermanInvalidWaterRescanCooldownTicks = clamp(fishermanInvalidWaterRescanCooldownTicks, MIN_PROFESSION_THROTTLE_DEFER_TICKS, MAX_PROFESSION_THROTTLE_DEFER_TICKS);
        quartermasterLumberjackReclaimQmChestFullnessThreshold = clamp(quartermasterLumberjackReclaimQmChestFullnessThreshold, 0.0D, 1.0D);
        shepherdFenceBatchMin = clamp(shepherdFenceBatchMin, MIN_SHEPHERD_FENCE_BATCH, MAX_SHEPHERD_FENCE_BATCH);
        shepherdFenceBatchMax = clamp(shepherdFenceBatchMax, MIN_SHEPHERD_FENCE_BATCH, MAX_SHEPHERD_FENCE_BATCH);
        if (shepherdFenceBatchMax < shepherdFenceBatchMin) {
            shepherdFenceBatchMax = shepherdFenceBatchMin;
        }
        foresterBonemealMaxApplicationsPerSession = clamp(
                foresterBonemealMaxApplicationsPerSession,
                MIN_BONEMEAL_APPLICATIONS_PER_SESSION,
                MAX_BONEMEAL_APPLICATIONS_PER_SESSION);
        foresterBonemealScanRadius = clamp(
                foresterBonemealScanRadius,
                MIN_FORESTER_BONEMEAL_SCAN_RADIUS,
                MAX_FORESTER_BONEMEAL_SCAN_RADIUS);
        farmerBonemealMaxApplicationsPerSession = clamp(
                farmerBonemealMaxApplicationsPerSession,
                MIN_BONEMEAL_APPLICATIONS_PER_SESSION,
                MAX_BONEMEAL_APPLICATIONS_PER_SESSION);
        farmerBonemealScanRadius = clamp(
                farmerBonemealScanRadius,
                MIN_FARMER_BONEMEAL_SCAN_RADIUS,
                MAX_FARMER_BONEMEAL_SCAN_RADIUS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
