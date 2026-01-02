package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ArmorerBlastFurnaceGoal;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerCraftingGoal;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public class ArmorerBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArmorerBehavior.class);
    private static final int BLAST_FURNACE_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final Map<VillagerEntity, ArmorerBlastFurnaceGoal> GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ArmorerCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            return;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.BLAST_FURNACE)) {
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            return;
        }

        LOGGER.info("Armorer {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        ArmorerBlastFurnaceGoal goal = GOALS.get(villager);
        if (goal == null) {
            goal = new ArmorerBlastFurnaceGoal(villager, jobPos, chestPos);
            GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(BLAST_FURNACE_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos);
        }

        ArmorerCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        ArmorerCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new ArmorerCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }
    }
}
