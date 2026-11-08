package dev.sterner.guardvillagers.client.professionalstorage;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Client-session cache. Exact sync-ID lookup prevents metadata crossing container screens. */
public final class ProfessionalStorageSnapshotCache {
    private static final Map<Integer, ProfessionalStorageSnapshot> SNAPSHOTS = new HashMap<>();

    private ProfessionalStorageSnapshotCache() {
    }

    public static void store(ProfessionalStorageSnapshot snapshot) {
        SNAPSHOTS.clear();
        SNAPSHOTS.put(snapshot.syncId(), snapshot);
    }

    public static Optional<ProfessionalStorageSnapshot> find(int currentSyncId) {
        ProfessionalStorageSnapshot snapshot = SNAPSHOTS.get(currentSyncId);
        return snapshot != null && snapshot.syncId() == currentSyncId
                ? Optional.of(snapshot)
                : Optional.empty();
    }

    public static void remove(int syncId) {
        SNAPSHOTS.remove(syncId);
    }

    public static void clear() {
        SNAPSHOTS.clear();
    }

    static int size() {
        return SNAPSHOTS.size();
    }
}
