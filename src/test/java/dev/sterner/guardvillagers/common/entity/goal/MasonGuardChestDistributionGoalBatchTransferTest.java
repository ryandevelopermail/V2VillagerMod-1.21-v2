package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MasonGuardChestDistributionGoalBatchTransferTest {

    @Test
    void cobblestoneBatchDistribution_givesEachRecipientAtLeastOneWhenSourceHasEnough() {
        List<BlockPos> targets = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0));
        Map<BlockPos, FakeInventory> inventories = new HashMap<>();
        for (BlockPos target : targets) {
            inventories.put(target, new FakeInventory(1));
        }

        ItemStack pending = new ItemStack(Items.COBBLESTONE, targets.size());
        BlockPos currentTarget = targets.getFirst();
        int targetIndex = 0;
        int failedAttempts = 0;

        while (!pending.isEmpty()) {
            MasonGuardChestDistributionGoal.BatchTransferStepResult step = MasonGuardChestDistributionGoal.performBatchTransferPass(
                    pending,
                    currentTarget,
                    targets,
                    targetIndex,
                    failedAttempts,
                    pos -> Optional.ofNullable(inventories.get(pos)),
                    this::insertSingleItem
            );

            if (step.deliveredItem()) {
                pending.decrement(1);
            }
            if (step.outcome() == MasonGuardChestDistributionGoal.BatchTransferOutcome.CONTINUE) {
                targetIndex = step.nextTargetIndex();
                failedAttempts = step.nextFailedAttempts();
                currentTarget = step.nextTarget();
            } else {
                break;
            }
        }

        for (FakeInventory inventory : inventories.values()) {
            assertEquals(1, inventory.getStack(0).getCount());
        }
    }

    @Test
    void batchDistribution_rotatesRecipientsAcrossConsecutiveRuns() {
        List<BlockPos> targets = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0));
        List<BlockPos> visitedTargets = new ArrayList<>();

        int cursor = 0;
        for (int run = 0; run < 3; run++) {
            int index = Math.floorMod(cursor, targets.size());
            BlockPos start = targets.get(index);
            visitedTargets.add(start);
            cursor = (cursor + 1) % targets.size();
        }

        assertEquals(targets, visitedTargets);
    }

    @Test
    void batchDistribution_fallsBackWhenSomeTargetsUnavailableOrFull() {
        List<BlockPos> targets = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0));
        FakeInventory fullInventory = new FakeInventory(1);
        fullInventory.setStack(0, new ItemStack(Items.COBBLESTONE, 64));
        FakeInventory availableInventory = new FakeInventory(1);

        Map<BlockPos, FakeInventory> inventories = new HashMap<>();
        inventories.put(targets.get(0), fullInventory);
        inventories.put(targets.get(2), availableInventory);

        ItemStack pending = new ItemStack(Items.COBBLESTONE, 2);
        BlockPos currentTarget = targets.getFirst();
        int targetIndex = 0;
        int failedAttempts = 0;

        MasonGuardChestDistributionGoal.BatchTransferStepResult first = MasonGuardChestDistributionGoal.performBatchTransferPass(
                pending,
                currentTarget,
                targets,
                targetIndex,
                failedAttempts,
                pos -> Optional.ofNullable(inventories.get(pos)),
                this::insertSingleItem
        );

        assertEquals(MasonGuardChestDistributionGoal.BatchTransferOutcome.CONTINUE, first.outcome());
        assertEquals(1, first.nextFailedAttempts());

        currentTarget = first.nextTarget();
        targetIndex = first.nextTargetIndex();
        failedAttempts = first.nextFailedAttempts();

        MasonGuardChestDistributionGoal.BatchTransferStepResult second = MasonGuardChestDistributionGoal.performBatchTransferPass(
                pending,
                currentTarget,
                targets,
                targetIndex,
                failedAttempts,
                pos -> Optional.ofNullable(inventories.get(pos)),
                this::insertSingleItem
        );

        assertEquals(MasonGuardChestDistributionGoal.BatchTransferOutcome.CONTINUE, second.outcome());
        assertEquals(2, second.nextFailedAttempts());

        currentTarget = second.nextTarget();
        targetIndex = second.nextTargetIndex();
        failedAttempts = second.nextFailedAttempts();

        MasonGuardChestDistributionGoal.BatchTransferStepResult third = MasonGuardChestDistributionGoal.performBatchTransferPass(
                pending,
                currentTarget,
                targets,
                targetIndex,
                failedAttempts,
                pos -> Optional.ofNullable(inventories.get(pos)),
                this::insertSingleItem
        );

        assertEquals(MasonGuardChestDistributionGoal.BatchTransferOutcome.CONTINUE, third.outcome());
        assertEquals(0, third.nextFailedAttempts());
        assertEquals(1, availableInventory.getStack(0).getCount());
    }

    private ItemStack insertSingleItem(Inventory inventory, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack existing = inventory.getStack(0);
        if (existing.isEmpty()) {
            inventory.setStack(0, stack.copy());
            return ItemStack.EMPTY;
        }

        if (!ItemStack.areItemsAndComponentsEqual(existing, stack) || existing.getCount() >= existing.getMaxCount()) {
            return stack;
        }

        existing.increment(1);
        return ItemStack.EMPTY;
    }

    private static class FakeInventory implements Inventory {
        private final ItemStack[] stacks;

        private FakeInventory(int size) {
            this.stacks = new ItemStack[size];
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
        public boolean canPlayerUse(net.minecraft.entity.player.PlayerEntity player) {
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
