package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartographerCompletionSeamTest {
    @Test
    void coldProtectionRequiresNonemptyLeatherBootsWithoutApplyingProtection() {
        assertTrue(CartographerMapExplorationGoal.isColdProtectionItemShape(true, true));
        assertFalse(CartographerMapExplorationGoal.isColdProtectionItemShape(false, true));
        assertFalse(CartographerMapExplorationGoal.isColdProtectionItemShape(true, false));
    }

    @Test
    void emptyMapClassificationMatchesPlainInvalidAndPopulatedRules() {
        assertTrue(CartographerMapExplorationGoal.isEmptyMapShape(true, false, false));
        assertTrue(CartographerMapExplorationGoal.isEmptyMapShape(false, true, false));
        assertFalse(CartographerMapExplorationGoal.isEmptyMapShape(false, true, true));
        assertFalse(CartographerMapExplorationGoal.isEmptyMapShape(false, false, false));
    }

    @Test
    void wallPredicatesAcceptAllFilledMapsButOnlyOrdinaryFrames() {
        assertTrue(CartographerMapWallGoal.isWallMapShape(true, true));
        assertFalse(CartographerMapWallGoal.isWallMapShape(false, true));
        assertTrue(CartographerMapWallGoal.isWallItemFrameShape(true, true, false));
        assertFalse(CartographerMapWallGoal.isWallItemFrameShape(true, false, true));
        assertFalse(CartographerMapWallGoal.isWallItemFrameShape(true, true, true));
    }

    @Test
    void wallReadinessUsesExactlyEightMapsAndFourFrames() {
        assertTrue(CartographerMapWallGoal.hasRequiredWallMaterials(8, 4));
        assertTrue(CartographerMapWallGoal.hasRequiredWallMaterials(9, 5));
        assertFalse(CartographerMapWallGoal.hasRequiredWallMaterials(7, 4));
        assertFalse(CartographerMapWallGoal.hasRequiredWallMaterials(8, 3));
    }

    @Test
    void craftableRecipeInspectionMatchesAllFiveConfiguredRecipesAndIsPure() {
        CartographerCraftingGoal.MaterialCounts all = new CartographerCraftingGoal.MaterialCounts(
                3, 8, 1, 1, 4, 1, 8, 1);
        assertEquals(5, CartographerCraftingGoal.countConfiguredRecipes(all));
        assertEquals(5, CartographerCraftingGoal.countConfiguredRecipes(all));
        assertEquals(new CartographerCraftingGoal.MaterialCounts(3, 8, 1, 1, 4, 1, 8, 1), all);
        assertEquals(0, CartographerCraftingGoal.countConfiguredRecipes(
                new CartographerCraftingGoal.MaterialCounts(2, 7, 0, 0, 3, 0, 7, 0)));
    }

    @Test
    void materialsCraftedRecordsActualOutputCountAfterConsumptionAndFullInsertion() {
        List<String> order = new ArrayList<>();
        AtomicInteger amount = new AtomicInteger();
        assertTrue(CartographerCraftingGoal.executeConfirmedCraft(
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
                    amount.addAndGet(3);
                }));
        assertEquals(List.of("consume", "insert", "metric"), order);
        assertEquals(3, amount.get());
    }

    @Test
    void failedIngredientsOrPartialOutputDoesNotRecordCrafting() {
        AtomicInteger insertions = new AtomicInteger();
        AtomicInteger records = new AtomicInteger();
        assertFalse(CartographerCraftingGoal.executeConfirmedCraft(
                () -> false,
                () -> {
                    insertions.incrementAndGet();
                    return true;
                },
                records::incrementAndGet));
        assertEquals(0, insertions.get());
        assertFalse(CartographerCraftingGoal.executeConfirmedCraft(
                () -> true,
                () -> false,
                records::incrementAndGet));
        assertEquals(0, records.get());
    }

    @Test
    void normalAndForcedWorkflowCompletionRecordEachIndexAtMostOnce() {
        Set<Integer> completed = new HashSet<>();
        AtomicInteger records = new AtomicInteger();
        assertTrue(CartographerMapExplorationGoal.recordNewWorkflowCompletion(
                completed, 0, records::incrementAndGet));
        assertTrue(CartographerMapExplorationGoal.recordNewWorkflowCompletion(
                completed, 1, records::incrementAndGet));
        assertFalse(CartographerMapExplorationGoal.recordNewWorkflowCompletion(
                completed, 0, records::incrementAndGet));
        assertFalse(CartographerMapExplorationGoal.recordNewWorkflowCompletion(
                completed, -1, records::incrementAndGet));
        assertEquals(Set.of(0, 1), completed);
        assertEquals(2, records.get());

        // Chest deposit and external normalization never call the completion seam.
        assertEquals(2, records.get());
    }

    @Test
    void mapCopyRecordsOnlyAfterTheWholeTransactionAndCursorAdvance() {
        AtomicInteger records = new AtomicInteger();
        assertTrue(CartographerMapExplorationGoal.recordConfirmedMapCopy(
                true, true, true, true, records::incrementAndGet));
        assertFalse(CartographerMapExplorationGoal.recordConfirmedMapCopy(
                false, true, true, true, records::incrementAndGet));
        assertFalse(CartographerMapExplorationGoal.recordConfirmedMapCopy(
                true, false, true, true, records::incrementAndGet));
        assertFalse(CartographerMapExplorationGoal.recordConfirmedMapCopy(
                true, true, false, true, records::incrementAndGet));
        assertFalse(CartographerMapExplorationGoal.recordConfirmedMapCopy(
                true, true, true, false, records::incrementAndGet));
        assertEquals(1, records.get());
    }

    @Test
    void mapDisplayRecordsSuccessfulSpawnAndPreservesBothMaterialsOnFailure() {
        AtomicInteger spawns = new AtomicInteger();
        AtomicInteger preserved = new AtomicInteger();
        AtomicInteger records = new AtomicInteger();
        assertTrue(CartographerMapWallGoal.executeConfirmedMapDisplay(
                false,
                () -> {
                    spawns.incrementAndGet();
                    return true;
                },
                () -> preserved.addAndGet(2),
                records::incrementAndGet));
        assertFalse(CartographerMapWallGoal.executeConfirmedMapDisplay(
                false,
                () -> {
                    spawns.incrementAndGet();
                    return false;
                },
                () -> preserved.addAndGet(2),
                records::incrementAndGet));
        assertEquals(2, spawns.get());
        assertEquals(2, preserved.get());
        assertEquals(1, records.get());
    }

    @Test
    void existingFrameSkipDoesNotSpawnPreserveOrRecord() {
        AtomicInteger spawns = new AtomicInteger();
        AtomicInteger preserved = new AtomicInteger();
        AtomicInteger records = new AtomicInteger();
        assertFalse(CartographerMapWallGoal.executeConfirmedMapDisplay(
                true,
                () -> {
                    spawns.incrementAndGet();
                    return true;
                },
                preserved::incrementAndGet,
                records::incrementAndGet));
        assertEquals(0, spawns.get());
        assertEquals(0, preserved.get());
        assertEquals(0, records.get());
    }
}
