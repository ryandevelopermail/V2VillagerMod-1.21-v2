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
 * Behavior for the MoreVillagers Hunter profession.
 * Job block: morevillagers:hunting_post
 *
 * Planned behaviors (not yet implemented):
 *  - Hunt nearby passive/neutral mobs and collect drops (bones, spider eyes, gunpowder)
 *  - Process mob drops (cook meat, craft fermented spider eyes) at crafting table
 *  - Distribute collected drops to other villager chests
 *  - Possibly equip a bow/sword from the chest (similar to ButcherBehavior weapon check)
 */
public class MoreVillagersHunter extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoreVillagersHunter.class);
    private static final String PROFESSION_ID = "morevillagers:hunter";
    private static final int PRIMARY_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;

    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isHunterJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Hunter {} paired chest at {} for job site {}",
                villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        // TODO: register hunt goal (target passive/neutral mobs, collect drops)
        // TODO: register distribution goal
        // TODO: check chest for weapon and equip (see ButcherBehavior.tryConvertWithWeapon pattern)

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS,
                (w, v) -> sender -> {
                    // TODO: trigger immediate re-check on chest change
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

        // TODO: register crafting goal (fermented spider eyes, magma cream, blaze powder)
    }

    private static boolean isHunterJobBlock(ServerWorld world, BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock())
                .equals(Identifier.of("morevillagers", "hunting_post"));
    }
}
