package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ToolsmithCraftingGoal;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public class ToolsmithBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolsmithBehavior.class);
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final Map<VillagerEntity, ToolsmithCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            return;
        }

        ToolsmithCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal != null) {
            goal.setTargets(jobPos, chestPos, goal.getCraftingTablePos());
        }
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        ToolsmithCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new ToolsmithCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }

        LOGGER.info("Toolsmith {} paired crafting table at {} for job site {}",
                villager.getUuidAsString(),
                craftingTablePos.toShortString(),
                jobPos.toShortString());
    }
}
