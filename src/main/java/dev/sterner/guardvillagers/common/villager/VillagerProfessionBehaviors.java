package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.villager.behavior.ButcherBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.FarmerBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior;
import net.minecraft.village.VillagerProfession;

public final class VillagerProfessionBehaviors {
    private VillagerProfessionBehaviors() {
    }

    public static void register() {
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.BUTCHER, new ButcherBehavior());
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.FARMER, new FarmerBehavior());
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.SHEPHERD, new ShepherdBehavior());
    }
}
