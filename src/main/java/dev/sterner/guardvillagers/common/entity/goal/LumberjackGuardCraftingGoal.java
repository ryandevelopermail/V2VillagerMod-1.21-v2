package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;

public class LumberjackGuardCraftingGoal extends Goal {
    private static final int DAILY_CRAFT_LIMIT = 2;

    private final LumberjackGuardEntity guard;
    private long lastCraftDay = -1L;
    private int craftedToday;
    private boolean basePairingEstablished;

    public LumberjackGuardCraftingGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }

        refreshDailyLimit(world);
        if (this.guard.getWorkflowStage() != LumberjackGuardEntity.WorkflowStage.CRAFTING || !this.guard.isAlive()) {
            return false;
        }
        if (!world.isDay() || craftedToday >= DAILY_CRAFT_LIMIT || this.guard.getGatheredStackBuffer().isEmpty()) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            return false;
        }
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.CRAFTING;
    }

    @Override
    public void tick() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return;
        }

        BlockPos tablePos = this.guard.getPairedCraftingTablePos();
        if (tablePos == null) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            return;
        }

        if (this.guard.squaredDistanceTo(Vec3d.ofCenter(tablePos)) > 9.0D) {
            this.guard.getNavigation().startMovingTo(tablePos.getX() + 0.5D, tablePos.getY(), tablePos.getZ() + 0.5D, 0.8D);
            return;
        }

        if (!basePairingEstablished) {
            basePairingEstablished = tryPlaceAndBindChest(world);
        }

        Inventory chestInventory = resolveChestInventory(world);
        boolean woodConversionSucceeded = performWoodConversion(chestInventory);
        boolean priorityOutputCrafted = craftPriorityOutputs(world, chestInventory, isBasePairingReadyForDemand());

        boolean meaningfulActionSucceeded = priorityOutputCrafted;
        // Wood conversion is tracked for clarity, but only completed priority crafting or chest placement
        // should count against the daily crafting budget.
        if (meaningfulActionSucceeded) {
            craftedToday++;
        } else if (woodConversionSucceeded) {
            // Intentionally do not count this toward DAILY_CRAFT_LIMIT.
        }

        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
    }

    private boolean performWoodConversion(Inventory chestInventory) {
        boolean converted = false;

        int availableLogs = countMatching(chestInventory, stack -> stack.isIn(ItemTags.LOGS))
                + countMatching(this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.LOGS));
        int logsToConvert = availableLogs / 2;
        if (logsToConvert > 0 && consumeMatching(chestInventory, this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.LOGS), logsToConvert)) {
            addToBuffer(new ItemStack(Items.OAK_PLANKS, logsToConvert * 4));
            converted = true;
        }

        int availablePlanks = countMatching(chestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countMatching(this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS));
        int planksToConvert = availablePlanks / 2;
        if (planksToConvert > 0 && consumeMatching(chestInventory, this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS), planksToConvert)) {
            addToBuffer(new ItemStack(Items.STICK, planksToConvert * 2));
            converted = true;
        }

        if (chestInventory != null) {
            chestInventory.markDirty();
        }

        return converted;
    }

    private boolean craftPriorityOutputs(ServerWorld world, Inventory chestInventory, boolean demandEnabled) {
        if (isBootstrapSession()) {
            if (shouldCraftBootstrapAxe(chestInventory) && craftIfPossible(chestInventory, 3, 2, Items.WOODEN_AXE)) {
                return true;
            }
            return false;
        }

        if (!demandEnabled) {
            return false;
        }

        LumberjackChestTriggerController.UpgradeDemand demand = LumberjackChestTriggerController.resolveNextUpgradeDemand(world, this.guard);
        if (demand != null && countByItem(chestInventory, demand.outputItem()) + countByItem(this.guard.getGatheredStackBuffer(), demand.outputItem()) <= 0) {
            if (craftIfPossible(chestInventory, demand.planksCost(), 0, demand.outputItem())) {
                stashCraftedOutput(chestInventory, demand.outputItem());
                LumberjackChestTriggerController.runImmediateVillageUpgradePass(world, this.guard);
                return true;
            }
            return false;
        }

        return false;
    }

    private boolean isBootstrapSession() {
        return this.guard.getPairedChestPos() == null;
    }

    private boolean isBasePairingReadyForDemand() {
        return basePairingEstablished && !isBootstrapSession();
    }

    private boolean tryPlaceAndBindChest(ServerWorld world) {
        BlockPos pairedChestPos = this.guard.getPairedChestPos();
        if (pairedChestPos != null) {
            return resolveChestInventory(world) != null;
        }

        BlockPos tablePos = this.guard.getPairedCraftingTablePos();
        if (tablePos == null) {
            return false;
        }

        BlockPos nearbyChest = JobBlockPairingHelper.findNearbyChest(world, tablePos).orElse(null);
        if (nearbyChest == null) {
            return false;
        }

        this.guard.setPairedChestPos(nearbyChest);
        return true;
    }

    private boolean shouldCraftBootstrapAxe(Inventory chestInventory) {
        int axesOnHand = countByItem(chestInventory, Items.WOODEN_AXE) + countByItem(this.guard.getGatheredStackBuffer(), Items.WOODEN_AXE);
        return axesOnHand < 1;
    }

    private void stashCraftedOutput(Inventory chestInventory, Item item) {
        if (!isBasePairingReadyForDemand()) {
            return;
        }

        ItemStack craftedStack = takeOneByItem(this.guard.getGatheredStackBuffer(), item);
        if (craftedStack.isEmpty()) {
            return;
        }

        if (chestInventory != null) {
            ItemStack remaining = insertIntoInventory(chestInventory, craftedStack);
            if (remaining.isEmpty()) {
                chestInventory.markDirty();
                return;
            }
            craftedStack = remaining;
        }

        addToBuffer(craftedStack);
    }

    private boolean craftIfPossible(Inventory chestInventory, int planksCost, int stickCost, Item output) {
        int planks = countMatching(chestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countMatching(this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS));
        int sticks = countByItem(chestInventory, Items.STICK) + countByItem(this.guard.getGatheredStackBuffer(), Items.STICK);
        if (planks < planksCost || sticks < stickCost) {
            return false;
        }

        boolean consumedPlanks = consumeMatching(chestInventory, this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS), planksCost);
        boolean consumedSticks = stickCost == 0 || consumeByItem(chestInventory, this.guard.getGatheredStackBuffer(), Items.STICK, stickCost);
        if (consumedPlanks && consumedSticks) {
            addToBuffer(new ItemStack(output, 1));
            return true;
        }

        return false;
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
        return remaining;
    }

    private ItemStack takeOneByItem(List<ItemStack> stacks, Item item) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }

            ItemStack split = stack.split(1);
            if (stack.isEmpty()) {
                stacks.set(i, ItemStack.EMPTY);
            }
            stacks.removeIf(ItemStack::isEmpty);
            return split;
        }

        return ItemStack.EMPTY;
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

    private void addToBuffer(ItemStack incoming) {
        List<ItemStack> buffer = this.guard.getGatheredStackBuffer();
        for (ItemStack existing : buffer) {
            if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                int move = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                existing.increment(move);
                incoming.decrement(move);
                if (incoming.isEmpty()) {
                    return;
                }
            }
        }

        if (!incoming.isEmpty()) {
            buffer.add(incoming);
        }
    }

    private int countMatching(Inventory inventory, java.util.function.Predicate<ItemStack> predicate) {
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countMatching(List<ItemStack> stacks, java.util.function.Predicate<ItemStack> predicate) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countByItem(Inventory inventory, Item item) {
        return countMatching(inventory, stack -> stack.isOf(item));
    }

    private int countByItem(List<ItemStack> stacks, Item item) {
        return countMatching(stacks, stack -> stack.isOf(item));
    }

    private boolean consumeMatching(Inventory inventory, List<ItemStack> buffer, java.util.function.Predicate<ItemStack> predicate, int amount) {
        int remaining = consumeFromInventory(inventory, predicate, amount);
        remaining = consumeFromBuffer(buffer, predicate, remaining);
        return remaining <= 0;
    }

    private boolean consumeByItem(Inventory inventory, List<ItemStack> buffer, Item item, int amount) {
        return consumeMatching(inventory, buffer, stack -> stack.isOf(item), amount);
    }

    private int consumeFromInventory(Inventory inventory, java.util.function.Predicate<ItemStack> predicate, int amount) {
        if (inventory == null) {
            return amount;
        }

        int remaining = amount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }

            int moved = Math.min(stack.getCount(), remaining);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            remaining -= moved;
        }
        return remaining;
    }

    private int consumeFromBuffer(List<ItemStack> buffer, java.util.function.Predicate<ItemStack> predicate, int amount) {
        int remaining = amount;
        for (int i = 0; i < buffer.size() && remaining > 0; i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }

            int moved = Math.min(stack.getCount(), remaining);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                buffer.set(i, ItemStack.EMPTY);
            }
            remaining -= moved;
        }

        buffer.removeIf(ItemStack::isEmpty);
        return remaining;
    }

    private void refreshDailyLimit(ServerWorld world) {
        long day = world.getTimeOfDay() / 24000L;
        if (day != lastCraftDay) {
            lastCraftDay = day;
            craftedToday = 0;
        }
    }
}
