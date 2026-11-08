package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        List<ProfessionalStorageTab> tabs
) {
    public static final int MAX_TABS = 4;
    public static final int MAX_ROWS_PER_TAB = 32;

    public ProfessionalStorageSnapshot {
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        canonicalPos = Objects.requireNonNull(canonicalPos, "canonicalPos").toImmutable();
        storageType = Objects.requireNonNull(storageType, "storageType");
        roleState = Objects.requireNonNull(roleState, "roleState");
        title = Objects.requireNonNull(title, "title");
        tabs = List.copyOf(Objects.requireNonNull(tabs, "tabs"));
        if (workerCount < 1) {
            throw new IllegalArgumentException("A professional storage snapshot requires at least one worker");
        }
        if (tabs.isEmpty() || tabs.size() > MAX_TABS) {
            throw new IllegalArgumentException(
                    "Professional storage snapshots require between 1 and " + MAX_TABS + " tabs");
        }
        Set<String> tabIds = new HashSet<>();
        boolean hasRows = false;
        for (ProfessionalStorageTab tab : tabs) {
            if (tab.id().isBlank()) {
                throw new IllegalArgumentException("Professional storage tab IDs cannot be blank");
            }
            if (!tabIds.add(tab.id())) {
                throw new IllegalArgumentException("Duplicate professional storage tab ID: " + tab.id());
            }
            if (tab.rows().size() > MAX_ROWS_PER_TAB) {
                throw new IllegalArgumentException(
                        "Professional storage tabs support at most " + MAX_ROWS_PER_TAB + " rows");
            }
            hasRows |= !tab.rows().isEmpty();
        }
        if (!hasRows) {
            throw new IllegalArgumentException("A professional storage snapshot requires at least one nonempty tab");
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
                tabs);
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
