package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class AxeGuardGatheringGoal extends Goal {
    private static final double MOVE_SPEED = 0.72D;
    private static final int TREE_SCAN_RADIUS = 20;

    private final AxeGuardEntity guard;
    private BlockPos currentTree;
    private long nextCheckTick;

    public AxeGuardGatheringGoal(AxeGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    public void requestImmediateCheck() {
        nextCheckTick = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }
        if (guard.getChestPos() == null || world.getTime() < nextCheckTick) {
            return false;
        }
        if (guard.getNextSessionStartTick() > world.getTime()) {
            return false;
        }
        if (!(guard.getMainHandStack().getItem() instanceof AxeItem)) {
            return false;
        }

        currentTree = findNearestTreeRoot(world).orElse(null);
        nextCheckTick = world.getTime() + 20L;
        return currentTree != null;
    }

    @Override
    public boolean shouldContinue() {
        return currentTree != null && guard.isAlive();
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world) || currentTree == null) {
            currentTree = null;
            return;
        }

        guard.getLookControl().lookAt(Vec3d.ofCenter(currentTree));
        if (guard.squaredDistanceTo(Vec3d.ofCenter(currentTree)) > 4.0D) {
            guard.getNavigation().startMovingTo(currentTree.getX() + 0.5D, currentTree.getY(), currentTree.getZ() + 0.5D, MOVE_SPEED);
            return;
        }

        chopTree(world, currentTree);
        currentTree = null;
        guard.incrementSessionsCompletedToday();
        guard.startWorkflowCountdown(world, 20L * 60L);
    }

    private Optional<BlockPos> findNearestTreeRoot(ServerWorld world) {
        BlockPos center = guard.getBlockPos();
        double bestDistance = Double.MAX_VALUE;
        BlockPos best = null;

        for (BlockPos candidate : BlockPos.iterate(center.add(-TREE_SCAN_RADIUS, -8, -TREE_SCAN_RADIUS), center.add(TREE_SCAN_RADIUS, 8, TREE_SCAN_RADIUS))) {
            BlockState state = world.getBlockState(candidate);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            if (world.getBlockState(candidate.down()).isIn(BlockTags.LOGS)) {
                continue;
            }
            double distance = guard.squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.toImmutable();
            }
        }

        return Optional.ofNullable(best);
    }

    private void chopTree(ServerWorld world, BlockPos rootPos) {
        Inventory inventory = guard.getPairedChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(rootPos);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current) || !rootPos.isWithinDistance(current, 10.0D)) {
                continue;
            }
            BlockState state = world.getBlockState(current);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            if (world.breakBlock(current, false, guard)) {
                ItemStack remainder = guard.insertIntoInventory(inventory, new ItemStack(state.getBlock().asItem()));
                if (!remainder.isEmpty()) {
                    guard.dropStack(remainder);
                }
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = current.add(dx, dy, dz);
                        if (!visited.contains(next) && world.getBlockState(next).isIn(BlockTags.LOGS)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }

        int logsForPlanks = guard.countMatching(inventory, stack -> stack.isIn(ItemTags.LOGS_THAT_BURN)) / 2;
        int consumed = guard.consumeMatching(inventory, stack -> stack.isIn(ItemTags.LOGS_THAT_BURN), logsForPlanks);
        if (consumed > 0) {
            guard.insertIntoInventory(inventory, new ItemStack(Items.OAK_PLANKS, consumed * 4));
        }
        inventory.markDirty();
    }
}
