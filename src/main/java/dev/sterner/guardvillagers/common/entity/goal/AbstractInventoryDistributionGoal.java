package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

public abstract class AbstractInventoryDistributionGoal extends Goal {
    protected static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    protected static final double TARGET_REACH_SQUARED = 4.0D;
    protected static final double MOVE_SPEED = 0.6D;

    protected final VillagerEntity villager;
    protected BlockPos jobPos;
    protected BlockPos chestPos;
    protected BlockPos craftingTablePos;
    protected Stage stage = Stage.IDLE;
    protected long nextCheckTime;
    protected boolean immediateCheckPending;
    protected ItemStack pendingItem = ItemStack.EMPTY;
    protected UUID pendingTargetId;
    protected BlockPos pendingTargetPos;

    protected AbstractInventoryDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.craftingTablePos = craftingTablePos != null ? craftingTablePos.toImmutable() : null;
        this.stage = Stage.IDLE;
    }

    public BlockPos getCraftingTablePos() {
        return craftingTablePos;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        if (!matchesProfession(villager)) {
            return false;
        }
        if (chestPos == null) {
            return false;
        }
        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }
        if (!canStartWithInventory(world, inventory)) {
            return false;
        }

        scheduleNextCooldown(world);
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_CHEST;
        moveTo(chestPos);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case GO_TO_CHEST -> {
                if (isNear(chestPos)) {
                    if (!selectPendingTransfer(world, getChestInventory(world).orElse(null))) {
                        stage = Stage.DONE;
                        return;
                    }
                    stage = Stage.GO_TO_TARGET;
                    moveTo(pendingTargetPos);
                } else {
                    moveTo(chestPos);
                }
            }
            case GO_TO_TARGET -> {
                if (pendingItem.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }
                if (!refreshTargetForPendingItem(world)) {
                    returnPendingItem(world);
                    stage = Stage.DONE;
                    return;
                }
                if (isNear(pendingTargetPos)) {
                    stage = Stage.EXECUTE_TRANSFER;
                } else {
                    moveTo(pendingTargetPos);
                }
            }
            case EXECUTE_TRANSFER -> {
                if (pendingItem.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }
                if (!refreshTargetForPendingItem(world)) {
                    returnPendingItem(world);
                    stage = Stage.DONE;
                    return;
                }
                if (executeTransfer(world)) {
                    clearPendingState();
                    stage = Stage.DONE;
                    return;
                }
                if (refreshTargetForPendingItem(world)) {
                    stage = Stage.GO_TO_TARGET;
                    return;
                }
                returnPendingItem(world);
                stage = Stage.DONE;
            }
            case IDLE, DONE -> {
            }
        }
    }

    public void requestImmediateDistribution() {
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    protected void scheduleNextCooldown(ServerWorld world) {
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        immediateCheckPending = false;
    }

    protected Optional<Inventory> getChestInventory(ServerWorld world) {
        if (chestPos == null) {
            return Optional.empty();
        }
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        return Optional.ofNullable(inventory);
    }

    protected void moveTo(BlockPos target) {
        if (target == null) {
            return;
        }
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    protected boolean isNear(BlockPos target) {
        if (target == null) {
            return false;
        }
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    protected BlockPos getDistributionCenter() {
        return chestPos != null ? chestPos : (craftingTablePos != null ? craftingTablePos : villager.getBlockPos());
    }

    protected ItemStack insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack toInsert = remaining.copy();
                toInsert.setCount(moved);
                inventory.setStack(slot, toInsert);
                remaining.decrement(moved);
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }

            if (!inventory.isValid(slot, remaining)) {
                continue;
            }

            int space = existing.getMaxCount() - existing.getCount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, remaining.getCount());
            existing.increment(moved);
            remaining.decrement(moved);
        }

        return remaining;
    }

    protected void returnPendingItem(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return;
        }
        ItemStack remaining = insertStack(getChestInventory(world).orElse(villager.getInventory()), pendingItem);
        if (!remaining.isEmpty()) {
            ItemStack villagerRemaining = insertStack(villager.getInventory(), remaining);
            if (!villagerRemaining.isEmpty()) {
                villager.dropStack(villagerRemaining);
            }
            villager.getInventory().markDirty();
        }
        clearPendingState();
    }

    protected void clearPendingState() {
        pendingItem = ItemStack.EMPTY;
        pendingTargetId = null;
        pendingTargetPos = null;
        clearPendingTargetState();
    }

    protected boolean hasDistributableItem(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (isDistributableItem(inventory.getStack(slot))) {
                return true;
            }
        }
        return false;
    }

    protected ItemStack extractSingleDistributableItem(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            return extracted;
        }
        return ItemStack.EMPTY;
    }

    protected enum Stage {
        IDLE,
        GO_TO_CHEST,
        GO_TO_TARGET,
        EXECUTE_TRANSFER,
        DONE
    }

    protected abstract boolean isDistributableItem(ItemStack stack);

    protected abstract boolean canStartWithInventory(ServerWorld world, Inventory inventory);

    protected abstract boolean selectPendingTransfer(ServerWorld world, Inventory inventory);

    protected abstract boolean refreshTargetForPendingItem(ServerWorld world);

    protected abstract boolean executeTransfer(ServerWorld world);

    protected abstract void clearPendingTargetState();

    protected abstract boolean matchesProfession(VillagerEntity villager);
}
