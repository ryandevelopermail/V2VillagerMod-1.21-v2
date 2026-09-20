package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionalStorageProfileProvidersTest {
    private static final StorageIdentity STORAGE =
            new StorageIdentity(World.OVERWORLD, new BlockPos(5, 64, 5));

    @Test
    void mixedRolesAlwaysUseGenericFallbackBeforeProviderLookup() {
        List<ProfessionalStorageResolution> mixed = List.of(
                resolution("30000000-0000-0000-0000-000000000001", "minecraft:farmer"),
                resolution("30000000-0000-0000-0000-000000000002", "minecraft:librarian"));

        assertTrue(ProfessionalStorageProfileProviders.createRows(null, STORAGE, mixed).isEmpty());
    }

    @Test
    void homogeneousRoleWithoutProviderUsesGenericFallback() {
        List<ProfessionalStorageResolution> unsupported = List.of(
                resolution("30000000-0000-0000-0000-000000000003", "example:beekeeper"));

        assertTrue(ProfessionalStorageProfileProviders.createRows(null, STORAGE, unsupported).isEmpty());
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
