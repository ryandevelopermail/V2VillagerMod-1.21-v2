package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.util.BellChestMappingState;
import dev.sterner.guardvillagers.common.util.LumberjackBootstrapLifecycleState;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
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
    private static final long STALE_SELECTION_RETRY_TICKS = 20L * 30L;

    private LumberjackBootstrapCoordinator() {
    }

    public static void onWorldLoad(ServerWorld world) {
        // Ensure persistent state object is materialized on world load.
        LumberjackBootstrapLifecycleState.get(world.getServer());
    }

    public static void tick(ServerWorld world) {
        // Reserved for lifecycle state maintenance. Explicit hook keeps lifecycle wiring
        // consistent with other world managers and allows future pruning without API churn.
    }

    public static void attemptSelection(ServerWorld world, VillagerEntity naturalVillager) {
        if (!isEligibleUnemployed(naturalVillager)) {
            return;
        }

        VillageScope scope = resolveScope(world, naturalVillager);
        if (scope == null) {
            return;
        }

        LumberjackBootstrapLifecycleState state = LumberjackBootstrapLifecycleState.get(world.getServer());
        LumberjackBootstrapLifecycleState.EntryValue existing = state
                .getEntry(world, scope.key().kind(), scope.key().packed())
                .orElse(null);

        if (existing != null && existing.stage().isTerminal()) {
            return;
        }

        if (existing != null && isStillEligible(world, existing.candidateUuid(), scope.searchBox())) {
            state.selectOrRefresh(world,
                    scope.key().kind(),
                    scope.key().packed(),
                    existing.candidateUuid(),
                    scope.anchor(),
                    world.getTime());
            return;
        }

        List<VillagerEntity> candidates = world.getEntitiesByClass(
                VillagerEntity.class,
                scope.searchBox(),
                LumberjackBootstrapCoordinator::isEligibleUnemployed
        );

        VillagerEntity selected = candidates.stream()
                .sorted(Comparator
                        .comparingDouble((VillagerEntity villager) -> villager.squaredDistanceTo(naturalVillager))
                        .thenComparing(villager -> villager.getUuid().toString()))
                .findFirst()
                .orElse(null);

        if (selected == null) {
            if (existing != null && world.getTime() - existing.updatedTick() >= STALE_SELECTION_RETRY_TICKS) {
                state.markRetry(world, scope.key().kind(), scope.key().packed(), world.getTime());
                state.advanceStage(world,
                        scope.key().kind(),
                        scope.key().packed(),
                        LumberjackBootstrapLifecycleState.Stage.FAILED,
                        world.getTime());
            }
            return;
        }

        LumberjackBootstrapLifecycleState.EntryValue updated = state.selectOrRefresh(
                world,
                scope.key().kind(),
                scope.key().packed(),
                selected.getUuid(),
                scope.anchor(),
                world.getTime()
        );

        LOGGER.debug("lumberjack-bootstrap pending selected village={} villager={} anchor={} candidates={} stage={} retries={}",
                scope.key(),
                selected.getUuidAsString(),
                scope.anchor().toShortString(),
                candidates.size(),
                updated.stage(),
                updated.retryCount());
    }

    public static void markNeedsTable(ServerWorld world, VillagerEntity villager) {
        transitionForVillager(world, villager, LumberjackBootstrapLifecycleState.Stage.NEEDS_TABLE, true);
    }

    public static void markReadyToConvert(ServerWorld world, VillagerEntity villager) {
        transitionForVillager(world, villager, LumberjackBootstrapLifecycleState.Stage.READY_TO_CONVERT, false);
    }

    public static void markDone(ServerWorld world, VillagerEntity villager) {
        transitionForVillager(world, villager, LumberjackBootstrapLifecycleState.Stage.DONE, false);
    }

    public static void onWorldUnload(RegistryKey<World> worldKey) {
        // No runtime cache to clear; lifecycle state is persistent.
    }

    private static void transitionForVillager(ServerWorld world,
                                              VillagerEntity villager,
                                              LumberjackBootstrapLifecycleState.Stage stage,
                                              boolean countRetry) {
        VillageScope scope = resolveScope(world, villager);
        if (scope == null) {
            return;
        }

        LumberjackBootstrapLifecycleState state = LumberjackBootstrapLifecycleState.get(world.getServer());
        LumberjackBootstrapLifecycleState.EntryValue entry = state
                .getEntry(world, scope.key().kind(), scope.key().packed())
                .orElse(null);
        if (entry == null || !entry.candidateUuid().equals(villager.getUuid())) {
            return;
        }

        if (countRetry) {
            state.markRetry(world, scope.key().kind(), scope.key().packed(), world.getTime());
        }
        state.advanceStage(world, scope.key().kind(), scope.key().packed(), stage, world.getTime());
    }

    @Nullable
    private static VillageScope resolveScope(ServerWorld world, VillagerEntity villager) {
        GlobalPos homeBell = VillageMembershipTracker.getHomeBell(villager);
        if (homeBell != null && homeBell.dimension().equals(world.getRegistryKey())) {
            BlockPos primaryBell = BellChestMappingState.get(world.getServer())
                    .getPrimaryBell(world, homeBell.pos())
                    .toImmutable();
            return new VillageScope(
                    VillageKey.forBell(primaryBell),
                    primaryBell,
                    new Box(primaryBell).expand(VillageGuardStandManager.BELL_EFFECT_RANGE)
            );
        }

        BlockPos center = regionCenter(villager.getBlockPos());
        return new VillageScope(
                VillageKey.forRegion(toRegionKey(villager.getBlockPos())),
                center,
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

    private record VillageScope(VillageKey key, BlockPos anchor, Box searchBox) {
    }

    private record VillageKey(LumberjackBootstrapLifecycleState.VillageKind kind, long packed) {
        static VillageKey forBell(BlockPos bellPos) {
            return new VillageKey(LumberjackBootstrapLifecycleState.VillageKind.BELL, bellPos.asLong());
        }

        static VillageKey forRegion(long regionKey) {
            return new VillageKey(LumberjackBootstrapLifecycleState.VillageKind.REGION, regionKey);
        }
    }
}
