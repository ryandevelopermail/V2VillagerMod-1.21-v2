package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

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
}
