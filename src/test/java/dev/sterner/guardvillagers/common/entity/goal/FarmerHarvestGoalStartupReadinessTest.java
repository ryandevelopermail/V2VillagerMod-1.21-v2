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
}
