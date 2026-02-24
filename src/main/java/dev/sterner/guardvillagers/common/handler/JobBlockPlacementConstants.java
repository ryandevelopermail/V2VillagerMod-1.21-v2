package dev.sterner.guardvillagers.common.handler;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ArmorStandItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;

public final class JobBlockPlacementConstants {
    public static final double ARMOR_STAND_PAIRING_RANGE = JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE;
    public static final double ARMOR_STAND_PLACEMENT_CHECK_RANGE = 0.75D;

    private JobBlockPlacementConstants() {
    }

    public static boolean isArmorStandItem(ItemStack stack) {
        return stack.getItem() instanceof ArmorStandItem;
    }

    public static boolean isPairingBlock(Block block) {
        return JobBlockPairingHelper.isPairingBlock(block);
    }

    public static boolean isPairingBlock(BlockState state) {
        return JobBlockPairingHelper.isPairingBlock(state);
    }

    public static boolean isCraftingTable(Block block) {
        return JobBlockPairingHelper.isCraftingTable(block);
    }

    public static boolean isCraftingTable(BlockState state) {
        return JobBlockPairingHelper.isCraftingTable(state);
    }

    public static boolean isSpecialModifierBlock(Block block) {
        return JobBlockPairingHelper.isSpecialModifierBlock(block);
    }

    public static boolean isBannerBlock(Block block) {
        return block.getDefaultState().isIn(BlockTags.BANNERS);
    }

    public static boolean isBannerBlock(BlockState state) {
        return state.isIn(BlockTags.BANNERS);
    }
}
