package dev.sterner.guardvillagers.compat.morevillagers.behavior;

import dev.sterner.guardvillagers.common.entity.goal.MoreVillagersHunterCombatGoal;
import dev.sterner.guardvillagers.common.entity.goal.MoreVillagersHunterNoSleepGoal;
import dev.sterner.guardvillagers.common.entity.goal.MoreVillagersHunterStringDistributionGoal;
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
 * Behavior for the MoreVillagers Hunter profession.
 * Job block: morevillagers:hunting_post
 */
public class MoreVillagersHunter extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoreVillagersHunter.class);
    private static final int PRIMARY_GOAL_PRIORITY = 3;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;
    private static final int NO_SLEEP_GOAL_PRIORITY = 1;

    private static final Map<VillagerEntity, MoreVillagersHunterCombatGoal> HUNT_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, MoreVillagersHunterStringDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, MoreVillagersHunterNoSleepGoal> NO_SLEEP_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onJobSiteReady(ServerWorld world, VillagerEntity villager, BlockPos jobPos) {
        if (!villager.isAlive() || !isHunterJobBlock(world, jobPos)) {
            return;
        }
        upsertGoal(NO_SLEEP_GOALS, villager, NO_SLEEP_GOAL_PRIORITY,
                () -> new MoreVillagersHunterNoSleepGoal(villager));
    }

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isHunterJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Hunter {} paired chest at {} for job site {}",
                villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        upsertGoal(NO_SLEEP_GOALS, villager, NO_SLEEP_GOAL_PRIORITY,
                () -> new MoreVillagersHunterNoSleepGoal(villager));

        MoreVillagersHunterCombatGoal huntGoal = upsertGoal(HUNT_GOALS, villager, PRIMARY_GOAL_PRIORITY,
                () -> new MoreVillagersHunterCombatGoal(villager, jobPos, chestPos));
        huntGoal.setTargets(jobPos, chestPos);
        huntGoal.requestImmediateCheck();

        MoreVillagersHunterStringDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new MoreVillagersHunterStringDistributionGoal(villager, jobPos, chestPos, null));
        distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        distributionGoal.requestImmediateDistribution();

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS,
                (serverWorld, pairedVillager) -> sender -> {
                    MoreVillagersHunterCombatGoal hunt = HUNT_GOALS.get(pairedVillager);
                    if (hunt != null) {
                        hunt.requestImmediateCheck();
                    }
                    MoreVillagersHunterStringDistributionGoal distribution = DISTRIBUTION_GOALS.get(pairedVillager);
                    if (distribution != null) {
                        distribution.requestImmediateDistribution();
                    }
                });
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isHunterJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Hunter {} paired crafting table at {} for job site {}",
                villager.getUuidAsString(), craftingTablePos.toShortString(), jobPos.toShortString());

        MoreVillagersHunterStringDistributionGoal distributionGoal = upsertGoal(DISTRIBUTION_GOALS, villager, DISTRIBUTION_GOAL_PRIORITY,
                () -> new MoreVillagersHunterStringDistributionGoal(villager, jobPos, chestPos, craftingTablePos));
        distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        distributionGoal.requestImmediateDistribution();
    }

    private static boolean isHunterJobBlock(ServerWorld world, BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock())
                .equals(Identifier.of("morevillagers", "hunting_post"));
    }
}
