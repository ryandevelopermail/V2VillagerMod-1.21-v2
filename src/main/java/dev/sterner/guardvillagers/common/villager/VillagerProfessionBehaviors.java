package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.behavior.FarmerBehavior;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.village.VillagerProfession;

public final class VillagerProfessionBehaviors {
    private VillagerProfessionBehaviors() {
    }

    public static void register() {
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.FARMER, new FarmerBehavior());
        Registries.BLOCK.stream()
                .filter(block -> Registries.BLOCK.getEntry(block).isIn(BlockTags.BANNERS))
                .forEach(block -> VillagerProfessionBehaviorRegistry.registerSpecialModifier(
                        new SpecialModifier(GuardVillagers.id("farmer_banner"), block, VillageGuardStandManager.BELL_EFFECT_RANGE)
                ));
    }
}
