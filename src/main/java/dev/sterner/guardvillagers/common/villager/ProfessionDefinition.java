package dev.sterner.guardvillagers.common.villager;

import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public record ProfessionDefinition(
        Identifier professionId,
        VillagerProfession profession,
        Set<Block> expectedJobBlocks,
        Supplier<VillagerProfessionBehavior> behaviorFactory,
        Consumer<ServerWorld> conversionHook,
        List<SpecialModifier> specialModifiers
) {
    public ProfessionDefinition {
        expectedJobBlocks = Set.copyOf(expectedJobBlocks);
        specialModifiers = List.copyOf(specialModifiers);
    }

    public boolean hasConversionHook() {
        return conversionHook != null;
    }
}
