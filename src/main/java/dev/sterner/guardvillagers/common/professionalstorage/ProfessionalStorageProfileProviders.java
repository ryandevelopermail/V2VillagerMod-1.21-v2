package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

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
                new ProfessionalRoleId(Identifier.of("minecraft", "farmer")),
                new FarmerProfessionalStorageProfileProvider());
        register(ToolsmithWorkMetrics.TOOLSMITH_ROLE, new ToolsmithProfessionalStorageProfileProvider());
        register(WeaponsmithWorkMetrics.WEAPONSMITH_ROLE, new WeaponsmithProfessionalStorageProfileProvider());
        register(FletcherWorkMetrics.FLETCHER_ROLE, new FletcherProfessionalStorageProfileProvider());
        register(LeatherworkerWorkMetrics.LEATHERWORKER_ROLE, new LeatherworkerProfessionalStorageProfileProvider());
    }

    public static void register(ProfessionalRoleId role, ProfessionalStorageProfileProvider provider) {
        PROVIDERS.put(role, provider);
    }

    static boolean hasProvider(ProfessionalRoleId role) {
        return PROVIDERS.containsKey(role);
    }

    public static Optional<List<ProfessionalStorageTab>> createTabs(
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
        return provider.createTabs(world, storage, List.copyOf(resolutions))
                .map(List::copyOf)
                .filter(ProfessionalStorageProfileProviders::withinBounds);
    }

    private static boolean withinBounds(List<ProfessionalStorageTab> tabs) {
        if (tabs.isEmpty() || tabs.size() > ProfessionalStorageSnapshot.MAX_TABS) {
            return false;
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        boolean hasRows = false;
        for (ProfessionalStorageTab tab : tabs) {
            if (tab.id().isBlank()
                    || !ids.add(tab.id())
                    || tab.rows().size() > ProfessionalStorageSnapshot.MAX_ROWS_PER_TAB) {
                return false;
            }
            hasRows |= !tab.rows().isEmpty();
        }
        return hasRows;
    }
}
