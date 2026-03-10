package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class LumberjackFurnaceModifierGoal extends Goal {
    private static final double MOVE_SPEED = 0.8D;
    private static final double REACH_DISTANCE_SQUARED = 9.0D;
    private static final int SEARCH_RADIUS = 3;

    private final LumberjackGuardEntity guard;
    private BlockPos targetFurnacePos;

    public LumberjackFurnaceModifierGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!this.guard.isAlive() || this.guard.getWorkflowStage() != LumberjackGuardEntity.WorkflowStage.CRAFTING) {
            return false;
        }

        Optional<BlockPos> designatedFurnace = findDesignatedFurnace(world);
        if (designatedFurnace.isEmpty()) {
            return false;
        }

        this.targetFurnacePos = designatedFurnace.get();
        return needsFurnaceService(world, this.targetFurnacePos);
    }

    @Override
    public boolean shouldContinue() {
        return this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.CRAFTING && this.targetFurnacePos != null;
    }

    @Override
    public void tick() {
        if (!(this.guard.getWorld() instanceof ServerWorld world) || this.targetFurnacePos == null) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            return;
        }

        if (this.guard.squaredDistanceTo(Vec3d.ofCenter(this.targetFurnacePos)) > REACH_DISTANCE_SQUARED) {
            this.guard.getNavigation().startMovingTo(this.targetFurnacePos.getX() + 0.5D, this.targetFurnacePos.getY(), this.targetFurnacePos.getZ() + 0.5D, MOVE_SPEED);
            return;
        }

        Inventory chestInventory = resolveChestInventory(world);
        Optional<AbstractFurnaceBlockEntity> furnaceOpt = resolveFurnace(world, this.targetFurnacePos);
        if (furnaceOpt.isEmpty()) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            return;
        }

        AbstractFurnaceBlockEntity furnace = furnaceOpt.get();
        serviceFurnace(chestInventory, furnace);
        if (chestInventory != null) {
            chestInventory.markDirty();
        }
        furnace.markDirty();

        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
        this.targetFurnacePos = null;
    }

    private void serviceFurnace(Inventory chestInventory, AbstractFurnaceBlockEntity furnace) {
        moveHalfLogsToInput(chestInventory, furnace);
        refillFuelFromCharcoalOutput(furnace);
        if (furnace.getStack(1).isEmpty()) {
            moveLogsToFuel(chestInventory, furnace, 3);
        }
        refillFuelFromCharcoalOutput(furnace);
        recoverFuelFromInputIfStalled(furnace);
    }

    private void moveHalfLogsToInput(Inventory chestInventory, AbstractFurnaceBlockEntity furnace) {
        ItemStack input = furnace.getStack(0);
        if (!input.isEmpty() && !input.isIn(ItemTags.LOGS)) {
            return;
        }

        int logsAvailable = countLogs(chestInventory) + countLogs(this.guard.getGatheredStackBuffer());
        int logsToMove = logsAvailable / 2;
        if (logsToMove <= 0) {
            return;
        }

        int space = input.isEmpty() ? 64 : (input.getMaxCount() - input.getCount());
        logsToMove = Math.min(logsToMove, space);
        if (logsToMove <= 0) {
            return;
        }

        ItemStack extracted = extractLogs(chestInventory, logsToMove, input.isEmpty() ? null : input.getItem().getTranslationKey());
        if (extracted.isEmpty()) {
            return;
        }

        ItemStack remaining = insertIntoSlot(furnace, extracted, 0);
        if (!remaining.isEmpty()) {
            returnLogs(chestInventory, remaining);
        }
    }

    private void moveLogsToFuel(Inventory chestInventory, AbstractFurnaceBlockEntity furnace, int amount) {
        ItemStack fuel = furnace.getStack(1);
        if (!fuel.isEmpty() && !fuel.isIn(ItemTags.LOGS)) {
            return;
        }

        int space = fuel.isEmpty() ? 64 : (fuel.getMaxCount() - fuel.getCount());
        int requested = Math.min(amount, space);
        if (requested <= 0) {
            return;
        }

        ItemStack extracted = extractLogs(chestInventory, requested, fuel.isEmpty() ? null : fuel.getItem().getTranslationKey());
        if (extracted.isEmpty()) {
            return;
        }

        ItemStack remaining = insertIntoSlot(furnace, extracted, 1);
        if (!remaining.isEmpty()) {
            returnLogs(chestInventory, remaining);
        }
    }

    private void refillFuelFromCharcoalOutput(AbstractFurnaceBlockEntity furnace) {
        ItemStack fuel = furnace.getStack(1);
        ItemStack output = furnace.getStack(2);
        if (!fuel.isEmpty() || output.isEmpty() || !output.isOf(Items.CHARCOAL)) {
            return;
        }

        int moved = Math.min(output.getCount(), output.getMaxCount());
        furnace.setStack(1, new ItemStack(Items.CHARCOAL, moved));
        output.decrement(moved);
        if (output.isEmpty()) {
            furnace.setStack(2, ItemStack.EMPTY);
        } else {
            furnace.setStack(2, output);
        }
    }

    private void recoverFuelFromInputIfStalled(AbstractFurnaceBlockEntity furnace) {
        ItemStack fuel = furnace.getStack(1);
        if (!fuel.isEmpty()) {
            return;
        }

        ItemStack output = furnace.getStack(2);
        if (!output.isEmpty() && output.isOf(Items.CHARCOAL)) {
            return;
        }

        ItemStack input = furnace.getStack(0);
        if (input.isEmpty() || !input.isIn(ItemTags.LOGS)) {
            return;
        }

        int moved = Math.min(3, input.getCount());
        ItemStack correctiveFuel = input.copyWithCount(moved);
        ItemStack remaining = insertIntoSlot(furnace, correctiveFuel, 1);
        int inserted = moved - remaining.getCount();
        if (inserted > 0) {
            input.decrement(inserted);
            if (input.isEmpty()) {
                furnace.setStack(0, ItemStack.EMPTY);
            } else {
                furnace.setStack(0, input);
            }
        }
    }

    private ItemStack insertIntoSlot(AbstractFurnaceBlockEntity furnace, ItemStack stack, int slot) {
        ItemStack existing = furnace.getStack(slot);
        if (existing.isEmpty()) {
            furnace.setStack(slot, stack.copy());
            return ItemStack.EMPTY;
        }
        if (!ItemStack.areItemsAndComponentsEqual(existing, stack)) {
            return stack;
        }

        int movable = Math.min(stack.getCount(), existing.getMaxCount() - existing.getCount());
        if (movable <= 0) {
            return stack;
        }
        existing.increment(movable);
        stack.decrement(movable);
        furnace.setStack(slot, existing);
        return stack;
    }

    private int countLogs(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.LOGS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countLogs(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && stack.isIn(ItemTags.LOGS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private ItemStack extractLogs(Inventory chestInventory, int amount, String requiredTranslationKey) {
        ItemStack extracted = extractLogsFromInventory(chestInventory, amount, requiredTranslationKey);
        if (extracted.getCount() >= amount) {
            return extracted;
        }

        ItemStack fromBuffer = extractLogsFromBuffer(amount - extracted.getCount(), requiredTranslationKey == null && !extracted.isEmpty() ? extracted.getItem().getTranslationKey() : requiredTranslationKey, this.guard.getGatheredStackBuffer());
        if (extracted.isEmpty()) {
            return fromBuffer;
        }
        if (!fromBuffer.isEmpty() && ItemStack.areItemsAndComponentsEqual(extracted, fromBuffer)) {
            extracted.increment(fromBuffer.getCount());
        } else if (!fromBuffer.isEmpty()) {
            returnLogs(chestInventory, fromBuffer);
        }
        return extracted;
    }

    private ItemStack extractLogsFromInventory(Inventory inventory, int amount, String requiredTranslationKey) {
        if (inventory == null || amount <= 0) {
            return ItemStack.EMPTY;
        }

        ItemStack extracted = ItemStack.EMPTY;
        for (int slot = 0; slot < inventory.size() && amount > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.LOGS)) {
                continue;
            }
            if (requiredTranslationKey != null && !stack.getItem().getTranslationKey().equals(requiredTranslationKey)) {
                continue;
            }

            if (requiredTranslationKey == null) {
                requiredTranslationKey = stack.getItem().getTranslationKey();
            }

            int moved = Math.min(stack.getCount(), amount);
            ItemStack movedStack = stack.copyWithCount(moved);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            amount -= moved;

            if (extracted.isEmpty()) {
                extracted = movedStack;
            } else {
                extracted.increment(movedStack.getCount());
            }
        }
        return extracted;
    }

    private ItemStack extractLogsFromBuffer(int amount, String requiredTranslationKey, List<ItemStack> buffer) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }

        ItemStack extracted = ItemStack.EMPTY;
        for (int i = 0; i < buffer.size() && amount > 0; i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty() || !stack.isIn(ItemTags.LOGS)) {
                continue;
            }
            if (requiredTranslationKey != null && !stack.getItem().getTranslationKey().equals(requiredTranslationKey)) {
                continue;
            }

            if (requiredTranslationKey == null) {
                requiredTranslationKey = stack.getItem().getTranslationKey();
            }

            int moved = Math.min(stack.getCount(), amount);
            ItemStack movedStack = stack.copyWithCount(moved);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                buffer.set(i, ItemStack.EMPTY);
            }
            amount -= moved;

            if (extracted.isEmpty()) {
                extracted = movedStack;
            } else {
                extracted.increment(movedStack.getCount());
            }
        }

        buffer.removeIf(ItemStack::isEmpty);
        return extracted;
    }

    private void returnLogs(Inventory chestInventory, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        if (chestInventory != null) {
            ItemStack remaining = stack.copy();
            for (int slot = 0; slot < chestInventory.size() && !remaining.isEmpty(); slot++) {
                ItemStack existing = chestInventory.getStack(slot);
                if (existing.isEmpty()) {
                    chestInventory.setStack(slot, remaining.copy());
                    remaining = ItemStack.EMPTY;
                    break;
                }
                if (!ItemStack.areItemsAndComponentsEqual(existing, remaining) || existing.getCount() >= existing.getMaxCount()) {
                    continue;
                }
                int moved = Math.min(existing.getMaxCount() - existing.getCount(), remaining.getCount());
                existing.increment(moved);
                remaining.decrement(moved);
            }
            if (remaining.isEmpty()) {
                return;
            }
            stack = remaining;
        }

        for (ItemStack buffered : this.guard.getGatheredStackBuffer()) {
            if (!ItemStack.areItemsAndComponentsEqual(buffered, stack) || buffered.getCount() >= buffered.getMaxCount()) {
                continue;
            }
            int moved = Math.min(buffered.getMaxCount() - buffered.getCount(), stack.getCount());
            buffered.increment(moved);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                return;
            }
        }

        if (!stack.isEmpty()) {
            this.guard.getGatheredStackBuffer().add(stack.copy());
        }
    }

    private boolean needsFurnaceService(ServerWorld world, BlockPos furnacePos) {
        Inventory chestInventory = resolveChestInventory(world);
        Optional<AbstractFurnaceBlockEntity> furnaceOpt = resolveFurnace(world, furnacePos);
        if (furnaceOpt.isEmpty()) {
            return false;
        }

        AbstractFurnaceBlockEntity furnace = furnaceOpt.get();
        if (furnace.getStack(1).isEmpty() && furnace.getStack(2).isOf(Items.CHARCOAL)) {
            return true;
        }
        if (furnace.getStack(1).isEmpty() && furnace.getStack(0).isIn(ItemTags.LOGS)) {
            return true;
        }
        if (furnace.getStack(1).isEmpty() && countLogs(chestInventory) + countLogs(this.guard.getGatheredStackBuffer()) >= 3) {
            return true;
        }

        ItemStack input = furnace.getStack(0);
        int space = input.isEmpty() ? 64 : (input.isIn(ItemTags.LOGS) ? input.getMaxCount() - input.getCount() : 0);
        int logsToMove = (countLogs(chestInventory) + countLogs(this.guard.getGatheredStackBuffer())) / 2;
        return logsToMove > 0 && space > 0;
    }

    private Optional<BlockPos> findDesignatedFurnace(ServerWorld world) {
        BlockPos tablePos = this.guard.getPairedCraftingTablePos();
        BlockPos chestPos = this.guard.getPairedChestPos();
        if (tablePos == null || chestPos == null) {
            return Optional.empty();
        }

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos min = BlockPos.ofFloored(
                Math.min(tablePos.getX(), chestPos.getX()) - SEARCH_RADIUS,
                Math.min(tablePos.getY(), chestPos.getY()) - 1,
                Math.min(tablePos.getZ(), chestPos.getZ()) - SEARCH_RADIUS
        );
        BlockPos max = BlockPos.ofFloored(
                Math.max(tablePos.getX(), chestPos.getX()) + SEARCH_RADIUS,
                Math.max(tablePos.getY(), chestPos.getY()) + 1,
                Math.max(tablePos.getZ(), chestPos.getZ()) + SEARCH_RADIUS
        );

        for (BlockPos pos : BlockPos.iterate(min, max)) {
            BlockState state = world.getBlockState(pos);
            if (!state.isOf(Blocks.FURNACE)) {
                continue;
            }
            if (!pos.isWithinDistance(tablePos, SEARCH_RADIUS + 1) || !pos.isWithinDistance(chestPos, SEARCH_RADIUS + 1)) {
                continue;
            }
            if (!hasNearbyModifier(world, pos)) {
                continue;
            }
            candidates.add(pos.toImmutable());
        }

        return candidates.stream()
                .min(Comparator.comparing((BlockPos pos) -> pos.getSquaredDistance(tablePos)).thenComparing(pos -> Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString()));
    }

    private boolean hasNearbyModifier(ServerWorld world, BlockPos furnacePos) {
        for (BlockPos pos : BlockPos.iterateOutwards(furnacePos, 1, 1, 1)) {
            if (world.getBlockState(pos).isOf(GuardVillagers.GUARD_STAND_MODIFIER)) {
                return true;
            }
        }
        return false;
    }

    private Optional<AbstractFurnaceBlockEntity> resolveFurnace(ServerWorld world, BlockPos furnacePos) {
        BlockEntity blockEntity = world.getBlockEntity(furnacePos);
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            return Optional.of(furnace);
        }
        return Optional.empty();
    }

    private Inventory resolveChestInventory(ServerWorld world) {
        BlockPos chestPos = this.guard.getPairedChestPos();
        if (chestPos == null || !world.getBlockState(chestPos).isOf(Blocks.CHEST)) {
            return null;
        }

        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }

        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }
}
