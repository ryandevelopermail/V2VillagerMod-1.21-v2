package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ShepherdBedCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdBedPlacerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdFenceCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdFencePlacerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdSpecialGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdToLibrarianDistributionGoal;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class ShepherdBehavior implements VillagerProfessionBehavior {
    private static final long SPECIAL_GOAL_CHECK_DEBOUNCE_TICKS = 10L;
    private static final long SPECIAL_GOAL_CHECK_MAX_COALESCE_DELAY_TICKS = 40L;
    private static final int SPECIAL_GOAL_PRIORITY = 3;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final int BED_CRAFTING_GOAL_PRIORITY = 6;
    private static final int BED_PLACER_GOAL_PRIORITY = 7;
    private static final int FENCE_CRAFTING_GOAL_PRIORITY = 8;
    private static final int FENCE_PLACER_GOAL_PRIORITY = 9;
    private static final Map<VillagerEntity, ShepherdCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdSpecialGoal> SPECIAL_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdToLibrarianDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdBedCraftingGoal> BED_CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdBedPlacerGoal> BED_PLACER_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdFenceCraftingGoal> FENCE_CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdFencePlacerGoal> FENCE_PLACER_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();
    private static final Map<VillagerEntity, Long> LAST_SPECIAL_GOAL_CHECK_TICKS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.SHEPHERD, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

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
        LAST_SPECIAL_GOAL_CHECK_TICKS.put(villager, world.getTime());

        ShepherdToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new ShepherdToLibrarianDistributionGoal(villager, jobPos, chestPos, null);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        }
        distributionGoal.requestImmediateDistribution();

        ShepherdCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }

        // Bed placer (runs on chest-pair only; doesn't need a crafting table)
        ShepherdBedPlacerGoal bedPlacerGoal = BED_PLACER_GOALS.get(villager);
        if (bedPlacerGoal == null) {
            bedPlacerGoal = new ShepherdBedPlacerGoal(villager, jobPos, chestPos);
            BED_PLACER_GOALS.put(villager, bedPlacerGoal);
            villager.goalSelector.add(BED_PLACER_GOAL_PRIORITY, bedPlacerGoal);
        } else {
            bedPlacerGoal.setTargets(jobPos, chestPos);
        }
        bedPlacerGoal.requestImmediateCheck();

        // Fence crafting (does NOT require a crafting table — simulated craft into chest).
        // Registered here in onChestPaired so it works without a crafting table being paired.
        // onCraftingTablePaired will update targets if a table is later paired.
        ShepherdFenceCraftingGoal fenceCraftingGoalFromChest = FENCE_CRAFTING_GOALS.get(villager);
        if (fenceCraftingGoalFromChest == null) {
            fenceCraftingGoalFromChest = new ShepherdFenceCraftingGoal(villager, jobPos, chestPos, null);
            FENCE_CRAFTING_GOALS.put(villager, fenceCraftingGoalFromChest);
            villager.goalSelector.add(FENCE_CRAFTING_GOAL_PRIORITY, fenceCraftingGoalFromChest);
        } else {
            fenceCraftingGoalFromChest.setTargets(jobPos, chestPos, fenceCraftingGoalFromChest.getCraftingTablePos());
        }
        fenceCraftingGoalFromChest.requestImmediateCraft(world);

        // Fence placer (runs on chest-pair; no crafting table needed — only places blocks)
        ShepherdFencePlacerGoal fencePlacerGoal = FENCE_PLACER_GOALS.get(villager);
        if (fencePlacerGoal == null) {
            fencePlacerGoal = new ShepherdFencePlacerGoal(villager, jobPos, chestPos);
            FENCE_PLACER_GOALS.put(villager, fencePlacerGoal);
            villager.goalSelector.add(FENCE_PLACER_GOAL_PRIORITY, fencePlacerGoal);
        } else {
            fencePlacerGoal.setTargets(jobPos, chestPos);
        }
        fencePlacerGoal.requestImmediateCheck();

        updateChestListener(world, villager, chestPos);
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

        // Bed crafting (requires crafting table)
        ShepherdBedCraftingGoal bedCraftingGoal = BED_CRAFTING_GOALS.get(villager);
        if (bedCraftingGoal == null) {
            bedCraftingGoal = new ShepherdBedCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            BED_CRAFTING_GOALS.put(villager, bedCraftingGoal);
            villager.goalSelector.add(BED_CRAFTING_GOAL_PRIORITY, bedCraftingGoal);
        } else {
            bedCraftingGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        bedCraftingGoal.requestImmediateCraft(world);

        // Fence crafting (requires crafting table; lower priority than bed crafting)
        ShepherdFenceCraftingGoal fenceCraftingGoal = FENCE_CRAFTING_GOALS.get(villager);
        if (fenceCraftingGoal == null) {
            fenceCraftingGoal = new ShepherdFenceCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            FENCE_CRAFTING_GOALS.put(villager, fenceCraftingGoal);
            villager.goalSelector.add(FENCE_CRAFTING_GOAL_PRIORITY, fenceCraftingGoal);
        } else {
            fenceCraftingGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        fenceCraftingGoal.requestImmediateCraft(world);

        ShepherdToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new ShepherdToLibrarianDistributionGoal(villager, jobPos, chestPos, craftingTablePos);
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
            LAST_SPECIAL_GOAL_CHECK_TICKS.remove(villager);
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
        ShepherdSpecialGoal specialGoal = SPECIAL_GOALS.get(villager);
        if (specialGoal != null) {
            specialGoal.onChestInventoryChanged(world);
            long now = world.getTime();
            long lastWakeTick = LAST_SPECIAL_GOAL_CHECK_TICKS.getOrDefault(villager, Long.MIN_VALUE);
            if (now - lastWakeTick >= SPECIAL_GOAL_CHECK_DEBOUNCE_TICKS) {
                specialGoal.requestImmediateCheck();
                LAST_SPECIAL_GOAL_CHECK_TICKS.put(villager, now);
            } else {
                specialGoal.requestCheckNoSoonerThan(lastWakeTick + SPECIAL_GOAL_CHECK_MAX_COALESCE_DELAY_TICKS);
            }
        }

        ShepherdCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.requestImmediateCraft(world);
        }

        ShepherdToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal != null) {
            distributionGoal.requestImmediateDistribution();
        }

        ShepherdBedCraftingGoal bedCraftingGoal = BED_CRAFTING_GOALS.get(villager);
        if (bedCraftingGoal != null) {
            bedCraftingGoal.requestImmediateCraft(world);
        }

        ShepherdBedPlacerGoal bedPlacerGoal = BED_PLACER_GOALS.get(villager);
        if (bedPlacerGoal != null) {
            bedPlacerGoal.requestImmediateCheck();
        }

        ShepherdFenceCraftingGoal fenceCraftingGoal = FENCE_CRAFTING_GOALS.get(villager);
        if (fenceCraftingGoal != null) {
            fenceCraftingGoal.requestImmediateCraft(world);
        }

        ShepherdFencePlacerGoal fencePlacerGoal = FENCE_PLACER_GOALS.get(villager);
        if (fencePlacerGoal != null) {
            fencePlacerGoal.requestImmediateCheck();
        }
    }

    private void clearChestListener(VillagerEntity villager) {
        ChestRegistration existing = CHEST_REGISTRATIONS.remove(villager);
        LAST_SPECIAL_GOAL_CHECK_TICKS.remove(villager);
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
