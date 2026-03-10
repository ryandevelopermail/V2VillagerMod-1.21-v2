package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumSet;

public class LumberjackGuardDepositLogsGoal extends Goal {
    private final LumberjackGuardEntity guard;

    public LumberjackGuardDepositLogsGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        return this.guard.getWorld() instanceof ServerWorld
                && this.guard.isAlive()
                && this.guard.isActiveSession();
    }

    @Override
    public void start() {
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_CHEST);
    }

    @Override
    public boolean shouldContinue() {
        return false;
    }

    @Override
    public void stop() {
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
        this.guard.setActiveSession(false);
    }
}
