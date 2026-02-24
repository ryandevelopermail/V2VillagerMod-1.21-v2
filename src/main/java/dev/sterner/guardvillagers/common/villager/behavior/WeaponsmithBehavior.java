package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.WeaponsmithCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.WeaponsmithDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.WeaponsmithRepairGoal;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public class WeaponsmithBehavior extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeaponsmithBehavior.class);
    private static final int DISTRIBUTION_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int REPAIR_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, WeaponsmithCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, WeaponsmithDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, WeaponsmithRepairGoal> REPAIR_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.WEAPONSMITH, serverWorld.getBlockState(pos)),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("Weaponsmith {} paired chest at {} for job site {}", villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        WeaponsmithDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new WeaponsmithDistributionGoal(villager, jobPos, chestPos, null));
        distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        distributionGoal.requestImmediateDistribution();

        WeaponsmithCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }

        WeaponsmithRepairGoal repairGoal = upsertGoal(REPAIR_GOALS, villager, REPAIR_GOAL_PRIORITY,
                () -> new WeaponsmithRepairGoal(villager, jobPos, chestPos));
        repairGoal.setTargets(jobPos, chestPos);
        repairGoal.requestImmediateRepairCheck(world);

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            WeaponsmithDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            WeaponsmithRepairGoal repair = REPAIR_GOALS.get(pairedVillager);
            if (repair != null) {
                repair.requestImmediateRepairCheck(serverWorld);
            }
        });
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        WeaponsmithCraftingGoal craftingGoal = upsertGoal(CRAFTING_GOALS, villager, CRAFTING_GOAL_PRIORITY,
                () -> new WeaponsmithCraftingGoal(villager, jobPos, chestPos, craftingTablePos));
        craftingGoal.setTargets(jobPos, chestPos, craftingTablePos);
        craftingGoal.requestImmediateCraft(world);

        WeaponsmithDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new WeaponsmithDistributionGoal(villager, jobPos, chestPos, craftingTablePos));
        distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        distributionGoal.requestImmediateDistribution();

        WeaponsmithRepairGoal repairGoal = upsertGoal(REPAIR_GOALS, villager, REPAIR_GOAL_PRIORITY,
                () -> new WeaponsmithRepairGoal(villager, jobPos, chestPos));
        repairGoal.setTargets(jobPos, chestPos);
        repairGoal.requestImmediateRepairCheck(world);

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            WeaponsmithDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
            if (distribution != null) {
                distribution.requestImmediateDistribution();
            }
            WeaponsmithRepairGoal repair = REPAIR_GOALS.get(pairedVillager);
            if (repair != null) {
                repair.requestImmediateRepairCheck(serverWorld);
            }
        });
    }
}
