package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;

import java.util.List;
import java.util.Optional;

/** Server-only extension point for profession-specific storage tabs. */
@FunctionalInterface
public interface ProfessionalStorageProfileProvider {
    Optional<List<ProfessionalStorageTab>> createTabs(
            ServerWorld world,
            StorageIdentity storage,
            List<ProfessionalStorageResolution> resolutions
    );
}
