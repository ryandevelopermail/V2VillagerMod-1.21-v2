package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.random.Random;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FishermanCraftingGoalRecipeSelectionTest {

    @Test
    void selectRecipeIndex_prefersFishingRodWhenRodNeedExistsAndAlternativesAreCraftable() {
        int selectedIndex = FishermanCraftingGoal.selectRecipeIndex(
                List.of(new ItemStack(Items.BUCKET), new ItemStack(Items.FISHING_ROD)),
                true,
                Random.create(123L)
        );

        assertEquals(1, selectedIndex);
    }
}
