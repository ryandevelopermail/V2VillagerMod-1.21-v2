package dev.sterner.guardvillagers.common.util;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.item.ToolMaterials;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.List;
import java.util.OptionalInt;

public final class GuardGearGradeComparator {
    private static final List<String> ARMOR_TIER_ORDER = List.of(
            "leather",
            "chainmail",
            "iron",
            "gold",
            "diamond",
            "netherite"
    );
    private static final List<ToolMaterial> TOOL_TIER_ORDER = List.of(
            ToolMaterials.WOOD,
            ToolMaterials.STONE,
            ToolMaterials.IRON,
            ToolMaterials.GOLD,
            ToolMaterials.DIAMOND,
            ToolMaterials.NETHERITE
    );

    private GuardGearGradeComparator() {
    }

    public static boolean isHigherGrade(ItemStack candidate, ItemStack current) {
        return compare(candidate, current) > 0;
    }

    public static int compare(ItemStack candidate, ItemStack current) {
        OptionalInt candidateTier = getTier(candidate);
        OptionalInt currentTier = getTier(current);

        if (current.isEmpty() && candidateTier.isPresent()) {
            return 1;
        }
        if (candidate.isEmpty() && currentTier.isPresent()) {
            return -1;
        }
        if (candidateTier.isEmpty() || currentTier.isEmpty()) {
            return 0;
        }
        return Integer.compare(candidateTier.getAsInt(), currentTier.getAsInt());
    }

    private static OptionalInt getTier(ItemStack stack) {
        if (stack.isEmpty()) {
            return OptionalInt.empty();
        }

        if (stack.getItem() instanceof ArmorItem armorItem) {
            return getArmorTierIndex(armorItem.getMaterial());
        }

        if (stack.getItem() instanceof ToolItem toolItem) {
            return getToolTierIndex(toolItem.getMaterial());
        }

        return OptionalInt.empty();
    }

    private static OptionalInt getArmorTierIndex(RegistryEntry<ArmorMaterial> material) {
        return material.getKey()
                .map(RegistryKey::getValue)
                .map(id -> ARMOR_TIER_ORDER.indexOf(id.getPath()))
                .filter(index -> index >= 0)
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }

    private static OptionalInt getToolTierIndex(ToolMaterial material) {
        int index = TOOL_TIER_ORDER.indexOf(material);
        if (index < 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(index);
    }
}
