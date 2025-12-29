package dev.sterner.guardvillagers.common.villager;

import net.minecraft.block.Block;
import net.minecraft.util.Identifier;

public record SpecialModifier(Identifier id, Block block, double range) {
}
