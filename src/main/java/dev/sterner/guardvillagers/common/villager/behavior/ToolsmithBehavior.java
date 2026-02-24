package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ToolsmithCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ToolsmithDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.ToolsmithSmithingGoal;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public class ToolsmithBehavior extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolsmithBehavior.class);
    private static final int DISTRIBUTION_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int SMITHING_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, ToolsmithCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ToolsmithDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ToolsmithSmithingGoal> SMITHING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.TOOLSMITH, serverWorld.getBlockState(pos)),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("Toolsmith {} paired chest at {} for job site {}", villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        ToolsmithDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new ToolsmithDistributionGoal(villager, jobPos, chestPos, null));
        distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        distributionGoal.requestImmediateDistribution();

        ToolsmithCraftingGoal craftingGoal = upsertGoal(CRAFTING_GOALS, villager, CRAFTING_GOAL_PRIORITY,
                () -> new ToolsmithCraftingGoal(villager, jobPos, chestPos, null));
        craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());

        ToolsmithSmithingGoal smithingGoal = upsertGoal(SMITHING_GOALS, villager, SMITHING_GOAL_PRIORITY,
                () -> new ToolsmithSmithingGoal(villager, jobPos, chestPos));
        smithingGoal.setTargets(jobPos, chestPos);
        smithingGoal.requestImmediateSmithing(world);

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            ToolsmithCraftingGoal crafting = CRAFTING_GOALS.get(pairedVillager);
            if (crafting != null) {
                crafting.requestImmediateCraft(serverWorld);
            }
            ToolsmithDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            ToolsmithSmithingGoal smithing = SMITHING_GOALS.get(pairedVillager);
            if (smithing != null) {
                smithing.requestImmediateSmithing(serverWorld);
            }
        });
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        ToolsmithCraftingGoal craftingGoal = upsertGoal(CRAFTING_GOALS, villager, CRAFTING_GOAL_PRIORITY,
                () -> new ToolsmithCraftingGoal(villager, jobPos, chestPos, craftingTablePos));
        craftingGoal.setTargets(jobPos, chestPos, craftingTablePos);
        craftingGoal.requestImmediateCraft(world);

        ToolsmithDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new ToolsmithDistributionGoal(villager, jobPos, chestPos, craftingTablePos));
        distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        distributionGoal.requestImmediateDistribution();

        ToolsmithSmithingGoal smithingGoal = upsertGoal(SMITHING_GOALS, villager, SMITHING_GOAL_PRIORITY,
                () -> new ToolsmithSmithingGoal(villager, jobPos, chestPos));
        smithingGoal.setTargets(jobPos, chestPos);
        smithingGoal.requestImmediateSmithing(world);

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            ToolsmithCraftingGoal crafting = CRAFTING_GOALS.get(pairedVillager);
            if (crafting != null) {
                crafting.requestImmediateCraft(serverWorld);
            }
            ToolsmithDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            ToolsmithSmithingGoal smithing = SMITHING_GOALS.get(pairedVillager);
            if (smithing != null) {
                smithing.requestImmediateSmithing(serverWorld);
            }
        });
    }
}
