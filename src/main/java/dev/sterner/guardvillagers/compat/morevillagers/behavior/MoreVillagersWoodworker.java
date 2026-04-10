package dev.sterner.guardvillagers.compat.morevillagers.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ForesterSaplingPlantingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ForesterSaplingProvisionGoal;
import dev.sterner.guardvillagers.common.entity.goal.ForesterTreeDropPickupGoal;
import dev.sterner.guardvillagers.common.villager.behavior.AbstractPairedProfessionBehavior;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Behavior for the MoreVillagers "Forester" profession.
 *
 * <p>Despite the display name "Forester", MoreVillagers internally registers this
 * profession as {@code morevillagers:woodworker} with job block
 * {@code morevillagers:woodworking_table} ("Forestry Bench").
 *
 * <p>Registered behaviors (active once a chest is paired):
 * <ol>
 *   <li><b>ForesterSaplingProvisionGoal</b> (priority 3) – each day, places 4
 *       biome-appropriate tree saplings in the paired chest.</li>
 *   <li><b>ForesterSaplingPlantingGoal</b> (priority 4) – fetches those saplings
 *       from the chest and plants them at the village outskirts (16–64 blocks from
 *       the QM anchor), spaced at least 5 blocks apart.</li>
 *   <li><b>ForesterTreeDropPickupGoal</b> (priority 5) – scans a 100-block radius
 *       for tree drops (saplings, sticks, apples, logs) left by Lumberjacks and
 *       deposits them in the paired chest.</li>
 * </ol>
 */
public class MoreVillagersWoodworker extends AbstractPairedProfessionBehavior {

    private static final Logger LOGGER = LoggerFactory.getLogger(MoreVillagersWoodworker.class);

    private static final int PROVISION_GOAL_PRIORITY = 3;
    private static final int PLANTING_GOAL_PRIORITY = 4;
    private static final int PICKUP_GOAL_PRIORITY = 5;

    // V2 goals (chest-paired)
    private static final Map<VillagerEntity, ForesterSaplingProvisionGoal> PROVISION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ForesterSaplingPlantingGoal> PLANTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ForesterTreeDropPickupGoal> PICKUP_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    // V1 goals (no chest – provision into own inventory, plant from inventory)
    private static final Map<VillagerEntity, ForesterSaplingProvisionGoal> V1_PROVISION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ForesterSaplingPlantingGoal> V1_PLANTING_GOALS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        boolean isJobBlock = isWoodworkerJobBlock(world, jobPos);
        boolean isAlive = villager.isAlive();
        boolean inRange = jobPos.isWithinDistance(chestPos, CHEST_PAIR_RANGE);
        LOGGER.info("[morevillagers-compat] Forester {} onChestPaired: jobPos={} chestPos={} isJobBlock={} isAlive={} inRange={}",
                villager.getUuidAsString(), jobPos.toShortString(), chestPos.toShortString(),
                isJobBlock, isAlive, inRange);
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isWoodworkerJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            LOGGER.warn("[morevillagers-compat] Forester {} onChestPaired REJECTED (isJobBlock={} isAlive={} inRange={})",
                    villager.getUuidAsString(), isJobBlock, isAlive, inRange);
            return;
        }

        LOGGER.info("[morevillagers-compat] Forester {} paired chest at {} for job site {}",
                villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        // Remove any lingering V1 goals before registering V2 goals
        ForesterSaplingProvisionGoal v1Prov = V1_PROVISION_GOALS.remove(villager);
        if (v1Prov != null) villager.goalSelector.remove(v1Prov);
        ForesterSaplingPlantingGoal v1Plant = V1_PLANTING_GOALS.remove(villager);
        if (v1Plant != null) villager.goalSelector.remove(v1Plant);

        // Sapling provision goal – stocks the chest with 4 biome-appropriate saplings each dawn
        ForesterSaplingProvisionGoal provisionGoal = upsertGoal(PROVISION_GOALS, villager, PROVISION_GOAL_PRIORITY,
                () -> new ForesterSaplingProvisionGoal(villager, jobPos, chestPos));
        provisionGoal.setTargets(jobPos, chestPos);

        // Planting goal – fetches saplings from chest and plants at outskirts
        ForesterSaplingPlantingGoal plantingGoal = upsertGoal(PLANTING_GOALS, villager, PLANTING_GOAL_PRIORITY,
                () -> new ForesterSaplingPlantingGoal(villager, jobPos, chestPos));
        plantingGoal.setTargets(jobPos, chestPos);

        // Wire provision↔planting feedback
        plantingGoal.linkProvisionGoal(provisionGoal);

        // Pickup goal – scans 100 blocks for tree drops and deposits them in chest
        ForesterTreeDropPickupGoal pickupGoal = upsertGoal(PICKUP_GOALS, villager, PICKUP_GOAL_PRIORITY,
                () -> new ForesterTreeDropPickupGoal(villager, jobPos, chestPos));
        pickupGoal.setTargets(jobPos, chestPos);

        // Chest listener: wake planting goal when chest contents change
        updateChestListener(world, villager, chestPos, CHEST_LISTENERS,
                (w, v) -> sender -> {
                    ForesterSaplingPlantingGoal planting = PLANTING_GOALS.get(v);
                    if (planting != null) {
                        planting.requestImmediateWorkCheck();
                    }
                });
    }

    @Override
    public void onJobSiteReady(ServerWorld world, VillagerEntity villager, BlockPos jobPos) {
        boolean isJobBlock = isWoodworkerJobBlock(world, jobPos);
        boolean hasV2 = PROVISION_GOALS.containsKey(villager) || PLANTING_GOALS.containsKey(villager);
        LOGGER.info("[morevillagers-compat] Forester {} onJobSiteReady: jobPos={} blockAt={} isJobBlock={} hasV2Goals={}",
                villager.getUuidAsString(), jobPos.toShortString(),
                Registries.BLOCK.getId(world.getBlockState(jobPos).getBlock()),
                isJobBlock, hasV2);
        if (!isJobBlock) return;

        // Skip if this villager already has v2 (chest-paired) goals – those are richer and take precedence
        if (hasV2) return;

        LOGGER.info("[morevillagers-compat] Forester {} registered v1 (chestless) planting goals for job site {}",
                villager.getUuidAsString(), jobPos.toShortString());

        // Provision goal – stocks the villager's own inventory with 4 biome-appropriate saplings each dawn
        ForesterSaplingProvisionGoal provisionGoal = upsertGoal(V1_PROVISION_GOALS, villager, PROVISION_GOAL_PRIORITY,
                () -> new ForesterSaplingProvisionGoal(villager, jobPos, null));
        provisionGoal.setTargets(jobPos, null);

        // Planting goal – plants from villager inventory
        ForesterSaplingPlantingGoal plantingGoal = upsertGoal(V1_PLANTING_GOALS, villager, PLANTING_GOAL_PRIORITY,
                () -> new ForesterSaplingPlantingGoal(villager, jobPos, null));
        plantingGoal.setTargets(jobPos, null);

        // Wire provision↔planting feedback
        plantingGoal.linkProvisionGoal(provisionGoal);
    }

    // -----------------------------------------------------------------------------------------
    // Job block detection
    // -----------------------------------------------------------------------------------------

    private static boolean isWoodworkerJobBlock(ServerWorld world, BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock())
                .equals(Identifier.of("morevillagers", "woodworking_table"));
    }
}
