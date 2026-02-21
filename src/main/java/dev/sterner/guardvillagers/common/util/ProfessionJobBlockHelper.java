package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.village.VillagerProfession;

import java.util.Map;
import java.util.Optional;

public final class ProfessionJobBlockHelper {
    private static final Map<VillagerProfession, Block> PROFESSION_JOB_BLOCKS = Map.ofEntries(
            Map.entry(VillagerProfession.ARMORER, Blocks.BLAST_FURNACE),
            Map.entry(VillagerProfession.BUTCHER, Blocks.SMOKER),
            Map.entry(VillagerProfession.CARTOGRAPHER, Blocks.CARTOGRAPHY_TABLE),
            Map.entry(VillagerProfession.CLERIC, Blocks.BREWING_STAND),
            Map.entry(VillagerProfession.FARMER, Blocks.COMPOSTER),
            Map.entry(VillagerProfession.FISHERMAN, Blocks.BARREL),
            Map.entry(VillagerProfession.FLETCHER, Blocks.FLETCHING_TABLE),
            Map.entry(VillagerProfession.LIBRARIAN, Blocks.LECTERN),
            Map.entry(VillagerProfession.LEATHERWORKER, Blocks.CAULDRON),
            Map.entry(VillagerProfession.MASON, Blocks.STONECUTTER),
            Map.entry(VillagerProfession.SHEPHERD, Blocks.LOOM),
            Map.entry(VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE),
            Map.entry(VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE)
    );

    private ProfessionJobBlockHelper() {
    }

    public static Optional<Block> resolveJobBlock(VillagerProfession profession, BlockState currentJobState) {
        if (!currentJobState.isAir()) {
            return Optional.of(currentJobState.getBlock());
        }
        return Optional.ofNullable(PROFESSION_JOB_BLOCKS.get(profession));
    }

    public static boolean hasSupportedJobBlock(VillagerProfession profession) {
        return PROFESSION_JOB_BLOCKS.containsKey(profession);
    }
}
