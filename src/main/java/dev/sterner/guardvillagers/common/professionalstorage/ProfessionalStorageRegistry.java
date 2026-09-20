package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import dev.sterner.guardvillagers.common.entity.FishermanGuardEntity;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.QuartermasterGoal;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Narrow mutation and storage-position query API for persistent professional pairings. */
public final class ProfessionalStorageRegistry {
    private static final Comparator<ProfessionalStorageResolution> RESOLUTION_ORDER = Comparator
            .comparing((ProfessionalStorageResolution result) -> result.pairing().role())
            .thenComparing(result -> result.pairing().workerUuid().toString());

    private ProfessionalStorageRegistry() {
    }

    public static void recordNativeVillagerPairing(
            ServerWorld world,
            VillagerEntity villager,
            BlockPos jobPos,
            BlockPos storagePos
    ) {
        Optional<ProfessionalStorageState> state = getState(world);
        Optional<StorageIdentity> storage = StorageIdentityResolver.resolve(world, storagePos);
        if (state.isEmpty() || storage.isEmpty()) {
            return;
        }
        state.get().upsert(new ProfessionalStoragePairing(
                storage.get(),
                storagePos,
                villager.getUuid(),
                ProfessionalRoleId.fromVillagerProfession(villager.getVillagerData().getProfession()),
                jobPos,
                world.getTime()));
    }

    public static void removePersistentPairing(ServerWorld world, UUID workerUuid) {
        getState(world).ifPresent(state -> state.removeWorker(workerUuid));
    }

    public static void transferToSpecialist(
            ServerWorld world,
            UUID sourceVillagerUuid,
            UUID specialistUuid,
            ProfessionalRoleId specialistRole,
            BlockPos storagePos,
            @Nullable BlockPos roleAnchorPos
    ) {
        JobBlockPairingHelper.evictVillagerChestPairingCache(world, sourceVillagerUuid);
        Optional<ProfessionalStorageState> state = getState(world);
        if (state.isEmpty()) {
            return;
        }
        Optional<StorageIdentity> storage = StorageIdentityResolver.resolve(world, storagePos);
        if (storage.isEmpty()) {
            state.get().removeWorker(sourceVillagerUuid);
            return;
        }
        state.get().transfer(sourceVillagerUuid, new ProfessionalStoragePairing(
                storage.get(),
                storagePos,
                specialistUuid,
                specialistRole,
                roleAnchorPos,
                world.getTime()));
    }

    public static void promoteQuartermaster(
            ServerWorld world,
            VillagerEntity villager,
            BlockPos jobPos,
            BlockPos storagePos
    ) {
        recordNativeVillagerPairing(world, villager, jobPos, storagePos);
        getState(world).ifPresent(state ->
                state.replaceRole(villager.getUuid(), ProfessionalRoleId.QUARTERMASTER, world.getTime()));
    }

    public static void demoteQuartermaster(ServerWorld world, VillagerEntity villager) {
        Optional<ProfessionalStorageState> state = getState(world);
        if (state.isEmpty()) {
            return;
        }
        Optional<ProfessionalStoragePairing> existing = state.get().getByWorker(villager.getUuid());
        if (existing.isEmpty()) {
            return;
        }
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        ProfessionalStoragePairing pairing = existing.get();
        Optional<StorageIdentity> resolvedStorage = StorageIdentityResolver.resolve(world, pairing.physicalStoragePos());
        boolean validNativePair = villager.isAlive()
                && villager.getVillagerData().getProfession() != VillagerProfession.NONE
                && villager.getVillagerData().getProfession() != VillagerProfession.NITWIT
                && jobSite.isPresent()
                && Objects.equals(jobSite.get().dimension(), world.getRegistryKey())
                && pairing.physicalStoragePos().isWithinDistance(
                        jobSite.get().pos(),
                        JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)
                && resolvedStorage.isPresent();
        if (!validNativePair) {
            state.get().removeWorker(villager.getUuid());
            return;
        }
        state.get().upsert(new ProfessionalStoragePairing(
                resolvedStorage.get(),
                pairing.physicalStoragePos(),
                villager.getUuid(),
                ProfessionalRoleId.fromVillagerProfession(villager.getVillagerData().getProfession()),
                jobSite.get().pos(),
                world.getTime()));
    }

    /** Resolves every pairing for the opened storage without scanning chunks or entity collections. */
    public static List<ProfessionalStorageResolution> query(ServerWorld world, BlockPos openedStoragePos) {
        Optional<ProfessionalStorageState> state = getState(world);
        if (state.isEmpty()) {
            return List.of();
        }
        Optional<StorageIdentity> identity = StorageIdentityResolver.resolve(world, openedStoragePos);
        if (identity.isEmpty()) {
            state.get().removeInvalidStoragePosition(world.getRegistryKey(), openedStoragePos);
            return List.of();
        }
        return validateCandidates(
                state.get(),
                identity.get(),
                pairing -> validateWorker(world, pairing));
    }

