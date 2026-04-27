package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.GuardConversionHelper;
import dev.sterner.guardvillagers.common.villager.LumberjackBootstrapCoordinator;
import dev.sterner.guardvillagers.common.villager.LumberjackConversionInitializer;
import dev.sterner.guardvillagers.common.villager.LumberjackPopulationBalancingService;
import dev.sterner.guardvillagers.common.villager.UnemployedLumberjackConversionHook;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.ServerWorldAccess;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Periodically ensures enough lumberjacks exist relative to village population.
 *
 * <p><b>Strategy (v3 — villager-first placement):</b>
 * <ol>
 *   <li>Count existing lumberjacks near the bell; if enough, skip.</li>
 *   <li>Gate on tree supply: if there aren't enough harvestable trees/saplings, defer.</li>
 *   <li>Find or spawn an unemployed villager near the bell.</li>
 *   <li>Place a crafting table on a solid, open block within
 *       {@link JobBlockPairingHelper#JOB_BLOCK_PAIRING_RANGE} of that villager.</li>
 *   <li>Force-convert the villager immediately — vanilla cannot produce custom entity types.</li>
 * </ol>
 *
 * <p>Placing next to the villager (not next to an existing chest) is critical because the
 * lumberjack later crafts and places its own chest beside its table.  Pre-pairing to a
 * natural chest disrupts the lumberjack workflow.
 */
public final class VillageLumberjackSpawnManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(VillageLumberjackSpawnManager.class);

    /** How often (ticks) the bell scan runs. 6000 ticks = 5 minutes at 20 TPS. */
    private static final long SCAN_INTERVAL_TICKS = 6000L;
    private static final long SCAN_PHASE_OFFSET = 3L;
    private static final String ATTEMPT_MAINTENANCE_SCAN = "maintenance_scan";
    private static final String ATTEMPT_UNDER_PROVISION_RETRY = "under_provision_retry";

    /**
     * Per-bell retry scheduling for under-provisioned villages.
     * Only bells in this map are revisited between maintenance scans.
     */
    private static final Map<BlockPos, Long> NEXT_RETRY_TICK_BY_BELL = new HashMap<>();

    /**
     * Tree-supply cache: maps bell position → expiry tick.
     * A cached {@code true} (enough trees) is valid until TREE_SUPPLY_CACHE_TTL_TICKS have elapsed.
     * We only cache the "sufficient" result — insufficient results are not cached so the system
     * can recheck quickly after foresters plant more saplings.
     */
    private static final Map<BlockPos, Long> TREE_SUPPLY_CACHE_EXPIRY = new HashMap<>();
    /** 1200 ticks = 60 s. Tree density doesn't change faster than this. */
    private static final long TREE_SUPPLY_CACHE_TTL_TICKS = 1200L;

    /**
     * C2 fix — orphaned-table sweep.
     *
     * {@link UnemployedLumberjackConversionHook} only scans within 8 blocks of each online player,
     * so crafting tables placed in areas without nearby players are never claimed.  This sweep runs
     * independently of player proximity: every {@value #ORPHAN_SWEEP_INTERVAL_TICKS} ticks it
     * finds unreserved, unpaired tables near each known bell and force-converts the nearest
     * unemployed villager within {@link #UNEMPLOYED_SCAN_RANGE}.  The same population-balancer and
     * reservation guards as the main spawn path apply.
     */
    private static final long ORPHAN_SWEEP_INTERVAL_TICKS = 1200L;
    /** Phase offset keeps the sweep from firing on the same tick as the maintenance scan. */
    private static final long ORPHAN_SWEEP_PHASE_OFFSET = 601L;

    /** Bell effect radius. */
    private static final int BELL_EFFECT_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;

    /**
     * How far from the bell we search for village chests to anchor table placement.
     * Generous so it covers large village layouts.
     */
    private static final int CHEST_SCAN_RANGE = 48;

    /**
     * How far from the placed table we scan for an unemployed villager to convert.
     * Large enough to reach wandering villagers but small enough to stay local.
     */
    private static final double UNEMPLOYED_SCAN_RANGE = 32.0D;

    /**
     * Max distance (blocks) we search around each chest for a free adjacent floor slot.
     * Must be ≤ JOB_BLOCK_PAIRING_RANGE (3.0) so the table stays within pairing range.
     */
    private static final int TABLE_ADJACENT_RANGE = 2;

    /** Minimum gap between any two crafting tables (avoids stacking). */
    private static final int TABLE_MIN_SPACING = 4;
    /** Minimum spacing from job blocks/chests/crafting tables for new placements/conversions. */
    private static final double PLACEMENT_EXCLUSION_RADIUS = 6.0D;

    /** Ratio: one lumberjack per N professionals. */
    private static final int RATIO_PROFESSIONALS = 3;

    /** Ratio: one lumberjack per N total villagers (lower bound). */
    private static final int RATIO_TOTAL = 6;

    /**
     * Hard cap on forced-spawn lumberjacks per village bell.
     * No matter how large the village gets, we never force more than this many
     * lumberjacks — prevents an explosion of crafting tables.
     */
    private static final int MAX_FORCED_LUMBERJACKS = 1;

    /**
     * Fix 1 — stale-table guard.
     *
     * A crafting table that has been sitting unclaimed for longer than one full maintenance
     * cycle (SCAN_INTERVAL_TICKS) has clearly failed to attract a villager.  Don't let it
     * keep suppressing new spawns.  We approximate "stale" by checking whether any unemployed
     * villager is reachable within UNEMPLOYED_SCAN_RANGE of the table at the time of the
     * pending-count query.  If nobody is there to claim it, it is treated as stale and is
     * excluded from the effective-existing count, allowing the spawn manager to try again.
     */
    // (no extra constant needed — reuses UNEMPLOYED_SCAN_RANGE)

    /**
     * Fix 3 — tree-supply gate.
     *
     * Before spawning a new lumberjack, check whether the local forest can sustain one more.
     * We count harvestable trees (blocks tagged {@code #logs}) and young saplings (tagged
     * {@code #saplings}) within {@code lumberjackBaseTreeSearchRadius} of the bell.
     * Each lumberjack needs at least {@value TREES_NEEDED_PER_LUMBERJACK} log-sources or
     * planted saplings to stay meaningfully busy.  If the supply falls short, the spawn is
     * deferred; the retry timer will re-check after foresters have had time to plant.
     *
     * <p>A "log-source" is counted as one tree trunk column: we count only the bottommost log
     * block (y == surface level or the log directly above a non-log block) to avoid
     * over-counting multi-log tall trees.  Saplings count at half weight because they haven't
     * grown yet.
     */
    private static final int TREES_NEEDED_PER_LUMBERJACK = 4;
    /** Deterministic escalation threshold for direct fallback spawn path. */
    private static final int ESCALATION_RETRY_THRESHOLD = 3;

    private VillageLumberjackSpawnManager() {
    }

    // -------------------------------------------------------------------------
    // Tick entry
    // -------------------------------------------------------------------------

    public static void tick(ServerWorld world) {
        // BellChestMappingState is stored on the overworld; villagers only exist in the overworld.
        if (!world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) {
            return;
        }

        long now = world.getTime();
        boolean maintenanceDue = now % SCAN_INTERVAL_TICKS == SCAN_PHASE_OFFSET;

        if (maintenanceDue) {
            java.util.Set<BlockPos> bellPositions = BellChestMappingState.get(world.getServer()).getBellPositions(world);
            if (!bellPositions.isEmpty()) {
                Set<BlockPos> normalizedBells = new HashSet<>();
                for (BlockPos bellPos : bellPositions) {
                    BlockPos immutableBellPos = bellPos.toImmutable();
                    normalizedBells.add(immutableBellPos);
                    processBell(world, immutableBellPos, ATTEMPT_MAINTENANCE_SCAN, now);
                }
                NEXT_RETRY_TICK_BY_BELL.keySet().removeIf(pos -> !normalizedBells.contains(pos));
                // Evict tree-supply cache for bells that no longer exist.
                TREE_SUPPLY_CACHE_EXPIRY.keySet().removeIf(pos -> !normalizedBells.contains(pos));
            } else {
                NEXT_RETRY_TICK_BY_BELL.clear();
                TREE_SUPPLY_CACHE_EXPIRY.clear();
            }
        }

        // C2 fix: orphaned-table sweep — runs every 60 s regardless of player proximity.
        if (now % ORPHAN_SWEEP_INTERVAL_TICKS == ORPHAN_SWEEP_PHASE_OFFSET) {
            java.util.Set<BlockPos> bellPositions = BellChestMappingState.get(world.getServer()).getBellPositions(world);
            if (!bellPositions.isEmpty()) {
                sweepOrphanedTables(world, bellPositions);
            }
        }

        if (NEXT_RETRY_TICK_BY_BELL.isEmpty()) {
            return;
        }

        List<BlockPos> dueRetryBells = new ArrayList<>();
        for (Map.Entry<BlockPos, Long> entry : NEXT_RETRY_TICK_BY_BELL.entrySet()) {
            if (entry.getValue() <= now) {
                dueRetryBells.add(entry.getKey());
            }
        }
        for (BlockPos bellPos : dueRetryBells) {
            processBell(world, bellPos, ATTEMPT_UNDER_PROVISION_RETRY, now);
        }
    }

    // -------------------------------------------------------------------------
    // C2 — Orphaned-table sweep (player-proximity-independent conversion)
    // -------------------------------------------------------------------------

    /**
     * Scans all known bell positions for orphaned crafting tables — tables that exist in the world
     * but are neither reserved for a converted worker nor paired to a living lumberjack.
     *
     * <p>For each orphaned table, the nearest unemployed villager within
     * {@link #UNEMPLOYED_SCAN_RANGE} is force-converted (same path as the main spawn manager,
     * same population-balancer and reservation guards).  This handles the edge case where the
     * spawn manager placed a table but no player was nearby, so the
     * {@link UnemployedLumberjackConversionHook} (8-block player-proximity scan) never fired.
     */
    private static void sweepOrphanedTables(ServerWorld world, java.util.Set<BlockPos> bellPositions) {
        int tableSearchRadius = CHEST_SCAN_RANGE + TABLE_ADJACENT_RANGE + 2;
        LumberjackBootstrapLifecycleState lifecycleState = LumberjackBootstrapLifecycleState.get(world.getServer());

        for (BlockPos bellPos : bellPositions) {
            if (lifecycleState.hasAutoLumberjackSpawnedEver(world, bellPos)) {
                LOGGER.debug("lumberjack-orphan-sweep bell={} — skipped: auto-spawn already completed previously",
                        bellPos.toShortString());
                NEXT_RETRY_TICK_BY_BELL.remove(bellPos);
                continue;
            }
            List<BlockPos> nearbyTables = findExistingTablesNear(world, bellPos, tableSearchRadius);
            if (nearbyTables.isEmpty()) {
                continue;
            }

            Box bellBox = new Box(bellPos).expand(BELL_EFFECT_RANGE);
            List<LumberjackGuardEntity> activeLumberjacks = world.getEntitiesByClass(
                    LumberjackGuardEntity.class, bellBox, LumberjackGuardEntity::isAlive);

            Set<BlockPos> pairedToLiving = new HashSet<>();
            for (LumberjackGuardEntity lj : activeLumberjacks) {
                BlockPos paired = lj.getPairedCraftingTablePos();
                if (paired != null) {
                    pairedToLiving.add(paired.toImmutable());
                }
            }

            for (BlockPos tablePos : nearbyTables) {
                if (!world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE)) {
                    continue;
                }
                if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, tablePos)) {
                    continue;
                }
                if (pairedToLiving.contains(tablePos)) {
                    continue;
                }
                String exclusionReason = placementExclusionReason(world, tablePos, tablePos);
                if (exclusionReason != null) {
                    LOGGER.debug("lumberjack-orphan-sweep bell={} table={} — skipped: {}",
                            bellPos.toShortString(), tablePos.toShortString(), exclusionReason);
                    continue;
                }

                // Table is orphaned — try to claim it with a nearby unemployed villager.
                VillagerEntity candidate = findNearestUnemployed(world, tablePos);
                if (candidate == null) {
                    LOGGER.debug("lumberjack-orphan-sweep bell={} table={} — no unemployed villager within {} blocks",
                            bellPos.toShortString(), tablePos.toShortString(), UNEMPLOYED_SCAN_RANGE);
                    continue;
                }

                if (!LumberjackPopulationBalancingService.shouldAllowCreationAttempts(world, tablePos, "orphan-sweep")) {
                    LOGGER.debug("lumberjack-orphan-sweep bell={} table={} — population balancer denied",
                            bellPos.toShortString(), tablePos.toShortString());
                    continue;
                }

                LOGGER.info("lumberjack-orphan-sweep bell={} table={} — converting orphaned table with villager {}",
                        bellPos.toShortString(), tablePos.toShortString(), candidate.getUuidAsString());
                forceConvert(world, candidate, tablePos, bellPos);
                // One conversion per bell per sweep to pace ourselves.
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-bell logic
    // -------------------------------------------------------------------------

    private static void processBell(ServerWorld world, BlockPos bellPos, String attemptType, long now) {
        LumberjackBootstrapLifecycleState lifecycleState = LumberjackBootstrapLifecycleState.get(world.getServer());
        if (lifecycleState.hasAutoLumberjackSpawnedEver(world, bellPos)) {
            LOGGER.debug("lumberjack-spawn attempt={} bell={} — skipped: auto-spawn already completed previously",
                    attemptType, bellPos.toShortString());
            NEXT_RETRY_TICK_BY_BELL.remove(bellPos);
            lifecycleState.clearSpawnRetryCount(world, bellPos);
            return;
        }
        if (LumberjackBootstrapCoordinator.isVillageInActiveBootstrapLifecycle(world, bellPos)) {
            LOGGER.debug("lumberjack-spawn attempt={} bell={} — skipped while bootstrap lifecycle is active",
                    attemptType, bellPos.toShortString());
            NEXT_RETRY_TICK_BY_BELL.remove(bellPos);
            return;
        }

        Box bellBox = new Box(bellPos).expand(BELL_EFFECT_RANGE);

        List<VillagerEntity> allVillagers = world.getEntitiesByClass(
                VillagerEntity.class, bellBox, VillageLumberjackSpawnManager::isEligibleVillager);

        int totalVillagers = allVillagers.size();
        int professionals = (int) allVillagers.stream()
                .filter(VillageLumberjackSpawnManager::isProfessional)
                .count();

        int desired = desiredLumberjackCount(professionals, totalVillagers, totalVillagers > 0);
        List<LumberjackGuardEntity> activeLumberjacks = world.getEntitiesByClass(
                LumberjackGuardEntity.class, bellBox, LumberjackGuardEntity::isAlive);
        int existing = activeLumberjacks.size();

        // Compute the table list once here and pass it through to avoid a duplicate scan
        // in tryPlaceTableAndConvert (P3 fix — findExistingTablesNear was called twice per tick).
        int tableSearchRadius = CHEST_SCAN_RANGE + TABLE_ADJACENT_RANGE + 2;
        List<BlockPos> nearbyTables = findExistingTablesNear(world, bellPos, tableSearchRadius);

        int pendingUnclaimedTables = countPendingUnclaimedCraftingTablesNearBell(world, bellPos, activeLumberjacks, nearbyTables);
        int effectiveExisting = existing + pendingUnclaimedTables;

        if (effectiveExisting >= desired) {
            NEXT_RETRY_TICK_BY_BELL.remove(bellPos);
            lifecycleState.clearSpawnRetryCount(world, bellPos);
            LOGGER.debug("lumberjack-spawn attempt={} bell={} professionals={} total={} desired={} active={} pending={} effective={} — skip placement",
                    attemptType, bellPos.toShortString(), professionals, totalVillagers, desired, existing, pendingUnclaimedTables, effectiveExisting);
            return;
        }

        // Fix 3: tree-supply gate — don't spawn a lumberjack when the forest can't keep them busy.
        // Count harvestable log-roots and planted saplings near the bell; if there aren't enough
        // to sustain the desired lumberjack count, defer until the forester has done more planting.
        int nextDesired = effectiveExisting + 1; // we're about to place one more
        if (!hasEnoughTreeSupplyForLumberjackCount(world, bellPos, nextDesired)) {
            long retryIntervalTicks = Math.max(20L, GuardVillagersConfig.lumberjackSpawnRetryIntervalTicks);
            NEXT_RETRY_TICK_BY_BELL.put(bellPos, now + retryIntervalTicks);
            LOGGER.info("lumberjack-spawn attempt={} bell={} — tree supply insufficient for {} lumberjack(s); deferring {}t",
                    attemptType, bellPos.toShortString(), nextDesired, retryIntervalTicks);
            return;
        }

        LOGGER.info("lumberjack-spawn attempt={} bell={} professionals={} total={} desired={} active={} pending={} effective={} — placing table",
                attemptType, bellPos.toShortString(), professionals, totalVillagers, desired, existing, pendingUnclaimedTables, effectiveExisting);

        // Attempt one table + conversion per scan cycle (pace ourselves).
        // Pass the pre-computed table list so tryPlaceTableAndConvert doesn't re-scan.
        boolean spawned = tryPlaceTableAndConvert(world, bellPos, attemptType, nearbyTables);
        if (spawned) {
            NEXT_RETRY_TICK_BY_BELL.remove(bellPos);
            lifecycleState.clearSpawnRetryCount(world, bellPos);
            return;
        }
        long retryIntervalTicks = Math.max(20L, GuardVillagersConfig.lumberjackSpawnRetryIntervalTicks);
        NEXT_RETRY_TICK_BY_BELL.put(bellPos, now + retryIntervalTicks);
    }

    // -------------------------------------------------------------------------
    // Villager-first table placement + conversion
    // -------------------------------------------------------------------------

    /**
     * Correct spawn order:
     * <ol>
     *   <li>Find the nearest unemployed villager within {@link #UNEMPLOYED_SCAN_RANGE} of the bell.</li>
     *   <li>If none found, spawn one at a safe surface position near the bell.</li>
     *   <li>Place a crafting table on a solid, open block within 3 blocks of the villager.</li>
     *   <li>Force-convert that villager immediately (vanilla cannot convert to custom entity types).</li>
     * </ol>
     *
     * <p>This ensures the table is placed beside the villager, not beside a random
     * existing chest. The lumberjack will later craft and place its own chest next to
     * the table — placing the table next to a natural chest first would pre-pair the
     * lumberjack to the wrong chest and disrupt its workflow.
     */
    private static boolean tryPlaceTableAndConvert(ServerWorld world, BlockPos bellPos, String attemptType,
                                                   List<BlockPos> existingTables) {
        if (!LumberjackPopulationBalancingService.shouldAllowCreationAttempts(world, bellPos, attemptType)) {
            LOGGER.debug("lumberjack-spawn attempt={} bell={} — population balancer denied at pre-placement", attemptType, bellPos.toShortString());
            return handleFailedCycleAndMaybeEscalate(world, bellPos, attemptType, existingTables, "population-balancer-denied");
        }

        // Step 1: find or spawn an unemployed villager.
        Box bellBox = new Box(bellPos).expand(UNEMPLOYED_SCAN_RANGE);
        VillagerEntity candidate = findNearestUnemployed(world, bellPos);
        if (candidate == null) {
            // Spawn a fresh unemployed villager at a safe position near the bell.
            candidate = spawnUnemployedVillagerNearBell(world, bellPos);
            if (candidate == null) {
                LOGGER.debug("lumberjack-spawn attempt={} bell={} — could not find or spawn unemployed villager; aborting",
                        attemptType, bellPos.toShortString());
                return handleFailedCycleAndMaybeEscalate(world, bellPos, attemptType, existingTables, "no-candidate-villager");
            }
            LOGGER.info("lumberjack-spawn attempt={} bell={} — spawned unemployed villager {} for conversion",
                    attemptType, bellPos.toShortString(), candidate.getUuidAsString());
        } else {
            LOGGER.info("lumberjack-spawn attempt={} bell={} — found unemployed villager {} at {}",
                    attemptType, bellPos.toShortString(), candidate.getUuidAsString(),
                    candidate.getBlockPos().toShortString());
        }

        // Step 2: place a crafting table within 3 blocks of that villager.
        BlockPos tablePos = findFreeSlotNearVillager(world, candidate.getBlockPos(), existingTables);
        if (tablePos == null) {
            LOGGER.debug("lumberjack-spawn attempt={} bell={} — no open floor slot within 3 blocks of villager {}; aborting",
                    attemptType, bellPos.toShortString(), candidate.getUuidAsString());
            return handleFailedCycleAndMaybeEscalate(world, bellPos, attemptType, existingTables, "no-table-placement-slot");
        }

        world.setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
        LOGGER.info("lumberjack-spawn attempt={} placed crafting table at {} beside villager {} near bell {}",
                attemptType, tablePos.toShortString(), candidate.getUuidAsString(), bellPos.toShortString());

        // Step 3: immediately force-convert (vanilla cannot produce custom entity types).
        ConversionResult conversionResult = forceConvert(world, candidate, tablePos, bellPos);
        if (!conversionResult.success()) {
            LOGGER.info("lumberjack-spawn attempt={} bell={} — conversion failed (reason={})",
                    attemptType, bellPos.toShortString(), conversionResult.reason());
            return handleFailedCycleAndMaybeEscalate(world, bellPos, attemptType, existingTables,
                    "conversion-blocked:" + conversionResult.reason());
        }
        return true;
    }

    private static boolean handleFailedCycleAndMaybeEscalate(ServerWorld world,
                                                             BlockPos bellPos,
                                                             String attemptType,
                                                             List<BlockPos> existingTables,
                                                             String failureReason) {
        LumberjackBootstrapLifecycleState lifecycleState = LumberjackBootstrapLifecycleState.get(world.getServer());
        int retryCount = lifecycleState.incrementSpawnRetryCount(world, bellPos);
        LOGGER.info("lumberjack-spawn attempt={} bell={} — failed cycle reason={} retry_count={} escalation_threshold={}",
                attemptType, bellPos.toShortString(), failureReason, retryCount, ESCALATION_RETRY_THRESHOLD);
        if (retryCount < ESCALATION_RETRY_THRESHOLD) {
            return false;
        }

        LOGGER.info("lumberjack-spawn attempt={} bell={} — escalation triggered after {} failed cycles; starting direct fallback",
                attemptType, bellPos.toShortString(), retryCount);
        FallbackSpawnResult fallbackResult = runDirectFallbackSpawn(world, bellPos, attemptType, existingTables);
        if (fallbackResult.success()) {
            lifecycleState.clearSpawnRetryCount(world, bellPos);
            NEXT_RETRY_TICK_BY_BELL.remove(bellPos);
            LOGGER.info("lumberjack-spawn attempt={} bell={} — escalation success guard={} table={}",
                    attemptType, bellPos.toShortString(), fallbackResult.guardUuid(), fallbackResult.tablePos().toShortString());
            return true;
        }

        LOGGER.warn("lumberjack-spawn attempt={} bell={} — escalation failed reason={}",
                attemptType, bellPos.toShortString(), fallbackResult.reason());
        return false;
    }

    /**
     * Finds a free floor slot within {@link JobBlockPairingHelper#JOB_BLOCK_PAIRING_RANGE}
     * of the given villager position. Air above, solid floor below, not too close to an
     * existing table.
     */
    private static BlockPos findFreeSlotNearVillager(ServerWorld world, BlockPos villagerPos, List<BlockPos> existingTables) {
        int range = (int) Math.floor(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos candidate = villagerPos.add(dx, dy, dz);
                    if (candidate.equals(villagerPos)) continue;
                    if (!villagerPos.isWithinDistance(candidate, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) {
                        logPlacementReject("spawn-candidate", candidate, "outside pairing range from villager");
                        continue;
                    }
                    if (!world.getBlockState(candidate).isReplaceable()) {
                        logPlacementReject("spawn-candidate", candidate, "target block not replaceable");
                        continue;
                    }
                    if (!isStandablePlacement(world, candidate, false)) {
                        logPlacementReject("spawn-candidate", candidate, "invalid support/headroom (uneven or obstructed)");
                        continue;
                    }
                    if (isTooCloseToExistingTable(candidate, existingTables)) {
                        logPlacementReject("spawn-candidate", candidate, "too close to an existing crafting table");
                        continue;
                    }
                    String exclusionReason = placementExclusionReason(world, candidate, candidate);
                    if (exclusionReason != null) {
                        logPlacementReject("spawn-candidate", candidate, exclusionReason);
                        continue;
                    }
                    candidates.add(candidate.toImmutable());
                }
            }
        }
        if (candidates.isEmpty()) return null;
        // Prefer positions at the same Y as the villager (most likely to be the floor they stand on).
        candidates.sort((a, b) -> Integer.compare(
                Math.abs(a.getY() - villagerPos.getY()), Math.abs(b.getY() - villagerPos.getY())));
        return candidates.get(0);
    }

    /**
     * Spawns a fresh unemployed villager at a safe surface position near the bell.
     */
    private static VillagerEntity spawnUnemployedVillagerNearBell(ServerWorld world, BlockPos bellPos) {
        BlockPos spawnPos = findSafeVillagerSpawnNearBell(world, bellPos);
        if (spawnPos == null) return null;

        VillagerEntity villager = EntityType.VILLAGER.create(world);
        if (villager == null) return null;
        villager.refreshPositionAndAngles(
                spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D,
                world.random.nextFloat() * 360.0F, 0.0F);
        villager.initialize((ServerWorldAccess) world, world.getLocalDifficulty(spawnPos), SpawnReason.MOB_SUMMONED, null);
        villager.getBrain().forget(MemoryModuleType.JOB_SITE);
        villager.getBrain().forget(MemoryModuleType.POTENTIAL_JOB_SITE);
        return world.spawnEntity(villager) ? villager : null;
    }

    private static BlockPos findSafeVillagerSpawnNearBell(ServerWorld world, BlockPos bellPos) {
        // Scan outward from bell in a ring, looking for safe ground (solid below, air at feet + head).
        for (int radius = 2; radius <= 12; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue; // perimeter only
                    int wx = bellPos.getX() + dx;
                    int wz = bellPos.getZ() + dz;
                    int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                    BlockPos candidate = new BlockPos(wx, surfaceY, wz);
                    if (isSafeVillagerSpawnPos(world, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Force-conversion (no reachability gate)
    // -------------------------------------------------------------------------

    private static ConversionResult forceConvert(ServerWorld world, VillagerEntity villager, BlockPos tablePos, BlockPos bellPos) {
        if (!LumberjackPopulationBalancingService.shouldAllowCreationAttempts(world, tablePos, "force-convert")) {
            LOGGER.debug("lumberjack-spawn population balancer blocked conversion at {}", tablePos.toShortString());
            return ConversionResult.failure("population-balancer-denied");
        }
        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, tablePos)) {
            LOGGER.debug("lumberjack-spawn table {} already reserved — skipping", tablePos.toShortString());
            return ConversionResult.failure("table-already-reserved");
        }
        String exclusionReason = placementExclusionReason(world, tablePos, tablePos);
        if (exclusionReason != null) {
            LOGGER.debug("lumberjack-spawn conversion rejected at {}: {}",
                    tablePos.toShortString(), exclusionReason);
            return ConversionResult.failure("placement-exclusion:" + exclusionReason);
        }
        if (!isStandablePlacement(world, tablePos, false)) {
            LOGGER.debug("lumberjack-spawn conversion rejected at {}: invalid support/headroom (uneven or obstructed)",
                    tablePos.toShortString());
            return ConversionResult.failure("invalid-standable-placement");
        }

        LumberjackGuardEntity guard = GuardVillagers.LUMBERJACK_GUARD_VILLAGER.create(world);
        if (guard == null) {
            LOGGER.warn("lumberjack-spawn could not create LumberjackGuardEntity at {}", tablePos.toShortString());
            return ConversionResult.failure("guard-entity-create-failed");
        }

        GuardConversionHelper.initializeConvertedGuard(world, villager, guard, tablePos);
        initializeSpawnedLumberjackState(world, guard, tablePos, "manual");

        ConvertedWorkerJobSiteReservationManager.reserve(world, tablePos, guard.getUuid(),
                VillagerProfession.NONE, "lumberjack-spawn-manager force-convert");

        world.spawnEntityAndPassengers(guard);
        LumberjackBootstrapLifecycleState.get(world.getServer())
                .markAutoLumberjackSpawnedEver(world, bellPos, guard.getUuid(), world.getTime());
        NEXT_RETRY_TICK_BY_BELL.remove(bellPos);
        LOGGER.info("lumberjack-spawn converted {} → lumberjack {} at table {}",
                villager.getUuidAsString(), guard.getUuidAsString(), tablePos.toShortString());

        JobBlockPairingHelper.playPairingAnimation(world, tablePos, villager, tablePos);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);
        GuardConversionHelper.cleanupVillagerAfterConversion(villager);
        return ConversionResult.success(guard, tablePos);
    }

    private static FallbackSpawnResult runDirectFallbackSpawn(ServerWorld world,
                                                              BlockPos bellPos,
                                                              String attemptType,
                                                              List<BlockPos> existingTables) {
        BlockPos safeSpawnPos = findSafeVillagerSpawnNearBell(world, bellPos);
        if (safeSpawnPos == null) {
            return FallbackSpawnResult.failure("no-safe-spawn-position");
        }

        BlockPos tablePos = findFreeSlotNearVillager(world, safeSpawnPos, existingTables);
        if (tablePos == null) {
            return FallbackSpawnResult.failure("no-table-placement-slot-near-fallback-spawn");
        }

        if (!LumberjackPopulationBalancingService.shouldAllowCreationAttempts(world, bellPos, attemptType + "_direct_fallback")) {
            return FallbackSpawnResult.failure("population-balancer-denied-direct-fallback");
        }

        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, tablePos)) {
            return FallbackSpawnResult.failure("table-already-reserved");
        }
        String exclusionReason = placementExclusionReason(world, tablePos, tablePos);
        if (exclusionReason != null) {
            return FallbackSpawnResult.failure("placement-exclusion:" + exclusionReason);
        }
        if (!isStandablePlacement(world, tablePos, false)) {
            return FallbackSpawnResult.failure("invalid-standable-placement");
        }

        world.setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
        LOGGER.info("lumberjack-spawn attempt={} bell={} — escalation placed crafting table at {} near fallback spawn {}",
                attemptType, bellPos.toShortString(), tablePos.toShortString(), safeSpawnPos.toShortString());

        LumberjackGuardEntity guard = GuardVillagers.LUMBERJACK_GUARD_VILLAGER.create(world);
        if (guard == null) {
            return FallbackSpawnResult.failure("guard-entity-create-failed");
        }

        guard.refreshPositionAndAngles(
                safeSpawnPos.getX() + 0.5D, safeSpawnPos.getY(), safeSpawnPos.getZ() + 0.5D,
                world.random.nextFloat() * 360.0F, 0.0F);
        guard.initialize((ServerWorldAccess) world, world.getLocalDifficulty(safeSpawnPos), SpawnReason.MOB_SUMMONED, null);
        initializeSpawnedLumberjackState(world, guard, tablePos, "manual-direct-fallback");

        ConvertedWorkerJobSiteReservationManager.reserve(world, tablePos, guard.getUuid(),
                VillagerProfession.NONE, "lumberjack-spawn-manager direct-fallback");
        world.spawnEntityAndPassengers(guard);
        LumberjackBootstrapLifecycleState lifecycleState = LumberjackBootstrapLifecycleState.get(world.getServer());
        lifecycleState.markAutoLumberjackSpawnedEver(world, bellPos, guard.getUuid(), world.getTime());
        lifecycleState.clearSpawnRetryCount(world, bellPos);
        NEXT_RETRY_TICK_BY_BELL.remove(bellPos);

        VillageGuardStandManager.handleGuardSpawn(world, guard, null);
        return FallbackSpawnResult.success(guard, tablePos);
    }

    private static void initializeSpawnedLumberjackState(ServerWorld world,
                                                         LumberjackGuardEntity guard,
                                                         BlockPos tablePos,
                                                         String conversionSource) {
        GuardConversionHelper.applyStandardEquipmentDropChances(guard);
        clearAllEquipment(guard);
        LumberjackConversionInitializer.initializePostConversion(world, guard, tablePos, conversionSource, null);
    }

    private static void clearAllEquipment(LumberjackGuardEntity guard) {
        guard.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    // -------------------------------------------------------------------------
    // Spatial helpers
    // -------------------------------------------------------------------------

    private static boolean isTooCloseToExistingTable(BlockPos candidate, List<BlockPos> existingTables) {
        for (BlockPos table : existingTables) {
            if (candidate.isWithinDistance(table, TABLE_MIN_SPACING)) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> findExistingTablesNear(ServerWorld world, BlockPos bellPos, int radius) {
        List<BlockPos> tables = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(
                bellPos.add(-radius, -8, -radius),
                bellPos.add(radius, 8, radius))) {
            if (world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
                tables.add(pos.toImmutable());
            }
        }
        return tables;
    }

    /**
     * Counts nearby crafting tables that are present but not already accounted for by active
     * lumberjacks. These represent pending supply that can convert unemployed villagers later.
     *
     * <p>This overload accepts a pre-computed table list to avoid a duplicate block scan when
     * the caller already has it (P3 fix).
     */
    private static int countPendingUnclaimedCraftingTablesNearBell(ServerWorld world, BlockPos bellPos,
                                                                    List<LumberjackGuardEntity> activeLumberjacks,
                                                                    List<BlockPos> nearbyTables) {
        if (nearbyTables.isEmpty()) {
            return 0;
        }

        Set<BlockPos> pairedToLivingLumberjacks = new HashSet<>();
        for (LumberjackGuardEntity lumberjack : activeLumberjacks) {
            BlockPos pairedPos = lumberjack.getPairedCraftingTablePos();
            if (pairedPos != null) {
                pairedToLivingLumberjacks.add(pairedPos.toImmutable());
            }
        }

        int pending = 0;
        for (BlockPos tablePos : nearbyTables) {
            if (!isPendingLumberjackConversionTable(world, tablePos, pairedToLivingLumberjacks)) {
                continue;
            }
            pending++;
        }

        LOGGER.debug("lumberjack-spawn bell={} nearbyTables={} pendingUnclaimed={}",
                bellPos.toShortString(), nearbyTables.size(), pending);
        return pending;
    }

    /**
     * Returns true when this table is eligible as pending supply:
     * block exists, not reserved for converted workers, not already paired to a living lumberjack.
     */
    private static boolean isPendingLumberjackConversionTable(ServerWorld world, BlockPos tablePos, Set<BlockPos> pairedToLivingLumberjacks) {
        if (!world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE)) {
            return false;
        }
        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, tablePos)) {
            return false;
        }
        return !pairedToLivingLumberjacks.contains(tablePos);
    }

    private static VillagerEntity findNearestUnemployed(ServerWorld world, BlockPos center) {
        Box scanBox = new Box(center).expand(UNEMPLOYED_SCAN_RANGE);
        List<VillagerEntity> candidates = world.getEntitiesByClass(
                VillagerEntity.class, scanBox, VillageLumberjackSpawnManager::isUnemployed);
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Double.compare(
                a.squaredDistanceTo(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5),
                b.squaredDistanceTo(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5)));
        return candidates.get(0);
    }

    private static boolean isSafeVillagerSpawnPos(ServerWorld world, BlockPos pos) {
        if (!isStandablePlacement(world, pos, true)) {
            logPlacementReject("villager-spawn", pos, "invalid support/headroom (uneven or obstructed)");
            return false;
        }
        String exclusionReason = placementExclusionReason(world, pos, null);
        if (exclusionReason != null) {
            logPlacementReject("villager-spawn", pos, exclusionReason);
            return false;
        }
        return true;
    }

    private static boolean isStandablePlacement(ServerWorld world, BlockPos pos, boolean requireReplaceableFeet) {
        if (requireReplaceableFeet && !world.getBlockState(pos).isReplaceable()) {
            return false;
        }
        if (!world.getBlockState(pos.up()).isReplaceable()) {
            return false;
        }
        BlockPos below = pos.down();
        if (!world.getBlockState(below).isSolidBlock(world, below)) {
            return false;
        }
        // Reject one-off/uneven terrain by requiring contiguous support around the floor block.
        for (BlockPos edgeSupport : new BlockPos[]{below.north(), below.south(), below.east(), below.west()}) {
            if (!world.getBlockState(edgeSupport).isSolidBlock(world, edgeSupport)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isVillagerJobSiteBlock(BlockState state) {
        return state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.SMOKER)
                || state.isOf(Blocks.CARTOGRAPHY_TABLE)
                || state.isOf(Blocks.BREWING_STAND)
                || state.isOf(Blocks.COMPOSTER)
                || state.isOf(Blocks.BARREL)
                || state.isOf(Blocks.FLETCHING_TABLE)
                || state.isOf(Blocks.LECTERN)
                || state.isOf(Blocks.CAULDRON)
                || state.isOf(Blocks.STONECUTTER)
                || state.isOf(Blocks.LOOM)
                || state.isOf(Blocks.SMITHING_TABLE)
                || state.isOf(Blocks.GRINDSTONE);
    }

    private static String placementExclusionReason(ServerWorld world, BlockPos candidate, BlockPos ignorePos) {
        int exclusion = (int) Math.ceil(PLACEMENT_EXCLUSION_RADIUS);
        for (BlockPos checkPos : BlockPos.iterate(
                candidate.add(-exclusion, -exclusion, -exclusion),
                candidate.add(exclusion, exclusion, exclusion))) {
            if (!candidate.isWithinDistance(checkPos, PLACEMENT_EXCLUSION_RADIUS)) {
                continue;
            }
            if (ignorePos != null && checkPos.equals(ignorePos)) {
                continue;
            }

            BlockState state = world.getBlockState(checkPos);
            if (isVillagerJobSiteBlock(state)) {
                return "within " + PLACEMENT_EXCLUSION_RADIUS + " blocks of job block at " + checkPos.toShortString();
            }
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                return "within " + PLACEMENT_EXCLUSION_RADIUS + " blocks of chest at " + checkPos.toShortString();
            }
            if (state.isOf(Blocks.CRAFTING_TABLE)) {
                return "within " + PLACEMENT_EXCLUSION_RADIUS + " blocks of crafting table at " + checkPos.toShortString();
            }
        }
        return null;
    }

    private static void logPlacementReject(String context, BlockPos pos, String reason) {
        LOGGER.debug("lumberjack-spawn {} rejected {}: {}", context, pos.toShortString(), reason);
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private record ConversionResult(boolean success, String reason, @Nullable LumberjackGuardEntity guard, @Nullable BlockPos tablePos) {
        static ConversionResult success(LumberjackGuardEntity guard, BlockPos tablePos) {
            return new ConversionResult(true, "success", guard, tablePos);
        }

        static ConversionResult failure(String reason) {
            return new ConversionResult(false, reason, null, null);
        }
    }

    private record FallbackSpawnResult(boolean success,
                                       String reason,
                                       @Nullable String guardUuid,
                                       @Nullable BlockPos tablePos) {
        static FallbackSpawnResult success(LumberjackGuardEntity guard, BlockPos tablePos) {
            return new FallbackSpawnResult(true, "success", guard.getUuidAsString(), tablePos);
        }

        static FallbackSpawnResult failure(String reason) {
            return new FallbackSpawnResult(false, reason, null, null);
        }
    }

    // -------------------------------------------------------------------------
    // Tree-supply gate (Fix 3)
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the local forest around {@code bellPos} has enough
     * harvestable log-roots and planted saplings to keep {@code desiredLumberjackCount}
     * lumberjacks meaningfully busy.
     *
     * <p>A "tree unit" is:
     * <ul>
     *   <li>1.0 for each distinct harvestable tree trunk — counted as the bottommost log block
     *       in a vertical log column (i.e. the block below is not also a log).  This avoids
     *       inflating the count for tall trees.</li>
     *   <li>0.5 for each planted sapling (half weight — it hasn't grown yet).</li>
     * </ul>
     *
     * <p>The scan radius matches {@code lumberjackBaseTreeSearchRadius} from config, which is
     * the same radius the lumberjack itself uses when looking for trees to chop.
     */
    private static boolean hasEnoughTreeSupplyForLumberjackCount(ServerWorld world, BlockPos bellPos, int desiredLumberjackCount) {
        if (desiredLumberjackCount <= 0) return true;

        // Return cached "sufficient" result without re-scanning.
        // We only cache true: an insufficient result can flip quickly after foresters plant.
        long now = world.getTime();
        Long cacheExpiry = TREE_SUPPLY_CACHE_EXPIRY.get(bellPos);
        if (cacheExpiry != null && now < cacheExpiry) {
            LOGGER.debug("lumberjack-spawn tree-supply bell={} cache-hit (expires in {}t)",
                    bellPos.toShortString(), cacheExpiry - now);
            return true;
        }

        int needed = desiredLumberjackCount * TREES_NEEDED_PER_LUMBERJACK;

        int scanRadius = Math.max(GuardVillagersConfig.MIN_LUMBERJACK_BASE_TREE_SEARCH_RADIUS,
                GuardVillagersConfig.lumberjackBaseTreeSearchRadius);
        // Scan a 2D horizontal footprint around the bell at all relevant Y levels.
        int yRange = 16;

        double treeUnits = 0.0;
        for (BlockPos pos : BlockPos.iterate(
                bellPos.add(-scanRadius, -yRange, -scanRadius),
                bellPos.add(scanRadius, yRange, scanRadius))) {
            if (!bellPos.isWithinDistance(pos, scanRadius)) continue;
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.LOGS)) {
                // Count only the bottom of each log column to avoid multi-counting tall trunks.
                BlockState below = world.getBlockState(pos.down());
                if (!below.isIn(BlockTags.LOGS)) {
                    treeUnits += 1.0;
                    if (treeUnits >= needed) {
                        // Cache the positive result so subsequent retries skip the scan.
                        TREE_SUPPLY_CACHE_EXPIRY.put(bellPos.toImmutable(), now + TREE_SUPPLY_CACHE_TTL_TICKS);
                        return true;
                    }
                }
            } else if (state.isIn(BlockTags.SAPLINGS)) {
                treeUnits += 0.5;
                if (treeUnits >= needed) {
                    TREE_SUPPLY_CACHE_EXPIRY.put(bellPos.toImmutable(), now + TREE_SUPPLY_CACHE_TTL_TICKS);
                    return true;
                }
            }
        }

        LOGGER.debug("lumberjack-spawn tree-supply bell={} scanRadius={} treeUnits={} needed={} (for {} lumberjack(s))",
                bellPos.toShortString(), scanRadius, treeUnits, needed, desiredLumberjackCount);
        // Insufficient — don't cache, let the next retry re-scan.
        TREE_SUPPLY_CACHE_EXPIRY.remove(bellPos);
        return false;
    }

    // -------------------------------------------------------------------------
    // Population helpers
    // -------------------------------------------------------------------------

    static int desiredLumberjackCount(int professionals, int totalVillagers, boolean hasEligibleVillageResident) {
        int fromProfessionals = professionals > 0 ? (professionals + RATIO_PROFESSIONALS - 1) / RATIO_PROFESSIONALS : 0;
        int fromTotal = totalVillagers > 0 ? (totalVillagers + RATIO_TOTAL - 1) / RATIO_TOTAL : 0;
        int ratioDesired = Math.max(fromProfessionals, fromTotal);

        int desired = !hasEligibleVillageResident
                ? ratioDesired
                : Math.max(ratioDesired, configuredVillageMinimum(totalVillagers));

        // Hard cap: never force-spawn more than MAX_FORCED_LUMBERJACKS per village bell.
        return Math.min(desired, MAX_FORCED_LUMBERJACKS);
    }

    private static int configuredVillageMinimum(int totalVillagers) {
        int floor = Math.max(0, GuardVillagersConfig.lumberjackVillageMin);
        if (totalVillagers >= GuardVillagersConfig.lumberjackVillageMinLargeVillagePopulation) {
            floor = Math.max(floor, GuardVillagersConfig.lumberjackVillageMinLargeVillage);
        }
        return floor;
    }

    private static boolean isEligibleVillager(VillagerEntity villager) {
        if (!villager.isAlive() || villager.isRemoved() || villager.isBaby()) return false;
        return villager.getVillagerData().getProfession() != VillagerProfession.NITWIT;
    }

    private static boolean isProfessional(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT;
    }

    private static boolean isUnemployed(VillagerEntity villager) {
        if (!villager.isAlive() || villager.isRemoved() || villager.isBaby()) return false;
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession != VillagerProfession.NONE) {
            return false;
        }
        if (villager.getWorld() instanceof ServerWorld serverWorld
                && LumberjackBootstrapCoordinator.isCandidateInActiveBootstrapLifecycle(serverWorld, villager)) {
            return false;
        }
        return true;
    }
}
