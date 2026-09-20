package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;

/** Immutable server-authored data used by the client storage panel. */
public record ProfessionalStorageSnapshot(
        int syncId,
        String dimensionId,
        BlockPos canonicalPos,
        StorageType storageType,
        RoleState roleState,
        int workerCount,
        String title,
        boolean customTitlePreserved,
        List<ProfessionalStorageRow> rows
) {
    public static final int MAX_ROWS = 6;

    public ProfessionalStorageSnapshot {
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        canonicalPos = Objects.requireNonNull(canonicalPos, "canonicalPos").toImmutable();
        storageType = Objects.requireNonNull(storageType, "storageType");
        roleState = Objects.requireNonNull(roleState, "roleState");
        title = Objects.requireNonNull(title, "title");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (workerCount < 1) {
            throw new IllegalArgumentException("A professional storage snapshot requires at least one worker");
        }
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("Professional storage snapshots support at most " + MAX_ROWS + " rows");
        }
    }

    public ProfessionalStorageSnapshot withSyncId(int newSyncId) {
        return new ProfessionalStorageSnapshot(
                newSyncId,
                dimensionId,
                canonicalPos,
                storageType,
                roleState,
                workerCount,
                title,
                customTitlePreserved,
                rows);
    }

    public enum StorageType {
        NORMAL_CHEST,
        TRAPPED_CHEST,
        BARREL
    }

    public enum RoleState {
        SINGLE_WORKER,
        SAME_ROLE_SHARED,
        MIXED_ROLE_SHARED
    }
}
