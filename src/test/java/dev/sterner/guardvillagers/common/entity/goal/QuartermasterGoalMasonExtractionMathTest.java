package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuartermasterGoalMasonExtractionMathTest {

    @Test
    void masonCobblestoneExtraction_exact64WhenReserveAllows() {
        assertEquals(64, QuartermasterGoal.computeMasonCobblestoneExtractionCount(72, 8));
    }

    @Test
    void masonCobblestoneExtraction_lowStockUsesStepDownAmounts() {
        assertEquals(32, QuartermasterGoal.computeMasonCobblestoneExtractionCount(55, 8));
        assertEquals(16, QuartermasterGoal.computeMasonCobblestoneExtractionCount(26, 8));
        assertEquals(0, QuartermasterGoal.computeMasonCobblestoneExtractionCount(23, 8));
    }

    @Test
    void masonCobblestoneExtraction_neverDrainsChestToZero() {
        for (int available = 1; available <= 96; available++) {
            int extracted = QuartermasterGoal.computeMasonCobblestoneExtractionCount(available, 0);
            assertTrue(extracted < available, "extracted=" + extracted + ", available=" + available);
        }
    }
}
