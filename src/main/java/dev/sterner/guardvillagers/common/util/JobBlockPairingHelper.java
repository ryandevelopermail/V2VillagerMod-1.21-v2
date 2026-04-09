package dev.sterner.guardvillagers.common.util;

import com.google.common.collect.Sets;
import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import dev.sterner.guardvillagers.common.villager.ButcherBannerTracker;
import dev.sterner.guardvillagers.common.villager.SpecialModifier;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehaviorRegistry;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
import dev.sterner.guardvillagers.common.villager.ShepherdBannerTracker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.WallBannerBlock;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.VillagerProfession;
import dev.sterner.guardvillagers.common.villager.FarmerBannerTracker;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public final class JobBlockPairingHelper {
    public static final double JOB_BLOCK_PAIRING_RANGE = 3.0D;
    private static final double NEARBY_VILLAGER_SCAN_RANGE = 8.0D;
    private static final double FARMER_BANNER_PAIR_RANGE = 500.0D;
    private static final double SHEPHERD_BANNER_PAIR_RANGE = 500.0D;
    private static final double BUTCHER_BANNER_PAIR_RANGE = 64.0D;
    private static final Set<Block> PAIRING_BLOCKS = Sets.newIdentityHashSet();
    private static final Map<WorldKey, Map<UUID, CachedVillagerChestPairing>> CACHED_VILLAGER_CHESTS = new HashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(JobBlockPairingHelper.class);

    /**
     * Per-world banner position cache. Key = WorldKey; value = all known banner BlockPos within
     * the world (populated lazily on first banner scan, invalidated on banner placement/removal).
     *
     * <p>Without this cache, {@code refreshVillagerPairings} calls
     * {@code findBannersWithinRange(world, jobPos, 300)} for every Farmer and Shepherd on world
     * load, each time iterating a 37×37 chunk grid (13 k+ chunk lookups per villager).
     * The cached collection is safe to reuse across multiple villagers on the same tick because
     * banner positions change only when players place or break banners, which is handled by
     * {@link #handleBannerPlacement} / {@link #invalidateBannerCache}.
     */
    private static final Map<WorldKey, List<BlockPos>> CACHED_BANNER_POSITIONS = new HashMap<>();

    static {
        registerPairingBlock(Blocks.CHEST);
        registerPairingBlock(Blocks.TRAPPED_CHEST);
        // Barrels are used as paired storage by the fisherman (job block = BARREL).
        // Without this, findNearbyChest() returns empty for barrel-paired workers,
        // breaking toolsmith demand detection and rod distribution entirely.
        registerPairingBlock(Blocks.BARREL);
    }

    private JobBlockPairingHelper() {
    }

    public static void registerPairingBlock(Block block) {
        PAIRING_BLOCKS.add(block);
    }

    public static boolean isPairingBlock(BlockState state) {
        return isPairingBlock(state.getBlock());
    }

    public static boolean isPairingBlock(Block block) {
        return PAIRING_BLOCKS.contains(block);
    }

    public static boolean isSpecialModifierBlock(Block block) {
        return VillagerProfessionBehaviorRegistry.isSpecialModifierBlock(block);
    }

    public static void handlePairingBlockPlacement(ServerWorld world, BlockPos placedPos, BlockState placedState) {
        if (!isPairingBlock(placedState)) {
            return;
        }

        findEmployedVillagersWithJobSiteNear(world, placedPos, JOB_BLOCK_PAIRING_RANGE)
                .forEach(villager -> tryPlayPairingAnimation(world, villager, placedPos));
        VillagerConversionCandidateIndex.markCandidatesNear(world, placedPos, NEARBY_VILLAGER_SCAN_RANGE);
        ProfessionDefinitions.runConversionHooks(world);
    }

    public static void handleCraftingTablePlacement(ServerWorld world, BlockPos placedPos) {
        findEmployedVillagersWithJobSiteNear(world, placedPos, JOB_BLOCK_PAIRING_RANGE)
                .forEach(villager -> tryPlayPairingAnimationWithCrafting(world, villager, placedPos));

        ProfessionDefinitions.runUnemployedConversionHooks(world);
    }

    public static void handleSpecialModifierPlacement(ServerWorld world, BlockPos placedPos, BlockState placedState) {
        Optional<SpecialModifier> modifier = VillagerProfessionBehaviorRegistry.getSpecialModifier(placedState.getBlock());
        if (modifier.isEmpty()) {
            return;
        }

        SpecialModifier resolvedModifier = modifier.get();

        findEmployedVillagersWithJobSiteNear(world, placedPos, resolvedModifier.range())
                .forEach(villager -> tryPlayPairingAnimationWithSpecialModifier(world, villager, placedPos, resolvedModifier));
    }

    private static Collection<VillagerEntity> findEmployedVillagersWithJobSiteNear(ServerWorld world, BlockPos placedPos, double range) {
        // Use a tight search box around the placed block instead of getWorldBounds().
        // getWorldBounds() = entire world border (~60k×60k) → O(all entities) per chest placement.
        // Any villager whose JOB_SITE is within `range` of placedPos must themselves be within
        // JOB_BLOCK_PAIRING_RANGE (3.0) of that job site, so they are at most range + 3 blocks
        // from placedPos. Use a generous 64-block box to safely cover all realistic cases.
        double scanRadius = range + 64.0D;
        Box searchBox = new Box(placedPos).expand(scanRadius);
        return world.getEntitiesByClass(VillagerEntity.class, searchBox, villager -> {
            if (!isEmployedVillager(villager)) {
                return false;
            }

            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isEmpty()) {
                return false;
            }

            GlobalPos globalPos = jobSite.get();
            if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
                return false;
            }

            return globalPos.pos().isWithinDistance(placedPos, range);
        });
    }

    private static void tryPlayPairingAnimation(ServerWorld world, VillagerEntity villager, BlockPos placedPos) {
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return;
        }

        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, globalPos.pos())) {
            LOGGER.debug("pairing animation suppressed (reserved): villager={} jobSite={} trigger={}",
                    villager.getUuidAsString(),
                    globalPos.pos().toShortString(),
                    placedPos.toShortString());
            return;
        }

        if (globalPos.pos().isWithinDistance(placedPos, JOB_BLOCK_PAIRING_RANGE)) {
            playPairingAnimation(world, placedPos, villager, globalPos.pos());
            cacheVillagerChestPairing(world, villager, globalPos.pos(), placedPos);
            VillagerProfessionBehaviorRegistry.notifyChestPaired(world, villager, globalPos.pos(), placedPos);
        }
    }

    private static void tryPlayPairingAnimationWithCrafting(ServerWorld world, VillagerEntity villager, BlockPos placedPos) {
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return;
        }

        BlockPos jobPos = globalPos.pos();
        if (!jobPos.isWithinDistance(placedPos, JOB_BLOCK_PAIRING_RANGE)) {
            return;
        }

        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, jobPos)) {
            LOGGER.debug("pairing animation suppressed (reserved): villager={} jobSite={} trigger={}",
                    villager.getUuidAsString(),
                    jobPos.toShortString(),
                    placedPos.toShortString());
            return;
        }

        // Exclude jobPos so that a fisherman's barrel job block doesn't self-match as its own chest.
        Optional<BlockPos> nearbyChest = findNearbyChestWithinRangeOfBoth(world, jobPos, placedPos, JOB_BLOCK_PAIRING_RANGE, jobPos);
        if (nearbyChest.isEmpty()) {
            return;
        }

        playPairingAnimation(world, placedPos, villager, jobPos);
        VillagerProfessionBehaviorRegistry.notifyCraftingTablePaired(world, villager, jobPos, nearbyChest.get(), placedPos);
    }

    private static void tryPlayPairingAnimationWithSpecialModifier(ServerWorld world, VillagerEntity villager, BlockPos placedPos, SpecialModifier modifier) {
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return;
        }

        BlockPos jobPos = globalPos.pos();
        if (!jobPos.isWithinDistance(placedPos, modifier.range())) {
            return;
        }

        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, jobPos)) {
            LOGGER.debug("pairing animation suppressed (reserved): villager={} jobSite={} trigger={}",
                    villager.getUuidAsString(),
                    jobPos.toShortString(),
                    placedPos.toShortString());
            return;
        }

        Optional<BlockPos> nearbyChest = findNearbyChest(world, jobPos, jobPos);
        if (nearbyChest.isEmpty()) {
            return;
        }

        if (!nearbyChest.get().isWithinDistance(placedPos, modifier.range())) {
            return;
        }

        playPairingAnimation(world, placedPos, villager, jobPos);
        VillagerProfessionBehaviorRegistry.notifySpecialModifierPaired(world, villager, jobPos, nearbyChest.get(), modifier, placedPos);
    }

    public static void handleBannerPlacement(ServerWorld world, BlockPos bannerPos, BlockState bannerState) {
        if (!isBannerOnFence(world, bannerPos, bannerState)) {
            return;
        }
        // Invalidate so the next refreshVillagerPairings picks up the new banner.
        invalidateBannerCache(world);

        double range = FARMER_BANNER_PAIR_RANGE;
        int pairedCount = 0;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, new Box(bannerPos).expand(range), villager -> villager.isAlive() && villager.getVillagerData().getProfession() == VillagerProfession.FARMER)) {
            if (pairFarmerWithBanner(world, villager, bannerPos)) {
                pairedCount++;
            }
        }

        int shepherdPairedCount = 0;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, new Box(bannerPos).expand(SHEPHERD_BANNER_PAIR_RANGE), villager -> villager.isAlive() && villager.getVillagerData().getProfession() == VillagerProfession.SHEPHERD)) {
            if (pairShepherdWithBanner(world, villager, bannerPos)) {
                shepherdPairedCount++;
            }
        }

        int guardPairedCount = 0;
        for (ButcherGuardEntity guard : world.getEntitiesByClass(ButcherGuardEntity.class, new Box(bannerPos).expand(BUTCHER_BANNER_PAIR_RANGE), Entity::isAlive)) {
            if (pairButcherGuardWithBanner(world, guard, bannerPos)) {
                guardPairedCount++;
            }
        }

        LOGGER.info("Banner {} paired with {} Farmer(s), {} Shepherd(s), and {} Butcher Guard(s)", bannerPos.toShortString(), pairedCount, shepherdPairedCount, guardPairedCount);
    }

    public static void refreshVillagerPairings(ServerWorld world, VillagerEntity villager) {
        if (!isEmployedVillager(villager)) {
            invalidateVillagerChestPairing(world, villager.getUuid());
            return;
        }

        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            invalidateVillagerChestPairing(world, villager.getUuid());
            return;
        }

        BlockPos jobPos = globalPos.pos();
        VillagerProfessionBehaviorRegistry.ensureUniversalJobBlockGoal(villager, jobPos);
        // Exclude jobPos itself so that a fisherman's barrel job block doesn't self-match as its chest.
        Optional<BlockPos> nearbyChest = findNearbyChest(world, jobPos, jobPos);
        nearbyChest.ifPresentOrElse(chestPos -> {
                    cacheVillagerChestPairing(world, villager, jobPos, chestPos);
                    VillagerProfessionBehaviorRegistry.notifyChestPaired(world, villager, jobPos, chestPos);
                },
                () -> {
                    invalidateVillagerChestPairing(world, villager.getUuid());
                    // No chest present — give behaviors a chance to run in chestless (v1) mode
                    VillagerProfessionBehaviorRegistry.notifyJobSiteReady(world, villager, jobPos);
                });

        if (nearbyChest.isPresent()) {
            Optional<BlockPos> craftingTablePos = findNearbyCraftingTable(world, jobPos);
            craftingTablePos.ifPresent(pos -> VillagerProfessionBehaviorRegistry.notifyCraftingTablePaired(world, villager, jobPos, nearbyChest.get(), pos));
        }

        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == VillagerProfession.FARMER || profession == VillagerProfession.SHEPHERD) {
            // Use the cached banner list instead of the raw 37×37-chunk scan to avoid
            // thousands of chunk lookups per villager during world load.
            Collection<BlockPos> bannerPositions = findBannersWithinRangeCached(world, jobPos, 300);
            for (BlockPos bannerPos : bannerPositions) {
                BlockState bannerState = world.getBlockState(bannerPos);
                if (isBannerOnFence(world, bannerPos, bannerState)) {
                    handleBannerPlacement(world, bannerPos, bannerState);
                }
            }
        }
    }

    public static void refreshWorldPairings(ServerWorld world) {
        VillageAnchorState.get(world.getServer()).pruneInvalidAnchors(world);
        Box worldBounds = getWorldBounds(world);
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, worldBounds, Entity::isAlive)) {
            refreshVillagerPairings(world, villager);
        }
        for (ButcherGuardEntity guard : world.getEntitiesByClass(ButcherGuardEntity.class, worldBounds, Entity::isAlive)) {
            refreshButcherGuardPairings(world, guard);
        }
    }

    public static Box getWorldBounds(ServerWorld world) {
        WorldBorder border = world.getWorldBorder();
        double halfSize = border.getSize() / 2.0D;
        double minX = border.getCenterX() - halfSize;
        double maxX = border.getCenterX() + halfSize;
        double minZ = border.getCenterZ() - halfSize;
        double maxZ = border.getCenterZ() + halfSize;
        int minY = world.getBottomY();
        int maxY = world.getTopY();
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean pairFarmerWithBanner(ServerWorld world, VillagerEntity villager, BlockPos bannerPos) {
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return false;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return false;
        }

        BlockPos jobPos = globalPos.pos();
        if (villager.squaredDistanceTo(bannerPos.getX() + 0.5D, bannerPos.getY() + 0.5D, bannerPos.getZ() + 0.5D) > FARMER_BANNER_PAIR_RANGE * FARMER_BANNER_PAIR_RANGE) {
            return false;
        }

        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, jobPos)) {
            LOGGER.debug("pairing animation suppressed (reserved): villager={} jobSite={} trigger={}",
                    villager.getUuidAsString(),
                    jobPos.toShortString(),
                    bannerPos.toShortString());
            return false;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.FARMER, world.getBlockState(jobPos))) {
            return false;
        }

        if (findNearbyChest(world, jobPos, jobPos).isEmpty()) {
            return false;
        }

        playPairingAnimation(world, bannerPos, villager, jobPos);
        FarmerBannerTracker.setBanner(villager, bannerPos);
        return true;
    }

    private static boolean pairShepherdWithBanner(ServerWorld world, VillagerEntity villager, BlockPos bannerPos) {
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return false;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return false;
        }

        BlockPos jobPos = globalPos.pos();
        if (villager.squaredDistanceTo(bannerPos.getX() + 0.5D, bannerPos.getY() + 0.5D, bannerPos.getZ() + 0.5D) > SHEPHERD_BANNER_PAIR_RANGE * SHEPHERD_BANNER_PAIR_RANGE) {
            return false;
        }

        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, jobPos)) {
            LOGGER.debug("pairing animation suppressed (reserved): villager={} jobSite={} trigger={}",
                    villager.getUuidAsString(),
                    jobPos.toShortString(),
                    bannerPos.toShortString());
            return false;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.SHEPHERD, world.getBlockState(jobPos))) {
            return false;
        }

        if (findNearbyChest(world, jobPos, jobPos).isEmpty()) {
            return false;
        }

        playPairingAnimation(world, bannerPos, villager, jobPos);
        ShepherdBannerTracker.setBanner(villager, bannerPos);
        return true;
    }

    private static boolean pairButcherGuardWithBanner(ServerWorld world, ButcherGuardEntity guard, BlockPos bannerPos) {
        if (guard.squaredDistanceTo(bannerPos.getX() + 0.5D, bannerPos.getY() + 0.5D, bannerPos.getZ() + 0.5D) > BUTCHER_BANNER_PAIR_RANGE * BUTCHER_BANNER_PAIR_RANGE) {
            return false;
        }

        playPairingAnimation(world, bannerPos, guard, guard.getBlockPos());
        ButcherBannerTracker.setBanner(guard, bannerPos);
        guard.setPairedBannerPos(bannerPos);
        return true;
    }

    public static void refreshButcherGuardPairings(ServerWorld world, ButcherGuardEntity guard) {
        if (!guard.isAlive()) {
            return;
        }

        BlockPos pairedBannerPos = guard.getPairedBannerPos();
        if (pairedBannerPos != null) {
            BlockState bannerState = world.getBlockState(pairedBannerPos);
            if (isBannerOnFence(world, pairedBannerPos, bannerState)) {
                ButcherBannerTracker.setBanner(guard, pairedBannerPos);
                return;
            }
        }

        Collection<BlockPos> bannerPositions = findBannersWithinRange(world, guard.getBlockPos(), (int) BUTCHER_BANNER_PAIR_RANGE);
        for (BlockPos bannerPos : bannerPositions) {
            BlockState bannerState = world.getBlockState(bannerPos);
            if (isBannerOnFence(world, bannerPos, bannerState)) {
                pairButcherGuardWithBanner(world, guard, bannerPos);
                break;
            }
        }
    }

    private static boolean isBannerOnFence(ServerWorld world, BlockPos bannerPos, BlockState bannerState) {
        if (getBannerFenceBase(world, bannerPos, bannerState) != null) {
            return true;
        }
        return isInsideFencePen(world, bannerPos);
    }

    private static BlockPos getBannerFenceBase(ServerWorld world, BlockPos bannerPos, BlockState bannerState) {
        if (bannerState.getBlock() instanceof WallBannerBlock && bannerState.contains(WallBannerBlock.FACING)) {
            Direction facing = bannerState.get(WallBannerBlock.FACING);
            BlockPos attachedPos = bannerPos.offset(facing.getOpposite());
            BlockState attachedState = world.getBlockState(attachedPos);
            if (attachedState.getBlock() instanceof FenceBlock || attachedState.getBlock() instanceof FenceGateBlock) {
                return attachedPos;
            }
        }

        BlockState below = world.getBlockState(bannerPos.down());
        if (below.getBlock() instanceof FenceBlock || below.getBlock() instanceof FenceGateBlock) {
            return bannerPos.down();
        }

        return null;
    }

    private static boolean isInsideFencePen(ServerWorld world, BlockPos bannerPos) {
        int maxDistance = 16;
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (!hasFenceInDirection(world, bannerPos, direction, maxDistance)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasFenceInDirection(ServerWorld world, BlockPos start, Direction direction, int maxDistance) {
        for (int i = 1; i <= maxDistance; i++) {
            BlockPos pos = start.offset(direction, i);
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
                return true;
            }
        }
        return false;
    }

    public static Optional<BlockPos> findNearbyCraftingTable(ServerWorld world, BlockPos center) {
        int range = (int) Math.ceil(JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (center.isWithinDistance(checkPos, JOB_BLOCK_PAIRING_RANGE) && isCraftingTable(world.getBlockState(checkPos))) {
                return Optional.of(checkPos.toImmutable());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all banners within {@code range} of {@code center}, using a world-level cache
     * to avoid the 37×37 chunk grid scan on every villager load.
     *
     * <p>The first call per world populates the cache with ALL banners in a large radius
     * (the maximum expected village spread). Subsequent calls filter the cached list to the
     * requested range in O(n) instead of O(chunks). The cache is invalidated by
     * {@link #invalidateBannerCache} whenever a banner is placed or removed.
     */
    private static Collection<BlockPos> findBannersWithinRangeCached(ServerWorld world, BlockPos center, int range) {
        WorldKey worldKey = WorldKey.of(world);
        List<BlockPos> allBanners = CACHED_BANNER_POSITIONS.get(worldKey);
        if (allBanners == null) {
            // Populate cache: scan the maximum banner range we ever query (500 blocks = farmer/shepherd).
            // We always scan a fixed large radius from the world origin so subsequent calls from
            // different village positions all share the same cache entry.
            // For worlds with multiple widely-separated villages this may miss far-outlier banners;
            // the full per-position scan in handleBannerPlacement handles new banners correctly,
            // and invalidateBannerCache() ensures the next world-load refresh sees everything.
            int cacheRadius = 600; // covers FARMER_BANNER_PAIR_RANGE (500) + village spread margin
            int chunkRadius = MathHelper.ceil(cacheRadius / 16.0D);
            int centerChunkX = center.getX() >> 4;
            int centerChunkZ = center.getZ() >> 4;
            allBanners = new ArrayList<>();
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    Chunk chunk = world.getChunkManager().getChunk(centerChunkX + dx, centerChunkZ + dz, ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    for (BlockPos pos : chunk.getBlockEntityPositions()) {
                        BlockEntity blockEntity = chunk.getBlockEntity(pos);
                        if (blockEntity instanceof BannerBlockEntity) {
                            allBanners.add(pos.toImmutable());
                        }
                    }
                }
            }
            CACHED_BANNER_POSITIONS.put(worldKey, allBanners);
            LOGGER.debug("banner-cache: populated {} banner(s) for world {}", allBanners.size(), world.getRegistryKey().getValue());
        }

        if (allBanners.isEmpty()) {
            return List.of();
        }

        double rangeSq = (double) range * range;
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : allBanners) {
            if (center.getSquaredDistance(pos) <= rangeSq) {
                result.add(pos);
            }
        }
        return result;
    }

    private static Collection<BlockPos> findBannersWithinRange(ServerWorld world, BlockPos center, int range) {
        int chunkRadius = MathHelper.ceil(range / 16.0D);
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        Collection<BlockPos> banners = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = world.getChunkManager().getChunk(centerChunkX + dx, centerChunkZ + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos pos : chunk.getBlockEntityPositions()) {
                    if (!center.isWithinDistance(pos, range)) {
                        continue;
                    }
                    BlockEntity blockEntity = chunk.getBlockEntity(pos);
                    if (blockEntity instanceof BannerBlockEntity) {
                        banners.add(pos.toImmutable());
                    }
                }
            }
        }
        return banners;
    }

    public static Optional<BlockPos> findNearestPenBanner(ServerWorld world, BlockPos center, int range) {
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (BlockPos bannerPos : findBannersWithinRange(world, center, range)) {
            BlockState bannerState = world.getBlockState(bannerPos);
            if (!isBannerOnFence(world, bannerPos, bannerState)) {
                continue;
            }
            double distance = center.getSquaredDistance(bannerPos);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = bannerPos;
            }
        }
        return Optional.ofNullable(closest);
    }

    public static void playPairingAnimation(ServerWorld world, BlockPos blockPos, LivingEntity villager, BlockPos jobPos) {
        if (villager instanceof VillagerEntity villagerEntity) {
            VillagerProfession profession = villagerEntity.getVillagerData().getProfession();
            Identifier professionId = Registries.VILLAGER_PROFESSION.getId(profession);
            Identifier blockId = Registries.BLOCK.getId(world.getBlockState(blockPos).getBlock());
            LOGGER.debug("{} paired with {} at [{}] - {} ID: {}",
                    professionId,
                    blockId,
                    blockPos.toShortString(),
                    professionId,
                    villager.getId());
        }

        spawnHappyParticles(world, blockPos);
        spawnHappyParticles(world, jobPos);
        spawnHappyParticles(world, villager);

        world.playSound(null, blockPos, SoundEvents.ENTITY_VILLAGER_CELEBRATE, SoundCategory.BLOCKS, 0.75F, 1.0F);
        world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 0.85F, 1.0F);
        world.emitGameEvent(villager, GameEvent.BLOCK_CHANGE, blockPos);
    }

    private static boolean isEmployedVillager(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT && !villager.isBaby();
    }

    private static void spawnHappyParticles(ServerWorld world, BlockPos pos) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 12, 0.35D, 0.35D, 0.35D, 0.0D);
    }

    private static void spawnHappyParticles(ServerWorld world, LivingEntity entity) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, entity.getX(), entity.getBodyY(0.5D), entity.getZ(), 12, 0.35D, 0.5D, 0.35D, 0.0D);
    }

    public static boolean isCraftingTable(BlockState state) {
        return isCraftingTable(state.getBlock());
    }

    public static boolean isCraftingTable(Block block) {
        return block == Blocks.CRAFTING_TABLE;
    }

    public static Optional<BlockPos> findNearbyChest(ServerWorld world, BlockPos center) {
        return findNearbyChest(world, center, null);
    }

    /**
     * Finds the nearest pairing block (chest, trapped chest, barrel) within
     * {@link #JOB_BLOCK_PAIRING_RANGE} of {@code center}, optionally excluding
     * {@code excludePos}. The exclusion is used when {@code center} IS the job block
     * (e.g. a fisherman's barrel) so that the job block doesn't self-match as its
     * own paired storage.
     */
    public static Optional<BlockPos> findNearbyChest(ServerWorld world, BlockPos center, BlockPos excludePos) {
        int range = (int) Math.ceil(JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (excludePos != null && checkPos.equals(excludePos)) {
                continue;
            }
            if (center.isWithinDistance(checkPos, JOB_BLOCK_PAIRING_RANGE) && isPairingBlock(world.getBlockState(checkPos))) {
                return Optional.of(checkPos.toImmutable());
            }
        }
        return Optional.empty();
    }

    public static void cacheVillagerChestPairing(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive() || villager.isRemoved()) {
            invalidateVillagerChestPairing(world, villager.getUuid());
            return;
        }
        if (!isEmployedVillager(villager)) {
            invalidateVillagerChestPairing(world, villager.getUuid());
            return;
        }
        if (!jobPos.isWithinDistance(chestPos, JOB_BLOCK_PAIRING_RANGE)) {
            invalidateVillagerChestPairing(world, villager.getUuid());
            return;
        }
        if (!isPairingBlock(world.getBlockState(chestPos))) {
            invalidateVillagerChestPairing(world, villager.getUuid());
            return;
        }
        WorldKey worldKey = WorldKey.of(world);
        CACHED_VILLAGER_CHESTS
                .computeIfAbsent(worldKey, ignored -> new HashMap<>())
                .put(villager.getUuid(), new CachedVillagerChestPairing(
                        villager.getUuid(),
                        villager.getVillagerData().getProfession(),
                        jobPos.toImmutable(),
                        chestPos.toImmutable()));
    }

    public static void invalidateVillagerChestPairing(ServerWorld world, UUID villagerUuid) {
        WorldKey worldKey = WorldKey.of(world);
        Map<UUID, CachedVillagerChestPairing> byVillager = CACHED_VILLAGER_CHESTS.get(worldKey);
        if (byVillager == null) {
            return;
        }
        byVillager.remove(villagerUuid);
        if (byVillager.isEmpty()) {
            CACHED_VILLAGER_CHESTS.remove(worldKey);
        }
    }

    public static List<CachedVillagerChestPairing> getCachedVillagerChestPairings(ServerWorld world) {
        WorldKey worldKey = WorldKey.of(world);
        Map<UUID, CachedVillagerChestPairing> byVillager = CACHED_VILLAGER_CHESTS.get(worldKey);
        if (byVillager == null || byVillager.isEmpty()) {
            return List.of();
        }

        List<CachedVillagerChestPairing> valid = new ArrayList<>(byVillager.size());
        var iterator = byVillager.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CachedVillagerChestPairing> entry = iterator.next();
            CachedVillagerChestPairing pairing = entry.getValue();
            Entity entity = world.getEntity(entry.getKey());
            if (!(entity instanceof VillagerEntity villager) || !villager.isAlive() || villager.isRemoved()) {
                iterator.remove();
                continue;
            }

            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isEmpty()
                    || !Objects.equals(jobSite.get().dimension(), world.getRegistryKey())
                    || !jobSite.get().pos().equals(pairing.jobPos())) {
                iterator.remove();
                continue;
            }

            if (!isEmployedVillager(villager)
                    || !isPairingBlock(world.getBlockState(pairing.chestPos()))
                    || !pairing.jobPos().isWithinDistance(pairing.chestPos(), JOB_BLOCK_PAIRING_RANGE)) {
                iterator.remove();
                continue;
            }

            valid.add(new CachedVillagerChestPairing(
                    villager.getUuid(),
                    villager.getVillagerData().getProfession(),
                    pairing.jobPos(),
                    pairing.chestPos()));
        }

        if (byVillager.isEmpty()) {
            CACHED_VILLAGER_CHESTS.remove(worldKey);
        }
        return valid;
    }

    public static void clearWorldCaches(ServerWorld world) {
        WorldKey key = WorldKey.of(world);
        CACHED_VILLAGER_CHESTS.remove(key);
        CACHED_BANNER_POSITIONS.remove(key);
    }

    /**
     * Invalidates the banner position cache for the given world.
     * Must be called whenever a banner is placed or removed so that the next villager
     * refresh picks up the updated banner list.
     */
    public static void invalidateBannerCache(ServerWorld world) {
        CACHED_BANNER_POSITIONS.remove(WorldKey.of(world));
    }

    public record CachedVillagerChestPairing(UUID villagerUuid, VillagerProfession profession, BlockPos jobPos, BlockPos chestPos) {}

    private record WorldKey(String dimensionId) {
        private static WorldKey of(ServerWorld world) {
            return new WorldKey(world.getRegistryKey().getValue().toString());
        }
    }


    /**
     * Finds the nearest pairing block within {@code range} of BOTH centers, optionally excluding
     * {@code excludePos}. The exclusion prevents a fisherman's barrel job block from self-matching
     * as its own paired storage when the crafting table is placed nearby.
     */
    private static Optional<BlockPos> findNearbyChestWithinRangeOfBoth(ServerWorld world, BlockPos primaryCenter, BlockPos secondaryCenter, double range, @Nullable BlockPos excludePos) {
        int blockRange = (int) Math.ceil(range);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos checkPos : BlockPos.iterate(primaryCenter.add(-blockRange, -blockRange, -blockRange), primaryCenter.add(blockRange, blockRange, blockRange))) {
            if (excludePos != null && checkPos.equals(excludePos)) {
                continue;
            }
            if (!primaryCenter.isWithinDistance(checkPos, range) || !secondaryCenter.isWithinDistance(checkPos, range)) {
                continue;
            }
            if (!isPairingBlock(world.getBlockState(checkPos))) {
                continue;
            }

            double distance = primaryCenter.getSquaredDistance(checkPos);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = checkPos.toImmutable();
            }
        }

        return Optional.ofNullable(nearest);
    }
}
