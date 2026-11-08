package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmerCraftingGoalTransactionTest {
    @Test
    void successfulTransactionRequiresCapacityConsumptionAndCompleteInsertion() {
        AtomicInteger calls = new AtomicInteger();

        boolean crafted = FarmerCraftingGoal.executeCraftTransaction(
                () -> {
                    calls.compareAndSet(0, 1);
                    return true;
                },
                () -> {
                    calls.compareAndSet(1, 2);
                    return true;
                },
                () -> {
                    calls.compareAndSet(2, 3);
                    return true;
                });

        assertTrue(crafted);
        assertEquals(3, calls.get());
    }

    @Test
    void insufficientOutputCapacityDoesNotConsumeIngredients() {
        AtomicInteger ingredientConsumption = new AtomicInteger();
        AtomicInteger insertion = new AtomicInteger();

        boolean crafted = FarmerCraftingGoal.executeCraftTransaction(
                () -> false,
                () -> {
                    ingredientConsumption.incrementAndGet();
                    return true;
                },
                () -> {
                    insertion.incrementAndGet();
                    return true;
                });

        assertFalse(crafted);
        assertEquals(0, ingredientConsumption.get());
        assertEquals(0, insertion.get());
    }

    @Test
    void missingIngredientsDoesNotAttemptOutputInsertion() {
        AtomicInteger insertion = new AtomicInteger();

        boolean crafted = FarmerCraftingGoal.executeCraftTransaction(
                () -> true,
                () -> false,
                () -> {
                    insertion.incrementAndGet();
                    return true;
                });

        assertFalse(crafted);
        assertEquals(0, insertion.get());
    }

    @Test
    void incompleteOutputInsertionReportsFailure() {
        assertFalse(FarmerCraftingGoal.executeCraftTransaction(
                () -> true,
                () -> true,
                () -> false));
    }
}
