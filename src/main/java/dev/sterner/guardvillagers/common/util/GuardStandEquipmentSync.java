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
}
