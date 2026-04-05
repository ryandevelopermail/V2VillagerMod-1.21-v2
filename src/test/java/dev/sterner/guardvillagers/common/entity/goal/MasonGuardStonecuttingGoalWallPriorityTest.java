package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonGuardStonecuttingGoalWallPriorityTest {

    @Test
    void preCompletion_cobblestoneLocksToWallOutputOnly() {
        Ingredient cobblestoneIngredient = Ingredient.ofItems(Items.COBBLESTONE);

        assertTrue(MasonGuardStonecuttingGoal.shouldSuppressForCobblestoneWallLock(
                cobblestoneIngredient,
                Items.COBBLESTONE_SLAB,
                true
        ));
        assertTrue(MasonGuardStonecuttingGoal.shouldSuppressForCobblestoneWallLock(
                cobblestoneIngredient,
                Items.COBBLESTONE_STAIRS,
                true
        ));
        assertFalse(MasonGuardStonecuttingGoal.shouldSuppressForCobblestoneWallLock(
                cobblestoneIngredient,
                Items.COBBLESTONE_WALL,
                true
        ));
    }

    @Test
    void preCompletion_nonCobblestoneOutputsRemainUnchanged() {
        Ingredient stoneIngredient = Ingredient.ofItems(Items.STONE);

        assertFalse(MasonGuardStonecuttingGoal.shouldSuppressForCobblestoneWallLock(
                stoneIngredient,
                Items.STONE_SLAB,
                true
        ));
        assertFalse(MasonGuardStonecuttingGoal.shouldSuppressForCobblestoneWallLock(
                stoneIngredient,
                Items.STONE_STAIRS,
                true
        ));
    }

    @Test
    void postCompletion_cobblestoneOutputsUseRandomizedSelectionPath() {
        int selectedIndex = MasonGuardStonecuttingGoal.pickRecipeIndex(
                List.of(Items.COBBLESTONE_WALL, Items.COBBLESTONE_SLAB, Items.COBBLESTONE_STAIRS),
                Items.COBBLESTONE_WALL,
                0,
                false
        );

        assertNotEquals(0, selectedIndex);
        assertEquals(1, selectedIndex);
    }
}
