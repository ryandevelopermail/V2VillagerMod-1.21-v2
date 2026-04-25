package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmerHarvestGoalStartupReadinessTest {

    @Test
    void startupReadiness_requiresPlantables_whenBootstrapFloorIsZero() {
        boolean startupReady = FarmerHarvestGoal.hasStartupPlantingReadiness(false, false, 0, 0);
        boolean forceSeedGather = FarmerHarvestGoal.shouldForceBootstrapSeedGather(false);

        assertFalse(startupReady);
        assertTrue(forceSeedGather);
    }

    @Test
    void startupReadiness_allowsNonWheatPlantables_whenBootstrapFloorIsZero() {
        boolean startupReady = FarmerHarvestGoal.hasStartupPlantingReadiness(true, true, 0, 0);
        boolean forceSeedGather = FarmerHarvestGoal.shouldForceBootstrapSeedGather(true);

        assertTrue(startupReady);
        assertFalse(forceSeedGather);
    }

    @Test
    void startupReadiness_enforcesMinimumBootstrapFloorOfOne_forWheatSeedsOnly() {
        boolean startupReadyWithoutSeeds = FarmerHarvestGoal.hasStartupPlantingReadiness(true, false, 0, 0);
        boolean startupReadyWithOneSeed = FarmerHarvestGoal.hasStartupPlantingReadiness(true, false, 1, 0);

        assertFalse(startupReadyWithoutSeeds);
        assertTrue(startupReadyWithOneSeed);
    }

    @Test
    void computeSeedTargetFromEligibleArea_scalesWithAreaAndReserveMargin() {
        assertFalse(FarmerHarvestGoal.computeSeedTargetFromEligibleArea(0) > 0);
        assertTrue(FarmerHarvestGoal.computeSeedTargetFromEligibleArea(1) >= 3);
        assertTrue(FarmerHarvestGoal.computeSeedTargetFromEligibleArea(20) >= 24);
    }

    @Test
    void canStartDecision_highAdaptiveLoad_withMatureCrops_allowsHarvest() {
        boolean shouldThrottleExpansion = FarmerHarvestGoal.shouldApplyExpansionThrottle(5, false);

        assertFalse(shouldThrottleExpansion);
    }

    @Test
    void canStartDecision_highAdaptiveLoad_noMatureCrops_expansionCandidate_defers() {
        boolean shouldThrottleExpansion = FarmerHarvestGoal.shouldApplyExpansionThrottle(0, true);

        assertTrue(shouldThrottleExpansion);
    }

    @Test
    void canStartDecision_lowLoad_withMatureCrops_allowsHarvest() {
        boolean shouldThrottleExpansion = FarmerHarvestGoal.shouldApplyExpansionThrottle(2, true);

        assertFalse(shouldThrottleExpansion);
    }

    @Test
    void canStartDecision_migratedWorldShape_matureCropsWithinHarvestRadius_withSparseTerritory_allowsStart() {
        boolean blockedByBootstrap = FarmerHarvestGoal.isBlockedByTerritoryBootstrap(1, 0, 2);

        assertFalse(blockedByBootstrap);
    }

    @Test
    void canStartDecision_noMatureCrops_andNoViableTerritory_remainsBlocked() {
        boolean blockedByBootstrap = FarmerHarvestGoal.isBlockedByTerritoryBootstrap(0, 0, 2);

        assertTrue(blockedByBootstrap);
    }
}
