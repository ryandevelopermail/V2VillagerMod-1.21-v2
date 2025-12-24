package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.FarmerHarvestGoal;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.WeakHashMap;

public class FarmerBehavior implements VillagerProfessionBehavior {
    private static final int HARVEST_GOAL_PRIORITY = 3;
    private static final Map<VillagerEntity, FarmerHarvestGoal> GOALS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        FarmerHarvestGoal goal = GOALS.get(villager);
        if (goal == null) {
            goal = new FarmerHarvestGoal(villager, jobPos, chestPos);
            GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(HARVEST_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos);
        }
    }
}
