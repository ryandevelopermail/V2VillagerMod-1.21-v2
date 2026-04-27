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
    private static final double TARGET_REACH_SQ = 2.5D * 2.5D;
    private static final int PATH_RETRY_TICKS = 20;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private BlockPos currentNavTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private int actionsThisRun;

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
        this.actionsThisRun = 0;
    }

    @Override
    public boolean canStart() {
        if (!GuardVillagersConfig.farmerBonemealEnabled || !villager.isAlive() || jobPos == null || chestPos == null) return false;
        if (!(villager.getWorld() instanceof ServerWorld world) || !world.isDay()) return false;

        Optional<Inventory> chestInv = getChestInventory(world);
        if (chestInv.isEmpty() || countBonemeal(chestInv.get()) <= 0) return false;

        List<BlockPos> found = findTargets(world);
        if (found.isEmpty()) return false;
        targets.clear();
        targets.addAll(found);
        actionsThisRun = 0;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return villager.isAlive()
                && actionsThisRun < GuardVillagersConfig.farmerBonemealMaxApplicationsPerSession
                && currentTarget != null;
    }

    @Override
    public void start() {
        currentTarget = targets.pollFirst();
        if (currentTarget != null) {
            moveTo(currentTarget);
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        currentTarget = null;
        targets.clear();
        actionsThisRun = 0;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            currentTarget = null;
            return;
        }
        if (!world.isDay() || currentTarget == null) {
            currentTarget = null;
            return;
        }

        if (!isValidCropTarget(world, currentTarget)) {
            advanceTarget(world);
            return;
        }

        if (!isNear(currentTarget, TARGET_REACH_SQ)) {
            if (!moveTo(currentTarget)) {
                advanceTarget(world);
            }
            return;
        }

        Optional<Inventory> chestInv = getChestInventory(world);
        if (chestInv.isEmpty() || countBonemeal(chestInv.get()) <= 0) {
            currentTarget = null;
            return;
        }

        BlockState beforeBonemealState = world.getBlockState(currentTarget);
        if (tryApplyBonemeal(world, currentTarget) && consumeOneBonemeal(chestInv.get())) {
            FarmerBehavior.onBonemealGrowthApplied(world, currentTarget, beforeBonemealState);
            actionsThisRun++;
        }

        if (actionsThisRun >= GuardVillagersConfig.farmerBonemealMaxApplicationsPerSession
                || countBonemeal(chestInv.get()) <= 0) {
            currentTarget = null;
            return;
        }

        advanceTarget(world);
    }

    private void advanceTarget(ServerWorld world) {
        while ((currentTarget = targets.pollFirst()) != null) {
            if (isValidCropTarget(world, currentTarget)) {
                moveTo(currentTarget);
                return;
            }
        }
    }

    private List<BlockPos> findTargets(ServerWorld world) {
        int radius = Math.max(1, GuardVillagersConfig.farmerBonemealScanRadius);
        int maxTargets = GuardVillagersConfig.farmerBonemealMaxApplicationsPerSession;
        List<BlockPos> found = new ArrayList<>();

        for (BlockPos pos : BlockPos.iterate(jobPos.add(-radius, -1, -radius), jobPos.add(radius, 2, radius))) {
            if (isValidCropTarget(world, pos)) {
                found.add(pos.toImmutable());
            }
        }

        found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(villager.getPos())));
        if (found.size() > maxTargets) {
            return new ArrayList<>(found.subList(0, maxTargets));
        }
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
