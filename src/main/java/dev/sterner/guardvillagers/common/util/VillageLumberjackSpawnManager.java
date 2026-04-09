package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.GuardConversionHelper;
import dev.sterner.guardvillagers.common.villager.LumberjackPopulationBalancingService;
import dev.sterner.guardvillagers.common.villager.UnemployedLumberjackConversionHook;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Periodically ensures enough lumberjacks exist relative to village population.
 *
 * <p><b>Strategy (v2 — reliable village-interior placement):</b>
 * <ol>
 *   <li>Count existing lumberjacks near the bell; if enough, skip.</li>
 *   <li>Find every chest within {@link #CHEST_SCAN_RANGE} of the bell.</li>
 *   <li>For each chest, try adjacent floor positions (within 3 blocks) for a
 *       crafting table: space is air, floor below is solid, no existing table nearby.</li>
 *   <li>Place the table immediately.</li>
 *   <li>Force-convert the closest unemployed villager within {@link #UNEMPLOYED_SCAN_RANGE}
 *       of the table — no reachability check, no hook delay.</li>
 * </ol>
 *
 * <p>Placing next to a chest is reliable because:
 * <ul>
 *   <li>Chests are almost always indoors (or adjacent to a work site).</li>
 *   <li>The table lands within {@link JobBlockPairingHelper#JOB_BLOCK_PAIRING_RANGE} of the
 *       chest, so {@code findNearbyChest()} in the conversion immediately succeeds.</li>
 * </ul>
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

    /** Ratio: one lumberjack per N professionals. */
    private static final int RATIO_PROFESSIONALS = 3;

    /** Ratio: one lumberjack per N total villagers (lower bound). */
    private static final int RATIO_TOTAL = 6;

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
            } else {
                NEXT_RETRY_TICK_BY_BELL.clear();
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
    // Per-bell logic
    // -------------------------------------------------------------------------

    private static void processBell(ServerWorld world, BlockPos bellPos, String attemptType, long now) {
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

        int pendingUnclaimedTables = countPendingUnclaimedCraftingTablesNearBell(world, bellPos, activeLumberjacks);
        int effectiveExisting = existing + pendingUnclaimedTables;

        if (effectiveExisting >= desired) {
            NEXT_RETRY_TICK_BY_BELL.remove(bellPos);
            LOGGER.info("lumberjack-spawn attempt={} bell={} professionals={} total={} desired={} active={} pending={} effective={} — skip placement",
                    attemptType, bellPos.toShortString(), professionals, totalVillagers, desired, existing, pendingUnclaimedTables, effectiveExisting);
            return;
        }

        LOGGER.info("lumberjack-spawn attempt={} bell={} professionals={} total={} desired={} active={} pending={} effective={} — placing table",
                attemptType, bellPos.toShortString(), professionals, totalVillagers, desired, existing, pendingUnclaimedTables, effectiveExisting);

        // Attempt one table + conversion per scan cycle (pace ourselves).
        tryPlaceTableAndConvert(world, bellPos, attemptType);
        long retryIntervalTicks = Math.max(20L, GuardVillagersConfig.lumberjackSpawnRetryIntervalTicks);
        NEXT_RETRY_TICK_BY_BELL.put(bellPos, now + retryIntervalTicks);
    }

    // -------------------------------------------------------------------------
    // Table placement + immediate conversion
    // -------------------------------------------------------------------------

    private static void tryPlaceTableAndConvert(ServerWorld world, BlockPos bellPos, String attemptType) {
        // Gather existing tables so we can enforce spacing.
        List<BlockPos> existingTables = findExistingTablesNear(world, bellPos, CHEST_SCAN_RANGE + TABLE_ADJACENT_RANGE + 2);

        // Find all chests near the bell and shuffle them for variety.
        List<BlockPos> chests = findChestsNearBell(world, bellPos);
        if (chests.isEmpty()) {
            LOGGER.info("lumberjack-spawn attempt={} bell={} — no chests found within {} blocks; cannot place table",
                    attemptType, bellPos.toShortString(), CHEST_SCAN_RANGE);
            // Fallback: try a free surface spot near the bell itself
            tryPlaceTableNearBellFallback(world, bellPos, existingTables, attemptType);
            return;
        }

        Collections.shuffle(chests, new Random(world.getTime() ^ bellPos.asLong()));

        for (BlockPos chestPos : chests) {
            BlockPos tablePos = findFreeSlotNearChest(world, chestPos, existingTables);
            if (tablePos == null) {
                continue;
            }

            placeTableAndConvert(world, bellPos, tablePos, attemptType);
            return;
        }

        LOGGER.info("lumberjack-spawn attempt={} bell={} — {} chest(s) found but none had a free adjacent floor slot",
                attemptType, bellPos.toShortString(), chests.size());

        // Fallback: open surface near bell
        tryPlaceTableNearBellFallback(world, bellPos, existingTables, attemptType);
    }

    /**
     * Place table at {@code tablePos} and immediately force-convert the nearest
     * unemployed villager. If none is found the table still stays — the conversion
     * hook will pick it up later.
     */
    private static void placeTableAndConvert(ServerWorld world, BlockPos bellPos, BlockPos tablePos, String attemptType) {
        world.setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
        LOGGER.info("lumberjack-spawn attempt={} placed crafting table at {} near bell {}",
                attemptType, tablePos.toShortString(), bellPos.toShortString());

        // Immediately find and convert the closest unemployed villager.
        VillagerEntity candidate = findNearestUnemployed(world, tablePos);
        if (candidate != null) {
            forceConvert(world, candidate, tablePos);
        } else {
            LOGGER.info("lumberjack-spawn attempt={} no unemployed villager within {} blocks of {} — table placed, conversion hook will fire later",
                    attemptType, UNEMPLOYED_SCAN_RANGE, tablePos.toShortString());
            // Nudge the hook for the next tick just in case.
            if (!world.getPlayers().isEmpty()) {
                UnemployedLumberjackConversionHook.tryConvertUnemployedVillagersNearCraftingTables(world);
            }
        }
    }

    /**
     * Fallback: place table on open surface within a small ring around the bell.
     * Less ideal but better than nothing.
     */
    private static void tryPlaceTableNearBellFallback(ServerWorld world, BlockPos bellPos, List<BlockPos> existingTables, String attemptType) {
        for (int radius = 3; radius <= 12; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int dist = Math.abs(dx) + Math.abs(dz);
                    if (dist != radius) continue; // walk the perimeter only at each radius
                    int wx = bellPos.getX() + dx;
                    int wz = bellPos.getZ() + dz;
                    int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                    BlockPos candidate = new BlockPos(wx, surfaceY, wz);
                    BlockPos below = candidate.down();
                    if (!world.getBlockState(below).isSolidBlock(world, below)) continue;
                    if (!world.getBlockState(candidate).isReplaceable()) continue;
                    if (isTooCloseToExistingTable(candidate, existingTables)) continue;

                    placeTableAndConvert(world, bellPos, candidate, attemptType);
                    return;
                }
            }
        }
        LOGGER.info("lumberjack-spawn attempt={} bell={} — fallback surface scan also found no placement slot", attemptType, bellPos.toShortString());
    }

    // -------------------------------------------------------------------------
    // Force-conversion (no reachability gate)
    // -------------------------------------------------------------------------

    private static void forceConvert(ServerWorld world, VillagerEntity villager, BlockPos tablePos) {
        if (!LumberjackPopulationBalancingService.shouldAllowCreationAttempts(world, tablePos, "force-convert")) {
            LOGGER.info("lumberjack-spawn population balancer blocked conversion at {}", tablePos.toShortString());
            return;
        }
        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, tablePos)) {
            LOGGER.info("lumberjack-spawn table {} already reserved — skipping", tablePos.toShortString());
            return;
        }

        LumberjackGuardEntity guard = GuardVillagers.LUMBERJACK_GUARD_VILLAGER.create(world);
        if (guard == null) {
            LOGGER.warn("lumberjack-spawn could not create LumberjackGuardEntity at {}", tablePos.toShortString());
            return;
        }

        GuardConversionHelper.initializeConvertedGuard(world, villager, guard, tablePos);
        GuardConversionHelper.applyStandardEquipmentDropChances(guard);
        clearAllEquipment(guard);
        guard.setPairedCraftingTablePos(tablePos);
        JobBlockPairingHelper.findNearbyChest(world, tablePos).ifPresent(guard::setPairedChestPos);
        guard.startChopCountdown(world.getTime(), 0L);

        ConvertedWorkerJobSiteReservationManager.reserve(world, tablePos, guard.getUuid(),
                VillagerProfession.NONE, "lumberjack-spawn-manager force-convert");

        world.spawnEntityAndPassengers(guard);
        LOGGER.info("lumberjack-spawn converted {} → lumberjack {} at table {}",
                villager.getUuidAsString(), guard.getUuidAsString(), tablePos.toShortString());

        JobBlockPairingHelper.playPairingAnimation(world, tablePos, villager, tablePos);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);
        GuardConversionHelper.cleanupVillagerAfterConversion(villager);
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

    /**
     * Finds all chest blocks within {@link #CHEST_SCAN_RANGE} of the bell,
     * including interior ones (full 3-D scan, not just surface).
     */
    private static List<BlockPos> findChestsNearBell(ServerWorld world, BlockPos bellPos) {
        List<BlockPos> chests = new ArrayList<>();
        int r = CHEST_SCAN_RANGE;
        // Scan a generous Y range to catch chests at any floor level.
        for (BlockPos pos : BlockPos.iterate(
                bellPos.add(-r, -8, -r),
                bellPos.add(r, 8, r))) {
            if (!bellPos.isWithinDistance(pos, r)) continue;
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock) {
                chests.add(pos.toImmutable());
            }
        }
        return chests;
    }

    /**
     * Finds a free floor slot within {@link #TABLE_ADJACENT_RANGE} of {@code chestPos}.
     * Requirements: air at the slot, solid block below, not too close to an existing table.
     * Returns the first valid position found, or null.
     */
    private static BlockPos findFreeSlotNearChest(ServerWorld world, BlockPos chestPos, List<BlockPos> existingTables) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -TABLE_ADJACENT_RANGE; dx <= TABLE_ADJACENT_RANGE; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -TABLE_ADJACENT_RANGE; dz <= TABLE_ADJACENT_RANGE; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue; // skip chest itself
                    BlockPos candidate = chestPos.add(dx, dy, dz);
                    if (!chestPos.isWithinDistance(candidate, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) continue;

                    if (!world.getBlockState(candidate).isReplaceable()) continue;
                    BlockPos below = candidate.down();
                    if (!world.getBlockState(below).isSolidBlock(world, below)) continue;
                    if (isTooCloseToExistingTable(candidate, existingTables)) continue;

                    candidates.add(candidate.toImmutable());
                }
            }
        }
        if (candidates.isEmpty()) return null;
        // Prefer candidates at the same Y level as the chest (most likely correct floor).
        candidates.sort((a, b) -> Integer.compare(
                Math.abs(a.getY() - chestPos.getY()), Math.abs(b.getY() - chestPos.getY())));
        return candidates.get(0);
    }

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
     */
    private static int countPendingUnclaimedCraftingTablesNearBell(ServerWorld world, BlockPos bellPos, List<LumberjackGuardEntity> activeLumberjacks) {
        int tableSearchRadius = CHEST_SCAN_RANGE + TABLE_ADJACENT_RANGE + 2;
        List<BlockPos> nearbyTables = findExistingTablesNear(world, bellPos, tableSearchRadius);
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

        LOGGER.info("lumberjack-spawn bell={} nearbyTables={} pendingUnclaimed={}",
                bellPos.toShortString(), nearbyTables.size(), pending);
        return pending;
    }

    /**
     * Returns true when this table is eligible as pending supply:
     * block exists, not reserved for converted workers, and not already paired to a living lumberjack.
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

    private static VillagerEntity findNearestUnemployed(ServerWorld world, BlockPos tablePos) {
        Box scanBox = new Box(tablePos).expand(UNEMPLOYED_SCAN_RANGE);
        List<VillagerEntity> candidates = world.getEntitiesByClass(
                VillagerEntity.class, scanBox, VillageLumberjackSpawnManager::isUnemployed);
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Double.compare(
                a.squaredDistanceTo(tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5),
                b.squaredDistanceTo(tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5)));
        return candidates.get(0);
    }

    // -------------------------------------------------------------------------
    // Population helpers
    // -------------------------------------------------------------------------

    static int desiredLumberjackCount(int professionals, int totalVillagers, boolean hasEligibleVillageResident) {
        int fromProfessionals = professionals > 0 ? (professionals + RATIO_PROFESSIONALS - 1) / RATIO_PROFESSIONALS : 0;
        int fromTotal = totalVillagers > 0 ? (totalVillagers + RATIO_TOTAL - 1) / RATIO_TOTAL : 0;
        int ratioDesired = Math.max(fromProfessionals, fromTotal);

        if (!hasEligibleVillageResident) {
            return ratioDesired;
        }

        return Math.max(ratioDesired, configuredVillageMinimum(totalVillagers));
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
        return profession == VillagerProfession.NONE;
    }
}
