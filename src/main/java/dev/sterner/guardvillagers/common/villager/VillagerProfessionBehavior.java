package dev.sterner.guardvillagers.common.villager;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public interface VillagerProfessionBehavior {
    default void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
    }

    default void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
    }

    default void onSpecialModifierPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, SpecialModifier modifier, BlockPos modifierPos) {
    }
}
