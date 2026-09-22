package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.ArmorerWorkMetrics;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalWorkStatsState;
import dev.sterner.guardvillagers.common.util.ArmorerStandManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorerCompletionSeamTest {
    private static final UUID WORKER = UUID.fromString("83000000-0000-0000-0000-000000000001");

    @Test
    void processableAndFuelShapesMatchGoalRules() {
        assertTrue(ArmorerBlastFurnaceGoal.isProcessableShape(true, false));
        assertTrue(ArmorerBlastFurnaceGoal.isProcessableShape(false, true));
        assertFalse(ArmorerBlastFurnaceGoal.isProcessableShape(false, false));
        assertTrue(ArmorerBlastFurnaceGoal.isFuelShape(true));
        assertFalse(ArmorerBlastFurnaceGoal.isFuelShape(false));
    }

    @Test
    void furnaceStateUsesRequiredPriorityOrder() {
        assertEquals(ArmorerBlastFurnaceGoal.FurnaceState.MISSING,
                state(false, true, true, true, true));
        assertEquals(ArmorerBlastFurnaceGoal.FurnaceState.OUTPUT_READY,
                state(true, true, true, true, false));
        assertEquals(ArmorerBlastFurnaceGoal.FurnaceState.SMELTING,
                state(true, false, true, true, false));
        assertEquals(ArmorerBlastFurnaceGoal.FurnaceState.NEEDS_FUEL,
                state(true, false, false, true, false));
        assertEquals(ArmorerBlastFurnaceGoal.FurnaceState.LOADED,
                state(true, false, false, true, true));
        assertEquals(ArmorerBlastFurnaceGoal.FurnaceState.IDLE,
                state(true, false, false, false, false));
    }

    @Test
    void armorAwaitingPlacementRequiresNonemptyArmorStackShape() {
        assertTrue(ArmorerDistributionGoal.isDistributableArmorShape(true, true));
        assertFalse(ArmorerDistributionGoal.isDistributableArmorShape(false, true));
        assertFalse(ArmorerDistributionGoal.isDistributableArmorShape(true, false));
    }

    @Test
    void craftableArmorRequiresArmorOutputAndAvailableIngredients() {
        assertTrue(ArmorerCraftingGoal.isCraftableArmorRecipe(true, true));
        assertFalse(ArmorerCraftingGoal.isCraftableArmorRecipe(false, true));
        assertFalse(ArmorerCraftingGoal.isCraftableArmorRecipe(true, false));
    }

    @Test
    void craftableArmorIngredientInspectionUsesCopiesAndDoesNotMutateStorage() {
        MutableStack iron = new MutableStack("iron", 2);
        MutableStack leather = new MutableStack("leather", 1);
        List<MutableStack> stored = List.of(iron, leather);
        assertTrue(ArmorerCraftingGoal.canSatisfyIngredientsReadOnly(
                stored,
                List.of(
                        stack -> stack.kind().equals("iron"),
                        stack -> stack.kind().equals("iron"),
                        stack -> stack.kind().equals("leather")),
                MutableStack::copy,
                stack -> stack.count() == 0,
                MutableStack::consumeOne));
        assertEquals(2, iron.count());
        assertEquals(1, leather.count());
    }

    @Test
    void confirmedCraftRecordsOutputCountAfterCommitAndDirty() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        List<String> order = new ArrayList<>();
        assertTrue(ArmorerCraftingGoal.executeConfirmedCraft(
                () -> {
                    order.add("consume");
                    return true;
                },
                () -> {
                    order.add("commit");
                    return true;
                },
                () -> order.add("dirty"),
                () -> {
                    order.add("metric");
                    state.increment(
                            WORKER,
                            ArmorerWorkMetrics.ARMORER_ROLE,
                            ArmorerWorkMetrics.ARMOR_CRAFTED,
                            3);
                }));
        assertEquals(List.of("consume", "commit", "dirty", "metric"), order);
        assertEquals(3, state.read(WORKER, ArmorerWorkMetrics.ARMORER_ROLE, ArmorerWorkMetrics.ARMOR_CRAFTED));
    }

    @Test
    void failedOrAbandonedCraftDoesNotRecordOrMarkDirty() {
        AtomicInteger dirty = new AtomicInteger();
        AtomicInteger records = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        assertFalse(ArmorerCraftingGoal.executeConfirmedCraft(
                () -> false,
                () -> {
                    commits.incrementAndGet();
                    return true;
                },
                dirty::incrementAndGet,
                records::incrementAndGet));
        assertFalse(ArmorerCraftingGoal.executeConfirmedCraft(
                () -> true,
                () -> false,
                dirty::incrementAndGet,
                records::incrementAndGet));
        assertEquals(0, commits.get());
        assertEquals(0, dirty.get());
        assertEquals(0, records.get());
    }

    @Test
    void smeltedOutputRecordsFullPartialAndBlockedMovedAmounts() {
        AtomicInteger recorded = new AtomicInteger();
        assertEquals(8, ArmorerBlastFurnaceGoal.recordMovedOutput(8, 0, recorded::addAndGet));
        assertEquals(3, ArmorerBlastFurnaceGoal.recordMovedOutput(8, 5, recorded::addAndGet));
        assertEquals(0, ArmorerBlastFurnaceGoal.recordMovedOutput(8, 8, recorded::addAndGet));
        assertEquals(11, recorded.get());
    }

    @Test
    void sharedPlacementSeamCountsCraftingAndDistributionSuccessOnceEach() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        Runnable recordPlacement = () -> state.increment(
                WORKER,
                ArmorerWorkMetrics.ARMORER_ROLE,
                ArmorerWorkMetrics.ARMOR_EQUIPPED,
                1);
        assertTrue(ArmorerStandManager.recordConfirmedPlacement(true, recordPlacement));
        assertTrue(ArmorerStandManager.recordConfirmedPlacement(true, recordPlacement));
        assertEquals(2, state.read(
                WORKER,
                ArmorerWorkMetrics.ARMORER_ROLE,
                ArmorerWorkMetrics.ARMOR_EQUIPPED));
    }

    @Test
    void failedPlacementRecordsNothingAndCraftedItemIsNotCountedTwiceAsCrafted() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        state.increment(WORKER, ArmorerWorkMetrics.ARMORER_ROLE, ArmorerWorkMetrics.ARMOR_CRAFTED, 1);
        assertFalse(ArmorerStandManager.recordConfirmedPlacement(false, () -> state.increment(
                WORKER,
                ArmorerWorkMetrics.ARMORER_ROLE,
                ArmorerWorkMetrics.ARMOR_EQUIPPED,
                1)));
        ArmorerStandManager.recordConfirmedPlacement(true, () -> state.increment(
                WORKER,
                ArmorerWorkMetrics.ARMORER_ROLE,
                ArmorerWorkMetrics.ARMOR_EQUIPPED,
                1));
        assertEquals(1, state.read(WORKER, ArmorerWorkMetrics.ARMORER_ROLE, ArmorerWorkMetrics.ARMOR_CRAFTED));
        assertEquals(1, state.read(WORKER, ArmorerWorkMetrics.ARMORER_ROLE, ArmorerWorkMetrics.ARMOR_EQUIPPED));
    }

    private static ArmorerBlastFurnaceGoal.FurnaceState state(
            boolean present,
            boolean output,
            boolean lit,
            boolean input,
            boolean fuel
    ) {
        return ArmorerBlastFurnaceGoal.classifyFurnaceState(present, output, lit, input, fuel);
    }

    private static final class MutableStack {
        private final String kind;
        private int count;

        private MutableStack(String kind, int count) {
            this.kind = kind;
            this.count = count;
        }

        private String kind() {
            return kind;
        }

        private int count() {
            return count;
        }

        private MutableStack copy() {
            return new MutableStack(kind, count);
        }

        private void consumeOne() {
            count--;
        }
    }
}
