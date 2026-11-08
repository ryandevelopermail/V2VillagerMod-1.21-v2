package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionalStorageStateTest {
    private static final StorageIdentity STORAGE = new StorageIdentity(World.OVERWORLD, new BlockPos(12, 70, -8));

    @Test
    void professionalRoleIdentifiersAreStableAndNamespaced() {
        assertEquals("minecraft:farmer", new ProfessionalRoleId(Identifier.of("minecraft", "farmer")).toString());
        assertEquals("guardvillagers:butcher_guard", ProfessionalRoleId.BUTCHER_GUARD.toString());
        assertEquals("guardvillagers:fisherman_guard", ProfessionalRoleId.FISHERMAN_GUARD.toString());
        assertEquals("guardvillagers:mason_guard", ProfessionalRoleId.MASON_GUARD.toString());
        assertEquals("guardvillagers:lumberjack", ProfessionalRoleId.LUMBERJACK.toString());
        assertEquals("guardvillagers:quartermaster", ProfessionalRoleId.QUARTERMASTER.toString());
    }

    @Test
    void nbtRoundTripPreservesEveryStoredField() {
        ProfessionalStorageState state = new ProfessionalStorageState();
        ProfessionalStoragePairing pairing = new ProfessionalStoragePairing(
                STORAGE,
                new BlockPos(13, 70, -8),
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                ProfessionalRoleId.MASON_GUARD,
                new BlockPos(10, 70, -8),
                987654321L);
        state.upsert(pairing);

        NbtCompound encoded = state.writeNbt(new NbtCompound(), null);
        ProfessionalStorageState restored = ProfessionalStorageState.fromNbt(encoded, null);

        assertEquals(pairing, restored.getByWorker(pairing.workerUuid()).orElseThrow());
    }

    @Test
    void multipleWorkersShareStorageAndAreReturnedDeterministically() {
        ProfessionalStorageState state = new ProfessionalStorageState();
        ProfessionalStoragePairing farmer = pairing(
                "00000000-0000-0000-0000-000000000002",
                new ProfessionalRoleId(Identifier.of("minecraft", "farmer")));
        ProfessionalStoragePairing mason = pairing(
                "00000000-0000-0000-0000-000000000001",
                ProfessionalRoleId.MASON_GUARD);

        state.upsert(mason);
        state.upsert(farmer);

        assertEquals(List.of(mason, farmer), state.getByStorage(STORAGE));
    }

    @Test
    void upsertingOneWorkerDoesNotEraseAnotherAndIdenticalUpsertStaysClean() {
        ProfessionalStorageState state = new ProfessionalStorageState();
        ProfessionalStoragePairing first = pairing(
                "20000000-0000-0000-0000-000000000001",
                ProfessionalRoleId.BUTCHER_GUARD);
        ProfessionalStoragePairing second = pairing(
                "20000000-0000-0000-0000-000000000002",
                ProfessionalRoleId.FISHERMAN_GUARD);
        state.upsert(first);
        state.upsert(second);
        state.setDirty(false);

        assertFalse(state.upsert(second));
        assertFalse(state.isDirty());
        assertEquals(2, state.size());
    }

    @Test
    void confirmedInvalidationRemovesPersistentPairing() {
        ProfessionalStorageState state = new ProfessionalStorageState();
        ProfessionalStoragePairing pairing = pairing(
                "30000000-0000-0000-0000-000000000001",
                ProfessionalRoleId.LUMBERJACK);
        state.upsert(pairing);

        assertTrue(state.removeWorker(pairing.workerUuid()));
        assertTrue(state.getByWorker(pairing.workerUuid()).isEmpty());
    }

    @Test
    void quartermasterPromotionAndDemotionReplaceRoleInPlace() {
        ProfessionalStorageState state = new ProfessionalStorageState();
        UUID worker = UUID.fromString("40000000-0000-0000-0000-000000000001");
        ProfessionalRoleId librarian = new ProfessionalRoleId(Identifier.of("minecraft", "librarian"));
        state.upsert(pairing(worker.toString(), librarian));

        assertTrue(state.replaceRole(worker, ProfessionalRoleId.QUARTERMASTER, 20L));
        assertEquals(ProfessionalRoleId.QUARTERMASTER, state.getByWorker(worker).orElseThrow().role());
        assertTrue(state.replaceRole(worker, librarian, 30L));
        assertEquals(librarian, state.getByWorker(worker).orElseThrow().role());
    }

    @Test
    void villagerToSpecialistTransferIsAtomicAndPreservesStorageMapping() {
        ProfessionalStorageState state = new ProfessionalStorageState();
        UUID villager = UUID.fromString("50000000-0000-0000-0000-000000000001");
        UUID specialist = UUID.fromString("50000000-0000-0000-0000-000000000002");
        state.upsert(pairing(villager.toString(), new ProfessionalRoleId(Identifier.of("minecraft", "mason"))));
        ProfessionalStoragePairing replacement = new ProfessionalStoragePairing(
                STORAGE,
                STORAGE.canonicalPos(),
                specialist,
                ProfessionalRoleId.MASON_GUARD,
                new BlockPos(11, 70, -8),
                40L);

        assertTrue(state.transfer(villager, replacement));
        assertTrue(state.getByWorker(villager).isEmpty());
        assertEquals(replacement, state.getByWorker(specialist).orElseThrow());
        assertEquals(List.of(replacement), state.getByStorage(STORAGE));
    }

    private static ProfessionalStoragePairing pairing(String uuid, ProfessionalRoleId role) {
        return new ProfessionalStoragePairing(
                STORAGE,
                STORAGE.canonicalPos(),
                UUID.fromString(uuid),
                role,
                new BlockPos(11, 70, -8),
                10L);
    }
}
