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
import net.minecraft.registry.RegistryKey;
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
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class JobBlockPairingHelper {
    public static final double JOB_BLOCK_PAIRING_RANGE = 3.0D;
    private static final double NEARBY_VILLAGER_SCAN_RANGE = 8.0D;
    private static final double FARMER_BANNER_PAIR_RANGE = 500.0D;
    private static final double SHEPHERD_BANNER_PAIR_RANGE = 500.0D;
    private static final double BUTCHER_BANNER_PAIR_RANGE = 64.0D;
    private static final Set<Block> PAIRING_BLOCKS = Sets.newIdentityHashSet();
    private static final Logger LOGGER = LoggerFactory.getLogger(JobBlockPairingHelper.class);
    private static final double BOOTSTRAP_PLAYER_PROXIMITY_RADIUS = 256.0D;
    private static final int CHUNK_HYDRATION_RING = 1;
    private static final long BACKGROUND_CATCHUP_INTERVAL_TICKS = 40L;
    private static final int BACKGROUND_CATCHUP_CHUNK_BUDGET = 8;
    private static final int BACKGROUND_CATCHUP_ENTITY_BUDGET = 64;
    private static final long WORLD_AGE_WARMUP_TICKS = 200L;
    private static final int CHUNK_LOAD_IMMEDIATE_ENTITY_BUDGET = 24;
    private static final int BOOTSTRAP_CHUNK_ENQUEUE_BUDGET = 384;
    private static final int MAX_BOOTSTRAP_CHUNK_SPAN_PER_AXIS = 96;
    private static final int ANCHOR_SEED_SAMPLE_COUNT = 8;
    private static final int HYDRATION_MAX_ITERATIONS_PER_TICK = 128;
    private static final long HYDRATION_MAX_ELAPSED_MS_PER_TICK = 4L;
    private static final Map<RegistryKey<World>, HydrationState> HYDRATION_STATE = new HashMap<>();

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
            return;
        }

        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return;
        }

        BlockPos jobPos = globalPos.pos();
        VillagerProfessionBehaviorRegistry.ensureUniversalJobBlockGoal(villager, jobPos);
        // Exclude jobPos itself so that a fisherman's barrel job block doesn't self-match as its chest.
        Optional<BlockPos> nearbyChest = findNearbyChest(world, jobPos, jobPos);
        nearbyChest.ifPresent(chestPos -> VillagerProfessionBehaviorRegistry.notifyChestPaired(world, villager, jobPos, chestPos));

        if (nearbyChest.isPresent()) {
            Optional<BlockPos> craftingTablePos = findNearbyCraftingTable(world, jobPos);
            craftingTablePos.ifPresent(pos -> VillagerProfessionBehaviorRegistry.notifyCraftingTablePaired(world, villager, jobPos, nearbyChest.get(), pos));
        }

        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == VillagerProfession.FARMER || profession == VillagerProfession.SHEPHERD) {
            Collection<BlockPos> bannerPositions = findBannersWithinRange(world, jobPos, 300);
            for (BlockPos bannerPos : bannerPositions) {
                BlockState bannerState = world.getBlockState(bannerPos);
                if (isBannerOnFence(world, bannerPos, bannerState)) {
                    handleBannerPlacement(world, bannerPos, bannerState);
                }
            }
        }
    }

    public static void refreshWorldPairings(ServerWorld world) {
        VillageAnchorState anchorState = VillageAnchorState.get(world.getServer());
        anchorState.pruneInvalidAnchors(world);

        HydrationState state = stateFor(world);
        Box scanBox = buildSpawnPrepSafeScanBox(world, anchorState, state, BOOTSTRAP_PLAYER_PROXIMITY_RADIUS);
        if (scanBox == null) {
            // Spawn-prep safe: no nearby players and no known anchors means no broad fallback scan.
            state.bootstrapScanCursor = null;
            return;
        }

        // Spawn-prep safe: queue chunk-local hydration with a hard enqueue budget and resume cursor.
        enqueueChunksForBoxWithBudget(world, state, scanBox, BOOTSTRAP_CHUNK_ENQUEUE_BUDGET);
        if (world.getTime() >= WORLD_AGE_WARMUP_TICKS) {
            refreshEntitiesInBox(world, scanBox);
        }
    }

    public static void onChunkLoaded(ServerWorld world, Chunk chunk) {
        enqueueChunkRing(stateFor(world), chunk.getPos().x, chunk.getPos().z, CHUNK_HYDRATION_RING);
        if (world.getTime() < WORLD_AGE_WARMUP_TICKS) {
            return;
        }
        hydrateChunkRing(world, chunk.getPos().x, chunk.getPos().z, CHUNK_HYDRATION_RING, CHUNK_LOAD_IMMEDIATE_ENTITY_BUDGET);
    }

    public static void runBackgroundCatchUp(ServerWorld world) {
        HydrationState state = stateFor(world);
        long time = world.getTime();
        if (time - state.lastCatchUpTick < BACKGROUND_CATCHUP_INTERVAL_TICKS) {
            return;
        }
        state.lastCatchUpTick = time;
        hydrateQueuedChunksWithBudget(world, state, BACKGROUND_CATCHUP_ENTITY_BUDGET, BACKGROUND_CATCHUP_CHUNK_BUDGET);
    }

    public static void onWorldUnload(ServerWorld world) {
        HYDRATION_STATE.remove(world.getRegistryKey());
    }

    @Nullable
    private static Box buildSpawnPrepSafeScanBox(ServerWorld world, VillageAnchorState anchorState, HydrationState state, double radius) {
        Box playerBox = buildPlayerProximityBox(world, radius);
        if (playerBox != null) {
            return playerBox;
        }
        return buildAnchorSeedBox(anchorState, world, state, radius);
    }

    @Nullable
    private static Box buildPlayerProximityBox(ServerWorld world, double radius) {
        var players = world.getPlayers();
        if (players.isEmpty()) {
            return null;
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (var player : players) {
            minX = Math.min(minX, player.getX());
            minY = Math.min(minY, player.getY());
            minZ = Math.min(minZ, player.getZ());
            maxX = Math.max(maxX, player.getX());
            maxY = Math.max(maxY, player.getY());
            maxZ = Math.max(maxZ, player.getZ());
        }
        return new Box(minX - radius, minY - radius, minZ - radius, maxX + radius, maxY + radius, maxZ + radius);
    }



    @Nullable
    private static Box buildAnchorSeedBox(VillageAnchorState anchorState, ServerWorld world, HydrationState state, double radius) {
        var anchors = new ArrayList<>(anchorState.getAllQmChests(world));
        if (anchors.isEmpty()) {
            return null;
        }

        int sampleSize = Math.min(ANCHOR_SEED_SAMPLE_COUNT, anchors.size());
        int start = Math.floorMod(state.anchorSeedCursor, anchors.size());
        state.anchorSeedCursor = (start + sampleSize) % anchors.size();

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < sampleSize; i++) {
            BlockPos anchor = anchors.get((start + i) % anchors.size());
            minX = Math.min(minX, anchor.getX());
            minY = Math.min(minY, anchor.getY());
            minZ = Math.min(minZ, anchor.getZ());
            maxX = Math.max(maxX, anchor.getX());
            maxY = Math.max(maxY, anchor.getY());
            maxZ = Math.max(maxZ, anchor.getZ());
        }

        return new Box(minX - radius, minY - radius, minZ - radius, maxX + radius, maxY + radius, maxZ + radius);
    }

    private static void refreshEntitiesInBox(ServerWorld world, Box box) {
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, box, JobBlockPairingHelper::isEmployedVillager)) {
            refreshVillagerPairings(world, villager);
        }
        for (ButcherGuardEntity guard : world.getEntitiesByClass(ButcherGuardEntity.class, box, Entity::isAlive)) {
            refreshButcherGuardPairings(world, guard);
        }
    }

    private static void enqueueChunksForBoxWithBudget(ServerWorld world, HydrationState state, Box box, int chunkBudget) {
        if (chunkBudget <= 0) {
            return;
        }

        ChunkBoxCursor nextCursor = ChunkBoxCursor.forBox(box, MAX_BOOTSTRAP_CHUNK_SPAN_PER_AXIS);
        if (state.bootstrapScanCursor == null || !state.bootstrapScanCursor.sameBounds(nextCursor)) {
            state.bootstrapScanCursor = nextCursor;
        }

        ChunkBoxCursor cursor = state.bootstrapScanCursor;
        int remaining = chunkBudget;
        while (remaining > 0 && !cursor.complete()) {
            enqueueChunk(state, cursor.nextChunkX, cursor.nextChunkZ);
            cursor.advance();
            remaining--;
        }

        if (cursor.complete()) {
            state.bootstrapScanCursor = null;
        }
    }

    /**
     * Must be bounded per tick to avoid unbounded queue drain work in a single invocation.
     */
    private static void hydrateQueuedChunksWithBudget(ServerWorld world, HydrationState state, int entityBudget, int chunkBudget) {
        int remainingEntityBudget = entityBudget;
        int remainingChunkBudget = chunkBudget;
        int processedChunks = 0;
        int refreshedEntities = 0;
        TickWorkGuard guard = new TickWorkGuard(HYDRATION_MAX_ITERATIONS_PER_TICK, HYDRATION_MAX_ELAPSED_MS_PER_TICK);
        while (remainingEntityBudget > 0
                && remainingChunkBudget > 0
                && !state.pendingChunks.isEmpty()
                && guard.shouldContinue(processedChunks)) {
            long packed = state.pendingChunks.removeFirst();
            state.enqueuedChunks.remove(packed);
            int chunkX = unpackChunkX(packed);
            int chunkZ = unpackChunkZ(packed);
            int hydrated = hydrateChunk(world, chunkX, chunkZ, remainingEntityBudget);
            remainingEntityBudget -= hydrated;
            remainingChunkBudget--;
            processedChunks++;
            refreshedEntities += hydrated;
        }

        boolean hasLeftover = !state.pendingChunks.isEmpty();
        if (hasLeftover && (guard.hitTimeCap() || guard.hitIterationCap(processedChunks, true))) {
            LOGGER.warn("background hydration queue guard tripped: world={} reason={} queueSize={} processedChunks={} refreshedEntities={} remainingEntityBudget={} remainingChunkBudget={}",
                    world.getRegistryKey().getValue(),
                    guard.hitTimeCap() ? "elapsed-time" : "iteration-cap",
                    state.pendingChunks.size(),
                    processedChunks,
                    refreshedEntities,
                    remainingEntityBudget,
                    remainingChunkBudget);
        }

        if (processedChunks > 0 && refreshedEntities == 0) {
            LOGGER.debug("background hydration made zero entity progress: world={} processedChunks={} refreshedEntities={} pendingChunks={} remainingEntityBudget={} remainingChunkBudget={}",
                    world.getRegistryKey().getValue(),
                    processedChunks,
                    refreshedEntities,
                    state.pendingChunks.size(),
                    remainingEntityBudget,
                    remainingChunkBudget);
        }
    }

    private static void hydrateChunkRing(ServerWorld world, int centerChunkX, int centerChunkZ, int ringRadius, int entityBudget) {
        HydrationState state = stateFor(world);
        int remaining = entityBudget;
        for (int dx = -ringRadius; dx <= ringRadius; dx++) {
            for (int dz = -ringRadius; dz <= ringRadius; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                int refreshed = hydrateChunk(world, chunkX, chunkZ, remaining);
                remaining -= refreshed;
                if (remaining <= 0) {
                    enqueueChunk(state, chunkX, chunkZ);
                    return;
                }
            }
        }
    }

    private static int hydrateChunk(ServerWorld world, int chunkX, int chunkZ, int remainingBudget) {
        if (remainingBudget <= 0) {
            return 0;
        }

        Chunk chunk = world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return 0;
        }

        BlockPos startPos = chunk.getPos().getStartPos();
        Box chunkBox = new Box(startPos.getX(), world.getBottomY(), startPos.getZ(), startPos.getX() + 16.0D, world.getTopY(), startPos.getZ() + 16.0D);
        int refreshed = 0;

        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, chunkBox, JobBlockPairingHelper::isEmployedVillager)) {
            if (refreshed >= remainingBudget) {
                return refreshed;
            }
            refreshVillagerPairings(world, villager);
            refreshed++;
        }

        for (ButcherGuardEntity guard : world.getEntitiesByClass(ButcherGuardEntity.class, chunkBox, Entity::isAlive)) {
            if (refreshed >= remainingBudget) {
                return refreshed;
            }
            refreshButcherGuardPairings(world, guard);
            refreshed++;
        }

        return refreshed;
    }

    private static HydrationState stateFor(ServerWorld world) {
        return HYDRATION_STATE.computeIfAbsent(world.getRegistryKey(), key -> new HydrationState());
    }

    private static void enqueueChunk(HydrationState state, int chunkX, int chunkZ) {
        long packed = packChunk(chunkX, chunkZ);
        if (state.enqueuedChunks.add(packed)) {
            state.pendingChunks.addLast(packed);
        }
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ChunkPos.toLong(chunkX, chunkZ);
    }

    private static int unpackChunkX(long packed) {
        return ChunkPos.getPackedX(packed);
    }

    private static int unpackChunkZ(long packed) {
        return ChunkPos.getPackedZ(packed);
    }

    private static void enqueueChunkRing(HydrationState state, int centerChunkX, int centerChunkZ, int ringRadius) {
        for (int dx = -ringRadius; dx <= ringRadius; dx++) {
            for (int dz = -ringRadius; dz <= ringRadius; dz++) {
                enqueueChunk(state, centerChunkX + dx, centerChunkZ + dz);
            }
        }
    }

    private static final class HydrationState {
        private final Deque<Long> pendingChunks = new ArrayDeque<>();
        private final Set<Long> enqueuedChunks = new HashSet<>();
        private long lastCatchUpTick = Long.MIN_VALUE;
        private int anchorSeedCursor;
        @Nullable
        private ChunkBoxCursor bootstrapScanCursor;
    }

    private static final class ChunkBoxCursor {
        private final int minChunkX;
        private final int maxChunkX;
        private final int minChunkZ;
        private final int maxChunkZ;
        private int nextChunkX;
        private int nextChunkZ;

        private ChunkBoxCursor(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
            this.minChunkX = minChunkX;
            this.maxChunkX = maxChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkZ = maxChunkZ;
            this.nextChunkX = minChunkX;
            this.nextChunkZ = minChunkZ;
        }

        private static ChunkBoxCursor forBox(Box box, int maxChunkSpanPerAxis) {
            int minChunkX = MathHelper.floor(box.minX) >> 4;
            int maxChunkX = MathHelper.floor(box.maxX) >> 4;
            int minChunkZ = MathHelper.floor(box.minZ) >> 4;
            int maxChunkZ = MathHelper.floor(box.maxZ) >> 4;

            int centerChunkX = (minChunkX + maxChunkX) >> 1;
            int centerChunkZ = (minChunkZ + maxChunkZ) >> 1;
            int halfSpan = Math.max(1, maxChunkSpanPerAxis / 2);

            return new ChunkBoxCursor(
                    Math.max(minChunkX, centerChunkX - halfSpan),
                    Math.min(maxChunkX, centerChunkX + halfSpan),
                    Math.max(minChunkZ, centerChunkZ - halfSpan),
                    Math.min(maxChunkZ, centerChunkZ + halfSpan));
        }

        private boolean sameBounds(ChunkBoxCursor other) {
            return this.minChunkX == other.minChunkX
                    && this.maxChunkX == other.maxChunkX
                    && this.minChunkZ == other.minChunkZ
                    && this.maxChunkZ == other.maxChunkZ;
        }

        private boolean complete() {
            return nextChunkX > maxChunkX;
        }

        private void advance() {
            nextChunkZ++;
            if (nextChunkZ > maxChunkZ) {
                nextChunkZ = minChunkZ;
                nextChunkX++;
            }
        }
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
            LOGGER.info("{} paired with {} at [{}] - {} ID: {}",
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
