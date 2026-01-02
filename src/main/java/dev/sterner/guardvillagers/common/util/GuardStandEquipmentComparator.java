package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;

public final class GuardStandEquipmentComparator {
    private GuardStandEquipmentComparator() {
    }

    public static boolean isUpgrade(ItemStack candidate, ItemStack current, EquipmentSlot slot) {
        if (candidate.isEmpty()) {
            return false;
        }

        if (current.isEmpty()) {
            return true;
        }

        if (slot.isArmorSlot()) {
            int armorComparison = Double.compare(
                    getAttributeTotal(candidate, slot, EntityAttributes.GENERIC_ARMOR),
                    getAttributeTotal(current, slot, EntityAttributes.GENERIC_ARMOR)
            );
            if (armorComparison != 0) {
                return armorComparison > 0;
            }

            int toughnessComparison = Double.compare(
                    getAttributeTotal(candidate, slot, EntityAttributes.GENERIC_ARMOR_TOUGHNESS),
                    getAttributeTotal(current, slot, EntityAttributes.GENERIC_ARMOR_TOUGHNESS)
            );
            if (toughnessComparison != 0) {
                return toughnessComparison > 0;
            }

            int knockbackComparison = Double.compare(
                    getAttributeTotal(candidate, slot, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE),
                    getAttributeTotal(current, slot, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE)
            );
            if (knockbackComparison != 0) {
                return knockbackComparison > 0;
            }
        }

        if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
            int damageComparison = Double.compare(
                    getAttributeTotal(candidate, slot, EntityAttributes.GENERIC_ATTACK_DAMAGE),
                    getAttributeTotal(current, slot, EntityAttributes.GENERIC_ATTACK_DAMAGE)
            );
            if (damageComparison != 0) {
                return damageComparison > 0;
            }

            int speedComparison = Double.compare(
                    getAttributeTotal(candidate, slot, EntityAttributes.GENERIC_ATTACK_SPEED),
                    getAttributeTotal(current, slot, EntityAttributes.GENERIC_ATTACK_SPEED)
            );
            if (speedComparison != 0) {
                return speedComparison > 0;
            }
        }

        if (candidate.isOf(current.getItem()) && candidate.isDamageable() && current.isDamageable()) {
            return candidate.getDamage() < current.getDamage();
        }

        return false;
    }

    private static double getAttributeTotal(ItemStack stack, EquipmentSlot slot, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute) {
        double[] total = new double[1];
        stack.applyAttributeModifiers(slot, (entry, modifier) -> {
            if (entry.equals(attribute)) {
                total[0] += modifier.value();
            }
        });
        return total[0];
    }
}
