package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishermanCompletionSeamTest {
    @Test
    void craftingRecordsOnlyAfterConsumptionAndCompleteInsertion() {
        List<String> order = new ArrayList<>();
        assertTrue(FishermanCraftingGoal.executeConfirmedCraft(
                () -> { order.add("consume"); return true; },
                () -> { order.add("insert"); return true; },
                () -> order.add("metric")));
        assertEquals(List.of("consume", "insert", "metric"), order);
        AtomicInteger records = new AtomicInteger();
        assertFalse(FishermanCraftingGoal.executeConfirmedCraft(() -> false, () -> true, records::incrementAndGet));
        assertFalse(FishermanCraftingGoal.executeConfirmedCraft(() -> true, () -> false, records::incrementAndGet));
        assertEquals(0, records.get());
    }

    @Test
    void successfulCraftOutputHasAtMostOneMetricCategory() {
        assertEquals(FishermanCraftingGoal.CraftedOutputKind.FISHING_ROD,
                FishermanCraftingGoal.classifyCraftedOutputShape(true, true, true, true, true));
        assertEquals(FishermanCraftingGoal.CraftedOutputKind.BUCKET,
                FishermanCraftingGoal.classifyCraftedOutputShape(true, false, true, true, true));
        assertEquals(FishermanCraftingGoal.CraftedOutputKind.BOAT,
                FishermanCraftingGoal.classifyCraftedOutputShape(true, false, false, true, true));
        assertEquals(FishermanCraftingGoal.CraftedOutputKind.NONE,
                FishermanCraftingGoal.classifyCraftedOutputShape(false, true, true, true, true));
    }

    @Test
    void directFishDeliveryRecordsOnceAndExcludesOtherRoutesOrInvalidTargets() {
        AtomicInteger delivered = new AtomicInteger();
        assertTrue(FishermanDistributionGoal.recordConfirmedDirectFishDelivery(
                true, true, true, 1, amount -> delivered.addAndGet((int) amount)));
        assertFalse(FishermanDistributionGoal.recordConfirmedDirectFishDelivery(
                false, true, true, 1, amount -> delivered.addAndGet((int) amount)));
        assertFalse(FishermanDistributionGoal.recordConfirmedDirectFishDelivery(
                true, false, true, 1, amount -> delivered.addAndGet((int) amount)));
        assertFalse(FishermanDistributionGoal.recordConfirmedDirectFishDelivery(
                true, true, false, 1, amount -> delivered.addAndGet((int) amount)));
        assertFalse(FishermanDistributionGoal.recordConfirmedDirectFishDelivery(
                true, true, true, 0, amount -> delivered.addAndGet((int) amount)));
        assertEquals(1, delivered.get());
    }
}