    static List<ProfessionalStorageResolution> validateCandidates(
            ProfessionalStorageState state,
            StorageIdentity queriedStorage,
            Function<ProfessionalStoragePairing, WorkerValidation> validator
    ) {
        List<ProfessionalStorageResolution> resolutions = new ArrayList<>();
        for (ProfessionalStoragePairing pairing : state.getByStorage(queriedStorage)) {
            WorkerValidation validation = validator.apply(pairing);
            if (validation.status() == ValidationStatus.INVALID) {
                state.removeWorker(pairing.workerUuid());
                continue;
            }
            if (validation.status() == ValidationStatus.UNLOADED) {
                resolutions.add(new ProfessionalStorageResolution(
                        pairing,
                        ProfessionalStorageResolution.WorkerAvailability.UNLOADED));
                continue;
            }
            ProfessionalStoragePairing updated = validation.updatedPairing();
            state.upsert(updated);
            if (updated.storage().equals(queriedStorage)) {
                resolutions.add(new ProfessionalStorageResolution(
                        updated,
                        ProfessionalStorageResolution.WorkerAvailability.LOADED));
            }
        }
        resolutions.sort(RESOLUTION_ORDER);
        return List.copyOf(resolutions);
    }

    private static WorkerValidation validateWorker(ServerWorld world, ProfessionalStoragePairing pairing) {
        Optional<StorageIdentity> persistedStorage = StorageIdentityResolver.resolve(world, pairing.physicalStoragePos());
        if (persistedStorage.isEmpty() || !persistedStorage.get().equals(pairing.storage())) {
            return WorkerValidation.invalid();
        }
        Entity entity = world.getEntity(pairing.workerUuid());
        if (entity == null) {
            return WorkerValidation.unloaded();
        }
        if (!entity.isAlive() || entity.isRemoved()) {
            return WorkerValidation.invalid();
        }

        LoadedWorkerSnapshot snapshot = snapshotLoadedWorker(world, entity, pairing);
        if (snapshot == null || snapshot.storagePos() == null || snapshot.roleAnchorPos() == null) {
            return WorkerValidation.invalid();
        }
        Optional<StorageIdentity> currentStorage = StorageIdentityResolver.resolve(world, snapshot.storagePos());
        if (currentStorage.isEmpty()) {
            return WorkerValidation.invalid();
        }
        if (!snapshot.storagePos().isWithinDistance(
                snapshot.roleAnchorPos(),
                JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) {
            return WorkerValidation.invalid();
        }
        return WorkerValidation.loaded(pairing.withValidation(
                currentStorage.get(),
                snapshot.storagePos(),
                snapshot.role(),
                snapshot.roleAnchorPos(),
                world.getTime()));
    }

    @Nullable
    private static LoadedWorkerSnapshot snapshotLoadedWorker(
            ServerWorld world,
            Entity entity,
            ProfessionalStoragePairing pairing
    ) {
        if (entity instanceof VillagerEntity villager) {
            VillagerProfession profession = villager.getVillagerData().getProfession();
            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (profession == VillagerProfession.NONE
                    || profession == VillagerProfession.NITWIT
                    || jobSite.isEmpty()
                    || !Objects.equals(jobSite.get().dimension(), world.getRegistryKey())
                    || !ProfessionDefinitions.isExpectedJobBlock(profession, world.getBlockState(jobSite.get().pos()))) {
                return null;
            }
            ProfessionalRoleId role = QuartermasterGoal.isActiveQuartermaster(world, villager.getUuid())
                    ? ProfessionalRoleId.QUARTERMASTER
                    : ProfessionalRoleId.fromVillagerProfession(profession);
            return new LoadedWorkerSnapshot(role, pairing.physicalStoragePos(), jobSite.get().pos());
        }
        if (entity instanceof ButcherGuardEntity guard) {
            return new LoadedWorkerSnapshot(
                    ProfessionalRoleId.BUTCHER_GUARD,
                    guard.getPairedChestPos(),
                    guard.getPairedSmokerPos());
        }
        if (entity instanceof FishermanGuardEntity guard) {
            BlockPos storagePos = guard.getPairedChestPos();
            if (storagePos == null && guard.getPairedJobPos() != null
                    && StorageIdentityResolver.resolve(world, guard.getPairedJobPos()).isPresent()) {
                storagePos = guard.getPairedJobPos();
            }
            return new LoadedWorkerSnapshot(
                    ProfessionalRoleId.FISHERMAN_GUARD,
                    storagePos,
                    guard.getPairedJobPos());
        }
        if (entity instanceof MasonGuardEntity guard) {
            return new LoadedWorkerSnapshot(
                    ProfessionalRoleId.MASON_GUARD,
                    guard.getPairedChestPos(),
                    guard.getPairedJobPos());
        }
        if (entity instanceof LumberjackGuardEntity guard) {
            return new LoadedWorkerSnapshot(
                    ProfessionalRoleId.LUMBERJACK,
                    guard.getPairedChestPos(),
                    guard.getPairedCraftingTablePos());
        }
        return null;
    }

    private static Optional<ProfessionalStorageState> getState(ServerWorld world) {
        MinecraftServer server = world.getServer();
        return server == null || server.getOverworld() == null
                ? Optional.empty()
                : Optional.of(ProfessionalStorageState.get(server));
    }

    enum ValidationStatus {
        LOADED,
        UNLOADED,
        INVALID
    }

    record WorkerValidation(ValidationStatus status, @Nullable ProfessionalStoragePairing updatedPairing) {
        static WorkerValidation loaded(ProfessionalStoragePairing pairing) {
            return new WorkerValidation(ValidationStatus.LOADED, pairing);
        }

        static WorkerValidation unloaded() {
            return new WorkerValidation(ValidationStatus.UNLOADED, null);
        }

        static WorkerValidation invalid() {
            return new WorkerValidation(ValidationStatus.INVALID, null);
        }
    }

    private record LoadedWorkerSnapshot(
            ProfessionalRoleId role,
            @Nullable BlockPos storagePos,
            @Nullable BlockPos roleAnchorPos
    ) {
    }
}
