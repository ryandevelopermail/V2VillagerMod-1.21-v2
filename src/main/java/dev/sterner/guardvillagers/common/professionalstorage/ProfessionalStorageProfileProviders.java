package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Selects one bounded profile provider only for homogeneous supported roles. */
public final class ProfessionalStorageProfileProviders {
    private static final Map<ProfessionalRoleId, ProfessionalStorageProfileProvider> PROVIDERS = new HashMap<>();

    private ProfessionalStorageProfileProviders() {
    }

    public static void registerDefaults() {
        register(
                ProfessionalRoleId.fromVillagerProfession(VillagerProfession.FARMER),
                new FarmerProfessionalStorageProfileProvider());
    }

    public static void register(ProfessionalRoleId role, ProfessionalStorageProfileProvider provider) {
        PROVIDERS.put(role, provider);
    }

    public static Optional<List<ProfessionalStorageRow>> createRows(
            ServerWorld world,
            StorageIdentity storage,
            List<ProfessionalStorageResolution> resolutions
    ) {
        if (resolutions.isEmpty()) {
            return Optional.empty();
        }
        ProfessionalRoleId role = resolutions.getFirst().pairing().role();
        if (resolutions.stream().anyMatch(resolution -> !resolution.pairing().role().equals(role))) {
            return Optional.empty();
        }
        ProfessionalStorageProfileProvider provider = PROVIDERS.get(role);
        if (provider == null) {
            return Optional.empty();
        }
        return provider.createRows(world, storage, List.copyOf(resolutions))
                .map(List::copyOf)
                .filter(rows -> !rows.isEmpty() && rows.size() <= ProfessionalStorageSnapshot.MAX_ROWS);
    }
}
