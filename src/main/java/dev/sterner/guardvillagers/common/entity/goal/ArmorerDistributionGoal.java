package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.ArmorerStandManager;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
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
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        if (!hasDistributableItem(inventory)) {
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            EquipmentSlot equipmentSlot = ((ArmorItem) stack.getItem()).getSlotType();
            if (findPlacementStand(world, equipmentSlot).isPresent()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            EquipmentSlot equipmentSlot = ((ArmorItem) stack.getItem()).getSlotType();
            Optional<ArmorStandEntity> stand = findPlacementStand(world, equipmentSlot);
            if (stand.isEmpty()) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            pendingItem = extracted;
            pendingSlot = equipmentSlot;
            pendingTargetId = stand.get().getUuid();
            pendingTargetPos = stand.get().getBlockPos();
            return true;
        }
        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        ArmorStandEntity stand = resolveTargetStand(world);
        if (stand != null && ArmorerStandManager.isStandAvailableForSlot(villager, stand, pendingSlot)) {
            pendingTargetPos = stand.getBlockPos();
            return true;
        }

        Optional<ArmorStandEntity> selectedStand = findPlacementStand(world, pendingSlot);
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
                && ArmorerStandManager.isStandAvailableForSlot(villager, stand, pendingSlot)
                && ArmorerStandManager.placeArmorOnStand(world, villager, stand, pendingItem);
    }

    @Override
    protected void clearPendingTargetState() {
        pendingSlot = null;
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.ARMORER;
    }

    private Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, EquipmentSlot slot) {
        return ArmorerStandManager.findPlacementStand(world, villager, getDistributionCenter(), slot);
    }

    private ArmorStandEntity resolveTargetStand(ServerWorld world) {
        if (pendingTargetId == null) {
            return null;
        }
        return world.getEntity(pendingTargetId) instanceof ArmorStandEntity stand ? stand : null;
    }
}
