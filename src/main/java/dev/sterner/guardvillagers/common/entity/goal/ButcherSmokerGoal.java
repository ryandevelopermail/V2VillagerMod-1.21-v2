package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.recipe.RecipeType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class ButcherSmokerGoal extends Goal {
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int CHECK_INTERVAL_TICKS = 20;

    private final ButcherGuardEntity guard;
    private long nextCheckTime;
    private Stage stage = Stage.IDLE;

    private enum Stage {
        IDLE,
        MOVE_TO_CHEST,
        MOVE_TO_SMOKER,
        DONE
    }

    public ButcherSmokerGoal(ButcherGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (guard.getTarget() != null || guard.isEating() || guard.isBlocking()) {
            return false;
        }
        if (world.getTime() < nextCheckTime) {
            return false;
        }
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        return hasTransferableItems(world);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && guard.isAlive() && guard.getTarget() == null;
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_CHEST;
        moveTo(guard.getPairedChestPos());
    }

    @Override
    public void stop() {
        stage = Stage.DONE;
        guard.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.MOVE_TO_CHEST) {
            if (!hasTransferableItems(world)) {
                stage = Stage.DONE;
                return;
            }
            if (isNear(guard.getPairedChestPos())) {
                stage = Stage.MOVE_TO_SMOKER;
                moveTo(guard.getPairedSmokerPos());
            } else if (guard.getNavigation().isIdle()) {
                moveTo(guard.getPairedChestPos());
            }
            return;
        }

        if (stage == Stage.MOVE_TO_SMOKER) {
            if (!hasTransferableItems(world)) {
                stage = Stage.DONE;
                return;
            }
            if (isNear(guard.getPairedSmokerPos())) {
                transferItems(world);
                stage = Stage.DONE;
            } else if (guard.getNavigation().isIdle()) {
                moveTo(guard.getPairedSmokerPos());
            }
        }
    }

    private boolean hasTransferableItems(ServerWorld world) {
        BlockPos chestPos = guard.getPairedChestPos();
        BlockPos smokerPos = guard.getPairedSmokerPos();
        if (chestPos == null || smokerPos == null) {
            return false;
        }
        Inventory chestInventory = getChestInventory(world, chestPos);
        if (chestInventory == null) {
            return false;
        }
        if (getSmokerInventory(world, smokerPos).isEmpty()) {
            return false;
        }
        return findBestMeat(world, chestInventory).isPresent() && findBestFuel(chestInventory).isPresent();
    }

    private void transferItems(ServerWorld world) {
        BlockPos chestPos = guard.getPairedChestPos();
        BlockPos smokerPos = guard.getPairedSmokerPos();
        if (chestPos == null || smokerPos == null) {
            return;
        }
        Inventory chestInventory = getChestInventory(world, chestPos);
        Optional<SmokerBlockEntity> smokerOpt = getSmokerInventory(world, smokerPos);
        if (chestInventory == null || smokerOpt.isEmpty()) {
            return;
        }
        SmokerBlockEntity smoker = smokerOpt.get();
        ItemStack meatStack = extractBestMeat(world, chestInventory);
        if (!meatStack.isEmpty()) {
            ItemStack remaining = insertIntoSmoker(smoker, meatStack, 0);
            if (!remaining.isEmpty()) {
                insertIntoInventory(chestInventory, remaining);
            }
        }
        ItemStack fuelStack = extractBestFuel(chestInventory);
        if (!fuelStack.isEmpty()) {
            ItemStack remaining = insertIntoSmoker(smoker, fuelStack, 1);
            if (!remaining.isEmpty()) {
                insertIntoInventory(chestInventory, remaining);
            }
        }
        chestInventory.markDirty();
        smoker.markDirty();
    }

    private Optional<ItemStack> findBestMeat(ServerWorld world, Inventory inventory) {
        return getSortedStacks(inventory, stack -> isSmokable(world, stack), false)
                .stream()
                .findFirst();
    }

    private Optional<ItemStack> findBestFuel(Inventory inventory) {
        return getSortedStacks(inventory, this::isFuel, true)
                .stream()
                .findFirst();
    }

    private ItemStack extractBestMeat(ServerWorld world, Inventory inventory) {
        return extractBestStack(inventory, stack -> isSmokable(world, stack), false);
    }

    private ItemStack extractBestFuel(Inventory inventory) {
        return extractBestStack(inventory, this::isFuel, true);
    }

    private List<ItemStack> getSortedStacks(Inventory inventory, java.util.function.Predicate<ItemStack> predicate, boolean fuel) {
        return inventoryToList(inventory).stream()
                .filter(stack -> !stack.isEmpty() && predicate.test(stack))
                .sorted(stackComparator(fuel))
                .toList();
    }

    private List<ItemStack> inventoryToList(Inventory inventory) {
        return java.util.stream.IntStream.range(0, inventory.size())
                .mapToObj(inventory::getStack)
                .filter(stack -> !stack.isEmpty())
                .toList();
    }

    private ItemStack extractBestStack(Inventory inventory, java.util.function.Predicate<ItemStack> predicate, boolean fuel) {
        int bestSlot = -1;
        ItemStack bestStack = ItemStack.EMPTY;
        Comparator<ItemStack> comparator = stackComparator(fuel);
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            if (bestSlot == -1 || comparator.compare(stack, bestStack) < 0) {
                bestSlot = slot;
                bestStack = stack;
            }
        }
        if (bestSlot == -1) {
            return ItemStack.EMPTY;
        }
        ItemStack extracted = inventory.getStack(bestSlot);
        inventory.setStack(bestSlot, ItemStack.EMPTY);
        return extracted;
    }

    private Comparator<ItemStack> stackComparator(boolean fuel) {
        Comparator<ItemStack> comparator = Comparator.<ItemStack>comparingInt(ItemStack::getCount).reversed();
        if (fuel) {
            comparator = comparator.thenComparingInt(this::fuelPriority);
        }
        return comparator;
    }

    private int fuelPriority(ItemStack stack) {
        if (stack.isOf(Items.LAVA_BUCKET)) {
            return 0;
        }
        if (stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL)) {
            return 1;
        }
        if (stack.isIn(ItemTags.LOGS) || stack.isIn(ItemTags.PLANKS) || stack.isIn(ItemTags.LOGS_THAT_BURN)) {
            return 2;
        }
        return 3;
    }

    private boolean isSmokable(ServerWorld world, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        SimpleInventory inventory = new SimpleInventory(1);
        inventory.setStack(0, stack.copy());
        return world.getRecipeManager().getFirstMatch(RecipeType.SMOKING, inventory, world).isPresent();
    }

    private boolean isFuel(ItemStack stack) {
        return AbstractFurnaceBlockEntity.canUseAsFuel(stack);
    }

    private ItemStack insertIntoSmoker(SmokerBlockEntity smoker, ItemStack stack, int slot) {
        ItemStack existing = smoker.getStack(slot);
        if (existing.isEmpty()) {
            smoker.setStack(slot, stack.copy());
            return ItemStack.EMPTY;
        }
        if (!ItemStack.areItemsAndComponentsEqual(existing, stack)) {
            return stack;
        }
        int maxStack = Math.min(existing.getMaxCount(), smoker.getMaxCountPerStack());
        int space = maxStack - existing.getCount();
        if (space <= 0) {
            return stack;
        }
        int toMove = Math.min(space, stack.getCount());
        existing.increment(toMove);
        ItemStack remaining = stack.copy();
        remaining.decrement(toMove);
        return remaining;
    }

    private ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                inventory.setStack(slot, remaining);
                return ItemStack.EMPTY;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }
            int maxStack = Math.min(existing.getMaxCount(), inventory.getMaxCountPerStack());
            int space = maxStack - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int toMove = Math.min(space, remaining.getCount());
            existing.increment(toMove);
            remaining.decrement(toMove);
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return remaining;
    }

    private Optional<SmokerBlockEntity> getSmokerInventory(ServerWorld world, BlockPos smokerPos) {
        BlockEntity blockEntity = world.getBlockEntity(smokerPos);
        if (blockEntity instanceof SmokerBlockEntity smoker) {
            return Optional.of(smoker);
        }
        return Optional.empty();
    }

    private Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private void moveTo(BlockPos pos) {
        if (pos == null) {
            return;
        }
        guard.getNavigation().startMovingTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return guard.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }
}
