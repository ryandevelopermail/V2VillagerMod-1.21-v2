package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartographerMapExplorationGoalTimeoutTest {

    @Test
    void hasTimedOut_returnsFalseBeforeThreshold() {
        assertFalse(CartographerMapExplorationGoal.hasTimedOut(199L, 0L, 200));
    }

    @Test
    void hasTimedOut_returnsTrueAtThresholdAndBeyond() {
        assertTrue(CartographerMapExplorationGoal.hasTimedOut(200L, 0L, 200));
        assertTrue(CartographerMapExplorationGoal.hasTimedOut(260L, 10L, 200));
    }

    @Test
    void shouldAbortMappingBatch_requiresNonZeroStartTickAndThreshold() {
        assertFalse(CartographerMapExplorationGoal.shouldAbortMappingBatch(10L, 0L));
        assertFalse(CartographerMapExplorationGoal.shouldAbortMappingBatch(20L * 359L, 1L));
        assertTrue(CartographerMapExplorationGoal.shouldAbortMappingBatch(20L * 361L, 1L));
    }

    @Test
    void collectCompletedWorkflowMapsForDeposit_returnsOnlyCompletedIndices() {
        List<ItemStack> workflowMaps = List.of(
                new ItemStack(Items.FILLED_MAP),
                new ItemStack(Items.FILLED_MAP),
                new ItemStack(Items.FILLED_MAP),
                new ItemStack(Items.FILLED_MAP)
        );

        List<ItemStack> selected = CartographerMapExplorationGoal.collectCompletedWorkflowMapsForDeposit(
                workflowMaps,
                Set.of(1, 3)
        );

        assertEquals(2, selected.size());
    }
}
