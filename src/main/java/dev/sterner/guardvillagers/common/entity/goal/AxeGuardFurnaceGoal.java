package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Predicate;

public class AxeGuardFurnaceGoal extends Goal {
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int CHECK_INTERVAL_TICKS = 10;

    private final AxeGuardEntity guard;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;

    private boolean batchActive;
    private int batchInputLogs;
    private int targetFuelCharcoal;
    private int charcoalMovedToFuel;

    public AxeGuardFurnaceGoal(AxeGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void requestImmediateCheck() {
        nextCheckTime = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }
        if (guard.getPairedFurnacePos() == null || guard.getChestPos() == null || world.getTime() < nextCheckTime) {
            return false;
        }

        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        return hasWork(world);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && guard.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_CHEST;
        moveTo(guard.getChestPos());
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.MOVE_TO_CHEST) {
            if (!hasWork(world)) {
                stage = Stage.DONE;
                return;
            }
            if (isNear(guard.getChestPos())) {
                stage = Stage.MOVE_TO_FURNACE;
                moveTo(guard.getPairedFurnacePos());
            } else if (guard.getNavigation().isIdle()) {
                moveTo(guard.getChestPos());
            }
            return;
        }

        if (stage == Stage.MOVE_TO_FURNACE) {
            if (!hasWork(world)) {
                stage = Stage.DONE;
                return;
            }
            if (isNear(guard.getPairedFurnacePos())) {
                transferItems(world);
                stage = Stage.DONE;
            } else if (guard.getNavigation().isIdle()) {
                moveTo(guard.getPairedFurnacePos());
            }
        }
    }

    private boolean hasWork(ServerWorld world) {
        Inventory chestInventory = guard.getPairedChestInventory(world).orElse(null);
        FurnaceBlockEntity furnace = guard.getPairedFurnace(world).orElse(null);
        if (chestInventory == null || furnace == null) {
            return false;
        }

        if (!batchActive && countMatching(chestInventory, this::isBurnableLog) > 0) {
            return true;
        }

        ItemStack input = furnace.getStack(0);
        ItemStack fuel = furnace.getStack(1);
        ItemStack output = furnace.getStack(2);

        if (batchActive) {
            if (charcoalMovedToFuel < targetFuelCharcoal && output.isOf(Items.CHARCOAL)) return true;
            if (fuel.isEmpty() && countMatching(chestInventory, this::isBurnableLog) > 0) return true;
            if (input.isEmpty() && !output.isEmpty()) return true;
        }

        return !output.isEmpty() || (input.isEmpty() && countMatching(chestInventory, this::isBurnableLog) > 0);
    }

    private void transferItems(ServerWorld world) {
        Inventory chestInventory = guard.getPairedChestInventory(world).orElse(null);
        FurnaceBlockEntity furnace = guard.getPairedFurnace(world).orElse(null);
        if (chestInventory == null || furnace == null) {
            return;
        }

        if (!batchActive) {
            int chestLogs = countMatching(chestInventory, this::isBurnableLog);
            if (chestLogs > 0) {
                int toInput = Math.max(1, chestLogs / 2);
                int movedInput = moveLogsToInput(chestInventory, furnace, toInput);
                if (movedInput > 0) {
                    batchActive = true;
                    batchInputLogs = movedInput;
                    targetFuelCharcoal = Math.max(1, (int) Math.ceil(batchInputLogs * 0.25D));
                    charcoalMovedToFuel = furnace.getStack(1).isOf(Items.CHARCOAL) ? furnace.getStack(1).getCount() : 0;
                }
            }
        }

        if (batchActive) {
            topUpFuelWithLogs(chestInventory, furnace);
            if (charcoalMovedToFuel < targetFuelCharcoal) {
                siphonOutputToFuel(furnace);
            }

            ItemStack input = furnace.getStack(0);
            if (input.isEmpty() && charcoalMovedToFuel >= targetFuelCharcoal) {
                moveOutputToChest(chestInventory, furnace);
                batchActive = false;
                batchInputLogs = 0;
                targetFuelCharcoal = 0;
                charcoalMovedToFuel = 0;
            }
        } else {
            moveOutputToChest(chestInventory, furnace);
        }

        chestInventory.markDirty();
        furnace.markDirty();
        guard.setFurnaceBatchInputLogs(batchInputLogs);
    }

    private int moveLogsToInput(Inventory chestInventory, FurnaceBlockEntity furnace, int targetCount) {
        int remaining = targetCount;
        int moved = 0;

        while (remaining > 0) {
            int slot = findMatchingSlot(chestInventory, this::isBurnableLog);
            if (slot < 0) break;

            ItemStack source = chestInventory.getStack(slot);
            int take = Math.min(remaining, source.getCount());
            ItemStack candidate = source.copy();
            candidate.setCount(take);
            ItemStack inputRemainder = insertIntoFurnace(furnace, candidate, 0);
            int accepted = take - inputRemainder.getCount();
            if (accepted <= 0) break;

            source.decrement(accepted);
            if (source.isEmpty()) {
                chestInventory.setStack(slot, ItemStack.EMPTY);
            }

            moved += accepted;
            remaining -= accepted;

            if (!inputRemainder.isEmpty()) {
                guard.insertIntoInventory(chestInventory, inputRemainder);
            }
        }

        return moved;
    }

    private void topUpFuelWithLogs(Inventory chestInventory, FurnaceBlockEntity furnace) {
        ItemStack fuel = furnace.getStack(1);
        if (!fuel.isEmpty() && !isBurnableLog(fuel)) return;

        int desiredFuelLogs = 3;
        int current = fuel.isEmpty() ? 0 : fuel.getCount();
        int needed = desiredFuelLogs - current;
        if (needed <= 0) return;

        int slot = findMatchingSlot(chestInventory, this::isBurnableLog);
        if (slot < 0) return;

        ItemStack source = chestInventory.getStack(slot);
        int toMove = Math.min(needed, source.getCount());
        ItemStack moved = source.copy();
        moved.setCount(toMove);
        ItemStack remainder = insertIntoFurnace(furnace, moved, 1);
        int accepted = toMove - remainder.getCount();
        if (accepted <= 0) return;

        source.decrement(accepted);
        if (source.isEmpty()) {
            chestInventory.setStack(slot, ItemStack.EMPTY);
        }

        if (!remainder.isEmpty()) {
            guard.insertIntoInventory(chestInventory, remainder);
        }
    }

    private void siphonOutputToFuel(FurnaceBlockEntity furnace) {
        ItemStack output = furnace.getStack(2);
        if (!output.isOf(Items.CHARCOAL)) return;

        int needed = targetFuelCharcoal - charcoalMovedToFuel;
        if (needed <= 0) return;

        ItemStack fuel = furnace.getStack(1);
        int canMove;
        if (fuel.isEmpty()) {
            canMove = Math.min(output.getCount(), needed);
            ItemStack moved = output.copy();
            moved.setCount(canMove);
            furnace.setStack(1, moved);
        } else if (fuel.isOf(Items.CHARCOAL)) {
            int space = fuel.getMaxCount() - fuel.getCount();
            canMove = Math.min(Math.min(space, output.getCount()), needed);
            if (canMove <= 0) return;
            fuel.increment(canMove);
        } else {
            return;
        }

        output.decrement(canMove);
        if (output.isEmpty()) {
            furnace.setStack(2, ItemStack.EMPTY);
        }

        charcoalMovedToFuel += canMove;
    }

    private void moveOutputToChest(Inventory chestInventory, FurnaceBlockEntity furnace) {
        ItemStack output = furnace.getStack(2);
        if (output.isEmpty()) {
            return;
        }

        ItemStack remainder = guard.insertIntoInventory(chestInventory, output.copy());
        furnace.setStack(2, remainder.isEmpty() ? ItemStack.EMPTY : remainder);
    }

    private int countMatching(Inventory inventory, Predicate<ItemStack> matcher) {
        return guard.countMatching(inventory, matcher);
    }

    private int findMatchingSlot(Inventory inventory, Predicate<ItemStack> matcher) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) return slot;
        }
        return -1;
    }

    private boolean isBurnableLog(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.LOGS);
    }

    private ItemStack insertIntoFurnace(FurnaceBlockEntity furnace, ItemStack stack, int slot) {
        ItemStack existing = furnace.getStack(slot);
        if (existing.isEmpty()) {
            furnace.setStack(slot, stack.copy());
            return ItemStack.EMPTY;
        }
        if (!ItemStack.areItemsAndComponentsEqual(existing, stack)) return stack;

        int maxStack = Math.min(existing.getMaxCount(), furnace.getMaxCountPerStack());
        int space = maxStack - existing.getCount();
        if (space <= 0) return stack;

        int moved = Math.min(space, stack.getCount());
        existing.increment(moved);
        ItemStack remaining = stack.copy();
        remaining.decrement(moved);
        return remaining;
    }

    private void moveTo(BlockPos pos) {
        if (pos == null) return;
        guard.getNavigation().startMovingTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos pos) {
        if (pos == null) return false;
        return guard.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private enum Stage { IDLE, MOVE_TO_CHEST, MOVE_TO_FURNACE, DONE }
}
