package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.village.VillagerProfession;

import java.util.Optional;

public final class ProfessionJobBlockHelper {
    private ProfessionJobBlockHelper() {
    }

    public static Optional<Block> resolveJobBlock(VillagerProfession profession, BlockState currentJobState) {
        return ProfessionDefinitions.resolveJobBlock(profession, currentJobState);
    }

    public static boolean hasSupportedJobBlock(VillagerProfession profession) {
        return ProfessionDefinitions.hasDefinition(profession);
    }
}
