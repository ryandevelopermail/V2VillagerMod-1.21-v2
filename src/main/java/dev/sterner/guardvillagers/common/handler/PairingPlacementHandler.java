package dev.sterner.guardvillagers.common.handler;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class PairingPlacementHandler {
    private final GuardModifierApplicationService guardModifierService;

    public PairingPlacementHandler(GuardModifierApplicationService guardModifierService) {
        this.guardModifierService = guardModifierService;
    }

    public void handlePlacement(ServerWorld world, BlockPos placementPos, boolean checkPairingBlocks, boolean checkCraftingTable, boolean checkSpecialModifier, boolean checkBanner) {
        BlockState placedState = world.getBlockState(placementPos);

        if (checkPairingBlocks && JobBlockPlacementConstants.isPairingBlock(placedState)) {
            JobBlockPairingHelper.handlePairingBlockPlacement(world, placementPos, placedState);
        }

        if (checkCraftingTable && JobBlockPlacementConstants.isCraftingTable(placedState)) {
            JobBlockPairingHelper.handleCraftingTablePlacement(world, placementPos);
        }

        if (checkSpecialModifier && JobBlockPlacementConstants.isSpecialModifierBlock(placedState.getBlock())) {
            JobBlockPairingHelper.handleSpecialModifierPlacement(world, placementPos, placedState);
            guardModifierService.applySpecialModifierToNearbyConvertedGuards(world, placementPos, placedState);
        }

        if (checkBanner && JobBlockPlacementConstants.isBannerBlock(placedState)) {
            JobBlockPairingHelper.handleBannerPlacement(world, placementPos, placedState);
        }
    }
}
