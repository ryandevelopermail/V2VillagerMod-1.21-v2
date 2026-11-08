package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.ButcherWorkMetrics;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalWorkStatsState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButcherCompletionSeamTest {
    private static final UUID WORKER = UUID.fromString("87000000-0000-0000-0000-000000000001");

    @Test
    void smokerInputAndFuelShapesMatchGoalRules() {
        assertTrue(ButcherSmokerGoal.isSmokableShape(true));
        assertFalse(ButcherSmokerGoal.isSmokableShape(false));
        assertTrue(ButcherSmokerGoal.isFuelShape(true));
        assertFalse(ButcherSmokerGoal.isFuelShape(false));
    }

    @Test
    void smokerStateUsesRequiredPriorityOrder() {
        assertEquals(ButcherSmokerGoal.SmokerState.MISSING, state(false, true, true, true, true));
        assertEquals(ButcherSmokerGoal.SmokerState.OUTPUT_READY, state(true, true, true, true, false));
        assertEquals(ButcherSmokerGoal.SmokerState.SMOKING, state(true, false, true, true, false));
        assertEquals(ButcherSmokerGoal.SmokerState.NEEDS_FUEL, state(true, false, false, true, false));
        assertEquals(ButcherSmokerGoal.SmokerState.LOADED, state(true, false, false, true, true));
        assertEquals(ButcherSmokerGoal.SmokerState.IDLE, state(true, false, false, false, true));
    }

    @Test
    void configuredSmokerRecipeRequiresOneFurnaceAndFourBurnableLogs() {
        assertTrue(ButcherCraftingGoal.isConfiguredSmokerRecipeCraftable(1, 4));
        assertTrue(ButcherCraftingGoal.isConfiguredSmokerRecipeCraftable(2, 8));
        assertFalse(ButcherCraftingGoal.isConfiguredSmokerRecipeCraftable(0, 4));
        assertFalse(ButcherCraftingGoal.isConfiguredSmokerRecipeCraftable(1, 3));
    }

    @Test
    void confirmedSmokerCraftRecordsAfterCapacityConsumptionAndInsertion() {
        ProfessionalWorkStatsState stats = new ProfessionalWorkStatsState();
        List<String> order = new ArrayList<>();
        assertTrue(ButcherCraftingGoal.executeConfirmedSmokerCraft(
                () -> {
                    order.add("capacity");
                    return true;
                },
                () -> {
                    order.add("consume");
                    return true;
                },
                () -> {
                    order.add("insert");
                    return true;
                },
                () -> {
                    order.add("metric");
                    stats.increment(WORKER, ButcherWorkMetrics.BUTCHER_ROLE,
                            ButcherWorkMetrics.SMOKERS_CRAFTED, 1);
                }));
        assertEquals(List.of("capacity", "consume", "insert", "metric"), order);
        assertEquals(1, stats.read(WORKER, ButcherWorkMetrics.BUTCHER_ROLE,
                ButcherWorkMetrics.SMOKERS_CRAFTED));
    }

    @Test
    void failedSmokerCraftDoesNotRecordAndCapacityFailureDoesNotConsume() {
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger recorded = new AtomicInteger();
        assertFalse(ButcherCraftingGoal.executeConfirmedSmokerCraft(
                () -> false,
                () -> {
                    consumed.incrementAndGet();
                    return true;
                },
                () -> {
                    inserted.incrementAndGet();
                    return true;
                },
                recorded::incrementAndGet));
        assertEquals(0, consumed.get());
        assertEquals(0, inserted.get());
        assertEquals(0, recorded.get());

        assertFalse(ButcherCraftingGoal.executeConfirmedSmokerCraft(
                () -> true, () -> false, () -> true, recorded::incrementAndGet));
        assertFalse(ButcherCraftingGoal.executeConfirmedSmokerCraft(
                () -> true, () -> true, () -> false, recorded::incrementAndGet));
        assertEquals(0, recorded.get());
    }

    @Test
    void cookedOutputRecordsFullPartialAndBlockedMovedAmounts() {
        AtomicInteger recorded = new AtomicInteger();
        assertEquals(8, ButcherSmokerGoal.recordMovedOutput(8, 0, recorded::addAndGet));
        assertEquals(3, ButcherSmokerGoal.recordMovedOutput(8, 5, recorded::addAndGet));
        assertEquals(0, ButcherSmokerGoal.recordMovedOutput(8, 8, recorded::addAndGet));
        assertEquals(11, recorded.get());
    }

    @Test
    void cookedMealAndLeatherShapesRequireNonemptyConfiguredItems() {
        assertTrue(ButcherMeatDistributionGoal.isCookedMeatShape(true, true));
        assertFalse(ButcherMeatDistributionGoal.isCookedMeatShape(false, true));
        assertFalse(ButcherMeatDistributionGoal.isCookedMeatShape(true, false));
        assertTrue(ButcherToLeatherworkerDistributionGoal.isLeatherOrHideShape(true, true));
        assertFalse(ButcherToLeatherworkerDistributionGoal.isLeatherOrHideShape(false, true));
        assertFalse(ButcherToLeatherworkerDistributionGoal.isLeatherOrHideShape(true, false));
    }

    @Test
    void completedMealDeliveryCountsBothCompletionPathsButNotFailuresOrReturns() {
        AtomicInteger recorded = new AtomicInteger();
        assertTrue(ButcherMeatDistributionGoal.recordCompletedMealDelivery(
                true, true, 2, amount -> recorded.addAndGet((int) amount)));
        assertTrue(ButcherMeatDistributionGoal.recordCompletedMealDelivery(
                true, true, 3, amount -> recorded.addAndGet((int) amount)));
        assertFalse(ButcherMeatDistributionGoal.recordCompletedMealDelivery(
                false, true, 4, amount -> recorded.addAndGet((int) amount)));
        assertFalse(ButcherMeatDistributionGoal.recordCompletedMealDelivery(
                true, false, 5, amount -> recorded.addAndGet((int) amount)));
        assertFalse(ButcherMeatDistributionGoal.recordCompletedMealDelivery(
                true, true, 0, amount -> recorded.addAndGet((int) amount)));
        assertEquals(5, recorded.get());
    }

    @Test
    void leatherMetricRequiresConfirmedDirectLeatherworkerDelivery() {
        assertTrue(ButcherToLeatherworkerDistributionGoal.isConfirmedDirectLeatherworkerDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, true, true));
        assertFalse(ButcherToLeatherworkerDistributionGoal.isConfirmedDirectLeatherworkerDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.UNIVERSAL, true, true, true));
        assertFalse(ButcherToLeatherworkerDistributionGoal.isConfirmedDirectLeatherworkerDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.OVERFLOW, true, true, true));
        assertFalse(ButcherToLeatherworkerDistributionGoal.isConfirmedDirectLeatherworkerDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, false, true, true));
        assertFalse(ButcherToLeatherworkerDistributionGoal.isConfirmedDirectLeatherworkerDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, false, true));
        assertFalse(ButcherToLeatherworkerDistributionGoal.isConfirmedDirectLeatherworkerDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, true, false));
    }

    private static ButcherSmokerGoal.SmokerState state(
            boolean present,
            boolean output,
            boolean lit,
            boolean input,
            boolean fuel
    ) {
        return ButcherSmokerGoal.classifySmokerState(present, output, lit, input, fuel);
    }
}
