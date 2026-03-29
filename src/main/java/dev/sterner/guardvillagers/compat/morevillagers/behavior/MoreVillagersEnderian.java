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
 * Behavior for the MoreVillagers Enderian profession.
 * Job block: morevillagers:purpur_altar
 *
 * Planned behaviors (not yet implemented):
 *  - Brew end-themed potions (slow falling, levitation) using a nearby brewing stand
 *  - Craft end items (ender pearls into eyes of ender, chorus fruit processing) at crafting table
 *  - Distribute end materials to other villager chests
 */
public class MoreVillagersEnderian extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoreVillagersEnderian.class);
    private static final String PROFESSION_ID = "morevillagers:enderian";
    private static final int PRIMARY_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;

    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isEnderianJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Enderian {} paired chest at {} for job site {}",
                villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        // TODO: register brewing goal (end-themed potions)
        // TODO: register distribution goal

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS,
                (w, v) -> sender -> {
                    // TODO: trigger immediate re-check on chest change
                });
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isEnderianJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Enderian {} paired crafting table at {} for job site {}",
                villager.getUuidAsString(), craftingTablePos.toShortString(), jobPos.toShortString());

        // TODO: register crafting goal (eyes of ender, popped chorus fruit, end rods)
    }

    private static boolean isEnderianJobBlock(ServerWorld world, BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock())
                .equals(Identifier.of("morevillagers", "purpur_altar"));
    }
}
