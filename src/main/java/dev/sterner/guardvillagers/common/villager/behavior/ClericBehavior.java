package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public class ClericBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClericBehavior.class);
    private static final Map<VillagerEntity, BlockPos> PAIRED_CHESTS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearPairedChest(villager);
            return;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.BREWING_STAND)) {
            clearPairedChest(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearPairedChest(villager);
            return;
        }

        LOGGER.info("Cleric {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());
        PAIRED_CHESTS.put(villager, chestPos.toImmutable());
    }

    public static BlockPos getPairedChestPos(VillagerEntity villager) {
        BlockPos pos = PAIRED_CHESTS.get(villager);
        return pos == null ? null : pos.toImmutable();
    }

    public static void clearPairedChest(VillagerEntity villager) {
        PAIRED_CHESTS.remove(villager);
    }
}
