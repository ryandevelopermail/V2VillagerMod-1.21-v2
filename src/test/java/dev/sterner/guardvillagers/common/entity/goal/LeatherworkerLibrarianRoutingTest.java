package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeatherworkerLibrarianRoutingTest {
    @Test
    void onlyLibrarianRecipeInputsTargetAnOrdinaryLibrarian() {
        assertTrue(LeatherworkerDistributionGoal.isLibrarianCraftingInput(new ItemStack(Items.LEATHER)));
        assertTrue(LeatherworkerDistributionGoal.isLibrarianCraftingInput(new ItemStack(Items.BOOK)));
        assertFalse(LeatherworkerDistributionGoal.isLibrarianCraftingInput(new ItemStack(Items.SADDLE)));
        assertFalse(LeatherworkerDistributionGoal.isLibrarianCraftingInput(new ItemStack(Items.ENCHANTED_BOOK)));
    }
}
