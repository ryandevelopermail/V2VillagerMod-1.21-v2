package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfessionalStorageProfileProvidersTest {
    private static final StorageIdentity STORAGE =
            new StorageIdentity(World.OVERWORLD, new BlockPos(5, 64, 5));

    @Test
    void mixedRolesAlwaysUseGenericFallbackBeforeProviderLookup() {
        List<ProfessionalStorageResolution> mixed = List.of(
                resolution("30000000-0000-0000-0000-000000000001", "minecraft:farmer"),
                resolution("30000000-0000-0000-0000-000000000002", "minecraft:librarian"));

        assertTrue(ProfessionalStorageProfileProviders.createTabs(null, STORAGE, mixed).isEmpty());
    }

    @Test
    void homogeneousRoleWithoutProviderUsesGenericFallback() {
        List<ProfessionalStorageResolution> unsupported = List.of(
                resolution("30000000-0000-0000-0000-000000000003", "example:beekeeper"));

        assertTrue(ProfessionalStorageProfileProviders.createTabs(null, STORAGE, unsupported).isEmpty());
    }

    @Test
    void defaultsSelectTheRealFishermanProviderForNativeAndGuardRoles() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(FishermanWorkMetrics.FISHERMAN_ROLE));
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(ProfessionalRoleId.FISHERMAN_GUARD));

        assertEquals(List.of("overview", "crafting", "distribution"),
                selectedTabs(FishermanWorkMetrics.FISHERMAN_ROLE).stream().map(ProfessionalStorageTab::id).toList());
        assertEquals(List.of("overview", "crafting", "distribution"),
                selectedTabs(ProfessionalRoleId.FISHERMAN_GUARD).stream().map(ProfessionalStorageTab::id).toList());
    }

    @Test
    void loadedGuardResolutionUsesGuardValuesWithoutVillagerCastingOrFallback() {
        ProfessionalStorageProfileProviders.registerDefaults();
        List<ProfessionalStorageTab> tabs = selectedTabs(ProfessionalRoleId.FISHERMAN_GUARD);
        assertEquals("Not measured", value(tabs, "Crafting table"));
        assertEquals("Yes", value(tabs, "Barrel"));
        assertEquals("Not measured", value(tabs, "Craftable recipes"));
        assertEquals("4", value(tabs, "Eligible Butchers"));
        assertEquals("2", value(tabs, "Fishing rods stored"));
        assertEquals("3", value(tabs, "Buckets stored"));
        assertEquals("5", value(tabs, "Boats stored"));
        assertEquals("7", value(tabs, "Fish stored"));
        assertEquals(List.of("Status", "Crafting table", "Barrel", "Fishing rod ready"), labels(tabs.get(0)));
        assertEquals(List.of("Craftable recipes", "Fishing rods stored", "Buckets stored", "Boats stored",
                "Rods crafted", "Buckets crafted", "Boats crafted"), labels(tabs.get(1)));
        assertEquals(List.of("Fish stored", "Eligible Butchers", "Fish delivered"), labels(tabs.get(2)));
    }

    private static List<ProfessionalStorageTab> selectedTabs(ProfessionalRoleId role) {
        ProfessionalStorageResolution selected = resolution(
                "30000000-0000-0000-0000-000000000004", role.toString());
        return ProfessionalStorageProfileProviders.createTabs(null, STORAGE, List.of(selected),
                (provider, world, storage, resolutions) -> {
                    assertTrue(provider instanceof FishermanProfessionalStorageProfileProvider);
                    return ((FishermanProfessionalStorageProfileProvider) provider).createTabs(
                            storage, resolutions, new FishermanProfessionalStorageProfileProvider.ReadOnlyResolution() {
                                @Override
                                public FishermanProfessionalStorageProfileProvider.FishermanStorageCounts storageCounts(
                                        StorageIdentity ignored) {
                                    return new FishermanProfessionalStorageProfileProvider.FishermanStorageCounts(2, 3, 5, 7);
                                }

                                @Override
                                public FishermanProfessionalStorageProfileProvider.FishermanWorkerView loadedWorker(
                                        ProfessionalStorageResolution resolution, StorageIdentity ignored) {
                                    boolean guard = resolution.pairing().role().equals(ProfessionalRoleId.FISHERMAN_GUARD);
                                    return new FishermanProfessionalStorageProfileProvider.FishermanWorkerView(
                                            resolution.pairing().workerUuid(), resolution.workerAvailability(),
                                            guard ? null : true, true, guard ? null : 6, 4);
                                }

                                @Override
                                public FishermanProfessionalStorageProfileProvider.FishermanCareerTotals careerTotals(
                                        List<UUID> workers, ProfessionalRoleId selectedRole) {
                                    assertEquals(role, selectedRole);
                                    return new FishermanProfessionalStorageProfileProvider.FishermanCareerTotals(11, 13, 17, 19);
                                }
                            });
                }).orElseThrow();
    }

    private static List<String> labels(ProfessionalStorageTab tab) {
        return tab.rows().stream().map(ProfessionalStorageRow::label).toList();
    }

    private static String value(List<ProfessionalStorageTab> tabs, String label) {
        return tabs.stream().flatMap(tab -> tab.rows().stream()).filter(row -> row.label().equals(label))
                .findFirst().orElseThrow().value();
    }

    private static ProfessionalStorageResolution resolution(String uuid, String role) {
        return new ProfessionalStorageResolution(
                new ProfessionalStoragePairing(
                        STORAGE,
                        STORAGE.canonicalPos(),
                        UUID.fromString(uuid),
                        new ProfessionalRoleId(Identifier.of(role)),
                        new BlockPos(4, 64, 5),
                        10L),
                ProfessionalStorageResolution.WorkerAvailability.LOADED);
    }
}
