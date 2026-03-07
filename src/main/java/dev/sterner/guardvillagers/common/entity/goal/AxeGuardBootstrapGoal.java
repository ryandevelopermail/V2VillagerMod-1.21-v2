package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class AxeGuardBootstrapGoal extends Goal {
    private final AxeGuardEntity guard;
    private long nextCheckTick;

    public AxeGuardBootstrapGoal(AxeGuardEntity guard) {
        this.guard = guard;
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (world.getTime() < nextCheckTick) {
            return false;
        }
        nextCheckTick = world.getTime() + 80L;
        return guard.getJobPos() != null && guard.getChestPos() != null;
    }

    @Override
    public void start() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return;
        }

        BlockPos jobPos = guard.getJobPos();
        BlockPos chestPos = guard.getChestPos();
        if (jobPos == null || chestPos == null) {
            return;
        }

        if (guard.getCraftingTablePos() == null) {
            BlockPos found = findNearby(world, jobPos, Blocks.CRAFTING_TABLE);
            guard.setCraftingTablePos(found != null ? found : jobPos);
        }

        if (guard.getPairedFurnacePos() == null) {
            BlockPos found = findNearby(world, chestPos, Blocks.FURNACE);
            if (found != null) {
                guard.setPairedFurnacePos(found);
            }
        }

        AxeGuardWorkflowRegistry.updateWatch(guard, chestPos);
        if (guard.getNextSessionStartTick() <= 0L) {
            guard.startWorkflowCountdown(world, 20L * 20L);
        }
    }

    private BlockPos findNearby(ServerWorld world, BlockPos center, net.minecraft.block.Block block) {
        for (BlockPos candidate : BlockPos.iterate(center.add(-3, -2, -3), center.add(3, 2, 3))) {
            if (world.getBlockState(candidate).isOf(block)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }
}
