package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.ArmorerStandManager;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.Optional;

public class ArmorerDistributionGoal extends AbstractInventoryDistributionGoal {
    private EquipmentSlot pendingSlot;

    public ArmorerDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ArmorItem;
    }

    @Override
    protected Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem armorItem)) {
            return Optional.empty();
        }
        return ArmorerStandManager.findPlacementStand(world, villager, getDistributionCenter(), armorItem.getSlotType());
    }

    @Override
    protected void onPendingItemSelected(ItemStack pendingItem) {
        if (pendingItem.getItem() instanceof ArmorItem armorItem) {
            pendingSlot = armorItem.getSlotType();
        } else {
            pendingSlot = null;
        }
    }

    @Override
    protected boolean isStandAvailableForPendingItem(ServerWorld world, ArmorStandEntity stand) {
        return pendingSlot != null && ArmorerStandManager.isStandAvailableForSlot(villager, stand, pendingSlot);
    }

    @Override
    protected boolean placePendingItemOnStand(ServerWorld world, ArmorStandEntity stand) {
        return pendingSlot != null && ArmorerStandManager.placeArmorOnStand(world, villager, stand, pendingItem);
    }

    @Override
    protected void clearPendingTargetState() {
        pendingSlot = null;
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.ARMORER;
    }
}
