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
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ArmorerBlastFurnaceGoal extends Goal {
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final TagKey<Item> COMMON_ORES_TAG = TagKey.of(RegistryKeys.ITEM, new Identifier("c", "ores"));
    private static final TagKey<Item> MINECRAFT_ORES_TAG = TagKey.of(RegistryKeys.ITEM, new Identifier("minecraft", "ores"));

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;

    private enum Stage {
        IDLE,
        MOVE_TO_CHEST,
        MOVE_TO_BLAST_FURNACE,
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
                stage = Stage.MOVE_TO_BLAST_FURNACE;
                moveTo(jobPos);
            } else if (villager.getNavigation().isIdle()) {
                moveTo(chestPos);
            }
            return;
        }

        if (stage == Stage.MOVE_TO_BLAST_FURNACE) {
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
        Optional<BlastFurnaceBlockEntity> blastFurnaceOpt = getBlastFurnaceInventory(world);
        if (blastFurnaceOpt.isEmpty()) {
            return false;
        }
        BlastFurnaceBlockEntity blastFurnace = blastFurnaceOpt.get();
        if (!blastFurnace.getStack(2).isEmpty() && blastFurnace.getStack(0).isEmpty()) {
            return true;
        }
        return findBestOre(chestInventory).isPresent() || findBestFuel(chestInventory).isPresent();
    }

    private void transferItems(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world);
        Optional<BlastFurnaceBlockEntity> blastFurnaceOpt = getBlastFurnaceInventory(world);
        if (chestInventory == null || blastFurnaceOpt.isEmpty()) {
            return;
        }
        BlastFurnaceBlockEntity blastFurnace = blastFurnaceOpt.get();
        if (blastFurnace.getStack(0).isEmpty()) {
            extractBlastFurnaceOutput(chestInventory, blastFurnace);
        }
        ItemStack oreStack = extractBestOre(chestInventory);
        if (!oreStack.isEmpty()) {
            ItemStack currentInput = blastFurnace.getStack(0);
            if (!currentInput.isEmpty() && !ItemStack.areItemsAndComponentsEqual(currentInput, oreStack)) {
                extractBlastFurnaceOutput(chestInventory, blastFurnace);
                if (!blastFurnace.getStack(2).isEmpty()) {
                    insertIntoInventory(chestInventory, oreStack);
                    chestInventory.markDirty();
                    return;
                }
            }
            ItemStack remaining = insertIntoBlastFurnace(blastFurnace, oreStack, 0);
            if (!remaining.isEmpty()) {
                insertIntoInventory(chestInventory, remaining);
            }
        }
        ItemStack fuelStack = extractBestFuel(chestInventory);
        if (!fuelStack.isEmpty()) {
            ItemStack remaining = insertIntoBlastFurnace(blastFurnace, fuelStack, 1);
            if (!remaining.isEmpty()) {
                insertIntoInventory(chestInventory, remaining);
            }
        }
        chestInventory.markDirty();
        blastFurnace.markDirty();
    }

    private Optional<ItemStack> findBestOre(Inventory inventory) {
        return inventoryToStream(inventory)
                .filter(this::isOre)
                .sorted(stackComparator())
                .findFirst();
    }

    private Optional<ItemStack> findBestFuel(Inventory inventory) {
        return inventoryToStream(inventory)
                .filter(this::isFuel)
                .sorted(stackComparator())
                .findFirst();
    }

    private ItemStack extractBestOre(Inventory inventory) {
        return extractBestStack(inventory, this::isOre);
    }

    private ItemStack extractBestFuel(Inventory inventory) {
        return extractBestStack(inventory, this::isFuel);
    }

    private java.util.stream.Stream<ItemStack> inventoryToStream(Inventory inventory) {
        return java.util.stream.IntStream.range(0, inventory.size())
                .mapToObj(inventory::getStack)
                .filter(stack -> !stack.isEmpty());
    }

    private ItemStack extractBestStack(Inventory inventory, java.util.function.Predicate<ItemStack> predicate) {
        int bestSlot = -1;
        ItemStack bestStack = ItemStack.EMPTY;
        Comparator<ItemStack> comparator = stackComparator();
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

    private Comparator<ItemStack> stackComparator() {
        return Comparator.<ItemStack>comparingInt(ItemStack::getCount).reversed();
    }

    private boolean isFuel(ItemStack stack) {
        return AbstractFurnaceBlockEntity.canUseAsFuel(stack);
    }

    private boolean isOre(ItemStack stack) {
        return stack.isIn(COMMON_ORES_TAG) || stack.isIn(MINECRAFT_ORES_TAG);
    }

    private ItemStack insertIntoBlastFurnace(BlastFurnaceBlockEntity blastFurnace, ItemStack stack, int slot) {
        ItemStack existing = blastFurnace.getStack(slot);
        if (existing.isEmpty()) {
            blastFurnace.setStack(slot, stack.copy());
            return ItemStack.EMPTY;
        }
        if (!ItemStack.areItemsAndComponentsEqual(existing, stack)) {
            return stack;
        }
        int maxStack = Math.min(existing.getMaxCount(), blastFurnace.getMaxCountPerStack());
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

    private void extractBlastFurnaceOutput(Inventory chestInventory, BlastFurnaceBlockEntity blastFurnace) {
        ItemStack output = blastFurnace.getStack(2);
        if (output.isEmpty()) {
            return;
        }
        ItemStack remaining = insertIntoInventory(chestInventory, output.copy());
        if (remaining.isEmpty()) {
            blastFurnace.setStack(2, ItemStack.EMPTY);
        } else if (remaining.getCount() != output.getCount()) {
            blastFurnace.setStack(2, remaining);
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

    private Optional<BlastFurnaceBlockEntity> getBlastFurnaceInventory(ServerWorld world) {
        BlockEntity blockEntity = world.getBlockEntity(jobPos);
        if (blockEntity instanceof BlastFurnaceBlockEntity blastFurnace) {
            return Optional.of(blastFurnace);
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
