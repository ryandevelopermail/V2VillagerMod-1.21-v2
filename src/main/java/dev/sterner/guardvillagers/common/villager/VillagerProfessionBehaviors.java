package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.villager.behavior.ArmorerBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ButcherBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ClericBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.FarmerBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.LeatherworkerBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ToolsmithBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.WeaponsmithBehavior;
import net.minecraft.village.VillagerProfession;

public final class VillagerProfessionBehaviors {
    private VillagerProfessionBehaviors() {
    }

    public static void register() {
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.ARMORER, new ArmorerBehavior());
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.BUTCHER, new ButcherBehavior());
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.CLERIC, new ClericBehavior());
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.FARMER, new FarmerBehavior());
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.LEATHERWORKER, new LeatherworkerBehavior());
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.SHEPHERD, new ShepherdBehavior());
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.TOOLSMITH, new ToolsmithBehavior());
        VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.WEAPONSMITH, new WeaponsmithBehavior());
    }
}
