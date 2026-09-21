package dev.sterner.guardvillagers.client.professionalstorage;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageRow;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageSnapshot;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageTab;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionalStorageSnapshotCacheTest {
    @AfterEach
    void clearCache() {
        ProfessionalStorageSnapshotCache.clear();
    }

    @Test
    void exactSyncIdLookupSupportsPacketBeforeScreenAndRejectsMismatch() {
        ProfessionalStorageSnapshot snapshot = snapshot(18);
        ProfessionalStorageSnapshotCache.store(snapshot);

        assertEquals(snapshot, ProfessionalStorageSnapshotCache.find(18).orElseThrow());
        assertTrue(ProfessionalStorageSnapshotCache.find(17).isEmpty());
    }

    @Test
    void closingAndDisconnectCleanupRemoveSnapshots() {
        ProfessionalStorageSnapshotCache.store(snapshot(21));
        ProfessionalStorageSnapshotCache.remove(21);
        assertTrue(ProfessionalStorageSnapshotCache.find(21).isEmpty());

        ProfessionalStorageSnapshotCache.store(snapshot(22));
        ProfessionalStorageSnapshotCache.clear();
        assertEquals(0, ProfessionalStorageSnapshotCache.size());
    }

    @Test
    void newerPacketEvictsStalePendingSnapshot() {
        ProfessionalStorageSnapshotCache.store(snapshot(30));
        ProfessionalStorageSnapshotCache.store(snapshot(31));

        assertTrue(ProfessionalStorageSnapshotCache.find(30).isEmpty());
        assertEquals(31, ProfessionalStorageSnapshotCache.find(31).orElseThrow().syncId());
    }

    private static ProfessionalStorageSnapshot snapshot(int syncId) {
        return new ProfessionalStorageSnapshot(
                syncId,
                "minecraft:overworld",
                BlockPos.ORIGIN,
                ProfessionalStorageSnapshot.StorageType.BARREL,
                ProfessionalStorageSnapshot.RoleState.SINGLE_WORKER,
                1,
                "Fisherman Storage",
                false,
                List.of(new ProfessionalStorageTab("overview", "Overview", List.of(
                        new ProfessionalStorageRow("Profession", "Fisherman", ProfessionalStorageRow.Tone.NORMAL),
                        new ProfessionalStorageRow("Status", "Paired", ProfessionalStorageRow.Tone.PAIRED)))));
    }
}
