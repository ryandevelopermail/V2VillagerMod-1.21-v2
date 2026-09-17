package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperTreeSitePolicyTest {
    @Test
    void allowsAirAndDryReplaceableVegetation() {
        assertTrue(DeveloperTreeSitePolicy.allowsClearanceBlock(true, false, true));
        assertTrue(DeveloperTreeSitePolicy.allowsClearanceBlock(false, true, true));
    }

    @Test
    void rejectsSolidAndFluidFilledObstructions() {
        assertFalse(DeveloperTreeSitePolicy.allowsClearanceBlock(false, false, true));
        assertFalse(DeveloperTreeSitePolicy.allowsClearanceBlock(false, true, false));
    }

    @Test
    void distinguishesInvalidSitesFromVanillaGrowthFailures() {
        assertEquals(
                DeveloperTreeSitePolicy.FailureKind.NO_USABLE_CANDIDATE,
                DeveloperTreeSitePolicy.classifyFailure(0, 0));
        assertEquals(
                DeveloperTreeSitePolicy.FailureKind.PREFLIGHT_REJECTED,
                DeveloperTreeSitePolicy.classifyFailure(3, 0));
        assertEquals(
                DeveloperTreeSitePolicy.FailureKind.VANILLA_GROWTH_FAILED,
                DeveloperTreeSitePolicy.classifyFailure(3, 1));
    }
}
