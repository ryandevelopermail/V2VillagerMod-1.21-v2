package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class MoreVillagersOceanographerSugarCaneGoal extends Goal {
    private static final Identifier OCEANOGRAPHER_PROFESSION_ID = Identifier.of("morevillagers", "oceanographer");
    private static final int SCAN_RADIUS = 32;
    private static final int SCAN_Y_RANGE = 4;
    private static final int CHECK_INTERVAL_TICKS = 200;
    private static final int PATH_RETRY_INTERVAL_TICKS = 20;
    private static final int MIN_HARVEST_HEIGHT = 3;
    private static final int TARGET_BOOTSTRAP_CANE_BASES = 4;
    private static final int SUGAR_CANE_BOOTSTRAP_RESERVE = 4;
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private BlockPos currentTarget;
    private BlockPos currentNavigationTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private long nextCheckTime;
    private boolean immediateCheckPending;
    private boolean plantingRun;

    public MoreVillagersOceanographerSugarCaneGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
        this.currentTarget = null;
        this.currentNavigationTarget = null;
        this.lastPathRequestTick = Long.MIN_VALUE;
    }

    public void requestImmediateCheck() {
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay() || !villager.isAlive() || !matchesProfession()) {
            return false;
        }
        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }
        immediateCheckPending = false;
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;

        Optional<BlockPos> harvestTarget = findHarvestTarget(world);
        if (harvestTarget.isPresent()) {
            currentTarget = harvestTarget.get();
            plantingRun = false;
            return true;
        }

        if (countSugarCaneBases(world) >= TARGET_BOOTSTRAP_CANE_BASES) {
            return false;
        }

        Optional<Inventory> chestInventory = getChestInventory(world);
        if (chestInventory.isEmpty() || countSugarCane(chestInventory.get()) <= SUGAR_CANE_BOOTSTRAP_RESERVE) {
            return false;
        }

        Optional<BlockPos> plantTarget = findBootstrapPlantTarget(world);
        if (plantTarget.isEmpty()) {
            return false;
        }
        currentTarget = plantTarget.get();
        plantingRun = true;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return villager.isAlive() && stage != Stage.IDLE && stage != Stage.DONE;
    }

    @Override
    public void start() {
        stage = plantingRun ? Stage.GO_TO_CHEST : Stage.GO_TO_TARGET;
        moveTo(plantingRun ? chestPos : currentTarget);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        if (villager.getWorld() instanceof ServerWorld world && stage != Stage.IDLE && stage != Stage.DONE) {
            getChestInventory(world).ifPresent(this::depositSugarCaneFromVillager);
        }
        currentTarget = null;
        stage = Stage.IDLE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case GO_TO_CHEST -> {
                if (!isNear(chestPos)) {
                    moveTo(chestPos);
                    return;
                }
                Optional<Inventory> chestInventory = getChestInventory(world);
                if (chestInventory.isEmpty() || !takeOneBootstrapCane(chestInventory.get())) {
                    stage = Stage.DONE;
                    return;
                }
                stage = Stage.GO_TO_TARGET;
                moveTo(currentTarget);
            }
            case GO_TO_TARGET -> {
                if (currentTarget == null) {
                    stage = Stage.DONE;
                    return;
                }
                if (!isNear(currentTarget)) {
                    moveTo(currentTarget);
                    return;
                }
                stage = plantingRun ? Stage.PLANT : Stage.HARVEST;
            }
            case HARVEST -> {
                if (currentTarget != null) {
                    int harvested = harvestColumn(world, currentTarget);
                    if (harvested > 0) {
                        insertIntoVillagerInventory(new ItemStack(Items.SUGAR_CANE, harvested));
                    }
                }
                stage = Stage.RETURN_TO_CHEST;
                moveTo(chestPos);
            }
            case PLANT -> {
                if (currentTarget != null && canPlantSugarCaneAt(world, currentTarget) && consumeOneSugarCane(villager.getInventory())) {
                    world.setBlockState(currentTarget, Blocks.SUGAR_CANE.getDefaultState(), Block.NOTIFY_ALL);
                }
                stage = Stage.RETURN_TO_CHEST;
                moveTo(chestPos);
            }
            case RETURN_TO_CHEST -> {
                if (!isNear(chestPos)) {
                    moveTo(chestPos);
                    return;
                }
                getChestInventory(world).ifPresent(this::depositSugarCaneFromVillager);
                stage = Stage.DONE;
            }
            case DONE -> stage = Stage.IDLE;
            case IDLE -> {
            }
        }
    }

    private Optional<BlockPos> findHarvestTarget(ServerWorld world) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos candidate : BlockPos.iterate(
                jobPos.add(-SCAN_RADIUS, -SCAN_Y_RANGE, -SCAN_RADIUS),
                jobPos.add(SCAN_RADIUS, SCAN_Y_RANGE, SCAN_RADIUS))) {
            if (!world.getBlockState(candidate).isOf(Blocks.SUGAR_CANE)) {
                continue;
            }
            if (world.getBlockState(candidate.down()).isOf(Blocks.SUGAR_CANE)) {
                continue;
            }
            if (getSugarCaneHeight(world, candidate) >= MIN_HARVEST_HEIGHT) {
                candidates.add(candidate.toImmutable());
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(villager.getBlockPos())));
    }

    private Optional<BlockPos> findBootstrapPlantTarget(ServerWorld world) {
        for (BlockPos candidate : BlockPos.iterate(
                jobPos.add(-SCAN_RADIUS, -SCAN_Y_RANGE, -SCAN_RADIUS),
                jobPos.add(SCAN_RADIUS, SCAN_Y_RANGE, SCAN_RADIUS))) {
            if (canPlantSugarCaneAt(world, candidate)) {
                return Optional.of(candidate.toImmutable());
            }
        }
        return Optional.empty();
    }

    private boolean canPlantSugarCaneAt(ServerWorld world, BlockPos pos) {
        BlockState stateAtPos = world.getBlockState(pos);
        if (!stateAtPos.isReplaceable()) {
            return false;
        }
        BlockState sugarCaneState = Blocks.SUGAR_CANE.getDefaultState();
        return sugarCaneState.canPlaceAt(world, pos);
    }

    private int getSugarCaneHeight(ServerWorld world, BlockPos basePos) {
        int height = 0;
        BlockPos.Mutable mutable = basePos.mutableCopy();
        while (world.getBlockState(mutable).isOf(Blocks.SUGAR_CANE)) {
            height++;
            mutable.move(0, 1, 0);
        }
        return height;
    }

    private int harvestColumn(ServerWorld world, BlockPos basePos) {
        int height = getSugarCaneHeight(world, basePos);
        if (height < MIN_HARVEST_HEIGHT) {
            return 0;
        }

        int harvested = 0;
        for (int yOffset = height - 1; yOffset >= 1; yOffset--) {
            BlockPos harvestPos = basePos.up(yOffset);
            if (world.getBlockState(harvestPos).isOf(Blocks.SUGAR_CANE)) {
                world.setBlockState(harvestPos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                harvested++;
            }
        }
        return harvested;
    }

    private int countSugarCaneBases(ServerWorld world) {
        int bases = 0;
        for (BlockPos candidate : BlockPos.iterate(
                jobPos.add(-SCAN_RADIUS, -SCAN_Y_RANGE, -SCAN_RADIUS),
                jobPos.add(SCAN_RADIUS, SCAN_Y_RANGE, SCAN_RADIUS))) {
            if (world.getBlockState(candidate).isOf(Blocks.SUGAR_CANE)
                    && !world.getBlockState(candidate.down()).isOf(Blocks.SUGAR_CANE)) {
                bases++;
                if (bases >= TARGET_BOOTSTRAP_CANE_BASES) {
                    return bases;
                }
            }
        }
        return bases;
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, false));
    }

    private boolean takeOneBootstrapCane(Inventory chestInventory) {
        if (countSugarCane(chestInventory) <= SUGAR_CANE_BOOTSTRAP_RESERVE) {
            return false;
        }
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (!stack.isOf(Items.SUGAR_CANE)) {
                continue;
            }
            ItemStack taken = stack.split(1);
            chestInventory.setStack(slot, stack);
            chestInventory.markDirty();
            insertIntoVillagerInventory(taken);
            return true;
        }
        return false;
    }

    private int countSugarCane(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.SUGAR_CANE)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean consumeOneSugarCane(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.SUGAR_CANE)) {
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

    private void insertIntoVillagerInventory(ItemStack stack) {
        ItemStack remaining = insertStack(villager.getInventory(), stack);
        if (!remaining.isEmpty()) {
            villager.dropStack(remaining);
        }
        villager.getInventory().markDirty();
    }

    private void depositSugarCaneFromVillager(Inventory chestInventory) {
        Inventory villagerInventory = villager.getInventory();
        for (int slot = 0; slot < villagerInventory.size(); slot++) {
            ItemStack stack = villagerInventory.getStack(slot);
            if (!stack.isOf(Items.SUGAR_CANE)) {
                continue;
            }
            ItemStack remaining = insertStack(chestInventory, stack);
            if (remaining.isEmpty()) {
                villagerInventory.setStack(slot, ItemStack.EMPTY);
            } else {
                villagerInventory.setStack(slot, remaining);
            }
        }
        chestInventory.markDirty();
        villagerInventory.markDirty();
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

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining) || !inventory.isValid(slot, remaining)) {
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

    private boolean moveTo(BlockPos target) {
        if (target == null) {
            return false;
        }

        long currentTick = villager.getWorld().getTime();
        boolean shouldRequestPath = !target.equals(currentNavigationTarget)
                || villager.getNavigation().isIdle()
                || currentTick - lastPathRequestTick >= PATH_RETRY_INTERVAL_TICKS;
        if (!shouldRequestPath) {
            return true;
        }

        boolean started = villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        if (started) {
            currentNavigationTarget = target.toImmutable();
            lastPathRequestTick = currentTick;
        }
        return started;
    }

    private boolean isNear(BlockPos target) {
        return target != null && villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private boolean matchesProfession() {
        return OCEANOGRAPHER_PROFESSION_ID.equals(Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()));
    }

    private enum Stage {
        IDLE,
        GO_TO_CHEST,
        GO_TO_TARGET,
        HARVEST,
        PLANT,
        RETURN_TO_CHEST,
        DONE
    }
}
