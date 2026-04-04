package dev.sterner.guardvillagers;

import eu.midnightdust.lib.config.MidnightConfig;

import java.util.ArrayList;
import java.util.List;

public class GuardVillagersConfig extends MidnightConfig {
    public static final int MIN_HEAVY_SCAN_RANGE = 32;
    public static final int MAX_HEAVY_SCAN_RANGE = 512;
    public static final int MIN_OVERFLOW_RECIPIENT_SCAN_RANGE = 16;
    public static final int MAX_OVERFLOW_RECIPIENT_SCAN_RANGE = 256;
    public static final int MIN_MASON_WALL_FOOTPRINT_RADIUS = 32;
    public static final int MAX_MASON_WALL_FOOTPRINT_RADIUS = 256;
    public static final int MIN_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET = 250;
    public static final int MAX_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET = 32000;
    public static final int MIN_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET = 500;
    public static final int MAX_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET = 128000;

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
    @Entry
    public static boolean masonWallVerboseLogging = false;
    @Entry(min=1)
    public static int farmerWheatSeedReserveCap = 64;
    @Entry(min=0)
    public static int farmerWheatSeedBootstrapFloor = 0;
    @Entry(min=0)
    public static int lumberjackVillageMin = 1;
    @Entry(min=0)
    public static int lumberjackVillageMinLargeVillage = 2;
    @Entry(min=1)
    public static int lumberjackVillageMinLargeVillagePopulation = 12;
    @Entry(min=1)
    public static int lumberjackNaturalLeafSearchRadius = 3;
    @Entry(min=1)
    public static int lumberjackNaturalRequiredLeafCount = 4;
    @Entry(min=1)
    public static int lumberjackStructureProximityRadius = 2;
    @Entry(min=1)
    public static int lumberjackHousePoiProtectionRadius = 8;
    @Entry(min=250)
    public static int lumberjackTreeScanPerGuardBudgetCap = 8000;
    @Entry(min=500)
    public static int lumberjackTreeScanWorldSharedBudgetCap = 24000;
    @Entry
    public static int quartermasterScanRange = 128;
    @Entry
    public static int armorerFallbackScanRange = 128;
    @Entry
    public static int overflowRecipientScanRange = 96;
    @Entry
    public static int overflowFallbackQmSearchRadius = 128;

    public static void validateClampedRanges() {
        quartermasterScanRange = clamp(quartermasterScanRange, MIN_HEAVY_SCAN_RANGE, MAX_HEAVY_SCAN_RANGE);
        armorerFallbackScanRange = clamp(armorerFallbackScanRange, MIN_HEAVY_SCAN_RANGE, MAX_HEAVY_SCAN_RANGE);
        overflowRecipientScanRange = clamp(overflowRecipientScanRange, MIN_OVERFLOW_RECIPIENT_SCAN_RANGE, MAX_OVERFLOW_RECIPIENT_SCAN_RANGE);
        overflowFallbackQmSearchRadius = clamp(overflowFallbackQmSearchRadius, MIN_HEAVY_SCAN_RANGE, MAX_HEAVY_SCAN_RANGE);
        masonWallFootprintRadius = clamp(masonWallFootprintRadius, MIN_MASON_WALL_FOOTPRINT_RADIUS, MAX_MASON_WALL_FOOTPRINT_RADIUS);
        masonWallStagingMaxBelowSurfaceDelta = clamp(masonWallStagingMaxBelowSurfaceDelta, 0, 16);
        lumberjackTreeScanPerGuardBudgetCap = clamp(
                lumberjackTreeScanPerGuardBudgetCap,
                MIN_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET,
                MAX_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET);
        lumberjackTreeScanWorldSharedBudgetCap = clamp(
                lumberjackTreeScanWorldSharedBudgetCap,
                MIN_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET,
                MAX_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
