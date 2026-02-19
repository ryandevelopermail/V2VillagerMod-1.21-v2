package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.WeaponsmithStandManager;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.Optional;

public class WeaponsmithDistributionGoal extends AbstractInventoryDistributionGoal {
    public WeaponsmithDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof MaceItem;
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        return hasDistributableItem(inventory) && findPlacementStand(world).isPresent();
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        ItemStack extracted = extractSingleDistributableItem(inventory);
        if (extracted.isEmpty()) {
            return false;
        }
        pendingItem = extracted;

        Optional<ArmorStandEntity> stand = findPlacementStand(world);
        if (stand.isEmpty()) {
            pendingItem = ItemStack.EMPTY;
            return false;
        }

        pendingTargetId = stand.get().getUuid();
        pendingTargetPos = stand.get().getBlockPos();
        return true;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        ArmorStandEntity stand = resolveTargetStand(world);
        if (stand != null && WeaponsmithStandManager.isStandAvailableForHand(villager, stand, EquipmentSlot.MAINHAND)) {
            pendingTargetPos = stand.getBlockPos();
            return true;
        }

        Optional<ArmorStandEntity> selectedStand = findPlacementStand(world);
        if (selectedStand.isEmpty()) {
            return false;
        }
        pendingTargetId = selectedStand.get().getUuid();
        pendingTargetPos = selectedStand.get().getBlockPos();
        return true;
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        ArmorStandEntity stand = resolveTargetStand(world);
        return stand != null
                && WeaponsmithStandManager.isStandAvailableForHand(villager, stand, EquipmentSlot.MAINHAND)
                && WeaponsmithStandManager.placeWeaponOnStand(world, villager, stand, pendingItem, EquipmentSlot.MAINHAND);
    }

    @Override
    protected void clearPendingTargetState() {
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.WEAPONSMITH;
    }

    private Optional<ArmorStandEntity> findPlacementStand(ServerWorld world) {
        return WeaponsmithStandManager.findPlacementStand(world, villager, getDistributionCenter(), EquipmentSlot.MAINHAND);
    }

    private ArmorStandEntity resolveTargetStand(ServerWorld world) {
        if (pendingTargetId == null) {
            return null;
        }
        return world.getEntity(pendingTargetId) instanceof ArmorStandEntity stand ? stand : null;
    }
}
