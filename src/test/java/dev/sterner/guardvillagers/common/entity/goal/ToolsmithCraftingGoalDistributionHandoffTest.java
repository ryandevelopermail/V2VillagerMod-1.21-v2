package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsmithCraftingGoalDistributionHandoffTest {

    @Test
    void requestImmediateDistributionForCraftedOutput_returnsTrueForSuccessfulDistributableCraft() {
        boolean requested = ToolsmithCraftingGoal.requestImmediateDistributionForCraftedOutput(null, new ItemStack(Items.SHEARS));

        assertTrue(requested);
    }

    @Test
    void requestImmediateDistributionForCraftedOutput_returnsFalseWhenCraftOutputIsNotDistributable() {
        boolean requested = ToolsmithCraftingGoal.requestImmediateDistributionForCraftedOutput(null, new ItemStack(Items.WOODEN_SHOVEL));

        assertFalse(requested);
    }

    @Test
    void requestImmediateDistributionForCraftedOutput_returnsTrueForWoodenPickaxeCraftPath() {
        boolean requested = ToolsmithCraftingGoal.requestImmediateDistributionForCraftedOutput(null, new ItemStack(Items.WOODEN_PICKAXE));

        assertTrue(requested);
    }
}
