package dev.sterner.guardvillagers;

import eu.midnightdust.lib.config.MidnightConfig;

import java.util.ArrayList;
import java.util.List;

public class GuardVillagersConfig extends MidnightConfig {

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
}
