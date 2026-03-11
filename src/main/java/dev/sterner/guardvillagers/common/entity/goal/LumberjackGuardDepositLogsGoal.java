package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import net.minecraft.item.Items;

import java.util.EnumSet;
import java.util.List;

public class LumberjackGuardDepositLogsGoal extends Goal {
    private static final double ITEM_PICKUP_RADIUS = 2.5D;

    private final LumberjackGuardEntity guard;

    public LumberjackGuardDepositLogsGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        return this.guard.getWorld() instanceof ServerWorld
                && this.guard.isAlive()
                && this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.DEPOSITING
                && !this.guard.getGatheredStackBuffer().isEmpty();
    }

    @Override
    public void start() {
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_CHEST);
    }

    @Override
    public boolean shouldContinue() {
        return this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.MOVING_TO_CHEST
                && !this.guard.getGatheredStackBuffer().isEmpty();
    }

    @Override
    public void tick() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos chestPos = this.guard.getPairedChestPos();
        if (chestPos == null) {
            dropAll(world, this.guard.getBlockPos());
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
            return;
        }

        if (this.guard.squaredDistanceTo(Vec3d.ofCenter(chestPos)) > 9.0D) {
            collectNearbyWoodDrops(world);
            this.guard.getNavigation().startMovingTo(chestPos.getX() + 0.5D, chestPos.getY(), chestPos.getZ() + 0.5D, 0.8D);
            return;
        }

        Inventory chestInventory = getChestInventory(world, chestPos);
        if (chestInventory == null) {
            dropAll(world, chestPos);
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
            return;
        }

        List<ItemStack> buffer = this.guard.getGatheredStackBuffer();
        for (int i = 0; i < buffer.size(); i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = insertIntoInventory(chestInventory, stack);
            buffer.set(i, remaining);
        }
        buffer.removeIf(ItemStack::isEmpty);

        if (buffer.isEmpty()) {
            this.guard.requestTriggerEvaluation();
            LumberjackChestTriggerController.runImmediateVillageUpgradePass(world, this.guard);
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
        }
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
        if (this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.MOVING_TO_CHEST) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
        }
    }

    private Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                inventory.setStack(slot, remaining);
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                int transfer = Math.min(existing.getMaxCount() - existing.getCount(), remaining.getCount());
                if (transfer > 0) {
                    existing.increment(transfer);
                    remaining.decrement(transfer);
                    inventory.setStack(slot, existing);
                }
            }
        }
        inventory.markDirty();
        return remaining;
    }


    private void collectNearbyWoodDrops(ServerWorld world) {
        Box pickupBox = this.guard.getBoundingBox().expand(ITEM_PICKUP_RADIUS, 1.0D, ITEM_PICKUP_RADIUS);
        List<ItemEntity> nearbyItems = world.getEntitiesByClass(ItemEntity.class,
                pickupBox,
                entity -> entity.isAlive() && !entity.getStack().isEmpty() && isGatherableWoodDrop(entity.getStack()));

        for (ItemEntity itemEntity : nearbyItems) {
            bufferStack(itemEntity.getStack().copy());
            itemEntity.discard();
        }
    }

    private void bufferStack(ItemStack incoming) {
        List<ItemStack> buffer = this.guard.getGatheredStackBuffer();
        for (ItemStack existing : buffer) {
            if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                int transfer = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                existing.increment(transfer);
                incoming.decrement(transfer);
                if (incoming.isEmpty()) {
                    return;
                }
            }
        }

        if (!incoming.isEmpty()) {
            buffer.add(incoming);
        }
    }

    private boolean isGatherableWoodDrop(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS)
                || stack.isIn(ItemTags.PLANKS)
                || stack.isOf(Items.STICK)
                || stack.isOf(Items.CHARCOAL);
    }

    private void dropAll(ServerWorld world, BlockPos pos) {
        for (ItemStack stack : this.guard.getGatheredStackBuffer()) {
            if (!stack.isEmpty()) {
                world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack));
            }
        }
        this.guard.getGatheredStackBuffer().clear();
    }
}
