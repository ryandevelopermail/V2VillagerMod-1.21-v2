package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Overworld-backed authoritative persistence for profession-aware storage pairings. */
public final class ProfessionalStorageState extends PersistentState {
    private static final String STATE_ID = GuardVillagers.MODID + "_professional_storage";
    private static final String PAIRINGS_KEY = "Pairings";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String CANONICAL_STORAGE_POS_KEY = "CanonicalStoragePos";
    private static final String PHYSICAL_STORAGE_POS_KEY = "PhysicalStoragePos";
    private static final String WORKER_UUID_KEY = "WorkerUuid";
    private static final String ROLE_KEY = "Role";
    private static final String ROLE_ANCHOR_POS_KEY = "RoleAnchorPos";
    private static final String LAST_VALIDATION_TICK_KEY = "LastValidationTick";

    private static final Comparator<ProfessionalStoragePairing> PAIRING_ORDER = Comparator
            .comparing(ProfessionalStoragePairing::role)
            .thenComparing(pairing -> pairing.workerUuid().toString());

    private final Map<UUID, ProfessionalStoragePairing> pairingsByWorker = new HashMap<>();

    public static ProfessionalStorageState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
    }

    private static Type<ProfessionalStorageState> getType() {
        return new Type<>(ProfessionalStorageState::new, ProfessionalStorageState::fromNbt, null);
    }

    static ProfessionalStorageState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        ProfessionalStorageState state = new ProfessionalStorageState();
        NbtList rows = nbt.getList(PAIRINGS_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : rows) {
            if (!(element instanceof NbtCompound row)) {
                continue;
            }
            readPairing(row).ifPresent(pairing -> state.pairingsByWorker.put(pairing.workerUuid(), pairing));
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList rows = new NbtList();
        pairingsByWorker.values().stream().sorted(PAIRING_ORDER).forEach(pairing -> {
            NbtCompound row = new NbtCompound();
            row.putString(DIMENSION_KEY, pairing.storage().dimension().getValue().toString());
            row.put(CANONICAL_STORAGE_POS_KEY, NbtHelper.fromBlockPos(pairing.storage().canonicalPos()));
            row.put(PHYSICAL_STORAGE_POS_KEY, NbtHelper.fromBlockPos(pairing.physicalStoragePos()));
            row.putString(WORKER_UUID_KEY, pairing.workerUuid().toString());
            row.putString(ROLE_KEY, pairing.role().toString());
            pairing.roleAnchor().ifPresent(pos -> row.put(ROLE_ANCHOR_POS_KEY, NbtHelper.fromBlockPos(pos)));
            row.putLong(LAST_VALIDATION_TICK_KEY, pairing.lastValidationTick());
            rows.add(row);
        });
        nbt.put(PAIRINGS_KEY, rows);
        return nbt;
    }

    public boolean upsert(ProfessionalStoragePairing pairing) {
        ProfessionalStoragePairing existing = pairingsByWorker.get(pairing.workerUuid());
        if (pairing.equals(existing)) {
            return false;
        }
        pairingsByWorker.put(pairing.workerUuid(), pairing);
        markDirty();
        return true;
    }

    public boolean removeWorker(UUID workerUuid) {
        if (pairingsByWorker.remove(workerUuid) == null) {
            return false;
        }
        markDirty();
        return true;
    }

    public boolean replaceRole(UUID workerUuid, ProfessionalRoleId role, long validationTick) {
        ProfessionalStoragePairing existing = pairingsByWorker.get(workerUuid);
        if (existing == null) {
            return false;
        }
        return upsert(existing.withRole(role, validationTick));
    }

    /** Removes the source UUID and writes the replacement in one dirty-state transition. */
    public boolean transfer(UUID sourceWorkerUuid, ProfessionalStoragePairing replacement) {
        ProfessionalStoragePairing source = pairingsByWorker.get(sourceWorkerUuid);
        ProfessionalStoragePairing existingReplacement = pairingsByWorker.get(replacement.workerUuid());
        if (source == null && replacement.equals(existingReplacement)) {
            return false;
        }
        pairingsByWorker.remove(sourceWorkerUuid);
        pairingsByWorker.put(replacement.workerUuid(), replacement);
        markDirty();
        return true;
    }

    public Optional<ProfessionalStoragePairing> getByWorker(UUID workerUuid) {
        return Optional.ofNullable(pairingsByWorker.get(workerUuid));
    }

    public List<ProfessionalStoragePairing> getByStorage(StorageIdentity storage) {
        return pairingsByWorker.values().stream()
                .filter(pairing -> pairing.storage().equals(storage))
                .sorted(PAIRING_ORDER)
                .toList();
    }

    boolean removeInvalidStoragePosition(RegistryKey<World> dimension, BlockPos position) {
        List<UUID> removals = new ArrayList<>();
        for (ProfessionalStoragePairing pairing : pairingsByWorker.values()) {
            if (pairing.storage().dimension().equals(dimension)
                    && (pairing.storage().canonicalPos().equals(position)
                    || pairing.physicalStoragePos().equals(position))) {
                removals.add(pairing.workerUuid());
            }
        }
        if (removals.isEmpty()) {
            return false;
        }
        removals.forEach(pairingsByWorker::remove);
        markDirty();
        return true;
    }

    int size() {
        return pairingsByWorker.size();
    }

    private static Optional<ProfessionalStoragePairing> readPairing(NbtCompound row) {
        if (!row.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                || !row.contains(CANONICAL_STORAGE_POS_KEY, NbtElement.INT_ARRAY_TYPE)
                || !row.contains(PHYSICAL_STORAGE_POS_KEY, NbtElement.INT_ARRAY_TYPE)
                || !row.contains(WORKER_UUID_KEY, NbtElement.STRING_TYPE)
                || !row.contains(ROLE_KEY, NbtElement.STRING_TYPE)
                || !row.contains(LAST_VALIDATION_TICK_KEY, NbtElement.LONG_TYPE)) {
            return Optional.empty();
        }
        Identifier dimensionId = Identifier.tryParse(row.getString(DIMENSION_KEY));
        Optional<BlockPos> canonicalPos = NbtHelper.toBlockPos(row, CANONICAL_STORAGE_POS_KEY);
        Optional<BlockPos> physicalPos = NbtHelper.toBlockPos(row, PHYSICAL_STORAGE_POS_KEY);
        Optional<ProfessionalRoleId> role = ProfessionalRoleId.parse(row.getString(ROLE_KEY));
        if (dimensionId == null || canonicalPos.isEmpty() || physicalPos.isEmpty() || role.isEmpty()) {
            return Optional.empty();
        }
        UUID workerUuid;
        try {
            workerUuid = UUID.fromString(row.getString(WORKER_UUID_KEY));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        BlockPos anchor = NbtHelper.toBlockPos(row, ROLE_ANCHOR_POS_KEY).orElse(null);
        RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
        return Optional.of(new ProfessionalStoragePairing(
                new StorageIdentity(dimension, canonicalPos.get()),
                physicalPos.get(),
                workerUuid,
                role.get(),
                anchor,
                row.getLong(LAST_VALIDATION_TICK_KEY)));
    }
}
