package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable persistent association between one worker and one normalized storage container. */
public record ProfessionalStoragePairing(
        StorageIdentity storage,
        BlockPos physicalStoragePos,
        UUID workerUuid,
        ProfessionalRoleId role,
        @Nullable BlockPos roleAnchorPos,
        long lastValidationTick
) {
    public ProfessionalStoragePairing {
        storage = Objects.requireNonNull(storage, "storage");
        physicalStoragePos = Objects.requireNonNull(physicalStoragePos, "physicalStoragePos").toImmutable();
        workerUuid = Objects.requireNonNull(workerUuid, "workerUuid");
        role = Objects.requireNonNull(role, "role");
        roleAnchorPos = roleAnchorPos == null ? null : roleAnchorPos.toImmutable();
    }

    public Optional<BlockPos> roleAnchor() {
        return Optional.ofNullable(roleAnchorPos);
    }

    public ProfessionalStoragePairing withRole(ProfessionalRoleId newRole, long validationTick) {
        return new ProfessionalStoragePairing(storage, physicalStoragePos, workerUuid, newRole, roleAnchorPos, validationTick);
    }

    public ProfessionalStoragePairing withValidation(
            StorageIdentity newStorage,
            BlockPos newPhysicalStoragePos,
            ProfessionalRoleId newRole,
            @Nullable BlockPos newRoleAnchorPos,
            long validationTick
    ) {
        return new ProfessionalStoragePairing(
                newStorage,
                newPhysicalStoragePos,
                workerUuid,
                newRole,
                newRoleAnchorPos,
                validationTick);
    }
}
