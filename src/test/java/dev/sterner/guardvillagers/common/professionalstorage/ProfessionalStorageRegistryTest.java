package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionalStorageRegistryTest {
    private static final StorageIdentity STORAGE = new StorageIdentity(World.OVERWORLD, new BlockPos(5, 64, 5));

    @Test
    void unloadedWorkerIsReturnedUnavailableAndPersistentPairingIsPreserved() {
        ProfessionalStorageState state = new ProfessionalStorageState();
        ProfessionalStoragePairing pairing = pairing("60000000-0000-0000-0000-000000000001", 10L);
        state.upsert(pairing);
        state.setDirty(false);

        List<ProfessionalStorageResolution> results = ProfessionalStorageRegistry.validateCandidates(
                state,
                STORAGE,
                ignored -> ProfessionalStorageRegistry.WorkerValidation.unloaded());

        assertEquals(1, results.size());
        assertEquals(ProfessionalStorageResolution.WorkerAvailability.UNLOADED, results.getFirst().workerAvailability());
        assertEquals(pairing, state.getByWorker(pairing.workerUuid()).orElseThrow());
        assertFalse(state.isDirty());
    }

    @Test
    void loadedWorkerValidationUpdatesRecordWhileConfirmedInvalidationRemovesIt() {
        ProfessionalStorageState state = new ProfessionalStorageState();
        ProfessionalStoragePairing pairing = pairing("70000000-0000-0000-0000-000000000001", 10L);
        state.upsert(pairing);
        ProfessionalStoragePairing validated = pairing.withRole(ProfessionalRoleId.QUARTERMASTER, 25L);

        List<ProfessionalStorageResolution> loaded = ProfessionalStorageRegistry.validateCandidates(
                state,
                STORAGE,
                ignored -> ProfessionalStorageRegistry.WorkerValidation.loaded(validated));

        assertEquals(ProfessionalStorageResolution.WorkerAvailability.LOADED, loaded.getFirst().workerAvailability());
        assertEquals(validated, state.getByWorker(pairing.workerUuid()).orElseThrow());

        List<ProfessionalStorageResolution> invalid = ProfessionalStorageRegistry.validateCandidates(
                state,
                STORAGE,
                ignored -> ProfessionalStorageRegistry.WorkerValidation.invalid());

        assertTrue(invalid.isEmpty());
        assertTrue(state.getByWorker(pairing.workerUuid()).isEmpty());
    }

    private static ProfessionalStoragePairing pairing(String uuid, long validationTick) {
        return new ProfessionalStoragePairing(
                STORAGE,
                STORAGE.canonicalPos(),
                UUID.fromString(uuid),
                new ProfessionalRoleId(Identifier.of("minecraft", "farmer")),
                new BlockPos(4, 64, 5),
                validationTick);
    }
}
