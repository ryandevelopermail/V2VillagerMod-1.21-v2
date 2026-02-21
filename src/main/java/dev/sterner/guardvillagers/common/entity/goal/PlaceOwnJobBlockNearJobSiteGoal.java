package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.ProfessionJobBlockHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;

public class PlaceOwnJobBlockNearJobSiteGoal extends Goal {
    private static final int SEARCH_RADIUS = 20;
    private static final int MAX_VERTICAL_SEARCH = 4;
    private static final int NEARBY_DUPLICATE_RADIUS = 3;
    private static final long ATTEMPT_COOLDOWN_TICKS = 400L;
    private static final double MOVE_SPEED = 0.8D;
    private static final double PLACE_REACH_SQUARED = 4.0D;

    private final VillagerEntity villager;
    private final BlockPos jobPos;
    private long cooldownUntilTick;
    private BlockPos targetPos;
    private int stackSlot = -1;

    public PlaceOwnJobBlockNearJobSiteGoal(VillagerEntity villager, BlockPos jobPos) {
        this.villager = villager;
        this.jobPos = jobPos.toImmutable();
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world) || !villager.isAlive() || !world.isDay()) {
            return false;
        }

        if (world.getTime() < cooldownUntilTick) {
            return false;
        }

        Optional<GlobalPos> jobSiteMemory = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSiteMemory.isEmpty() || !Objects.equals(jobSiteMemory.get().dimension(), world.getRegistryKey())) {
            return false;
        }

        BlockPos memoryPos = jobSiteMemory.get().pos();
        if (!memoryPos.equals(jobPos)) {
            return false;
        }

        Optional<Block> jobBlock = ProfessionJobBlockHelper.resolveJobBlock(villager.getVillagerData().getProfession(), world.getBlockState(jobPos));
        if (jobBlock.isEmpty() || hasNearbyDuplicate(world, jobBlock.get())) {
            cooldownUntilTick = world.getTime() + ATTEMPT_COOLDOWN_TICKS;
            return false;
        }

        stackSlot = findJobBlockStackSlot(villager.getInventory(), jobBlock.get());
        if (stackSlot < 0) {
            cooldownUntilTick = world.getTime() + ATTEMPT_COOLDOWN_TICKS;
            return false;
        }

        targetPos = findPlacementPos(world, jobBlock.get());
        if (targetPos == null) {
            cooldownUntilTick = world.getTime() + ATTEMPT_COOLDOWN_TICKS;
            return false;
        }

        return true;
    }

    @Override
    public boolean shouldContinue() {
        return targetPos != null && stackSlot >= 0 && villager.isAlive();
    }

    @Override
    public void start() {
        villager.getNavigation().startMovingTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, MOVE_SPEED);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        targetPos = null;
        stackSlot = -1;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world) || targetPos == null) {
            return;
        }

        villager.getLookControl().lookAt(Vec3d.ofCenter(targetPos));
        villager.getNavigation().startMovingTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, MOVE_SPEED);

        if (villager.squaredDistanceTo(Vec3d.ofCenter(targetPos)) > PLACE_REACH_SQUARED) {
            return;
        }

        ItemStack stack = villager.getInventory().getStack(stackSlot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            cooldownUntilTick = world.getTime() + ATTEMPT_COOLDOWN_TICKS;
            stop();
            return;
        }

        BlockState stateToPlace = blockItem.getBlock().getDefaultState();
        if (!canPlaceAt(world, targetPos, stateToPlace)) {
            cooldownUntilTick = world.getTime() + ATTEMPT_COOLDOWN_TICKS;
            stop();
            return;
        }

        world.setBlockState(targetPos, stateToPlace);
        stack.decrement(1);
        cooldownUntilTick = world.getTime() + ATTEMPT_COOLDOWN_TICKS;
        stop();
    }

    private BlockPos findPlacementPos(ServerWorld world, Block jobBlock) {
        for (BlockPos pos : BlockPos.iterateOutwards(jobPos, SEARCH_RADIUS, MAX_VERTICAL_SEARCH, SEARCH_RADIUS)) {
            if (!jobPos.isWithinDistance(pos, SEARCH_RADIUS)) {
                continue;
            }

            BlockState candidateState = world.getBlockState(pos);
            if (!candidateState.isReplaceable()) {
                continue;
            }

            BlockState belowState = world.getBlockState(pos.down());
            if (belowState.isAir()) {
                continue;
            }

            BlockState stateToPlace = jobBlock.getDefaultState();
            if (canPlaceAt(world, pos, stateToPlace)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private boolean hasNearbyDuplicate(ServerWorld world, Block jobBlock) {
        for (BlockPos pos : BlockPos.iterateOutwards(jobPos, NEARBY_DUPLICATE_RADIUS, 2, NEARBY_DUPLICATE_RADIUS)) {
            if (world.getBlockState(pos).isOf(jobBlock)) {
                return true;
            }
        }
        return false;
    }

    private boolean canPlaceAt(ServerWorld world, BlockPos pos, BlockState state) {
        return world.getBlockState(pos).isReplaceable()
                && state.canPlaceAt(world, pos)
                && world.canPlace(state, pos, ShapeContext.absent())
                && world.getBlockState(pos.up()).isAir()
                && world.getBlockState(pos).getFluidState().isEmpty()
                && world.getBlockState(pos.up()).getFluidState().isEmpty();
    }

    private int findJobBlockStackSlot(SimpleInventory inventory, Block block) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }

            if (blockItem.getBlock() == block) {
                return slot;
            }
        }

        return -1;
    }
}
