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
 * Behavior for the MoreVillagers Forester profession.
 * Job block: {@code morevillagers:sapling_pot}
 *
 * <p>Registered behaviors (active once a chest is paired):
 * <ol>
 *   <li><b>ForesterSaplingProvisionGoal</b> (priority 3) — each day, places 4
 *       biome-appropriate saplings in the paired chest.</li>
 *   <li><b>ForesterSaplingPlantingGoal</b> (priority 4) — fetches those saplings
 *       from the chest and plants them at the village outskirts (16–64 blocks from
 *       the QM anchor), spaced at least 5 blocks apart.</li>
 *   <li><b>ForesterTreeDropPickupGoal</b> (priority 5) — scans a 100-block radius
 *       for tree drops (saplings, sticks, apples, logs) left by Lumberjacks and
 *       deposits them in the paired chest.</li>
 * </ol>
 *
 * <p>All job-block identification is done at runtime via the block registry so this
 * class compiles and runs without importing any MoreVillagers class directly.
 */
public class MoreVillagersForester extends AbstractPairedProfessionBehavior {

    private static final Logger LOGGER = LoggerFactory.getLogger(MoreVillagersForester.class);

    private static final int PROVISION_GOAL_PRIORITY = 3;
    private static final int PLANTING_GOAL_PRIORITY = 4;
    private static final int PICKUP_GOAL_PRIORITY = 5;

    private static final Map<VillagerEntity, ForesterSaplingProvisionGoal> PROVISION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ForesterSaplingPlantingGoal> PLANTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ForesterTreeDropPickupGoal> PICKUP_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isForesterJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Forester {} paired chest at {} for job site {}",
                villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        // Sapling provision goal — stocks the chest with 4 biome-appropriate saplings each dawn
        ForesterSaplingProvisionGoal provisionGoal = upsertGoal(PROVISION_GOALS, villager, PROVISION_GOAL_PRIORITY,
                () -> new ForesterSaplingProvisionGoal(villager, jobPos, chestPos));
        provisionGoal.setTargets(jobPos, chestPos);

        // Planting goal — fetches saplings from chest and plants at outskirts
        ForesterSaplingPlantingGoal plantingGoal = upsertGoal(PLANTING_GOALS, villager, PLANTING_GOAL_PRIORITY,
                () -> new ForesterSaplingPlantingGoal(villager, jobPos, chestPos));
        plantingGoal.setTargets(jobPos, chestPos);

        // Pickup goal — scans 100 blocks for tree drops and deposits them in chest
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

    // -------------------------------------------------------------------------
    // Job block detection
    // -------------------------------------------------------------------------

    private static boolean isForesterJobBlock(ServerWorld world, BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock())
                .equals(Identifier.of("morevillagers", "sapling_pot"));
    }
}
