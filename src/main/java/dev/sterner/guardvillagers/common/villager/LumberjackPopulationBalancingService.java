package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LumberjackPopulationBalancingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackPopulationBalancingService.class);
    private static final long BALANCING_INTERVAL_TICKS = 200L;
    private static final int REGION_SIZE_BLOCKS = 96;
    private static final int REGION_HALF_EXTENT = REGION_SIZE_BLOCKS / 2;

    /**
     * Dual-ratio gating: one lumberjack per N professionals (non-nitwit, non-unemployed)
     * OR one per M total non-nitwit villagers, whichever demands more lumberjacks.
     * The existing {@code belowRatioThreshold()} uses RATIO_TOTAL as the primary guard.
     */
    private static final int RATIO_PROFESSIONALS = 3;
    private static final int RATIO_TOTAL = 6;

    /** @deprecated Use RATIO_TOTAL for the region-snapshot guard. Kept for log messages. */
    @SuppressWarnings("DeprecatedIsStillUsed")
    @Deprecated
    private static final int MIN_VILLAGERS_PER_GUARD = RATIO_TOTAL;

    private static final Map<RegistryKey<World>, Map<Long, RegionSnapshot>> LATEST_SNAPSHOTS = new HashMap<>();
    private static final Map<RegistryKey<World>, Long> LAST_REFRESH_TICK = new HashMap<>();

    private LumberjackPopulationBalancingService() {
    }

    public static void tick(ServerWorld world) {
        long now = world.getTime();
        long last = LAST_REFRESH_TICK.getOrDefault(world.getRegistryKey(), Long.MIN_VALUE);
        if (now - last < BALANCING_INTERVAL_TICKS) {
            return;
        }
        refresh(world, "scheduled");
    }

    public static void onWorldUnload(RegistryKey<World> worldKey) {
        LATEST_SNAPSHOTS.remove(worldKey);
        LAST_REFRESH_TICK.remove(worldKey);
    }

    public static boolean shouldAllowCreationAttempts(ServerWorld world, BlockPos anchorPos, String trigger) {
        RegionSnapshot snapshot = getOrRefreshSnapshot(world, anchorPos, trigger);
        if (snapshot == null) {
            return false;
        }

        if (!snapshot.belowRatioThreshold()) {
            LOGGER.debug("lumberjack-balance deny trigger={} region={} villagers={} activeGuards={} unemployed={} reason=ratio-not-below-1:{}",
                    trigger,
                    snapshot.regionCenter().toShortString(),
                    snapshot.v2Villagers(),
                    snapshot.activeLumberjackGuards(),
                    snapshot.unemployedCandidates(),
                    MIN_VILLAGERS_PER_GUARD);
            return false;
        }

        if (snapshot.unemployedCandidates() <= 0) {
            LOGGER.debug("lumberjack-balance deny trigger={} region={} villagers={} activeGuards={} unemployed={} reason=no-eligible-unemployed",
                    trigger,
                    snapshot.regionCenter().toShortString(),
                    snapshot.v2Villagers(),
                    snapshot.activeLumberjackGuards(),
                    snapshot.unemployedCandidates());
            return false;
        }

        LOGGER.debug("lumberjack-balance allow trigger={} region={} villagers={} activeGuards={} unemployed={} ratioThreshold=1:{}",
                trigger,
                snapshot.regionCenter().toShortString(),
                snapshot.v2Villagers(),
                snapshot.activeLumberjackGuards(),
                snapshot.unemployedCandidates(),
                MIN_VILLAGERS_PER_GUARD);
        return true;
    }

    private static RegionSnapshot getOrRefreshSnapshot(ServerWorld world, BlockPos anchorPos, String trigger) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        long regionKey = toRegionKey(anchorPos);
        RegionSnapshot cached = LATEST_SNAPSHOTS.getOrDefault(worldKey, Map.of()).get(regionKey);
        if (cached != null) {
            return cached;
        }

        RegionSnapshot built = buildSnapshot(world, anchorPos);
        LATEST_SNAPSHOTS.computeIfAbsent(worldKey, ignored -> new HashMap<>()).put(regionKey, built);
        LOGGER.debug("lumberjack-balance snapshot source={} region={} villagers={} activeGuards={} unemployed={} ratioThreshold=1:{}",
                "on-demand:" + trigger,
                built.regionCenter().toShortString(),
                built.v2Villagers(),
                built.activeLumberjackGuards(),
                built.unemployedCandidates(),
                MIN_VILLAGERS_PER_GUARD);
        return built;
    }

    private static void refresh(ServerWorld world, String source) {
        Map<Long, RegionSnapshot> worldSnapshots = LATEST_SNAPSHOTS.computeIfAbsent(world.getRegistryKey(), ignored -> new HashMap<>());
        worldSnapshots.clear();

        for (AxeGuardEntity guard : world.getEntitiesByClass(AxeGuardEntity.class,
                new Box(world.getWorldBorder().getBoundWest(), world.getBottomY(), world.getWorldBorder().getBoundNorth(), world.getWorldBorder().getBoundEast(), world.getTopY(), world.getWorldBorder().getBoundSouth()),
                LumberjackPopulationBalancingService::isActiveLumberjackGuard)) {
            RegionSnapshot snapshot = buildSnapshot(world, guard.getBlockPos());
            worldSnapshots.put(snapshot.regionKey(), snapshot);
        }

        LAST_REFRESH_TICK.put(world.getRegistryKey(), world.getTime());
        if (worldSnapshots.isEmpty()) {
            RegionSnapshot fallback = buildSnapshot(world, BlockPos.ORIGIN);
            worldSnapshots.put(fallback.regionKey(), fallback);
            LOGGER.debug("lumberjack-balance snapshot source={} region={} villagers={} activeGuards={} unemployed={} ratioThreshold=1:{}",
                    source,
                    fallback.regionCenter().toShortString(),
                    fallback.v2Villagers(),
                    fallback.activeLumberjackGuards(),
                    fallback.unemployedCandidates(),
                    MIN_VILLAGERS_PER_GUARD);
            return;
        }

        for (RegionSnapshot snapshot : worldSnapshots.values()) {
            LOGGER.debug("lumberjack-balance snapshot source={} region={} villagers={} activeGuards={} unemployed={} ratioThreshold=1:{}",
                    source,
                    snapshot.regionCenter().toShortString(),
                    snapshot.v2Villagers(),
                    snapshot.activeLumberjackGuards(),
                    snapshot.unemployedCandidates(),
                    MIN_VILLAGERS_PER_GUARD);
        }
    }

    private static RegionSnapshot buildSnapshot(ServerWorld world, BlockPos anchorPos) {
        BlockPos center = regionCenter(anchorPos);
        Box scope = new Box(
                center.getX() - REGION_HALF_EXTENT,
                world.getBottomY(),
                center.getZ() - REGION_HALF_EXTENT,
                center.getX() + REGION_HALF_EXTENT + 1,
                world.getTopY(),
                center.getZ() + REGION_HALF_EXTENT + 1
        );

        List<VillagerEntity> allVillagers = world.getEntitiesByClass(VillagerEntity.class, scope, LumberjackPopulationBalancingService::isEligibleV2Villager);
        int villagerCount = allVillagers.size();
        int unemployedCount = (int) allVillagers.stream().filter(LumberjackPopulationBalancingService::isEligibleUnemployedVillager).count();
        int professionalCount = (int) allVillagers.stream().filter(LumberjackPopulationBalancingService::isProfessional).count();
        int guardCount = world.getEntitiesByClass(AxeGuardEntity.class, scope, LumberjackPopulationBalancingService::isActiveLumberjackGuard).size();

        return new RegionSnapshot(toRegionKey(anchorPos), center, villagerCount, professionalCount, guardCount, unemployedCount);
    }

    private static boolean isEligibleV2Villager(VillagerEntity villager) {
        if (!villager.isAlive() || villager.isRemoved() || villager.isBaby()) {
            return false;
        }
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession != VillagerProfession.NITWIT;
    }

    private static boolean isEligibleUnemployedVillager(VillagerEntity villager) {
        if (!isEligibleV2Villager(villager)) {
            return false;
        }
        return villager.getVillagerData().getProfession() == VillagerProfession.NONE;
    }

    private static boolean isProfessional(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT;
    }

    private static boolean isActiveLumberjackGuard(AxeGuardEntity guard) {
        return guard.isAlive() && !guard.isRemoved();
    }

    private static long toRegionKey(BlockPos pos) {
        int regionX = Math.floorDiv(pos.getX(), REGION_SIZE_BLOCKS);
        int regionZ = Math.floorDiv(pos.getZ(), REGION_SIZE_BLOCKS);
        return ((long) regionX << 32) | (regionZ & 0xffffffffL);
    }

    private static BlockPos regionCenter(BlockPos pos) {
        int regionX = Math.floorDiv(pos.getX(), REGION_SIZE_BLOCKS);
        int regionZ = Math.floorDiv(pos.getZ(), REGION_SIZE_BLOCKS);
        int centerX = regionX * REGION_SIZE_BLOCKS + REGION_SIZE_BLOCKS / 2;
        int centerZ = regionZ * REGION_SIZE_BLOCKS + REGION_SIZE_BLOCKS / 2;
        return new BlockPos(centerX, pos.getY(), centerZ);
    }

    private record RegionSnapshot(long regionKey,
                                  BlockPos regionCenter,
                                  int v2Villagers,
                                  int professionals,
                                  int activeLumberjackGuards,
                                  int unemployedCandidates) {
        /**
         * Returns {@code true} when more lumberjacks are wanted by either ratio:
         * <ul>
         *   <li>1 per {@value RATIO_PROFESSIONALS} professionals, OR</li>
         *   <li>1 per {@value RATIO_TOTAL} total non-nitwit villagers</li>
         * </ul>
         */
        private boolean belowRatioThreshold() {
            int desiredFromProfessionals = professionals > 0
                    ? (professionals + RATIO_PROFESSIONALS - 1) / RATIO_PROFESSIONALS : 0;
            int desiredFromTotal = v2Villagers > 0
                    ? (v2Villagers + RATIO_TOTAL - 1) / RATIO_TOTAL : 0;
            int desired = Math.max(desiredFromProfessionals, desiredFromTotal);
            return activeLumberjackGuards < desired;
        }
    }
}
