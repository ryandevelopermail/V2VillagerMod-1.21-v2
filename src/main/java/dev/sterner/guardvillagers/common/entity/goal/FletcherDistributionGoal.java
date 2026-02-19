package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.WeaponsmithStandManager;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.Optional;

public class FletcherDistributionGoal extends AbstractInventoryDistributionGoal {
    public FletcherDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }

    @Override
    protected Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, ItemStack stack) {
        return WeaponsmithStandManager.findPlacementStand(world, villager, getDistributionCenter(), EquipmentSlot.MAINHAND);
    }

    @Override
    protected boolean isStandAvailableForPendingItem(ServerWorld world, ArmorStandEntity stand) {
        return WeaponsmithStandManager.isStandAvailableForHand(villager, stand, EquipmentSlot.MAINHAND);
    }

    @Override
    protected boolean placePendingItemOnStand(ServerWorld world, ArmorStandEntity stand) {
        return WeaponsmithStandManager.placeWeaponOnStand(world, villager, stand, pendingItem, EquipmentSlot.MAINHAND);
    }

    @Override
    protected void clearPendingTargetState() {
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.FLETCHER;
    }
}
