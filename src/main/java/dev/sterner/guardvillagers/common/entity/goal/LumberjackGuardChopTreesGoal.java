package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

public class LumberjackGuardChopTreesGoal extends Goal {
    private static final int CHOP_INTERVAL_TICKS = 20 * 20;
    private final LumberjackGuardEntity guard;

    public LumberjackGuardChopTreesGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (!(this.guard.getWorld() instanceof ServerWorld world) || !this.guard.isAlive()) {
            return false;
        }

        BlockPos tablePos = this.guard.getPairedCraftingTablePos();
        BlockPos chestPos = this.guard.getPairedChestPos();
        if (tablePos == null || chestPos == null) {
            return false;
        }

        return world.getTime() >= this.guard.getNextChopTick();
    }

    @Override
    public void start() {
        this.guard.setActiveSession(true);
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_TREE);
        if (this.guard.getSelectedTreeTargets().isEmpty()) {
            this.guard.getSelectedTreeTargets().add(this.guard.getBlockPos().toImmutable());
        }
    }

    @Override
    public boolean shouldContinue() {
        return false;
    }

    @Override
    public void stop() {
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.CHOPPING);
        this.guard.setNextChopTick(this.guard.getWorld().getTime() + CHOP_INTERVAL_TICKS);
    }
}
