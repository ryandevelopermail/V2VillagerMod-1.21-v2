package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.LumberjackDemandPlanner;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class LumberjackGuardDepositLogsGoal extends Goal {
    private static final double ITEM_PICKUP_RADIUS = 2.5D;
    private static final int DISTRIBUTION_ATTEMPT_INTERVAL_TICKS = 20;
    private static final int MAX_DISTRIBUTION_ATTEMPTS_PER_VISIT = 6;

    private final LumberjackGuardEntity guard;
    private long nextDistributionAttemptTick;
    private int distributionAttempts;

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
        this.nextDistributionAttemptTick = Long.MIN_VALUE;
        this.distributionAttempts = 0;
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
            if (LumberjackChestTriggerController.isBootstrapSatisfied(world, this.guard)) {
                this.guard.setBootstrapComplete(true);
            }
            this.guard.requestTriggerEvaluation();
            LumberjackChestTriggerController.runImmediateVillageUpgradePass(world, this.guard);
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
            return;
        }

        tryDemandDrivenDistribution(world, chestInventory, chestPos);

        if (buffer.isEmpty()) {
            this.guard.requestTriggerEvaluation();
            LumberjackChestTriggerController.runImmediateVillageUpgradePass(world, this.guard);
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
        }
    }

    private void tryDemandDrivenDistribution(ServerWorld world, Inventory sourceInventory, BlockPos sourceChestPos) {
        long now = world.getTime();
        if (now < nextDistributionAttemptTick) {
            return;
        }
        if (distributionAttempts >= MAX_DISTRIBUTION_ATTEMPTS_PER_VISIT) {
            return;
        }

        distributionAttempts++;
        nextDistributionAttemptTick = now + DISTRIBUTION_ATTEMPT_INTERVAL_TICKS;

        LumberjackDemandPlanner.DemandSnapshot demandSnapshot = LumberjackDemandPlanner.buildSnapshot(world, this.guard, sourceInventory);
        List<DistributionCandidate> candidates = collectCandidates(sourceInventory, demandSnapshot);
        if (candidates.isEmpty()) {
            return;
        }

        DistributionCandidate selected = candidates.getFirst();
        ItemStack sourceStack = sourceInventory.getStack(selected.slot());
        if (sourceStack.isEmpty()) {
            return;
        }

        ItemStack moved = sourceStack.split(1);
        sourceInventory.setStack(selected.slot(), sourceStack);
        sourceInventory.markDirty();

        Optional<Inventory> recipientInventory = Optional.ofNullable(getChestInventory(world, selected.recipient().record().chestPos()));
        if (recipientInventory.isEmpty()) {
            ItemStack putBack = insertIntoInventory(sourceInventory, moved);
            if (!putBack.isEmpty()) {
                dropStackAt(world, sourceChestPos, putBack);
            }
            return;
        }

        ItemStack remaining = insertIntoInventory(recipientInventory.get(), moved);
        recipientInventory.get().markDirty();
        if (remaining.isEmpty()) {
            return;
        }

        ItemStack putBack = insertIntoInventory(sourceInventory, remaining);
        if (!putBack.isEmpty()) {
            dropStackAt(world, sourceChestPos, putBack);
        }
    }

    private List<DistributionCandidate> collectCandidates(Inventory sourceInventory, LumberjackDemandPlanner.DemandSnapshot demandSnapshot) {
        List<DistributionCandidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < sourceInventory.size(); slot++) {
            ItemStack stack = sourceInventory.getStack(slot);
            LumberjackDemandPlanner.MaterialType materialType = LumberjackDemandPlanner.MaterialType.fromStack(stack);
            if (materialType == null) {
                continue;
            }

            List<LumberjackDemandPlanner.RecipientDemand> rankedRecipients = demandSnapshot.rankedRecipientsFor(materialType);
            if (rankedRecipients.isEmpty()) {
                continue;
            }

            int demandScore = demandSnapshot.deficitFor(materialType);
            LumberjackDemandPlanner.RecipientDemand topRecipient = rankedRecipients.getFirst();
            candidates.add(new DistributionCandidate(slot, materialType, topRecipient, demandScore));
        }

        candidates.sort(Comparator
                .comparingInt(DistributionCandidate::demandScore).reversed()
                .thenComparing(Comparator.comparingInt((DistributionCandidate candidate) -> candidate.recipient().deficit()).reversed())
                .thenComparingDouble(candidate -> candidate.recipient().record().sourceSquaredDistance())
                .thenComparing(candidate -> candidate.recipient().record().recipient().getUuid(), UUID::compareTo));
        return candidates;
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

    private void dropStackAt(ServerWorld world, BlockPos pos, ItemStack stack) {
        if (!stack.isEmpty()) {
            world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack));
        }
    }

    private record DistributionCandidate(int slot,
                                         LumberjackDemandPlanner.MaterialType materialType,
                                         LumberjackDemandPlanner.RecipientDemand recipient,
                                         int demandScore) {
    }
}
