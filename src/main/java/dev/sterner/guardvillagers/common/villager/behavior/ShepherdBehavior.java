package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ShepherdCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdSpecialGoal;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.WeakHashMap;

public class ShepherdBehavior implements VillagerProfessionBehavior {
    private static final int SPECIAL_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final Map<VillagerEntity, ShepherdCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdSpecialGoal> SPECIAL_GOALS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        ShepherdSpecialGoal specialGoal = SPECIAL_GOALS.get(villager);
        if (specialGoal == null) {
            specialGoal = new ShepherdSpecialGoal(villager, jobPos, chestPos);
            SPECIAL_GOALS.put(villager, specialGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(SPECIAL_GOAL_PRIORITY, specialGoal);
        } else {
            specialGoal.setTargets(jobPos, chestPos);
        }
        specialGoal.requestImmediateCheck();

        ShepherdCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        ShepherdCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new ShepherdCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        goal.requestImmediateCraft(world);
    }
}
