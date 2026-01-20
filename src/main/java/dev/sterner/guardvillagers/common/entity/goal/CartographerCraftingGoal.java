package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

public class CartographerCraftingGoal extends Goal {
    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private BlockPos craftingTablePos;
    private boolean immediateCheckPending;

    public CartographerCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.noneOf(Control.class));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.craftingTablePos = craftingTablePos == null ? null : craftingTablePos.toImmutable();
    }

    public BlockPos getCraftingTablePos() {
        return craftingTablePos;
    }

    public void requestImmediateCraft(ServerWorld world) {
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
        return jobPos != null && chestPos != null && craftingTablePos != null;
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
