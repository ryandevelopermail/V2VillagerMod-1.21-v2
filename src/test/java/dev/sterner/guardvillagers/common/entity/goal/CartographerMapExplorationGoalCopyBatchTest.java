package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartographerMapExplorationGoalCopyBatchTest {

    @Test
    void shouldNotTriggerCopyBatchWhenOnlyFilledMapsExist() {
        assertFalse(CartographerMapExplorationGoal.shouldRunCopyBatch(4, 0, false));
    }

    @Test
    void shouldTriggerCopyBatchWhenFilledAndBlankMapsMeetThreshold() {
        assertTrue(CartographerMapExplorationGoal.shouldRunCopyBatch(4, 4, false));
    }

    @Test
    void copyBatchDoesNotConsumeOriginalFilledMaps() {
        FakeInventory inventory = new FakeInventory(9);
        inventory.setStack(0, new ItemStack(Items.FILLED_MAP, 4));
        inventory.setStack(1, new ItemStack(Items.MAP, 4));

        CartographerMapExplorationGoal.CopyBatchResult result =
                CartographerMapExplorationGoal.runCopyBatch(inventory, 4, 4, 4);

        assertTrue(result.success());
        assertEquals(8, count(inventory, Items.FILLED_MAP));
        assertEquals(4, inventory.getStack(0).getCount());
    }

    @Test
    void copyBatchConsumesFourBlanksAndProducesFourFilledCopies() {
        FakeInventory inventory = new FakeInventory(9);
        inventory.setStack(0, new ItemStack(Items.FILLED_MAP, 4));
        inventory.setStack(1, new ItemStack(Items.MAP, 4));

        CartographerMapExplorationGoal.CopyBatchResult result =
                CartographerMapExplorationGoal.runCopyBatch(inventory, 4, 4, 4);

        assertTrue(result.success());
        assertEquals(4, result.copiesCreated());
        assertEquals(4, result.blanksConsumed());
        assertEquals(8, count(inventory, Items.FILLED_MAP));
        assertEquals(0, count(inventory, Items.MAP));
    }

    private static int count(FakeInventory inventory, net.minecraft.item.Item item) {
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static class FakeInventory implements Inventory {
        private final ItemStack[] stacks;

        private FakeInventory(int size) {
            stacks = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                stacks[i] = ItemStack.EMPTY;
            }
        }

        @Override
        public int size() {
            return stacks.length;
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getStack(int slot) {
            return stacks[slot];
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            ItemStack stack = stacks[slot];
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack split = stack.split(amount);
            if (stack.isEmpty()) {
                stacks[slot] = ItemStack.EMPTY;
            }
            return split;
        }

        @Override
        public ItemStack removeStack(int slot) {
            ItemStack existing = stacks[slot];
            stacks[slot] = ItemStack.EMPTY;
            return existing;
        }

        @Override
        public void setStack(int slot, ItemStack stack) {
            stacks[slot] = stack;
        }

        @Override
        public void markDirty() {
        }

        @Override
        public boolean canPlayerUse(PlayerEntity player) {
            return true;
        }

        @Override
        public void clear() {
            for (int i = 0; i < stacks.length; i++) {
                stacks[i] = ItemStack.EMPTY;
            }
        }
    }
}
