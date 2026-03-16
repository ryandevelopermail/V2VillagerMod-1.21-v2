package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MasonGuardStonecuttingGoalReserveTest {

    @Test
    void getEligibleIngredientCount_defersCobblestoneConversionWhenReserveIsRequired() {
        FakeInventory inventory = new FakeInventory(1);
        inventory.setStack(0, new ItemStack(Items.COBBLESTONE, 8));

        Ingredient cobblestoneIngredient = Ingredient.ofItems(Items.COBBLESTONE);

        int eligibleWithReserve = MasonGuardStonecuttingGoal.getEligibleIngredientCount(inventory, cobblestoneIngredient, true);
        int eligibleWithoutReserve = MasonGuardStonecuttingGoal.getEligibleIngredientCount(inventory, cobblestoneIngredient, false);

        assertEquals(0, eligibleWithReserve);
        assertEquals(8, eligibleWithoutReserve);
    }

    private static class FakeInventory implements Inventory {
        private final ItemStack[] stacks;

        private FakeInventory(int size) {
            this.stacks = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                this.stacks[i] = ItemStack.EMPTY;
            }
        }

        @Override
        public int size() {
            return this.stacks.length;
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : this.stacks) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getStack(int slot) {
            return this.stacks[slot];
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            ItemStack stack = this.stacks[slot];
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack split = stack.split(amount);
            if (stack.isEmpty()) {
                this.stacks[slot] = ItemStack.EMPTY;
            }
            return split;
        }

        @Override
        public ItemStack removeStack(int slot) {
            ItemStack stack = this.stacks[slot];
            this.stacks[slot] = ItemStack.EMPTY;
            return stack;
        }

        @Override
        public void setStack(int slot, ItemStack stack) {
            this.stacks[slot] = stack;
        }

        @Override
        public void markDirty() {
        }

        @Override
        public boolean canPlayerUse(net.minecraft.entity.player.PlayerEntity player) {
            return true;
        }

        @Override
        public void clear() {
            for (int i = 0; i < this.stacks.length; i++) {
                this.stacks[i] = ItemStack.EMPTY;
            }
        }
    }
}
