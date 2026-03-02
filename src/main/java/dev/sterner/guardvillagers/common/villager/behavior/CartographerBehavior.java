package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.CartographerCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.CartographerMapExplorationGoal;
import dev.sterner.guardvillagers.common.entity.goal.CartographerToLibrarianDistributionGoal;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class CartographerBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(CartographerBehavior.class);
    private static final int EXPLORATION_GOAL_PRIORITY = 3;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, CartographerMapExplorationGoal> EXPLORATION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, CartographerToLibrarianDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, CartographerCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.CARTOGRAPHER, world.getBlockState(jobPos))) {
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

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.CARTOGRAPHER, world.getBlockState(jobPos))) {
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
        Set<BlockPos> observedChestPositions = getObservedChestPositions(world, chestPos);
        if (observedChestPositions.isEmpty()) {
            clearChestListener(villager);
            return;
        }

        ChestRegistration existing = CHEST_REGISTRATIONS.get(villager);
        if (existing != null && existing.observedChestPositions().equals(observedChestPositions)) {
            return;
        }

        if (existing != null) {
            removeChestListener(existing);
            CHEST_REGISTRATIONS.remove(villager);
        }

        for (BlockPos observedPos : observedChestPositions) {
            CHEST_WATCHERS_BY_POS.computeIfAbsent(observedPos, ignored -> new HashSet<>()).add(villager);
        }

        CHEST_REGISTRATIONS.put(villager, new ChestRegistration(villager, observedChestPositions));
    }

    public static void onChestInventoryMutated(ServerWorld world, BlockPos chestPos) {
        Set<VillagerEntity> villagers = CHEST_WATCHERS_BY_POS.get(chestPos);
        if (villagers == null || villagers.isEmpty()) {
            return;
        }

        Set<VillagerEntity> snapshot = Set.copyOf(villagers);
        for (VillagerEntity villager : snapshot) {
            if (!villager.isAlive() || villager.getWorld() != world) {
                continue;
            }
            triggerChestWakeups(world, villager);
        }
    }

    private static void triggerChestWakeups(ServerWorld world, VillagerEntity villager) {
        CartographerMapExplorationGoal explorationGoal = EXPLORATION_GOALS.get(villager);
        if (explorationGoal != null) {
            explorationGoal.onChestInventoryChanged(world);
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
    }

    private void clearChestListener(VillagerEntity villager) {
        ChestRegistration existing = CHEST_REGISTRATIONS.remove(villager);
        if (existing != null) {
            removeChestListener(existing);
        }
    }

    private void removeChestListener(ChestRegistration existing) {
        for (BlockPos observedPos : existing.observedChestPositions()) {
            Set<VillagerEntity> watchers = CHEST_WATCHERS_BY_POS.get(observedPos);
            if (watchers == null) {
                continue;
            }
            watchers.remove(existing.villager());
            if (watchers.isEmpty()) {
                CHEST_WATCHERS_BY_POS.remove(observedPos);
            }
        }
    }

    private Set<BlockPos> getObservedChestPositions(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return Set.of();
        }

        Set<BlockPos> positions = new HashSet<>();
        positions.add(chestPos.toImmutable());

        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType != ChestType.SINGLE) {
            Direction facing = state.get(ChestBlock.FACING);
            Direction offsetDirection = chestType == ChestType.LEFT
                    ? facing.rotateYClockwise()
                    : facing.rotateYCounterclockwise();
            BlockPos otherHalfPos = chestPos.offset(offsetDirection);
            BlockState otherState = world.getBlockState(otherHalfPos);
            if (otherState.getBlock() instanceof ChestBlock && otherState.get(ChestBlock.FACING) == facing) {
                positions.add(otherHalfPos.toImmutable());
            }
        }

        return positions;
    }

    private record ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
        private ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
            this.villager = villager;
            this.observedChestPositions = Set.copyOf(observedChestPositions);
        }
    }
}
