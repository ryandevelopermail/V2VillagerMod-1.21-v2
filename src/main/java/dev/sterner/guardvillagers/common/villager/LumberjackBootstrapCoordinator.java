package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.util.BellChestMappingState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.util.VillageMembershipTracker;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates deterministic "bootstrap pending" selection for unemployed villagers.
 *
 * <p>This hook runs on natural villager load and is intentionally independent of guard spawn
 * chance logic so both systems can evolve separately.
 */
public final class LumberjackBootstrapCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackBootstrapCoordinator.class);
    private static final int REGION_SIZE_BLOCKS = 96;
    private static final int REGION_HALF_EXTENT = REGION_SIZE_BLOCKS / 2;

    /**
     * World-scoped pending reservation map keyed by logical village region identity.
     */
    private static final Map<RegistryKey<World>, Map<VillageKey, UUID>> BOOTSTRAP_PENDING = new HashMap<>();

    private LumberjackBootstrapCoordinator() {
    }

    public static void attemptSelection(ServerWorld world, VillagerEntity naturalVillager) {
        if (!isEligibleUnemployed(naturalVillager)) {
            return;
        }

        VillageScope scope = resolveScope(world, naturalVillager);
        if (scope == null) {
            return;
        }

        Map<VillageKey, UUID> worldPending = BOOTSTRAP_PENDING.computeIfAbsent(world.getRegistryKey(), ignored -> new HashMap<>());
        UUID pendingUuid = worldPending.get(scope.key());
        if (pendingUuid != null && isStillEligible(world, pendingUuid, scope.searchBox())) {
            return;
        }

        List<VillagerEntity> candidates = world.getEntitiesByClass(
                VillagerEntity.class,
                scope.searchBox(),
                villager -> isEligibleCandidate(villager, worldPending)
        );

        VillagerEntity selected = candidates.stream()
                .sorted(Comparator
                        .comparingDouble((VillagerEntity villager) -> villager.squaredDistanceTo(naturalVillager))
                        .thenComparing(villager -> villager.getUuid().toString()))
                .findFirst()
                .orElse(null);

        if (selected == null) {
            worldPending.remove(scope.key());
            return;
        }

        worldPending.put(scope.key(), selected.getUuid());
        LOGGER.debug("lumberjack-bootstrap pending selected village={} villager={} anchor={} candidates={}",
                scope.key(),
                selected.getUuidAsString(),
                naturalVillager.getBlockPos().toShortString(),
                candidates.size());
    }

    public static void onWorldUnload(RegistryKey<World> worldKey) {
        BOOTSTRAP_PENDING.remove(worldKey);
    }

    private static VillageScope resolveScope(ServerWorld world, VillagerEntity villager) {
        GlobalPos homeBell = VillageMembershipTracker.getHomeBell(villager);
        if (homeBell != null && homeBell.dimension().equals(world.getRegistryKey())) {
            BlockPos primaryBell = BellChestMappingState.get(world.getServer())
                    .getPrimaryBell(world, homeBell.pos())
                    .toImmutable();
            return new VillageScope(
                    VillageKey.forBell(primaryBell),
                    new Box(primaryBell).expand(VillageGuardStandManager.BELL_EFFECT_RANGE)
            );
        }

        BlockPos center = regionCenter(villager.getBlockPos());
        return new VillageScope(
                VillageKey.forRegion(toRegionKey(villager.getBlockPos())),
                new Box(
                        center.getX() - REGION_HALF_EXTENT,
                        world.getBottomY(),
                        center.getZ() - REGION_HALF_EXTENT,
                        center.getX() + REGION_HALF_EXTENT + 1,
                        world.getTopY(),
                        center.getZ() + REGION_HALF_EXTENT + 1
                )
        );
    }

    private static boolean isEligibleCandidate(VillagerEntity villager, Map<VillageKey, UUID> worldPending) {
        if (!isEligibleUnemployed(villager)) {
            return false;
        }
        return worldPending.values().stream().noneMatch(villager.getUuid()::equals);
    }

    private static boolean isStillEligible(ServerWorld world, UUID pendingUuid, Box scope) {
        VillagerEntity existing = world.getEntitiesByClass(
                VillagerEntity.class,
                scope,
                villager -> villager.getUuid().equals(pendingUuid)
        ).stream().findFirst().orElse(null);

        return existing != null && isEligibleUnemployed(existing);
    }

    private static boolean isEligibleUnemployed(VillagerEntity villager) {
        if (!villager.isAlive() || villager.isRemoved() || villager.isBaby()) {
            return false;
        }
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession == VillagerProfession.NONE;
    }

    private static long toRegionKey(BlockPos pos) {
        int regionX = Math.floorDiv(pos.getX(), REGION_SIZE_BLOCKS);
        int regionZ = Math.floorDiv(pos.getZ(), REGION_SIZE_BLOCKS);
        return ((long) regionX << 32) | (regionZ & 0xffffffffL);
    }

    private static BlockPos regionCenter(BlockPos pos) {
        int regionX = Math.floorDiv(pos.getX(), REGION_SIZE_BLOCKS);
        int regionZ = Math.floorDiv(pos.getZ(), REGION_SIZE_BLOCKS);
        return new BlockPos(
                regionX * REGION_SIZE_BLOCKS + REGION_SIZE_BLOCKS / 2,
                pos.getY(),
                regionZ * REGION_SIZE_BLOCKS + REGION_SIZE_BLOCKS / 2
        );
    }

    private record VillageScope(VillageKey key, Box searchBox) {
    }

    private record VillageKey(Kind kind, long packed) {
        static VillageKey forBell(BlockPos bellPos) {
            return new VillageKey(Kind.BELL, bellPos.asLong());
        }

        static VillageKey forRegion(long regionKey) {
            return new VillageKey(Kind.REGION, regionKey);
        }

        private enum Kind {
            BELL,
            REGION
        }
    }
}
