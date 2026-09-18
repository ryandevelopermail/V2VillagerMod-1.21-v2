package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackAxeBootstrapPolicyTest {
    @Test
    void pairedChestWithoutAxeStillRequiresBootstrap() {
        assertTrue(LumberjackAxeBootstrapPolicy.needsBootstrap(true, false));
        assertFalse(LumberjackAxeBootstrapPolicy.needsBootstrap(true, true));
    }

    @Test
    void validPairedChestNeverRequestsDuplicateChestCraft() {
        assertFalse(LumberjackAxeBootstrapPolicy.shouldCraftChest(true, 0));
        assertTrue(LumberjackAxeBootstrapPolicy.shouldCraftChest(false, 0));
        assertFalse(LumberjackAxeBootstrapPolicy.shouldCraftChest(false, 1));
    }

    @Test
    void charcoalReservesEnoughLogsForAxePlanksAndSticks() {
        assertEquals(2, LumberjackAxeBootstrapPolicy.requiredLogReserveForAxe(false, 0, 0));
        assertEquals(14, LumberjackAxeBootstrapPolicy.charcoalEligibleLogs(16, false, 0, 0));
        assertEquals(0, LumberjackAxeBootstrapPolicy.charcoalEligibleLogs(2, false, 0, 0));
    }

    @Test
    void existingIngredientsReduceReserveAndEquippedAxeReleasesIt() {
        assertEquals(1, LumberjackAxeBootstrapPolicy.requiredLogReserveForAxe(false, 3, 0));
        assertEquals(1, LumberjackAxeBootstrapPolicy.requiredLogReserveForAxe(false, 0, 2));
        assertEquals(0, LumberjackAxeBootstrapPolicy.requiredLogReserveForAxe(false, 5, 0));
        assertEquals(16, LumberjackAxeBootstrapPolicy.charcoalEligibleLogs(16, true, 0, 0));
    }
}
