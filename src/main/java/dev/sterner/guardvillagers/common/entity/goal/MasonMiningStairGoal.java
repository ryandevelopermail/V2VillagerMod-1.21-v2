package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.Set;

public class MasonMiningStairGoal extends Goal {
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 1.8D;
    private static final int NO_PROGRESS_LIMIT = 5;
    private static final Set<Block> BREAKABLE_BLOCKS = Set.of(
            Blocks.DIRT,
            Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.PODZOL,
            Blocks.MYCELIUM,
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.ANDESITE,
            Blocks.DIORITE,
            Blocks.GRANITE,
            Blocks.DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE,
            Blocks.TUFF,
            Blocks.CALCITE
    );

    private final MasonGuardEntity guard;
    private Direction miningDirection;
    private BlockPos origin;
    private BlockPos currentStepTarget;
    private int stepIndex;
    private int noProgressTicks;
    private double lastDistanceToTarget = Double.MAX_VALUE;
    private Stage stage = Stage.IDLE;

    public MasonMiningStairGoal(MasonGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }
        if (guard.getTarget() != null || guard.isAttacking()) {
            return false;
        }

        BlockPos jobPos = guard.getPairedJobPos();
        BlockPos chestPos = guard.getPairedChestPos();
        if (jobPos == null || chestPos == null) {
            return false;
        }
        if (!world.isChunkLoaded(jobPos) || !world.isChunkLoaded(chestPos)) {
            return false;
        }
        if (!hasUsableMiningTool()) {
            return false;
        }

        Direction directionFromChest = Direction.getFacing(jobPos.getX() - chestPos.getX(), 0, jobPos.getZ() - chestPos.getZ());
        this.miningDirection = directionFromChest.getAxis().isHorizontal() ? directionFromChest : guard.getHorizontalFacing();
        this.origin = guard.getBlockPos();
        this.stepIndex = 0;
        this.currentStepTarget = computeStepTarget(stepIndex);
        this.noProgressTicks = 0;
        this.lastDistanceToTarget = Double.MAX_VALUE;
        this.stage = Stage.MINING;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }
        BlockPos chestPos = guard.getPairedChestPos();
        BlockPos jobPos = guard.getPairedJobPos();
        if (chestPos == null || jobPos == null) {
            return false;
        }
        if (!world.isChunkLoaded(chestPos) || !world.isChunkLoaded(jobPos)) {
            return false;
        }
        if (stage == Stage.MINING && !hasUsableMiningTool()) {
            stage = Stage.RETURN;
        }
        return stage != Stage.DONE;
    }

    @Override
    public void start() {
        guard.getNavigation().startMovingTo(currentStepTarget.getX() + 0.5D, currentStepTarget.getY(), currentStepTarget.getZ() + 0.5D, MOVE_SPEED);
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        guard.setTarget(null);

        if (stage == Stage.MINING) {
            tickMining(world);
            return;
        }

        if (stage == Stage.RETURN) {
            BlockPos chestPos = guard.getPairedChestPos();
            if (chestPos == null) {
                stage = Stage.DONE;
                return;
            }
            guard.getNavigation().startMovingTo(chestPos.getX() + 0.5D, chestPos.getY(), chestPos.getZ() + 0.5D, MOVE_SPEED);
            if (guard.squaredDistanceTo(Vec3d.ofCenter(chestPos)) <= 4.0D) {
                stage = Stage.DONE;
            }
        }
    }

    private void tickMining(ServerWorld world) {
        if (!ensureStepClear(world, currentStepTarget)) {
            stage = Stage.RETURN;
            return;
        }

        guard.getNavigation().startMovingTo(currentStepTarget.getX() + 0.5D, currentStepTarget.getY(), currentStepTarget.getZ() + 0.5D, MOVE_SPEED);
        double distanceToTarget = guard.squaredDistanceTo(Vec3d.ofBottomCenter(currentStepTarget));
        if (distanceToTarget + 0.01D < lastDistanceToTarget) {
            noProgressTicks = 0;
            lastDistanceToTarget = distanceToTarget;
        } else {
            noProgressTicks++;
        }

        if (noProgressTicks >= NO_PROGRESS_LIMIT) {
            stage = Stage.RETURN;
            return;
        }

        if (distanceToTarget <= TARGET_REACH_SQUARED) {
            stepIndex++;
            currentStepTarget = computeStepTarget(stepIndex);
            noProgressTicks = 0;
            lastDistanceToTarget = Double.MAX_VALUE;
        }
    }

    private boolean ensureStepClear(ServerWorld world, BlockPos footTarget) {
        BlockPos headTarget = footTarget.up();
        if (!clearBlockIfNeeded(world, footTarget)) {
            return false;
        }
        return clearBlockIfNeeded(world, headTarget);
    }

    private boolean clearBlockIfNeeded(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        if (state.getFluidState().isIn(FluidTags.WATER) || state.getFluidState().isIn(FluidTags.LAVA)) {
            return false;
        }
        if (!canMine(state)) {
            return false;
        }

        world.breakBlock(pos, true, guard);
        collectNearbyDrops(world, pos);
        return true;
    }

    private void collectNearbyDrops(ServerWorld world, BlockPos pos) {
        Box box = new Box(pos).expand(1.5D);
        for (ItemEntity itemEntity : world.getEntitiesByClass(ItemEntity.class, box, entity -> entity.isAlive() && !entity.getStack().isEmpty())) {
            ItemStack remaining = guard.guardInventory.addStack(itemEntity.getStack());
            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setStack(remaining);
            }
        }
    }

    private boolean canMine(BlockState state) {
        return BREAKABLE_BLOCKS.contains(state.getBlock());
    }

    private boolean hasUsableMiningTool() {
        ItemStack stack = guard.getMainHandStack();
        return !stack.isEmpty() && (stack.getItem() instanceof PickaxeItem || stack.getItem() instanceof ShovelItem);
    }

    private BlockPos computeStepTarget(int index) {
        return origin.offset(miningDirection, index + 1).down(index + 1);
    }

    private enum Stage {
        IDLE,
        MINING,
        RETURN,
        DONE
    }
}
