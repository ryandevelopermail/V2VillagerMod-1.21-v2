package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.CartographerCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.CartographerMapExplorationGoal;
import dev.sterner.guardvillagers.common.entity.goal.CartographerToLibrarianDistributionGoal;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.InventoryChangedListener;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public class CartographerBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(CartographerBehavior.class);
    private static final int EXPLORATION_GOAL_PRIORITY = 3;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, CartographerMapExplorationGoal> EXPLORATION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, CartographerToLibrarianDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, CartographerCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListener> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.CARTOGRAPHY_TABLE)) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        LOGGER.info("Cartographer {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        CartographerMapExplorationGoal explorationGoal = EXPLORATION_GOALS.get(villager);
        if (explorationGoal == null) {
            explorationGoal = new CartographerMapExplorationGoal(villager, jobPos, chestPos);
            EXPLORATION_GOALS.put(villager, explorationGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(EXPLORATION_GOAL_PRIORITY, explorationGoal);
        } else {
            explorationGoal.setTargets(jobPos, chestPos);
        }
        explorationGoal.requestImmediateCheck(world);

        CartographerToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new CartographerToLibrarianDistributionGoal(villager, jobPos, chestPos, null);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        }
        distributionGoal.requestImmediateDistribution();

        CartographerCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }
        updateChestListener(world, villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.CARTOGRAPHY_TABLE)) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        CartographerCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new CartographerCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        goal.requestImmediateCraft(world);

        CartographerMapExplorationGoal explorationGoal = EXPLORATION_GOALS.get(villager);
        if (explorationGoal == null) {
            explorationGoal = new CartographerMapExplorationGoal(villager, jobPos, chestPos);
            EXPLORATION_GOALS.put(villager, explorationGoal);
            villager.goalSelector.add(EXPLORATION_GOAL_PRIORITY, explorationGoal);
        } else {
            explorationGoal.setTargets(jobPos, chestPos);
        }
        explorationGoal.requestImmediateCheck(world);

        CartographerToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new CartographerToLibrarianDistributionGoal(villager, jobPos, chestPos, craftingTablePos);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            villager.goalSelector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        distributionGoal.requestImmediateDistribution();
        updateChestListener(world, villager, chestPos);
    }

    private void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Inventory inventory = getChestInventory(world, chestPos);
        ChestListener existing = CHEST_LISTENERS.get(villager);
        if (existing != null && existing.inventory() == inventory) {
            return;
        }
        if (existing != null) {
            removeChestListener(existing);
            CHEST_LISTENERS.remove(villager);
        }
        if (!(inventory instanceof SimpleInventory simpleInventory)) {
            return;
        }
        InventoryChangedListener listener = sender -> {
            CartographerMapExplorationGoal explorationGoal = EXPLORATION_GOALS.get(villager);
            if (explorationGoal != null) {
                explorationGoal.requestImmediateCheck(world);
            }
            CartographerCraftingGoal goal = CRAFTING_GOALS.get(villager);
            if (goal != null) {
                goal.requestImmediateCraft(world);
            }
            CartographerToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
            if (distributionGoal != null) {
                distributionGoal.requestImmediateDistribution();
            }
        };
        simpleInventory.addListener(listener);
        CHEST_LISTENERS.put(villager, new ChestListener(simpleInventory, listener));
    }

    private void clearChestListener(VillagerEntity villager) {
        ChestListener existing = CHEST_LISTENERS.remove(villager);
        if (existing != null) {
            removeChestListener(existing);
        }
    }

    private void removeChestListener(ChestListener existing) {
        existing.inventory().removeListener(existing.listener());
    }

    private Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private record ChestListener(SimpleInventory inventory, InventoryChangedListener listener) {
    }
}
