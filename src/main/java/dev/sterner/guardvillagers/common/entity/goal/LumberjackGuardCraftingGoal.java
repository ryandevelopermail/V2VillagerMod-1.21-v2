package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;

public class LumberjackGuardCraftingGoal extends Goal {
    // Per-day cap for priority outputs only (raw wood/plank conversion does not consume this limit).
    private static final int DAILY_CRAFT_LIMIT = 4;
    private static final int BOOTSTRAP_CHEST_PLANK_REQUIREMENT = 8;
    private static final int BOOTSTRAP_AXE_PLANK_REQUIREMENT = 3;
    private static final int BOOTSTRAP_AXE_STICK_REQUIREMENT = 2;

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
        if (!world.isDay() || craftedToday >= DAILY_CRAFT_LIMIT || !hasCraftingInputs(world)) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            return false;
        }
        return true;
    }

    private boolean hasCraftingInputs(ServerWorld world) {
        Inventory chestInventory = resolveChestInventory(world);
        return hasAnyCraftingInput(this.guard.getGatheredStackBuffer()) || hasAnyCraftingInput(chestInventory);
    }

    private boolean hasAnyCraftingInput(List<ItemStack> stacks) {
        return countMatching(stacks, this::isCraftingInput) > 0;
    }

    private boolean hasAnyCraftingInput(Inventory inventory) {
        return countMatching(inventory, this::isCraftingInput) > 0;
    }

    private boolean isCraftingInput(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS)
                || stack.isIn(ItemTags.PLANKS)
                || stack.isOf(Items.STICK)
                || stack.isOf(Items.CHEST)
                || stack.isOf(Items.WOODEN_AXE);
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
        int planksToConvert;
        if (isBootstrapSession()) {
            int plankReserve = 0;
            if (shouldCraftBootstrapChest(chestInventory)) {
                plankReserve += BOOTSTRAP_CHEST_PLANK_REQUIREMENT;
            }
            if (shouldCraftBootstrapAxe(chestInventory)) {
                plankReserve += BOOTSTRAP_AXE_PLANK_REQUIREMENT;
            }

            int requiredSticks = shouldCraftBootstrapAxe(chestInventory) ? BOOTSTRAP_AXE_STICK_REQUIREMENT : 0;
            int availableSticks = countByItem(chestInventory, Items.STICK) + countByItem(this.guard.getGatheredStackBuffer(), Items.STICK);
            int stickDeficit = Math.max(0, requiredSticks - availableSticks);

            int planksAvailableAfterReserve = Math.max(0, availablePlanks - plankReserve);
            int planksNeededForSticks = (stickDeficit + 1) / 2;
            planksToConvert = Math.min(planksAvailableAfterReserve, planksNeededForSticks);
        } else {
            planksToConvert = availablePlanks / 2;
        }

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
            boolean meaningfulAction = craftBootstrapChestAndAttemptPlacementIfNeeded(
                    shouldCraftBootstrapChest(chestInventory),
                    () -> craftIfPossible(chestInventory, BOOTSTRAP_CHEST_PLANK_REQUIREMENT, 0, Items.CHEST),
                    () -> {
                        boolean placed = tryPlaceAndBindChest(world);
                        if (placed) {
                            basePairingEstablished = true;
                        }
                        return placed;
                    }
            );

            if (shouldCraftBootstrapAxe(chestInventory) && craftIfPossible(chestInventory, BOOTSTRAP_AXE_PLANK_REQUIREMENT, BOOTSTRAP_AXE_STICK_REQUIREMENT, Items.WOODEN_AXE)) {
                meaningfulAction = true;
            }

            equipBootstrapAxeFromSupplies(chestInventory);
            return meaningfulAction;
        }

        if (!demandEnabled || !isBasePairingReadyForDemand()) {
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

    static boolean craftBootstrapChestAndAttemptPlacementIfNeeded(boolean shouldCraftBootstrapChest, BooleanSupplier craftChestAction, BooleanSupplier placeChestAction) {
        if (!shouldCraftBootstrapChest) {
            return false;
        }

        boolean craftedChest = craftChestAction.getAsBoolean();
        if (!craftedChest) {
            return false;
        }

        placeChestAction.getAsBoolean();
        return true;
    }


    private boolean isBootstrapSession() {
        return this.guard.getPairedChestPos() == null;
    }

    private boolean isBasePairingReadyForDemand() {
        return basePairingEstablished && !isBootstrapSession();
    }

    private boolean tryPlaceAndBindChest(ServerWorld world) {
        return tryPlaceAndBindChestForRecovery(world, this.guard, resolveChestInventory(world));
    }

    public static boolean tryPlaceAndBindChestForRecovery(ServerWorld world, LumberjackGuardEntity guard, Inventory chestInventory) {
        BlockPos pairedChestPos = guard.getPairedChestPos();
        if (pairedChestPos != null) {
            return resolveChestInventoryForGuard(world, guard) != null;
        }

        BlockPos tablePos = guard.getPairedCraftingTablePos();
        if (tablePos == null) {
            return false;
        }

        ItemStack chestStack = takeOneChestForPlacement(guard, chestInventory);
        if (chestStack.isEmpty()) {
            return false;
        }

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = tablePos.offset(direction);
            BlockPos below = candidate.down();
            if (!world.getBlockState(candidate).isAir()) {
                continue;
            }
            if (!world.getBlockState(below).isSolidBlock(world, below)) {
                continue;
            }
            if (!world.setBlockState(candidate, Blocks.CHEST.getDefaultState())) {
                continue;
            }

            guard.setPairedChestPos(candidate);
            guard.setBootstrapComplete(false);
            return true;
        }

        if (chestInventory != null) {
            ItemStack remainder = insertIntoInventoryStatic(chestInventory, chestStack);
            if (remainder.isEmpty()) {
                chestInventory.markDirty();
                return false;
            }
            chestStack = remainder;
        }

        addToBufferStatic(guard, chestStack);
        return false;
    }

    public static boolean ensureChestCraftingSuppliesForRecovery(LumberjackGuardEntity guard, Inventory chestInventory) {
        int availableLogs = countMatchingStatic(chestInventory, stack -> stack.isIn(ItemTags.LOGS))
                + countMatchingStatic(guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.LOGS));
        int logsToConvert = availableLogs / 2;
        if (logsToConvert <= 0) {
            return false;
        }

        if (!consumeMatchingStatic(chestInventory, guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.LOGS), logsToConvert)) {
            return false;
        }

        addToBufferStatic(guard, new ItemStack(Items.OAK_PLANKS, logsToConvert * 4));
        return true;
    }

    public static boolean craftChestForRecovery(LumberjackGuardEntity guard, Inventory chestInventory) {
        int chestCount = countByItemStatic(chestInventory, Items.CHEST) + countByItemStatic(guard.getGatheredStackBuffer(), Items.CHEST);
        if (chestCount > 0) {
            return true;
        }

        int planks = countMatchingStatic(chestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countMatchingStatic(guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS));
        if (planks < BOOTSTRAP_CHEST_PLANK_REQUIREMENT) {
            return false;
        }

        if (!consumeMatchingStatic(chestInventory, guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS), BOOTSTRAP_CHEST_PLANK_REQUIREMENT)) {
            return false;
        }

        addToBufferStatic(guard, new ItemStack(Items.CHEST, 1));
        return true;
    }

    public static Inventory resolveChestInventoryForGuard(ServerWorld world, LumberjackGuardEntity guard) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null || !world.getBlockState(chestPos).isOf(Blocks.CHEST)) {
            return null;
        }
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private static ItemStack takeOneChestForPlacement(LumberjackGuardEntity guard, Inventory chestInventory) {
        if (chestInventory != null) {
            for (int slot = 0; slot < chestInventory.size(); slot++) {
                ItemStack stack = chestInventory.getStack(slot);
                if (stack.isEmpty() || !stack.isOf(Items.CHEST)) {
                    continue;
                }

                ItemStack split = stack.split(1);
                if (stack.isEmpty()) {
                    chestInventory.setStack(slot, ItemStack.EMPTY);
                }
                chestInventory.markDirty();
                return split;
            }
        }

        return takeOneByItemStatic(guard.getGatheredStackBuffer(), Items.CHEST);
    }

    private boolean shouldCraftBootstrapAxe(Inventory chestInventory) {
        int equippedAxes = this.guard.getMainHandStack().isOf(Items.WOODEN_AXE) ? 1 : 0;
        int axesOnHand = equippedAxes + countByItem(chestInventory, Items.WOODEN_AXE) + countByItem(this.guard.getGatheredStackBuffer(), Items.WOODEN_AXE);
        return axesOnHand < 1;
    }

    private boolean shouldCraftBootstrapChest(Inventory chestInventory) {
        int chestsOnHand = countByItem(chestInventory, Items.CHEST) + countByItem(this.guard.getGatheredStackBuffer(), Items.CHEST);
        return chestsOnHand < 1;
    }

    private void equipBootstrapAxeFromSupplies(Inventory chestInventory) {
        if (this.guard.getMainHandStack().isOf(Items.WOODEN_AXE)) {
            return;
        }

        ItemStack bufferAxe = takeOneByItem(this.guard.getGatheredStackBuffer(), Items.WOODEN_AXE);
        if (!bufferAxe.isEmpty()) {
            this.guard.equipStack(EquipmentSlot.MAINHAND, bufferAxe);
            return;
        }

        ItemStack chestAxe = takeOneByItem(chestInventory, Items.WOODEN_AXE);
        if (!chestAxe.isEmpty()) {
            this.guard.equipStack(EquipmentSlot.MAINHAND, chestAxe);
            chestInventory.markDirty();
        }
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

    private ItemStack takeOneByItem(Inventory inventory, Item item) {
        if (inventory == null) {
            return ItemStack.EMPTY;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }

            ItemStack split = stack.split(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            return split;
        }

        return ItemStack.EMPTY;
    }

    private Inventory resolveChestInventory(ServerWorld world) {
        return resolveChestInventoryForGuard(world, this.guard);
    }

    private static ItemStack insertIntoInventoryStatic(Inventory inventory, ItemStack stack) {
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

    private static void addToBufferStatic(LumberjackGuardEntity guard, ItemStack incoming) {
        List<ItemStack> buffer = guard.getGatheredStackBuffer();
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

    private static int countMatchingStatic(Inventory inventory, java.util.function.Predicate<ItemStack> predicate) {
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

    private static int countMatchingStatic(List<ItemStack> stacks, java.util.function.Predicate<ItemStack> predicate) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countByItemStatic(Inventory inventory, Item item) {
        return countMatchingStatic(inventory, stack -> stack.isOf(item));
    }

    private static int countByItemStatic(List<ItemStack> stacks, Item item) {
        return countMatchingStatic(stacks, stack -> stack.isOf(item));
    }

    private static boolean consumeMatchingStatic(Inventory inventory, List<ItemStack> buffer, java.util.function.Predicate<ItemStack> predicate, int amount) {
        int remaining = consumeFromInventoryStatic(inventory, predicate, amount);
        remaining = consumeFromBufferStatic(buffer, predicate, remaining);
        return remaining <= 0;
    }

    private static int consumeFromInventoryStatic(Inventory inventory, java.util.function.Predicate<ItemStack> predicate, int amount) {
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

    private static int consumeFromBufferStatic(List<ItemStack> buffer, java.util.function.Predicate<ItemStack> predicate, int amount) {
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

    private static ItemStack takeOneByItemStatic(List<ItemStack> stacks, Item item) {
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
