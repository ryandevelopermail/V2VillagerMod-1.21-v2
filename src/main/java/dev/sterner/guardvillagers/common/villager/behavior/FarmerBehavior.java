package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.FarmerCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.FarmerDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.FarmerHarvestGoal;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.Map;
import java.util.WeakHashMap;

public class FarmerBehavior extends AbstractPairedProfessionBehavior {
    private static final int HARVEST_GOAL_PRIORITY = 3;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, FarmerHarvestGoal> GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, FarmerDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, FarmerCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.FARMER, serverWorld.getBlockState(pos)),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
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
            FarmerDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            FarmerCraftingGoal crafting = CRAFTING_GOALS.get(pairedVillager);
            if (crafting != null) {
                crafting.requestImmediateCraft(serverWorld);
            }
        });
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
            FarmerDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            FarmerCraftingGoal crafting = CRAFTING_GOALS.get(pairedVillager);
            if (crafting != null) {
                crafting.requestImmediateCraft(serverWorld);
            }
        });
    }
}
