package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;

public final class GuardStandEquipmentSync {
    private GuardStandEquipmentSync() {
    }

    public static void syncStandFromGuard(GuardEntity guard, ArmorStandEntity stand) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!(slot.isArmorSlot() || slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND)) {
                continue;
            }

            ItemStack stack = guard.getEquippedStack(slot);
            if (stack.isEmpty()) {
                stand.equipStack(slot, ItemStack.EMPTY);
                continue;
            }

            ItemStack copy = stack.copy();
            copy.setCount(1);
            stand.equipStack(slot, copy);
        }
    }

    public static void syncGuardFromStand(GuardEntity guard, ArmorStandEntity stand) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!(slot.isArmorSlot() || slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND)) {
                continue;
            }

            ItemStack stack = stand.getEquippedStack(slot);
            ItemStack copy = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
            if (!copy.isEmpty()) {
                copy.setCount(1);
            }
            guard.equipStack(slot, copy);
            guard.guardInventory.setStack(slotToInventoryIndex(slot), copy);
        }
    }

    public static boolean hasEquipment(ArmorStandEntity stand) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!(slot.isArmorSlot() || slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND)) {
                continue;
            }
            if (!stand.getEquippedStack(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static int slotToInventoryIndex(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            case OFFHAND -> 4;
            case MAINHAND -> 5;
            default -> 0;
        };
    }
}
