package dev.sterner.guardvillagers.compat.morevillagers.behavior;

import dev.sterner.guardvillagers.common.entity.goal.MoreVillagersOceanographerPaperCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.MoreVillagersOceanographerPaperDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.MoreVillagersOceanographerSugarCaneGoal;
import dev.sterner.guardvillagers.common.villager.behavior.AbstractPairedProfessionBehavior;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Behavior for the MoreVillagers Oceanographer profession.
 * Job block: morevillagers:oceanography_table
 *
 * Registered behaviors:
 *  - Harvest mature sugar cane columns while leaving the base planted
 *  - Plant protected bootstrap sugar cane from chest stock
 *  - Craft paper at a paired crafting table
 *  - Distribute paper to cartographer chests
 */
public class MoreVillagersOceanographer extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoreVillagersOceanographer.class);
    private static final int PRIMARY_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;

    private static final Map<VillagerEntity, MoreVillagersOceanographerSugarCaneGoal> SUGAR_CANE_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, MoreVillagersOceanographerPaperCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, MoreVillagersOceanographerPaperDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isOceanographerJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Oceanographer {} paired chest at {} for job site {}",
                villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        MoreVillagersOceanographerSugarCaneGoal sugarCaneGoal = upsertGoal(SUGAR_CANE_GOALS, villager, PRIMARY_GOAL_PRIORITY,
                () -> new MoreVillagersOceanographerSugarCaneGoal(villager, jobPos, chestPos));
        sugarCaneGoal.setTargets(jobPos, chestPos);
        sugarCaneGoal.requestImmediateCheck();

        MoreVillagersOceanographerPaperDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new MoreVillagersOceanographerPaperDistributionGoal(villager, jobPos, chestPos, null));
        distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        distributionGoal.requestImmediateDistribution();

        MoreVillagersOceanographerPaperCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }

        registerChestListener(world, villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isOceanographerJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Oceanographer {} paired crafting table at {} for job site {}",
                villager.getUuidAsString(), craftingTablePos.toShortString(), jobPos.toShortString());

        MoreVillagersOceanographerSugarCaneGoal sugarCaneGoal = upsertGoal(SUGAR_CANE_GOALS, villager, PRIMARY_GOAL_PRIORITY,
                () -> new MoreVillagersOceanographerSugarCaneGoal(villager, jobPos, chestPos));
        sugarCaneGoal.setTargets(jobPos, chestPos);
        sugarCaneGoal.requestImmediateCheck();

        MoreVillagersOceanographerPaperCraftingGoal craftingGoal = upsertGoal(CRAFTING_GOALS, villager, CRAFTING_GOAL_PRIORITY,
                () -> new MoreVillagersOceanographerPaperCraftingGoal(villager, jobPos, chestPos, craftingTablePos));
        craftingGoal.setTargets(jobPos, chestPos, craftingTablePos);
        craftingGoal.requestImmediateCraft(world);

        MoreVillagersOceanographerPaperDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new MoreVillagersOceanographerPaperDistributionGoal(villager, jobPos, chestPos, craftingTablePos));
        distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        distributionGoal.requestImmediateDistribution();

        registerChestListener(world, villager, chestPos);
    }

    private void registerChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        updateChestListener(world, villager, chestPos, CHEST_LISTENERS,
                (serverWorld, pairedVillager) -> sender -> {
                    MoreVillagersOceanographerSugarCaneGoal sugarCane = SUGAR_CANE_GOALS.get(pairedVillager);
                    if (sugarCane != null) {
                        sugarCane.requestImmediateCheck();
                    }
                    MoreVillagersOceanographerPaperCraftingGoal crafting = CRAFTING_GOALS.get(pairedVillager);
                    if (crafting != null) {
                        crafting.requestImmediateCraft(serverWorld);
                    }
                    MoreVillagersOceanographerPaperDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
                    if (distribution != null) {
                        distribution.requestImmediateDistribution();
                    }
                });
    }

    private static boolean isOceanographerJobBlock(ServerWorld world, BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock())
                .equals(Identifier.of("morevillagers", "oceanography_table"));
    }
}
