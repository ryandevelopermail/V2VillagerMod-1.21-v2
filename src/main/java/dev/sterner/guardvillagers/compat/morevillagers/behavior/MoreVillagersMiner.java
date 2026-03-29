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
 * Behavior for the MoreVillagers Miner profession.
 * Job block: morevillagers:mining_bench
 *
 * Planned behaviors (not yet implemented):
 *  - Mine nearby deepslate/stone/ore veins and deposit raw materials to chest
 *  - Smelt raw ores (copper, iron, gold) in a nearby furnace
 *  - Craft mining supplies (torches, ladders, rails) at crafting table
 *  - Distribute processed ores and mined goods to other villager chests
 *  - Possibly equip a pickaxe from the chest (similar to MasonBehavior mining tool pattern)
 */
public class MoreVillagersMiner extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoreVillagersMiner.class);
    private static final String PROFESSION_ID = "morevillagers:miner";
    private static final int PRIMARY_GOAL_PRIORITY = 3;
    private static final int SMELTING_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 6;

    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isMinerJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Miner {} paired chest at {} for job site {}",
                villager.getUuidAsString(), chestPos.toShortString(), jobPos.toShortString());

        // TODO: register mining goal (deepslate/stone scan, dig toward ore veins)
        // TODO: register smelting goal (raw copper/iron/gold → furnace)
        // TODO: register distribution goal
        // TODO: check chest for pickaxe and equip (see MasonBehavior mining tool pattern)

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS,
                (w, v) -> sender -> {
                    // TODO: trigger immediate re-check on chest change
                });
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (w, p) -> isMinerJobBlock(w, p),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("[morevillagers-compat] Miner {} paired crafting table at {} for job site {}",
                villager.getUuidAsString(), craftingTablePos.toShortString(), jobPos.toShortString());

        // TODO: register crafting goal (torches, ladders, rails, minecarts)
    }

    private static boolean isMinerJobBlock(ServerWorld world, BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock())
                .equals(Identifier.of("morevillagers", "mining_bench"));
    }
}
