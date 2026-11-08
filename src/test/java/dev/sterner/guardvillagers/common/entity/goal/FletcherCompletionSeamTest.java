package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.FletcherWorkMetrics;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalWorkStatsState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FletcherCompletionSeamTest {
    private static final UUID WORKER = UUID.fromString("74000000-0000-0000-0000-000000000001");

    @Test
    void configuredCraftingPredicateAndRecipeAvailabilityStayExact() {
        for (int output = 0; output < 5; output++) {
            boolean[] flags = new boolean[5];
            flags[output] = true;
            assertTrue(FletcherCraftingGoal.isFletcherOutputShape(
                    flags[0], flags[1], flags[2], flags[3], flags[4]));
        }
        assertFalse(FletcherCraftingGoal.isFletcherOutputShape(false, false, false, false, false));
        assertTrue(FletcherCraftingGoal.isCraftableRecipe(true, true));
        assertFalse(FletcherCraftingGoal.isCraftableRecipe(true, false));
        assertFalse(FletcherCraftingGoal.isCraftableRecipe(false, true));
    }

    @Test
    void standardCraftRecordsActualOutputCountOnlyAfterCompleteInsertion() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        AtomicInteger dailySuccesses = new AtomicInteger();
        assertTrue(FletcherCraftingGoal.executeConfirmedCraft(
                () -> true,
                () -> true,
                () -> true,
                () -> true,
                () -> {
                    state.increment(WORKER, FletcherWorkMetrics.FLETCHER_ROLE,
                            FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED, 4);
                    dailySuccesses.incrementAndGet();
                }));
        assertEquals(4, state.read(WORKER, FletcherWorkMetrics.FLETCHER_ROLE,
                FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED));
        assertEquals(1, dailySuccesses.get());
    }

    @Test
    void standardCraftFailuresDoNotConsumeOrAdvanceDailySuccess() {
        AtomicInteger consumes = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        assertFalse(FletcherCraftingGoal.executeConfirmedCraft(
                () -> false,
                () -> true,
                () -> { consumes.incrementAndGet(); return true; },
                () -> true,
                completions::incrementAndGet));
        assertFalse(FletcherCraftingGoal.executeConfirmedCraft(
                () -> true,
                () -> false,
                () -> { consumes.incrementAndGet(); return true; },
                () -> true,
                completions::incrementAndGet));
        assertFalse(FletcherCraftingGoal.executeConfirmedCraft(
                () -> true,
                () -> true,
                () -> false,
                () -> true,
                completions::incrementAndGet));
        assertFalse(FletcherCraftingGoal.executeConfirmedCraft(
                () -> true,
                () -> true,
                () -> true,
                () -> false,
                completions::incrementAndGet));
        assertEquals(0, consumes.get());
        assertEquals(0, completions.get());
    }

    @Test
    void fletchingTableBatchRecordsFourOnlyAfterConfirmedSuccess() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        AtomicInteger dailySuccesses = new AtomicInteger();
        assertTrue(FletcherFletchingTableGoal.executeConfirmedBatch(
                () -> true,
                () -> true,
                () -> true,
                () -> true,
                () -> {
                    state.increment(WORKER, FletcherWorkMetrics.FLETCHER_ROLE,
                            FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED,
                            FletcherFletchingTableGoal.ARROWS_PER_CRAFT);
                    dailySuccesses.incrementAndGet();
                }));
        assertEquals(4, state.read(WORKER, FletcherWorkMetrics.FLETCHER_ROLE,
                FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED));
        assertEquals(1, dailySuccesses.get());
    }

    @Test
    void invalidTableCapacityIngredientsAndInsertionDoNotCompleteBatch() {
        AtomicInteger consumes = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        assertFalse(FletcherFletchingTableGoal.executeConfirmedBatch(
                () -> false, () -> true,
                () -> { consumes.incrementAndGet(); return true; },
                () -> true, completions::incrementAndGet));
        assertFalse(FletcherFletchingTableGoal.executeConfirmedBatch(
                () -> true, () -> false,
                () -> { consumes.incrementAndGet(); return true; },
                () -> true, completions::incrementAndGet));
        assertFalse(FletcherFletchingTableGoal.executeConfirmedBatch(
                () -> true, () -> true, () -> false,
                () -> true, completions::incrementAndGet));
        assertFalse(FletcherFletchingTableGoal.executeConfirmedBatch(
                () -> true, () -> true, () -> true,
                () -> false, completions::incrementAndGet));
        assertEquals(0, consumes.get());
        assertEquals(0, completions.get());
    }

    @Test
    void arrowBatchReadinessRequiresIngredientsAndPostConsumptionCapacity() {
        assertTrue(FletcherFletchingTableGoal.isArrowBatchReady(List.of(
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FLINT, 1, 64, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.STICK, 1, 64, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FEATHER, 1, 64, 0))));
        assertTrue(FletcherFletchingTableGoal.isArrowBatchReady(List.of(
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FLINT, 2, 64, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.STICK, 2, 64, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FEATHER, 2, 64, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.ARROW, 60, 0, 4))));
        assertFalse(FletcherFletchingTableGoal.isArrowBatchReady(List.of(
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FLINT, 2, 0, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.STICK, 2, 0, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FEATHER, 2, 0, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.OTHER, 64, 0, 0))));
        assertFalse(FletcherFletchingTableGoal.isArrowBatchReady(List.of(
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FLINT, 1, 64, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.STICK, 1, 64, 0))));
        assertFalse(FletcherFletchingTableGoal.isArrowBatchReady(List.of(
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FLINT, 1, 64, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FEATHER, 1, 64, 0))));
        assertFalse(FletcherFletchingTableGoal.isArrowBatchReady(List.of(
                slot(FletcherFletchingTableGoal.ArrowBatchItem.STICK, 1, 64, 0),
                slot(FletcherFletchingTableGoal.ArrowBatchItem.FEATHER, 1, 64, 0))));
    }

    @Test
    void deliveryClassificationCountsOnlyAllowedRouteAndItemCombinations() {
        assertTrue(FletcherDistributionGoal.isRangedWeaponShape(true, false));
        assertTrue(FletcherDistributionGoal.isRangedWeaponShape(false, true));
        assertFalse(FletcherDistributionGoal.isRangedWeaponShape(false, false));
        assertTrue(FletcherDistributionGoal.isRangedWeaponTransferEligible(true, true, true));
        assertFalse(FletcherDistributionGoal.isRangedWeaponTransferEligible(false, true, true));
        assertFalse(FletcherDistributionGoal.isRangedWeaponTransferEligible(true, false, true));
        assertFalse(FletcherDistributionGoal.isRangedWeaponTransferEligible(true, true, false));
        assertTrue(FletcherDistributionGoal.isArrowTransferEligible(true, true, true));
        assertFalse(FletcherDistributionGoal.isArrowTransferEligible(false, true, true));
        assertFalse(FletcherDistributionGoal.isArrowTransferEligible(true, false, true));
        assertFalse(FletcherDistributionGoal.isArrowTransferEligible(true, true, false));

        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.RANGED_WEAPON,
                classify(AbstractInventoryDistributionGoal.TransferRoute.DIRECT,
                        FletcherDistributionGoal.DirectCompletionKind.RANGED_WEAPON,
                        true, false, false, true));
        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.ARROW,
                classify(AbstractInventoryDistributionGoal.TransferRoute.DIRECT,
                        FletcherDistributionGoal.DirectCompletionKind.ARROW,
                        false, true, false, true));
        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.STICK,
                classify(AbstractInventoryDistributionGoal.TransferRoute.DIRECT,
                        FletcherDistributionGoal.DirectCompletionKind.STICK,
                        false, false, true, true));
        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.STICK,
                classify(AbstractInventoryDistributionGoal.TransferRoute.UNIVERSAL,
                        FletcherDistributionGoal.DirectCompletionKind.NONE,
                        false, false, true, true));
        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.NONE,
                classify(AbstractInventoryDistributionGoal.TransferRoute.UNIVERSAL,
                        FletcherDistributionGoal.DirectCompletionKind.NONE,
                        true, false, false, true));
        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.NONE,
                classify(AbstractInventoryDistributionGoal.TransferRoute.OVERFLOW,
                        FletcherDistributionGoal.DirectCompletionKind.RANGED_WEAPON,
                        true, false, false, true));
        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.NONE,
                classify(AbstractInventoryDistributionGoal.TransferRoute.OVERFLOW,
                        FletcherDistributionGoal.DirectCompletionKind.ARROW,
                        false, true, false, true));
        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.NONE,
                classify(AbstractInventoryDistributionGoal.TransferRoute.OVERFLOW,
                        FletcherDistributionGoal.DirectCompletionKind.STICK,
                        false, false, true, true));
        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.NONE,
                classify(AbstractInventoryDistributionGoal.TransferRoute.DIRECT,
                        FletcherDistributionGoal.DirectCompletionKind.NONE,
                        false, true, false, true));
        assertEquals(FletcherDistributionGoal.FletcherDeliveryMetric.NONE,
                classify(AbstractInventoryDistributionGoal.TransferRoute.DIRECT,
                        FletcherDistributionGoal.DirectCompletionKind.RANGED_WEAPON,
                        true, false, false, false));
    }

    @Test
    void retryInvalidAndCanceledTransfersInvokeOneFinalMetricCompletion() {
        AtomicInteger completions = new AtomicInteger();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.INCOMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true, () -> false, () -> 3, ignored -> completions.incrementAndGet()));
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.TARGET_INVALID,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        false, () -> true, () -> 3, ignored -> completions.incrementAndGet()));
        assertFalse(AbstractInventoryDistributionGoal.notifyAfterComplete(
                false, () -> 3, ignored -> completions.incrementAndGet()));
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.COMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true, () -> true, () -> 3, ignored -> completions.incrementAndGet()));
        assertEquals(1, completions.get());
    }

    private static FletcherFletchingTableGoal.ArrowBatchSlotView slot(
            FletcherFletchingTableGoal.ArrowBatchItem item,
            int count,
            int emptyCapacity,
            int mergeCapacity
    ) {
        return new FletcherFletchingTableGoal.ArrowBatchSlotView(
                item, count, emptyCapacity, mergeCapacity);
    }

    private static FletcherDistributionGoal.FletcherDeliveryMetric classify(
            AbstractInventoryDistributionGoal.TransferRoute route,
            FletcherDistributionGoal.DirectCompletionKind completion,
            boolean weapon,
            boolean arrow,
            boolean stick,
            boolean targetValid
    ) {
        return FletcherDistributionGoal.classifyCompletedDelivery(
                route, completion, weapon, arrow, stick, targetValid);
    }
}
