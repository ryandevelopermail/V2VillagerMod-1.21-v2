package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;

public class FarmerHarvestGoal extends Goal {
    private static final int HARVEST_RADIUS = 50;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int TARGET_TIMEOUT_TICKS = 200;
    private static final Logger LOGGER = LoggerFactory.getLogger(FarmerHarvestGoal.class);

    private final VillagerEntity villager;
    private final Deque<BlockPos> harvestTargets = new ArrayDeque<>();

    private BlockPos jobPos;
    private BlockPos chestPos;
    private boolean enabled;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long lastHarvestDay = -1L;
    private boolean dailyHarvestRun;
    private FarmerCraftingGoal craftingGoal;
    private BlockPos currentTarget;
    private long currentTargetStartTick;

    public FarmerHarvestGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.enabled = true;
        this.stage = Stage.IDLE;
        this.harvestTargets.clear();
    }

    public void setCraftingGoal(FarmerCraftingGoal craftingGoal) {
        this.craftingGoal = craftingGoal;
    }

    @Override
    public boolean canStart() {
        if (!enabled || !villager.isAlive() || jobPos == null || chestPos == null) {
            return false;
        }
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (world.getTime() < nextCheckTime) {
            return false;
        }

        long day = world.getTimeOfDay() / 24000L;
        if (day != lastHarvestDay) {
            lastHarvestDay = day;
            dailyHarvestRun = true;
            nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
            return true;
        }

        int matureCount = countMatureCrops(world);
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        return matureCount >= 1;
    }

    @Override
    public boolean shouldContinue() {
        return enabled && villager.isAlive() && stage != Stage.DONE;
    }

    @Override
    public void start() {
        villager.setCanPickUpLoot(true);
        setStage(Stage.GO_TO_JOB);
        populateHarvestTargets();
        moveTo(jobPos);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        harvestTargets.clear();
        currentTarget = null;
        setStage(Stage.DONE);
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            setStage(Stage.DONE);
            return;
        }

        switch (stage) {
            case GO_TO_JOB -> {
                if (isNear(jobPos)) {
                    setStage(Stage.HARVEST);
                } else {
                    moveTo(jobPos);
                }
            }
            case HARVEST -> {
                if (harvestTargets.isEmpty()) {
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }

                BlockPos target = harvestTargets.peekFirst();
                if (currentTarget == null || !currentTarget.equals(target)) {
                    currentTarget = target;
                    currentTargetStartTick = serverWorld.getTime();
                }

                if (serverWorld.getTime() - currentTargetStartTick >= TARGET_TIMEOUT_TICKS) {
                    harvestTargets.removeFirst();
                    currentTarget = null;
                    return;
                }
                if (!isMatureCrop(serverWorld.getBlockState(target))) {
                    harvestTargets.removeFirst();
                    currentTarget = null;
                    return;
                }

                if (!isNear(target)) {
                    moveTo(target);
                    return;
                }

                BlockState harvestedState = serverWorld.getBlockState(target);
                serverWorld.breakBlock(target, true, villager);
                attemptReplant(serverWorld, target, harvestedState);
                collectNearbyDrops(serverWorld, target);
                harvestTargets.removeFirst();
                currentTarget = null;
            }
            case RETURN_TO_CHEST -> {
                if (isNear(chestPos)) {
                    setStage(Stage.DEPOSIT);
                } else {
                    moveTo(chestPos);
                }
            }
            case DEPOSIT -> {
                if (!isNear(chestPos)) {
                    setStage(Stage.RETURN_TO_CHEST);
                    return;
                }

                depositInventory(serverWorld);
                if (dailyHarvestRun) {
                    notifyDailyHarvestComplete(serverWorld);
                    dailyHarvestRun = false;
                }
                setStage(Stage.DONE);
            }
            case IDLE, DONE -> {
            }
        }
    }

    private void populateHarvestTargets() {
        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        harvestTargets.clear();
        int radius = HARVEST_RADIUS;
        int radiusSquared = radius * radius;
        BlockPos start = jobPos.add(-radius, -radius, -radius);
        BlockPos end = jobPos.add(radius, radius, radius);
        ArrayList<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (pos.getSquaredDistance(jobPos) > radiusSquared) {
                continue;
            }
            BlockState state = serverWorld.getBlockState(pos);
            if (isMatureCrop(state)) {
                targets.add(pos.toImmutable());
            }
        }

        targets.sort(Comparator.comparingDouble(pos -> villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)));
        harvestTargets.addAll(targets);
    }

    private int countMatureCrops(ServerWorld world) {
        int count = 0;
        int radius = HARVEST_RADIUS;
        int radiusSquared = radius * radius;
        BlockPos start = jobPos.add(-radius, -radius, -radius);
        BlockPos end = jobPos.add(radius, radius, radius);
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (pos.getSquaredDistance(jobPos) > radiusSquared) {
                continue;
            }
            if (isMatureCrop(world.getBlockState(pos))) {
                count++;
                if (count > 1) {
                    return count;
                }
            }
        }
        return count;
    }

    private boolean isMatureCrop(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        return false;
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private void notifyDailyHarvestComplete(ServerWorld world) {
        if (craftingGoal != null) {
            craftingGoal.notifyDailyHarvestComplete(world.getTimeOfDay() / 24000L);
        }
    }

    private void attemptReplant(ServerWorld world, BlockPos pos, BlockState harvestedState) {
        if (!(harvestedState.getBlock() instanceof CropBlock crop)) {
            return;
        }

        if (!world.getBlockState(pos).isAir()) {
            return;
        }

        Item seedItem = getSeedItem(crop);
        if (seedItem == null) {
            return;
        }

        Inventory inventory = villager.getInventory();
        if (!consumeSeed(inventory, seedItem) && !consumeSeedFromChest(world, seedItem)) {
            return;
        }

        BlockState replantedState = crop.getDefaultState();
        if (!replantedState.canPlaceAt(world, pos)) {
            return;
        }

        world.setBlockState(pos, replantedState);
    }

    private void collectNearbyDrops(ServerWorld world, BlockPos pos) {
        Box box = new Box(pos).expand(2.0D);
        for (ItemEntity itemEntity : world.getEntitiesByClass(ItemEntity.class, box, entity -> entity.isAlive() && !entity.getStack().isEmpty())) {
            pickupItemEntity(itemEntity);
        }
    }

    private void pickupItemEntity(ItemEntity itemEntity) {
        ItemStack remaining = insertStack(villager.getInventory(), itemEntity.getStack());
        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setStack(remaining);
        }
    }

    private Item getSeedItem(CropBlock crop) {
        Block block = crop;
        if (block == Blocks.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (block == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (block == Blocks.POTATOES) {
            return Items.POTATO;
        }
        if (block == Blocks.BEETROOTS) {
            return Items.BEETROOT_SEEDS;
        }
        return null;
    }

    private boolean consumeSeed(Inventory inventory, Item seedItem) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || stack.getItem() != seedItem) {
                continue;
            }

            stack.decrement(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            inventory.markDirty();
            return true;
        }
        return false;
    }

    private boolean consumeSeedFromChest(ServerWorld world, Item seedItem) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return false;
        }
        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        if (chestInventory == null) {
            return false;
        }
        return consumeSeed(chestInventory, seedItem);
    }

    private void depositInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return;
        }

        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        if (chestInventory == null) {
            return;
        }

        Inventory villagerInventory = villager.getInventory();
        for (int i = 0; i < villagerInventory.size(); i++) {
            ItemStack stack = villagerInventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack remaining = insertStack(chestInventory, stack);
            villagerInventory.setStack(i, remaining);
        }

        villagerInventory.markDirty();
        chestInventory.markDirty();
    }

    private ItemStack insertStack(Inventory inventory, ItemStack stack) {
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

    private void setStage(Stage newStage) {
        if (stage == newStage) {
            return;
        }
        stage = newStage;
        LOGGER.info("Farmer {} entering harvest stage {}", villager.getUuidAsString(), newStage);
    }

    private enum Stage {
        IDLE,
        GO_TO_JOB,
        HARVEST,
        RETURN_TO_CHEST,
        DEPOSIT,
        DONE
    }
}
