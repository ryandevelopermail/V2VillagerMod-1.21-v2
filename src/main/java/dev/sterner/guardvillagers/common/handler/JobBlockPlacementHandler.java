package dev.sterner.guardvillagers.common.handler;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class JobBlockPlacementHandler {
    private static final GuardModifierApplicationService GUARD_MODIFIER_SERVICE = new GuardModifierApplicationService();
    private static final PairingPlacementHandler PAIRING_PLACEMENT_HANDLER = new PairingPlacementHandler(GUARD_MODIFIER_SERVICE);
    private static final ArmorStandConversionHandler ARMOR_STAND_CONVERSION_HANDLER = new ArmorStandConversionHandler(GUARD_MODIFIER_SERVICE);

    private JobBlockPlacementHandler() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(JobBlockPlacementHandler::onUseBlock);
    }

    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return ActionResult.PASS;
        }

        ItemStack stack = player.getStackInHand(hand);
        boolean isArmorStand = JobBlockPlacementConstants.isArmorStandItem(stack);
        boolean isPairingBlockItem = stack.getItem() instanceof BlockItem blockItem && JobBlockPlacementConstants.isPairingBlock(blockItem.getBlock());
        boolean isCraftingTableItem = stack.getItem() instanceof BlockItem blockItem && JobBlockPlacementConstants.isCraftingTable(blockItem.getBlock());
        boolean isBannerItem = stack.getItem() instanceof BlockItem blockItem && JobBlockPlacementConstants.isBannerBlock(blockItem.getBlock());
        boolean isSpecialModifierItem = stack.getItem() instanceof BlockItem blockItem && JobBlockPlacementConstants.isSpecialModifierBlock(blockItem.getBlock());

        if (!isArmorStand && !isPairingBlockItem && !isCraftingTableItem && !isSpecialModifierItem && !isBannerItem) {
            return ActionResult.PASS;
        }

        ItemPlacementContext placementContext = new ItemPlacementContext(player, hand, stack, hitResult);
        BlockPos placementPos = serverWorld.getBlockState(placementContext.getBlockPos()).canReplace(placementContext) ? placementContext.getBlockPos() : placementContext.getBlockPos().offset(placementContext.getSide());

        serverWorld.getServer().execute(() -> {
            PAIRING_PLACEMENT_HANDLER.handlePlacement(serverWorld, placementPos, isPairingBlockItem, isCraftingTableItem, isSpecialModifierItem, isBannerItem);
            if (isArmorStand) {
                ARMOR_STAND_CONVERSION_HANDLER.tryConvertVillagerWithArmorStand(serverWorld, placementPos);
            }
        });
        return ActionResult.PASS;
    }
}
