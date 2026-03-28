package dev.sterner.guardvillagers.compat.morevillagers;

import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehaviorRegistry;
import dev.sterner.guardvillagers.compat.morevillagers.behavior.MoreVillagersEnderian;
import dev.sterner.guardvillagers.compat.morevillagers.behavior.MoreVillagersEngineer;
import dev.sterner.guardvillagers.compat.morevillagers.behavior.MoreVillagersFlorist;
import dev.sterner.guardvillagers.compat.morevillagers.behavior.MoreVillagersHunter;
import dev.sterner.guardvillagers.compat.morevillagers.behavior.MoreVillagersMiner;
import dev.sterner.guardvillagers.compat.morevillagers.behavior.MoreVillagersNetherian;
import dev.sterner.guardvillagers.compat.morevillagers.behavior.MoreVillagersOceanographer;
import dev.sterner.guardvillagers.compat.morevillagers.behavior.MoreVillagersWoodworker;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Registers GuardVillagers profession behaviors for MoreVillagers professions.
 * <p>
 * Must only be called after MoreVillagers has finished registering its professions
 * (i.e. from within {@code GuardVillagers.onInitialize()}, gated on
 * {@code FabricLoader.getInstance().isModLoaded("morevillagers")}).
 * <p>
 * Does NOT import any MoreVillagers class at compile time — all profession lookup
 * is done via the vanilla registry at runtime using string identifiers, so this
 * mod compiles and runs cleanly whether or not MoreVillagers is present.
 */
public final class MoreVillagersBehaviorBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoreVillagersBehaviorBridge.class);
    private static final String MV_NAMESPACE = "morevillagers";

    private MoreVillagersBehaviorBridge() {
    }

    /**
     * Looks up and registers behaviors for all 8 MoreVillagers professions.
     * Safe to call unconditionally inside an {@code isModLoaded} guard.
     */
    public static void register() {
        registerProfession("oceanographer", MoreVillagersOceanographer::new);
        registerProfession("netherian",     MoreVillagersNetherian::new);
        registerProfession("woodworker",    MoreVillagersWoodworker::new);
        registerProfession("enderian",      MoreVillagersEnderian::new);
        registerProfession("engineer",      MoreVillagersEngineer::new);
        registerProfession("florist",       MoreVillagersFlorist::new);
        registerProfession("hunter",        MoreVillagersHunter::new);
        registerProfession("miner",         MoreVillagersMiner::new);
    }

    private static void registerProfession(String name, java.util.function.Supplier<dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior> factory) {
        Identifier id = Identifier.of(MV_NAMESPACE, name);
        Optional<VillagerProfession> profession = Registries.VILLAGER_PROFESSION.getOrEmpty(id);
        if (profession.isEmpty()) {
            LOGGER.warn("[morevillagers-compat] Could not find profession '{}' in registry — skipping behavior registration.", id);
            return;
        }
        VillagerProfessionBehaviorRegistry.registerBehavior(profession.get(), factory.get());
        LOGGER.info("[morevillagers-compat] Registered behavior for profession '{}'.", id);
    }
}
