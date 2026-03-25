package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.random.Random;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeatherworkerCraftingGoalRecipeSelectionTest {

    @Test
    void selectRecipeIndex_prioritizesItemFrameWhenCartographerDemandExists() {
        int selectedIndex = LeatherworkerCraftingGoal.selectRecipeIndex(
                List.of(new ItemStack(Items.LEATHER_BOOTS), new ItemStack(Items.ITEM_FRAME)),
                true,
                Random.create(123L)
        );

        assertEquals(1, selectedIndex);
    }
}
