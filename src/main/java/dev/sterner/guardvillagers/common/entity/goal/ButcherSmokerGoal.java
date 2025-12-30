package dev.sterner.guardvillagers.common.entity.goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class ButcherSmokerGoal extends Goal {
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int CHECK_INTERVAL_TICKS = 10;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;

    private enum Stage {
        IDLE,
        MOVE_TO_CHEST,
        MOVE_TO_SMOKER,
        DONE
    }

    public ButcherSmokerGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!villager.isAlive() || villager.isSleeping()) {
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
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_CHEST;
        moveTo(chestPos);
    }

    @Override
    public void stop() {
        stage = Stage.DONE;
        villager.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.MOVE_TO_CHEST) {
            if (!hasTransferableItems(world)) {
                stage = Stage.DONE;
                return;
            }
            if (isNear(chestPos)) {
                stage = Stage.MOVE_TO_SMOKER;
                moveTo(jobPos);
            } else if (villager.getNavigation().isIdle()) {
                moveTo(chestPos);
            }
            return;
        }

        if (stage == Stage.MOVE_TO_SMOKER) {
            if (!hasTransferableItems(world)) {
                stage = Stage.DONE;
                return;
            }
            if (isNear(jobPos)) {
                transferItems(world);
                stage = Stage.DONE;
            } else if (villager.getNavigation().isIdle()) {
                moveTo(jobPos);
            }
        }
    }

    private boolean hasTransferableItems(ServerWorld world) {
        if (jobPos == null || chestPos == null) {
            return false;
        }
        Inventory chestInventory = getChestInventory(world);
        if (chestInventory == null) {
            return false;
        }
        if (getSmokerInventory(world).isEmpty()) {
            return false;
        }
        return findBestMeat(world, chestInventory).isPresent() && findBestFuel(chestInventory).isPresent();
    }

    private void transferItems(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world);
        Optional<SmokerBlockEntity> smokerOpt = getSmokerInventory(world);
        if (chestInventory == null || smokerOpt.isEmpty()) {
            return;
        }
        SmokerBlockEntity smoker = smokerOpt.get();
        extractSmokerOutput(chestInventory, smoker);
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
        return inventoryToStream(inventory)
                .filter(stack -> isSmokable(world, stack))
                .sorted(stackComparator(false))
                .findFirst();
    }

    private Optional<ItemStack> findBestFuel(Inventory inventory) {
        return inventoryToStream(inventory)
                .filter(this::isFuel)
                .sorted(stackComparator(true))
                .findFirst();
    }

    private ItemStack extractBestMeat(ServerWorld world, Inventory inventory) {
        return extractBestStack(inventory, stack -> isSmokable(world, stack), false);
    }

    private ItemStack extractBestFuel(Inventory inventory) {
        return extractBestStack(inventory, this::isFuel, true);
    }

    private java.util.stream.Stream<ItemStack> inventoryToStream(Inventory inventory) {
        return java.util.stream.IntStream.range(0, inventory.size())
                .mapToObj(inventory::getStack)
                .filter(stack -> !stack.isEmpty());
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

    private boolean isFuel(ItemStack stack) {
        return AbstractFurnaceBlockEntity.canUseAsFuel(stack);
    }

    private boolean isSmokable(ServerWorld world, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        SingleStackRecipeInput input = new SingleStackRecipeInput(stack.copy());
        return world.getRecipeManager().getFirstMatch(RecipeType.SMOKING, input, world).isPresent();
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

    private void extractSmokerOutput(Inventory chestInventory, SmokerBlockEntity smoker) {
        ItemStack output = smoker.getStack(2);
        if (output.isEmpty()) {
            return;
        }
        ItemStack remaining = insertIntoInventory(chestInventory, output.copy());
        if (remaining.isEmpty()) {
            smoker.setStack(2, ItemStack.EMPTY);
        } else if (remaining.getCount() != output.getCount()) {
            smoker.setStack(2, remaining);
        }
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

    private Optional<SmokerBlockEntity> getSmokerInventory(ServerWorld world) {
        BlockEntity blockEntity = world.getBlockEntity(jobPos);
        if (blockEntity instanceof SmokerBlockEntity smoker) {
            return Optional.of(smoker);
        }
        return Optional.empty();
    }

    private Inventory getChestInventory(ServerWorld world) {
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
        villager.getNavigation().startMovingTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }
}
