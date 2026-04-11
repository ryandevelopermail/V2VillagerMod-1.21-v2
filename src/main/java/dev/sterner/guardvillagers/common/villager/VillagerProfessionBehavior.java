package dev.sterner.guardvillagers.common.villager;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public interface VillagerProfessionBehavior {
    /**
     * Called when a villager's job site is confirmed but no paired chest is present.
     * Useful for professions that can operate without a chest (v1 mode).
     */
    default void onJobSiteReady(ServerWorld world, VillagerEntity villager, BlockPos jobPos) {
    }

    default void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
    }

    default void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
    }

    default void onSpecialModifierPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, SpecialModifier modifier, BlockPos modifierPos) {
    }
}
