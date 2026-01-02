package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.item.ToolMaterials;
import net.minecraft.item.AxeItem;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.List;
import java.util.OptionalInt;

public final class GearGradeComparator {
    private static final List<ToolMaterial> TOOL_TIER_ORDER = List.of(
            ToolMaterials.WOOD,
            ToolMaterials.STONE,
            ToolMaterials.IRON,
            ToolMaterials.GOLD,
            ToolMaterials.DIAMOND,
            ToolMaterials.NETHERITE
    );

    private GearGradeComparator() {
    }

    public static boolean isUpgrade(ItemStack candidate, ItemStack current, EquipmentSlot slot) {
        if (candidate.isEmpty()) {
            return false;
        }

        if (current.isEmpty()) {
            return true;
        }

        int tierComparison = compareTier(candidate, current, slot);
        if (tierComparison != 0) {
            return tierComparison > 0;
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

    private static int compareTier(ItemStack candidate, ItemStack current, EquipmentSlot slot) {
        OptionalInt candidateTier = getTier(candidate, slot);
        OptionalInt currentTier = getTier(current, slot);

        if (candidateTier.isPresent() && currentTier.isPresent()) {
            int comparison = Integer.compare(candidateTier.getAsInt(), currentTier.getAsInt());
            if (comparison != 0) {
                return comparison;
            }
        }

        return 0;
    }

    private static OptionalInt getTier(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return OptionalInt.empty();
        }

        if (slot.isArmorSlot() && stack.getItem() instanceof ArmorItem armorItem) {
            return getArmorTierIndex(armorItem.getMaterial());
        }

        if ((slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND)
                && (stack.getItem() instanceof ToolItem
                || stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem)) {
            return getToolTierIndex(((ToolItem) stack.getItem()).getMaterial());
        }

        return OptionalInt.empty();
    }

    private static OptionalInt getArmorTierIndex(RegistryEntry<ArmorMaterial> material) {
        return material.getKey()
                .map(RegistryKey::getValue)
                .map(id -> armorTierIndex(id.getPath()))
                .filter(index -> index >= 0)
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }

    private static int armorTierIndex(String materialPath) {
        return switch (materialPath) {
            case "leather" -> 0;
            case "chain", "chainmail" -> 1;
            case "iron" -> 2;
            case "gold" -> 3;
            case "diamond" -> 4;
            case "netherite" -> 5;
            default -> -1;
        };
    }

    private static OptionalInt getToolTierIndex(ToolMaterial material) {
        int index = TOOL_TIER_ORDER.indexOf(material);
        if (index < 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(index);
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
