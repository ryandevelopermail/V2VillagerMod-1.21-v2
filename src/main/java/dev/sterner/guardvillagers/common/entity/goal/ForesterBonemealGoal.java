package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Fertilizable;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class ForesterBonemealGoal extends Goal {
    private static final int MIN_PLANT_DISTANCE = 24;
    private static final int MAX_PLANT_DISTANCE = 80;
    private static final int SCAN_Y_RANGE = 10;
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

    public ForesterBonemealGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
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
        if (!GuardVillagersConfig.foresterBonemealEnabled || !villager.isAlive() || jobPos == null || chestPos == null) return false;
        if (!(villager.getWorld() instanceof ServerWorld world) || !world.isDay()) return false;

        Optional<Inventory> chestInv = getChestInventory(world);
        if (chestInv.isEmpty() || countBonemeal(chestInv.get()) <= 0) return false;

        List<BlockPos> found = findSaplingTargets(world);
        if (found.isEmpty()) return false;
        targets.clear();
        targets.addAll(found);
        actionsThisRun = 0;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return villager.isAlive()
                && actionsThisRun < GuardVillagersConfig.foresterBonemealMaxApplicationsPerSession
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

        if (!isValidSaplingTarget(world, currentTarget)) {
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

        if (tryApplyBonemeal(world, currentTarget) && consumeOneBonemeal(chestInv.get())) {
            actionsThisRun++;
        }

        if (actionsThisRun >= GuardVillagersConfig.foresterBonemealMaxApplicationsPerSession) {
            currentTarget = null;
            return;
        }

        if (countBonemeal(chestInv.get()) <= 0) {
            currentTarget = null;
            return;
        }

        advanceTarget(world);
    }

    private void advanceTarget(ServerWorld world) {
        while ((currentTarget = targets.pollFirst()) != null) {
            if (isValidSaplingTarget(world, currentTarget)) {
                moveTo(currentTarget);
                return;
            }
        }
    }

    private List<BlockPos> findSaplingTargets(ServerWorld world) {
        BlockPos center = VillageAnchorState.get(world.getServer())
                .getNearestQmChest(world, villager.getBlockPos(), 128)
                .orElse(jobPos);

        int configuredMax = Math.max(MIN_PLANT_DISTANCE, GuardVillagersConfig.foresterBonemealScanRadius);
        int maxRing = Math.min(configuredMax, MAX_PLANT_DISTANCE);
        int maxTargets = GuardVillagersConfig.foresterBonemealMaxApplicationsPerSession;
        List<BlockPos> found = new ArrayList<>();

        outer:
        for (int ring = MIN_PLANT_DISTANCE; ring <= maxRing; ring += 4) {
            int ringMax = Math.min(ring + 3, maxRing);
            for (BlockPos candidate : BlockPos.iterate(
                    center.add(-ringMax, -SCAN_Y_RANGE, -ringMax),
                    center.add(ringMax, SCAN_Y_RANGE, ringMax))) {
                double dx = candidate.getX() - center.getX();
                double dz = candidate.getZ() - center.getZ();
                double horizDist = Math.sqrt(dx * dx + dz * dz);
                if (horizDist < ring || horizDist > ringMax) continue;
                if (!isValidSaplingTarget(world, candidate)) continue;
                found.add(candidate.toImmutable());
                if (found.size() >= maxTargets) break outer;
            }
        }
        return found;
    }

    static boolean isValidSaplingTarget(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isIn(BlockTags.SAPLINGS) && isFertilizableTarget(world, pos, state);
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
