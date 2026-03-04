package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.FarmerCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.FarmerDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.FarmerHarvestGoal;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
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

public class FarmerBehavior extends AbstractPairedProfessionBehavior {
    private static final int HARVEST_GOAL_PRIORITY = 3;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, FarmerHarvestGoal> GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, FarmerDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, FarmerCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();
    private static final Map<VillagerEntity, Set<BlockPos>> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.FARMER, serverWorld.getBlockState(pos)),
                () -> {
                    clearChestListener(CHEST_LISTENERS, villager);
                    clearChestWatcher(villager);
                })) {
            return;
        }

        FarmerHarvestGoal harvestGoal = upsertGoal(GOALS, villager, HARVEST_GOAL_PRIORITY,
                () -> new FarmerHarvestGoal(villager, jobPos, chestPos));
        harvestGoal.setTargets(jobPos, chestPos);

        FarmerDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new FarmerDistributionGoal(villager, jobPos, chestPos, null));
        distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        distributionGoal.requestImmediateDistribution();

        FarmerCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
            harvestGoal.setCraftingGoal(craftingGoal);
        }

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            FarmerHarvestGoal harvest = GOALS.get(pairedVillager);
            if (harvest != null) {
                harvest.requestImmediateWorkCheck();
            }
            FarmerDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            FarmerCraftingGoal crafting = CRAFTING_GOALS.get(pairedVillager);
            if (crafting != null) {
                crafting.requestImmediateCraft(serverWorld);
            }
        });
        updateChestWatcher(world, villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        FarmerCraftingGoal craftingGoal = upsertGoal(CRAFTING_GOALS, villager, CRAFTING_GOAL_PRIORITY,
                () -> new FarmerCraftingGoal(villager, jobPos, chestPos, craftingTablePos));
        craftingGoal.setTargets(jobPos, chestPos, craftingTablePos);
        craftingGoal.requestImmediateCraft(world);

        FarmerDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new FarmerDistributionGoal(villager, jobPos, chestPos, craftingTablePos));
        distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        distributionGoal.requestImmediateDistribution();

        FarmerHarvestGoal harvestGoal = GOALS.get(villager);
        if (harvestGoal != null) {
            harvestGoal.setCraftingGoal(craftingGoal);
        }

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            FarmerHarvestGoal harvest = GOALS.get(pairedVillager);
            if (harvest != null) {
                harvest.requestImmediateWorkCheck();
            }
            FarmerDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            FarmerCraftingGoal crafting = CRAFTING_GOALS.get(pairedVillager);
            if (crafting != null) {
                crafting.requestImmediateCraft(serverWorld);
            }
        });
        updateChestWatcher(world, villager, chestPos);
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

            FarmerHarvestGoal harvest = GOALS.get(villager);
            if (harvest != null) {
                harvest.requestImmediateWorkCheck();
            }

            FarmerDistributionGoal distribution = DISTRIBUTION_GOALS.get(villager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }

            FarmerCraftingGoal crafting = CRAFTING_GOALS.get(villager);
            if (crafting != null) {
                crafting.requestImmediateCraft(world);
            }
        }
    }

    private void updateChestWatcher(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Set<BlockPos> observedChestPositions = getObservedChestPositions(world, chestPos);
        if (observedChestPositions.isEmpty()) {
            clearChestWatcher(villager);
            return;
        }

        Set<BlockPos> existing = CHEST_REGISTRATIONS.get(villager);
        if (existing != null && existing.equals(observedChestPositions)) {
            return;
        }

        clearChestWatcher(villager);
        for (BlockPos observedPos : observedChestPositions) {
            CHEST_WATCHERS_BY_POS.computeIfAbsent(observedPos, ignored -> new HashSet<>()).add(villager);
        }
        CHEST_REGISTRATIONS.put(villager, observedChestPositions);
    }

    private void clearChestWatcher(VillagerEntity villager) {
        Set<BlockPos> existing = CHEST_REGISTRATIONS.remove(villager);
        if (existing == null) {
            return;
        }
        for (BlockPos observedPos : existing) {
            Set<VillagerEntity> watchers = CHEST_WATCHERS_BY_POS.get(observedPos);
            if (watchers == null) {
                continue;
            }
            watchers.remove(villager);
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
}
