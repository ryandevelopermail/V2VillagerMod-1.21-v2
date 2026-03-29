package dev.sterner.guardvillagers.compat.morevillagers.behavior;

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
 * Behavior for the MoreVillagers Engineer profession.
 * Job block: morevillagers:blueprint_table
 *
 * Planned behaviors (not yet implemented):
 *  - Craft redstone components (repeaters, comparators, observers, pistons) at crafting table
 *  - Distribute crafted redstone goods to other villager chests
 *  - Possibly interact with nearby hoppers/dispensers for automated item routing
 */
public class MoreVillagersEngineer extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoreVillagersEngineer.class);
    private static final String PROFESSION_ID = "morevillagers:engineer";
    private static final int PRIMARY_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;

    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isEngineerJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Engineer {} paired chest at {} for job site {}",
                villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        // TODO: register distribution goal

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS,
                (w, v) -> sender -> {
                    // TODO: trigger immediate re-check on chest change
                });
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isEngineerJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Engineer {} paired crafting table at {} for job site {}",
                villager.getUuidAsString(), craftingTablePos.toShortString(), jobPos.toShortString());

        // TODO: register crafting goal (repeaters, comparators, observers, pistons, hoppers)
    }

    private static boolean isEngineerJobBlock(ServerWorld world, BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock())
                .equals(Identifier.of("morevillagers", "blueprint_table"));
    }
}
