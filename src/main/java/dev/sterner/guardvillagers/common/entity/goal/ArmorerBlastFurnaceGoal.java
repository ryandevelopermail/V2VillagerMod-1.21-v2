package dev.sterner.guardvillagers.common.entity.goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ArmorerBlastFurnaceGoal extends Goal {
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final TagKey<Item> ORES_TAG = TagKey.of(RegistryKeys.ITEM, Identifier.of("c", "ores"));

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;

    private enum Stage {
        IDLE,
        MOVE_TO_CHEST,
        MOVE_TO_FURNACE,
        DONE
    }

    public ArmorerBlastFurnaceGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
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
                stage = Stage.MOVE_TO_FURNACE;
                moveTo(jobPos);
            } else if (villager.getNavigation().isIdle()) {
                moveTo(chestPos);
            }
            return;
        }

        if (stage == Stage.MOVE_TO_FURNACE) {
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
        Optional<BlastFurnaceBlockEntity> furnaceOpt = getFurnaceInventory(world);
        if (furnaceOpt.isEmpty()) {
            return false;
        }
        BlastFurnaceBlockEntity furnace = furnaceOpt.get();
        ItemStack inputStack = furnace.getStack(0);
        ItemStack outputStack = furnace.getStack(2);
        if (!outputStack.isEmpty() && (inputStack.isEmpty() || hasDifferentOreInChest(world, chestInventory, inputStack))) {
            return true;
        }
        return findBestOre(world, chestInventory).isPresent() && findBestFuel(chestInventory).isPresent();
    }

    private void transferItems(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world);
        Optional<BlastFurnaceBlockEntity> furnaceOpt = getFurnaceInventory(world);
        if (chestInventory == null || furnaceOpt.isEmpty()) {
            return;
        }
        BlastFurnaceBlockEntity furnace = furnaceOpt.get();
        ItemStack inputStack = furnace.getStack(0);
        ItemStack outputStack = furnace.getStack(2);
        Optional<ItemStack> bestOre = findBestOre(world, chestInventory);
        if (!outputStack.isEmpty() && (inputStack.isEmpty() || (bestOre.isPresent() && !ItemStack.areItemsAndComponentsEqual(inputStack, bestOre.get())))) {
            extractFurnaceOutput(chestInventory, furnace);
        }
        ItemStack oreStack = extractBestOre(world, chestInventory);
        if (!oreStack.isEmpty()) {
            ItemStack remaining = insertIntoFurnace(furnace, oreStack, 0);
            if (!remaining.isEmpty()) {
                insertIntoInventory(chestInventory, remaining);
            }
        }
        ItemStack fuelStack = extractBestFuel(chestInventory);
        if (!fuelStack.isEmpty()) {
            ItemStack remaining = insertIntoFurnace(furnace, fuelStack, 1);
            if (!remaining.isEmpty()) {
                insertIntoInventory(chestInventory, remaining);
            }
        }
        chestInventory.markDirty();
        furnace.markDirty();
    }

    private boolean hasDifferentOreInChest(ServerWorld world, Inventory inventory, ItemStack currentInput) {
        return findBestOre(world, inventory)
                .filter(stack -> !ItemStack.areItemsAndComponentsEqual(stack, currentInput))
                .isPresent();
    }

    private Optional<ItemStack> findBestOre(ServerWorld world, Inventory inventory) {
        return inventoryToStream(inventory)
                .filter(stack -> isBlastable(world, stack))
                .sorted(stackComparator(false))
                .findFirst();
    }

    private Optional<ItemStack> findBestFuel(Inventory inventory) {
        return inventoryToStream(inventory)
                .filter(this::isFuel)
                .sorted(stackComparator(true))
                .findFirst();
    }

    private ItemStack extractBestOre(ServerWorld world, Inventory inventory) {
        return extractBestStack(inventory, stack -> isBlastable(world, stack), false);
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

    private boolean isBlastable(ServerWorld world, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.isIn(ORES_TAG)) {
            return true;
        }
        SingleStackRecipeInput input = new SingleStackRecipeInput(stack.copy());
        return world.getRecipeManager().getFirstMatch(RecipeType.BLASTING, input, world).isPresent();
    }

    private ItemStack insertIntoFurnace(BlastFurnaceBlockEntity furnace, ItemStack stack, int slot) {
        ItemStack existing = furnace.getStack(slot);
        if (existing.isEmpty()) {
            furnace.setStack(slot, stack.copy());
            return ItemStack.EMPTY;
        }
        if (!ItemStack.areItemsAndComponentsEqual(existing, stack)) {
            return stack;
        }
        int maxStack = Math.min(existing.getMaxCount(), furnace.getMaxCountPerStack());
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

    private void extractFurnaceOutput(Inventory chestInventory, BlastFurnaceBlockEntity furnace) {
        ItemStack output = furnace.getStack(2);
        if (output.isEmpty()) {
            return;
        }
        ItemStack remaining = insertIntoInventory(chestInventory, output.copy());
        if (remaining.isEmpty()) {
            furnace.setStack(2, ItemStack.EMPTY);
        } else if (remaining.getCount() != output.getCount()) {
            furnace.setStack(2, remaining);
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

    private Optional<BlastFurnaceBlockEntity> getFurnaceInventory(ServerWorld world) {
        BlockEntity blockEntity = world.getBlockEntity(jobPos);
        if (blockEntity instanceof BlastFurnaceBlockEntity furnace) {
            return Optional.of(furnace);
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
