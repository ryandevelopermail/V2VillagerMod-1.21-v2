package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.GuardVillagers;
import net.fabricmc.fabric.api.object.builder.v1.villager.VillagerProfessionBuilder;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestType;

public final class LumberjackProfession {
    public static final Identifier LUMBERJACK_ID = GuardVillagers.id("lumberjack");
    public static final RegistryKey<PointOfInterestType> LUMBERJACK_POI_KEY =
            RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, LUMBERJACK_ID);

    public static final PointOfInterestType LUMBERJACK_POI = PointOfInterestHelper.register(
            LUMBERJACK_ID,
            1,
            1,
            Blocks.CRAFTING_TABLE
    );

    public static final VillagerProfession LUMBERJACK = Registry.register(
            Registries.VILLAGER_PROFESSION,
            LUMBERJACK_ID,
            VillagerProfessionBuilder.create()
                    .id(LUMBERJACK_ID)
                    .workstation(LUMBERJACK_POI_KEY)
                    .workSound(SoundEvents.ENTITY_VILLAGER_WORK_FLETCHER)
                    .build()
    );

    private LumberjackProfession() {
    }

    public static void register() {
        // Intentionally empty: class loading performs registration.
    }
}
