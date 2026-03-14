package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MasonTableCraftingGoalTest {

    @Test
    void selectRecipeByPriority_prefersPickaxeWhenPickaxeAndNonToolAreCraftable() {
        MasonTableCraftingGoal.RecipeSelection selected = MasonTableCraftingGoal.selectRecipeByPriority(
                List.of(
                        MasonTableCraftingGoal.Recipe.WOODEN_PICKAXE,
                        MasonTableCraftingGoal.Recipe.STONECUTTER
                ),
                null
        );

        assertEquals(MasonTableCraftingGoal.Recipe.WOODEN_PICKAXE, selected.recipe());
        assertEquals("pickaxe", selected.priorityReason());
        assertEquals("none", selected.fallbackReason());
    }

    @Test
    void selectRecipeByPriority_ignoresLastCraftedAcrossPriorityTiers() {
        MasonTableCraftingGoal.RecipeSelection selected = MasonTableCraftingGoal.selectRecipeByPriority(
                List.of(
                        MasonTableCraftingGoal.Recipe.WOODEN_PICKAXE,
                        MasonTableCraftingGoal.Recipe.STONECUTTER
                ),
                Items.WOODEN_PICKAXE
        );

        assertEquals(MasonTableCraftingGoal.Recipe.WOODEN_PICKAXE, selected.recipe());
    }
}
