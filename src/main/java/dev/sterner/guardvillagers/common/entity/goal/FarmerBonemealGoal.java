package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.villager.behavior.FarmerBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.Fertilizable;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class FarmerBonemealGoal extends Goal {
    private static final double MOVE_SPEED = 0.6D;
    private static final double CHEST_REACH_SQ = 3.5D * 3.5D;
    private static final double TARGET_REACH_SQ = 2.5D * 2.5D;
    private static final int PATH_RETRY_TICKS = 20;
    private static final long BONEMEAL_COOLDOWN_TICKS = 24000L;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private Stage stage = Stage.IDLE;
    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private BlockPos currentNavTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private long nextUseTick;
    private int actionsThisRun;
    private FarmerHarvestGoal harvestGoal;

    private enum Stage { IDLE, FETCH_FROM_CHEST, MOVE_TO_TARGET, APPLY, RETURN_TO_CHEST, DONE }

    public FarmerBonemealGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos != null ? chestPos.toImmutable() : null;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos != null ? chestPos.toImmutable() : null;
        this.targets.clear();
        this.currentTarget = null;
        this.stage = Stage.IDLE;
        this.nextUseTick = 0L;
        this.actionsThisRun = 0;
    }

    public void setHarvestGoal(FarmerHarvestGoal harvestGoal) {
        this.harvestGoal = harvestGoal;
    }

    @Override
    public boolean canStart() {
        if (!GuardVillagersConfig.farmerBonemealEnabled || !villager.isAlive() || jobPos == null || chestPos == null) return false;
        if (!(villager.getWorld() instanceof ServerWorld world) || !world.isDay()) return false;
        if (harvestGoal != null && harvestGoal.hasPendingPriorityHarvestTarget(world)) return false;
        if (world.getTime() < nextUseTick) return false;

        Optional<Inventory> chestInv = getChestInventory(world);
        if (chestInv.isEmpty() || countBonemeal(chestInv.get()) <= 0) return false;

        targets.clear();
        currentTarget = null;
        actionsThisRun = 0;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return villager.isAlive() && stage != Stage.IDLE && stage != Stage.DONE;
    }

    @Override
    public void start() {
        stage = Stage.FETCH_FROM_CHEST;
        moveTo(chestPos);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        if (villager.getWorld() instanceof ServerWorld world) {
            if (stage == Stage.DONE) {
                nextUseTick = world.getTime() + BONEMEAL_COOLDOWN_TICKS;
            } else if (stage != Stage.IDLE) {
                getChestInventory(world).ifPresent(this::depositAllBonemealFromVillager);
            }
        }
        currentTarget = null;
        targets.clear();
        actionsThisRun = 0;
        stage = Stage.IDLE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case FETCH_FROM_CHEST -> {
                if (!isNear(chestPos, CHEST_REACH_SQ)) {
                    moveTo(chestPos);
                    return;
                }
                Optional<Inventory> chestInv = getChestInventory(world);
                if (chestInv.isEmpty() || countBonemeal(chestInv.get()) <= 0) {
                    stage = Stage.DONE;
                    return;
                }
                takeAllBonemealFromChest(chestInv.get());
                if (countBonemeal(villager.getInventory()) <= 0) {
                    stage = Stage.DONE;
                    return;
                }
                targets.clear();
                targets.addAll(findTargets(world));
                advanceTarget(world);
            }
            case MOVE_TO_TARGET -> {
                if (currentTarget == null) {
                    stage = Stage.RETURN_TO_CHEST;
                    return;
                }
                if (!isValidCropTarget(world, currentTarget)) {
                    advanceTarget(world);
                    return;
                }
                if (isNear(currentTarget, TARGET_REACH_SQ)) {
                    stage = Stage.APPLY;
                } else if (!moveTo(currentTarget)) {
                    advanceTarget(world);
                }
            }
            case APPLY -> {
                if (currentTarget == null) {
                    stage = Stage.RETURN_TO_CHEST;
                    return;
                }
                if (countBonemeal(villager.getInventory()) <= 0) {
                    stage = Stage.DONE;
                    return;
                }
                if (isValidCropTarget(world, currentTarget)) {
                    BlockState beforeBonemealState = world.getBlockState(currentTarget);
                    if (tryApplyBonemeal(world, currentTarget) && consumeOneBonemeal(villager.getInventory())) {
                        FarmerBehavior.onBonemealGrowthApplied(world, currentTarget, beforeBonemealState);
                        actionsThisRun++;
                    }
                }
                if (countBonemeal(villager.getInventory()) <= 0) {
                    stage = Stage.DONE;
                    return;
                }
                advanceTarget(world);
            }
            case RETURN_TO_CHEST -> {
                if (countBonemeal(villager.getInventory()) <= 0) {
                    stage = Stage.DONE;
                    return;
                }
                if (isNear(chestPos, CHEST_REACH_SQ)) {
                    getChestInventory(world).ifPresent(this::depositAllBonemealFromVillager);
                    stage = Stage.DONE;
                } else {
                    moveTo(chestPos);
                }
            }
            case DONE -> {
                nextUseTick = world.getTime() + BONEMEAL_COOLDOWN_TICKS;
                currentTarget = null;
                targets.clear();
                actionsThisRun = 0;
                stage = Stage.IDLE;
            }
            default -> {
            }
        }
    }

    private void advanceTarget(ServerWorld world) {
        while ((currentTarget = targets.pollFirst()) != null) {
            if (isValidCropTarget(world, currentTarget)) {
                stage = Stage.MOVE_TO_TARGET;
                moveTo(currentTarget);
                return;
            }
        }
        stage = countBonemeal(villager.getInventory()) > 0 ? Stage.RETURN_TO_CHEST : Stage.DONE;
    }

    private List<BlockPos> findTargets(ServerWorld world) {
        int radius = Math.max(1, GuardVillagersConfig.farmerBonemealScanRadius);
        List<BlockPos> found = new ArrayList<>();

        for (BlockPos pos : BlockPos.iterate(jobPos.add(-radius, -1, -radius), jobPos.add(radius, 2, radius))) {
            if (isValidCropTarget(world, pos)) {
                found.add(pos.toImmutable());
            }
        }

        found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(villager.getPos())));
        return found;
    }

    static boolean isValidCropTarget(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof CropBlock crop)) return false;
        return !crop.isMature(state) && isFertilizableTarget(world, pos, state);
    }

    private boolean tryApplyBonemeal(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!isFertilizableTarget(world, pos, state)) return false;
        Fertilizable fertilizable = (Fertilizable) state.getBlock();
        fertilizable.grow(world, world.getRandom(), pos, state);
        world.syncWorldEvent(1505, pos, 15);
        return true;
    }

    private static boolean isFertilizableTarget(ServerWorld world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof Fertilizable fertilizable)) return false;
        return fertilizable.isFertilizable(world, pos, state) && fertilizable.canGrow(world, world.random, pos, state);
    }

    static int countBonemeal(Inventory inv) {
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(Items.BONE_MEAL)) total += stack.getCount();
        }
        return total;
    }

    static boolean consumeOneBonemeal(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isOf(Items.BONE_MEAL)) continue;
            stack.decrement(1);
            if (stack.isEmpty()) {
                inv.setStack(i, ItemStack.EMPTY);
            }
            inv.markDirty();
            return true;
        }
        return false;
    }

    private void takeAllBonemealFromChest(Inventory chestInv) {
        Inventory villagerInv = villager.getInventory();
        for (int slot = 0; slot < chestInv.size(); slot++) {
            ItemStack stack = chestInv.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.BONE_MEAL)) continue;

            ItemStack extracted = stack.copy();
            ItemStack remaining = insertStack(villagerInv, extracted);
            int moved = extracted.getCount() - remaining.getCount();
            if (moved <= 0) continue;

            stack.decrement(moved);
            if (stack.isEmpty()) {
                chestInv.setStack(slot, ItemStack.EMPTY);
            }
            chestInv.markDirty();
            villagerInv.markDirty();

            if (!remaining.isEmpty()) {
                return;
            }
        }
    }

    private void depositAllBonemealFromVillager(Inventory chestInv) {
        Inventory villagerInv = villager.getInventory();
        for (int slot = 0; slot < villagerInv.size(); slot++) {
            ItemStack stack = villagerInv.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.BONE_MEAL)) continue;

            ItemStack original = stack.copy();
            ItemStack remaining = insertStack(chestInv, original);
            int moved = original.getCount() - remaining.getCount();
            if (moved <= 0) continue;

            stack.decrement(moved);
            if (stack.isEmpty()) {
                villagerInv.setStack(slot, ItemStack.EMPTY);
            }
            chestInv.markDirty();
            villagerInv.markDirty();
        }
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

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        Inventory inv = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        return Optional.ofNullable(inv);
    }

    private boolean moveTo(BlockPos target) {
        if (target == null) return false;
        long now = villager.getWorld().getTime();
        boolean shouldPath = !target.equals(currentNavTarget)
                || villager.getNavigation().isIdle()
                || now - lastPathRequestTick >= PATH_RETRY_TICKS;
        if (!shouldPath) return true;
        boolean started = villager.getNavigation().startMovingTo(
                target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        if (started) {
            currentNavTarget = target.toImmutable();
            lastPathRequestTick = now;
        }
        return started;
    }

    private boolean isNear(BlockPos target, double reachSq) {
        return target != null
                && villager.squaredDistanceTo(
                target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= reachSq;
    }
}
