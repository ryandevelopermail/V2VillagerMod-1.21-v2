package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ArmorerBlastFurnaceGoal;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerDistributionGoal;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public class ArmorerBehavior extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArmorerBehavior.class);
    private static final int BLAST_FURNACE_GOAL_PRIORITY = 3;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, BlockPos> PAIRED_CHESTS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ArmorerBlastFurnaceGoal> GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ArmorerCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ArmorerDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    public static BlockPos getPairedChestPos(VillagerEntity villager) {
        return PAIRED_CHESTS.get(villager);
    }

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.ARMORER, serverWorld.getBlockState(pos)),
                () -> {
                    clearChestListener(CHEST_LISTENERS, villager);
                    PAIRED_CHESTS.remove(villager);
                })) {
            return;
        }

        PAIRED_CHESTS.put(villager, chestPos.toImmutable());
        LOGGER.info("Armorer {} paired chest at {} for job site {}", villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        ArmorerBlastFurnaceGoal blastFurnaceGoal = upsertGoal(GOALS, villager, BLAST_FURNACE_GOAL_PRIORITY,
                () -> new ArmorerBlastFurnaceGoal(villager, jobPos, chestPos));
        blastFurnaceGoal.setTargets(jobPos, chestPos);

        ArmorerDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new ArmorerDistributionGoal(villager, jobPos, chestPos, null));
        distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        distributionGoal.requestImmediateDistribution();

        ArmorerCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            ArmorerDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            ArmorerCraftingGoal crafting = CRAFTING_GOALS.get(pairedVillager);
            if (crafting != null) {
                crafting.requestImmediateCraft(serverWorld);
            }
        });
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        PAIRED_CHESTS.put(villager, chestPos.toImmutable());

        ArmorerCraftingGoal craftingGoal = upsertGoal(CRAFTING_GOALS, villager, CRAFTING_GOAL_PRIORITY,
                () -> new ArmorerCraftingGoal(villager, jobPos, chestPos, craftingTablePos));
        craftingGoal.setTargets(jobPos, chestPos, craftingTablePos);
        craftingGoal.requestImmediateCraft(world);

        ArmorerDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new ArmorerDistributionGoal(villager, jobPos, chestPos, craftingTablePos));
        distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        distributionGoal.requestImmediateDistribution();

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            ArmorerDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            ArmorerCraftingGoal crafting = CRAFTING_GOALS.get(pairedVillager);
            if (crafting != null) {
                crafting.requestImmediateCraft(serverWorld);
            }
        });
    }
}
