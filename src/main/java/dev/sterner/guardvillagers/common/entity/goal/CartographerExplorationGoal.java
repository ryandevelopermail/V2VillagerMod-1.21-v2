package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

public class CartographerExplorationGoal extends Goal {
    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private boolean immediateCheckPending;

    public CartographerExplorationGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.noneOf(Control.class));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
    }

    public void requestImmediateCheck() {
        immediateCheckPending = true;
    }

    @Override
    public boolean canStart() {
        if (!immediateCheckPending) {
            return false;
        }
        if (!villager.isAlive()) {
            return false;
        }
        return jobPos != null && chestPos != null;
    }

    @Override
    public void start() {
        immediateCheckPending = false;
    }

    @Override
    public boolean shouldContinue() {
        return false;
    }
}
