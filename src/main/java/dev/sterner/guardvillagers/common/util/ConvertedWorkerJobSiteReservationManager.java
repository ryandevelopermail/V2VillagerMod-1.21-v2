package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ConvertedWorkerJobSiteReservationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConvertedWorkerJobSiteReservationManager.class);
    private static final Map<WorldKey, Map<BlockPos, Reservation>> RESERVATIONS_BY_POS = new HashMap<>();
    private static final Map<WorldKey, Map<UUID, Set<BlockPos>>> RESERVED_POS_BY_GUARD = new HashMap<>();

    private ConvertedWorkerJobSiteReservationManager() {
    }

    public static void reserve(ServerWorld world, BlockPos pos, UUID guardUuid, VillagerProfession expectedProfession, String source) {
        BlockPos immutablePos = pos.toImmutable();
        WorldKey worldKey = WorldKey.from(world);
        Reservation reservation = new Reservation(guardUuid, expectedProfession);

        Map<BlockPos, Reservation> byPos = RESERVATIONS_BY_POS.computeIfAbsent(worldKey, ignored -> new HashMap<>());
        Reservation previous = byPos.put(immutablePos, reservation);

        if (previous != null) {
            removeGuardLink(worldKey, previous.guardUuid(), immutablePos);
            LOGGER.debug("Replacing reserved job site {} in {} from guard {} to guard {} (source={})",
                    immutablePos.toShortString(), world.getRegistryKey().getValue(), previous.guardUuid(), guardUuid, source);
        } else {
            LOGGER.debug("Reserving job site {} in {} for guard {} (source={})",
                    immutablePos.toShortString(), world.getRegistryKey().getValue(), guardUuid, source);
        }

        RESERVED_POS_BY_GUARD
                .computeIfAbsent(worldKey, ignored -> new HashMap<>())
                .computeIfAbsent(guardUuid, ignored -> new HashSet<>())
                .add(immutablePos);
    }

    public static boolean isReserved(ServerWorld world, BlockPos pos) {
        WorldKey worldKey = WorldKey.from(world);
        Map<BlockPos, Reservation> byPos = RESERVATIONS_BY_POS.get(worldKey);
        if (byPos == null) {
            return false;
        }

        BlockPos immutablePos = pos.toImmutable();
        Reservation reservation = byPos.get(immutablePos);
        if (reservation == null) {
            return false;
        }

        if (!isReservationValid(world, immutablePos, reservation)) {
            clearReservation(world, worldKey, immutablePos, reservation, "expected workstation block mismatch");
            return false;
        }

        return true;
    }

    public static void unreserveByGuard(ServerWorld world, UUID guardUuid, String reason) {
        WorldKey worldKey = WorldKey.from(world);
        Map<UUID, Set<BlockPos>> byGuard = RESERVED_POS_BY_GUARD.get(worldKey);
        if (byGuard == null) {
            return;
        }

        Set<BlockPos> reserved = byGuard.remove(guardUuid);
        if (reserved == null || reserved.isEmpty()) {
            return;
        }

        Map<BlockPos, Reservation> byPos = RESERVATIONS_BY_POS.get(worldKey);
        if (byPos == null) {
            return;
        }

        for (BlockPos pos : reserved) {
            Reservation reservation = byPos.get(pos);
            if (reservation != null && reservation.guardUuid().equals(guardUuid)) {
                byPos.remove(pos);
                LOGGER.debug("Unreserving job site {} in {} for guard {} (reason={})",
                        pos.toShortString(), world.getRegistryKey().getValue(), guardUuid, reason);
            }
        }

        if (byPos.isEmpty()) {
            RESERVATIONS_BY_POS.remove(worldKey);
        }
        if (byGuard.isEmpty()) {
            RESERVED_POS_BY_GUARD.remove(worldKey);
        }
    }

    public static Optional<UUID> getReservedGuard(ServerWorld world, BlockPos pos) {
        if (!isReserved(world, pos)) {
            return Optional.empty();
        }

        Reservation reservation = RESERVATIONS_BY_POS.getOrDefault(WorldKey.from(world), Map.of()).get(pos.toImmutable());
        return reservation == null ? Optional.empty() : Optional.of(reservation.guardUuid());
    }

    private static boolean isReservationValid(ServerWorld world, BlockPos pos, Reservation reservation) {
        BlockState state = world.getBlockState(pos);
        return ProfessionDefinitions.isExpectedJobBlock(reservation.expectedProfession(), state);
    }

    private static void clearReservation(ServerWorld world, WorldKey worldKey, BlockPos pos, Reservation reservation, String reason) {
        Map<BlockPos, Reservation> byPos = RESERVATIONS_BY_POS.get(worldKey);
        if (byPos != null) {
            byPos.remove(pos);
            if (byPos.isEmpty()) {
                RESERVATIONS_BY_POS.remove(worldKey);
            }
        }

        removeGuardLink(worldKey, reservation.guardUuid(), pos);
        LOGGER.debug("Unreserving job site {} in {} for guard {} (reason={})",
                pos.toShortString(), world.getRegistryKey().getValue(), reservation.guardUuid(), reason);
    }

    private static void removeGuardLink(WorldKey worldKey, UUID guardUuid, BlockPos pos) {
        Map<UUID, Set<BlockPos>> byGuard = RESERVED_POS_BY_GUARD.get(worldKey);
        if (byGuard == null) {
            return;
        }

        Set<BlockPos> positions = byGuard.get(guardUuid);
        if (positions == null) {
            return;
        }

        positions.remove(pos);
        if (positions.isEmpty()) {
            byGuard.remove(guardUuid);
        }
        if (byGuard.isEmpty()) {
            RESERVED_POS_BY_GUARD.remove(worldKey);
        }
    }

    private record Reservation(UUID guardUuid, VillagerProfession expectedProfession) {
    }

    private record WorldKey(net.minecraft.registry.RegistryKey<World> worldKey) {
        private static WorldKey from(ServerWorld world) {
            return new WorldKey(world.getRegistryKey());
        }
    }
}
