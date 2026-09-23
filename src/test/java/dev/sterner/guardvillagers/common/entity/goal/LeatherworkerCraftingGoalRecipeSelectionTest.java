package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeatherworkerCraftingGoalRecipeSelectionTest {

    @Test
    void selectRecipeIndex_prioritizesItemFrameWhenCartographerDemandExists() {
        int selectedIndex = LeatherworkerCraftingGoal.selectRecipeIndexByFrameShape(
                List.of(false, true),
                true,
                bound -> 0
        );

        assertEquals(1, selectedIndex);
    }
}
