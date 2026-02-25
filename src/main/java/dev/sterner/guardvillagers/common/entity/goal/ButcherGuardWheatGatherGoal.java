package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class ButcherGuardWheatGatherGoal extends Goal {
    private static final double MOVE_SPEED = 0.65D;
    private static final int CHECK_INTERVAL_TICKS = 40;
    private static final int GATE_SEARCH_RADIUS = 8;
    private static final int ANIMAL_SEARCH_RADIUS = 18;
    private static final double REACH_SQUARED = 4.0D;
    private static final long HERD_WAIT_TICKS = 100L;

    private final ButcherGuardEntity guard;
    private long nextCheckTime;
    private Stage stage = Stage.IDLE;

    private BlockPos bannerPos;
    private BlockPos gatePos;
    private ItemStack wheatStack = ItemStack.EMPTY;
    private long herdWaitStartTick;

    private enum Stage {
        IDLE,
        MOVE_TO_CHEST,
        WANDER_TO_ANIMALS,
        LEAD_TO_BANNER,
        WAIT_FOR_HERD,
        EXIT_AND_CLOSE,
        DONE
    }

    public ButcherGuardWheatGatherGoal(ButcherGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!guard.isAlive() || guard.getTarget() != null || guard.isEating() || guard.isBlocking()) {
            return false;
        }
        if (world.getTime() < nextCheckTime) {
            return false;
        }
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;

        bannerPos = guard.getPairedBannerPos();
        if (bannerPos == null) {
            return false;
        }
        if (!(world.getBlockState(bannerPos).getBlock() instanceof AbstractBannerBlock)) {
            return false;
        }

        gatePos = findNearestGate(world, bannerPos);
        if (gatePos == null) {
            return false;
        }

        Inventory chestInventory = getChestInventory(world);
        return chestInventory != null && hasWheat(chestInventory) && hasWheatAnimalsNearby(world);
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_CHEST;
        moveTo(guard.getPairedChestPos());
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && guard.isAlive() && guard.getTarget() == null;
    }

    @Override
    public void stop() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return;
        }
        unequipWheat(world);
        closeGateIfOpen(world, gatePos);
        resetState();
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case MOVE_TO_CHEST -> tickMoveToChest(world);
            case WANDER_TO_ANIMALS -> tickWanderToAnimals(world);
            case LEAD_TO_BANNER -> tickLeadToBanner(world);
            case WAIT_FOR_HERD -> tickWaitForHerd(world);
            case EXIT_AND_CLOSE -> tickExitAndClose(world);
            default -> {
            }
        }
    }

    private void tickMoveToChest(ServerWorld world) {
        if (!isNear(guard.getPairedChestPos())) {
            if (guard.getNavigation().isIdle()) {
                moveTo(guard.getPairedChestPos());
            }
            return;
        }

        if (!equipWheat(world)) {
            stage = Stage.DONE;
            return;
        }

        AnimalEntity nearest = findNearestWheatAnimal(world);
        if (nearest == null) {
            stage = Stage.DONE;
            return;
        }

        stage = Stage.WANDER_TO_ANIMALS;
        moveTo(nearest.getBlockPos());
    }

    private void tickWanderToAnimals(ServerWorld world) {
        if (!guard.getOffHandStack().isOf(Items.WHEAT)) {
            stage = Stage.DONE;
            return;
        }

        AnimalEntity nearest = findNearestWheatAnimal(world);
        if (nearest != null && guard.squaredDistanceTo(nearest) > 16.0D) {
            moveTo(nearest.getBlockPos());
            return;
        }

        openGateIfClosed(world, gatePos);
        stage = Stage.LEAD_TO_BANNER;
        moveTo(bannerPos);
    }

    private void tickLeadToBanner(ServerWorld world) {
        if (!guard.getOffHandStack().isOf(Items.WHEAT)) {
            stage = Stage.DONE;
            return;
        }

        openGateIfClosed(world, gatePos);

        if (!isNear(bannerPos)) {
            if (guard.getNavigation().isIdle()) {
                moveTo(bannerPos);
            }
            return;
        }

        stage = Stage.WAIT_FOR_HERD;
        herdWaitStartTick = world.getTime();
        guard.getNavigation().stop();
    }

    private void tickWaitForHerd(ServerWorld world) {
        openGateIfClosed(world, gatePos);

        if (world.getTime() - herdWaitStartTick < HERD_WAIT_TICKS && hasWheatAnimalsNearby(world)) {
            return;
        }

        unequipWheat(world);
        stage = Stage.EXIT_AND_CLOSE;
        moveTo(exitPointFromGate());
    }

    private void tickExitAndClose(ServerWorld world) {
        BlockPos exitPos = exitPointFromGate();
        if (!isNear(exitPos)) {
            if (guard.getNavigation().isIdle()) {
                moveTo(exitPos);
            }
            return;
        }

        closeGateIfOpen(world, gatePos);
        stage = Stage.DONE;
    }

    private void resetState() {
        stage = Stage.DONE;
        bannerPos = null;
        gatePos = null;
        wheatStack = ItemStack.EMPTY;
        herdWaitStartTick = 0L;
        guard.getNavigation().stop();
    }

    private boolean equipWheat(ServerWorld world) {
        if (guard.getOffHandStack().isOf(Items.WHEAT)) {
            wheatStack = guard.getOffHandStack().copy();
            return true;
        }

        Inventory chestInventory = getChestInventory(world);
        if (chestInventory == null) {
            return false;
        }

        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.WHEAT)) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            chestInventory.markDirty();
            if (!guard.getOffHandStack().isEmpty()) {
                ItemStack remainder = insertIntoInventory(chestInventory, guard.getOffHandStack().copy());
                if (!remainder.isEmpty()) {
                    return false;
                }
            }
            guard.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, extracted);
            wheatStack = extracted.copy();
            return true;
        }

        return false;
    }

    private void unequipWheat(ServerWorld world) {
        if (!guard.getOffHandStack().isOf(Items.WHEAT)) {
            return;
        }

        Inventory chestInventory = getChestInventory(world);
        ItemStack offhand = guard.getOffHandStack().copy();
        guard.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        if (chestInventory != null) {
            ItemStack remainder = insertIntoInventory(chestInventory, offhand);
            if (!remainder.isEmpty()) {
                guard.dropStack(remainder);
            }
            chestInventory.markDirty();
        } else {
            guard.dropStack(offhand);
        }
    }

    private Inventory getChestInventory(ServerWorld world) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            return null;
        }
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private boolean hasWheat(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(Items.WHEAT)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasWheatAnimalsNearby(ServerWorld world) {
        return findNearestWheatAnimal(world) != null;
    }

    private AnimalEntity findNearestWheatAnimal(ServerWorld world) {
        Box searchBox = guard.getBoundingBox().expand(ANIMAL_SEARCH_RADIUS);
        List<AnimalEntity> animals = world.getEntitiesByClass(AnimalEntity.class, searchBox,
                animal -> animal.isAlive() && animal.canEat() && animal.isBreedingItem(Items.WHEAT.getDefaultStack()));
        animals.sort(Comparator.comparingDouble(guard::squaredDistanceTo));
        return animals.isEmpty() ? null : animals.get(0);
    }

    private BlockPos findNearestGate(ServerWorld world, BlockPos center) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (int x = center.getX() - GATE_SEARCH_RADIUS; x <= center.getX() + GATE_SEARCH_RADIUS; x++) {
            for (int y = center.getY() - 3; y <= center.getY() + 3; y++) {
                for (int z = center.getZ() - GATE_SEARCH_RADIUS; z <= center.getZ() + GATE_SEARCH_RADIUS; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!(state.getBlock() instanceof FenceGateBlock)) {
                        continue;
                    }
                    double dist = center.getSquaredDistance(pos);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = pos.toImmutable();
                    }
                }
            }
        }
        return nearest;
    }

    private void openGateIfClosed(ServerWorld world, BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return;
        }
        if (!state.get(FenceGateBlock.OPEN)) {
            world.setBlockState(pos, state.with(FenceGateBlock.OPEN, true), 2);
        }
    }

    private void closeGateIfOpen(ServerWorld world, BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return;
        }
        if (state.get(FenceGateBlock.OPEN)) {
            world.setBlockState(pos, state.with(FenceGateBlock.OPEN, false), 2);
        }
    }

    private BlockPos exitPointFromGate() {
        if (gatePos == null || bannerPos == null) {
            return guard.getBlockPos();
        }
        Vec3d away = Vec3d.of(gatePos).subtract(Vec3d.of(bannerPos));
        if (away.lengthSquared() < 0.001D) {
            away = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        away = away.normalize().multiply(2.0D);
        return BlockPos.ofFloored(gatePos.getX() + 0.5D + away.x, gatePos.getY(), gatePos.getZ() + 0.5D + away.z);
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
        return guard.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= REACH_SQUARED;
    }
}
