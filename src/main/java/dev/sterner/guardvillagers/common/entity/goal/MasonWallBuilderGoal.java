package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.util.VillageWallProjectState;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BedBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.registry.tag.PointOfInterestTypeTags;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Cluster 4 — Mason Defensive Wall Builder.
 *
 * <p>Behaviour summary:
 * <ol>
 *   <li>Scan configured POI set (job sites only, jobs+beds, or all POIs) within
 *       BELL_EFFECT_RANGE of the nearest QM chest
 *       → compute axis-aligned bounding rectangle → expand outward per config.</li>
 *   <li>Elect the mason with the most cobblestone as the builder; non-builders transfer their
 *       stone into the elected mason's paired chest, then resume normal goals.</li>
 *   <li>The elected builder must have enough cobblestone for ALL planned perimeter placements
 *       (excluding gap positions) before placing any block. Planning uses a deterministic
 *       3-layer perimeter (base/base+1/base+2) with strict layer-first ordering.</li>
 *   <li>Gap rules: skip DIRT_PATH positions; always leave at least 1 forced gap so the wall
 *       is never fully closed; reserve one position per face (4 total) for lumberjack fence
 *       gates (stored in entity NBT, not placed here).</li>
 *   <li>The builder walks to each wall segment position, places cobblestone, marks it done, and
 *       persists progress across restarts via NBT on MasonGuardEntity.</li>
 * </ol>
 */
public class MasonWallBuilderGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(MasonWallBuilderGoal.class);

    // Gameplay cadence: attempt a fresh build cycle every ~20 seconds (400 ticks),
    // if election/planning/material preconditions pass.
    private static final int SCAN_INTERVAL_TICKS = 400;
    // Minimum stone needed before the elected builder starts placing
    // (required total is per planned block placement, including anti-gap vertical fill columns).
    private static final int STONE_PER_SEGMENT = 1;
    // Movement speed
    private static final double MOVE_SPEED = 0.55D;
    // Reach distance squared (close enough to place)
    private static final double REACH_SQ = 3.5D * 3.5D;
    private static final int PATH_RETRY_MAX_ATTEMPTS = 4;
    private static final int PATH_RETRY_WINDOW_TICKS = 40;
    private static final long SEGMENT_CLAIM_DURATION_TICKS = 160L;
    private static final long HARD_UNREACHABLE_LOG_RATE_LIMIT_TICKS = 200L;
    private static final int NEAREST_STANDABLE_SEARCH_RADIUS = 4;
    // Gameplay cadence: placements happen in short local sorties of 3-5 segments
    // before the guard picks a new nearby anchor.
    static final int MIN_SEGMENTS_PER_SORTIE = 3;
    static final int MAX_SEGMENTS_PER_SORTIE = 5;
    static final int LOCAL_SORTIE_RADIUS = 5;
    static final boolean ALLOW_COBBLESTONE_PLACEMENT_FALLBACK = false;
    private static final int MAX_SORTIE_ANCHOR_ATTEMPTS_PER_TICK = 8;
    private static final int COMPLETION_SWEEP_MAX_RETRIES_PER_SEGMENT = 3;
    private static final int MAX_OBSTACLE_BREAKS_PER_TICK = 1;
    private static final int STRUCTURE_SAMPLE_HORIZONTAL_RADIUS = 7;
    private static final int STRUCTURE_SAMPLE_VERTICAL_RADIUS = 3;
    private static final int STRUCTURE_PROTECTION_EXPANSION_CAP = 12;
    private static final int STRUCTURE_MIN_ENCLOSURE_MARGIN = 2;
    // Maximum range for scanning peers
    private static final double PEER_SCAN_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;

    private final MasonGuardEntity guard;

    private long nextScanTick = 0L;
    private Stage stage = Stage.IDLE;

    // The computed wall segments for this run (only on the elected builder)
    private List<BlockPos> pendingSegments = new ArrayList<>();
    private int currentSegmentIndex = 0;
    private int lastProgressLoggedSegmentCount = 0;
    private BlockPos activeMoveTarget = null;
    private int activeMoveTargetTicks = 0;
    private double lastMoveDistSq = Double.MAX_VALUE;
    private int failedPathAttempts = 0;
    private long firstPathFailureTick = -1L;
    private BlockPos lastTriedNavTarget = null;
    private final Deque<BlockPos> localSortieQueue = new ArrayDeque<>();
    private final Set<BlockPos> skippedSegments = new HashSet<>();
    private final Deque<BlockPos> hardUnreachableRetryQueue = new ArrayDeque<>();
    private int sortiePlacements = 0;
    private int placedSegments = 0;
    private int plannedPlacementCount = 0;
    private boolean hardRetryPassStarted = false;
    private boolean conversionWaitActive = false;
    private boolean waitForStockProceedLogged = false;
    private int lastLoggedProceedWalls = -1;
    private int lastLoggedProceedThreshold = -1;
    private CycleEndReason cycleEndReason = CycleEndReason.WALL_COMPLETE;
    private BlockPos activeAnchorPos = null;
    private final Map<String, Long> retryLogRateLimitTickBySignature = new HashMap<>();
    private final Set<BlockPos> claimedSortieSegments = new HashSet<>();
    private boolean sortieActive = false;
    private int sortieActiveLayer = -1;
    private int sortieTransientRetriesAttempted = 0;
    private int sortieFallbackTargetsTried = 0;
    private int sortieHardUnreachableMarked = 0;
    private int sortieCandidatesConsidered = 0;
    private int sortieCandidatesAccepted = 0;
    private int sortieCandidatesPreflightPass = 0;
    private int sortieCandidatesPreflightRejected = 0;
    private int sortieCandidatesFallbackAccepted = 0;
    private int obstaclesCleared = 0;
    private int protectedObstaclesSkipped = 0;
    private int unbreakableObstaclesSkipped = 0;
    private long obstacleBreakTick = Long.MIN_VALUE;
    private int obstacleBreaksThisTick = 0;
    private long nextCompletionSweepAllowedTick = 0L;
    private int consecutiveDeferredSweeps = 0;
    private String lastSweepLogSignature = null;
    private long lastSweepLogTick = Long.MIN_VALUE;
    private static final long SWEEP_LOG_RATE_LIMIT_TICKS = 200L;
    private static final long PROGRESS_WATCHDOG_TIMEOUT_TICKS = 600L;
    private int suppressedSweepInfoLogCount = 0;
    private boolean sweepInfoSuppressionDebugEmitted = false;
    private long lastPlacementTick = -1L;
    private int placementsSinceCycleStart = 0;
    private final Map<BlockPos, String> skippedSegmentReasons = new HashMap<>();

    // Whether this mason is the elected builder this cycle
    private boolean isElectedBuilder = false;

    // Transfers: list of (peerChestPos, amount) to drain from peer chests into our chest
    private List<TransferTask> pendingTransfers = new ArrayList<>();
    private int currentTransferIndex = 0;

    /**
     * Cached wall rectangle from the last successful computeWallRect() call.
     * Re-used across canStart() cycles as long as the anchor position hasn't changed.
     * This avoids the expensive POI stream scan + heightmap lookups every 400 ticks.
     * Invalidated when the anchor changes or the wall is marked complete.
     */
    private WallRect cachedWallRect = null;
    /** Anchor position that produced {@link #cachedWallRect}. Null = no cache. */
    private BlockPos cachedWallRectAnchor = null;
    /** POI footprint signature that produced {@link #cachedWallRect}. Null = no cache. */
    private PoiFootprintSignature cachedPoiFootprintSignature = null;
    /** Last mode logged for wall footprint scans; avoids repeating the same info line every cycle. */
    private GuardVillagersConfig.MasonWallPoiMode lastLoggedPoiMode = null;
    /** Rectangle selected for the currently staged cycle; logged once at cycle start. */
    private WallRect cycleWallRect = null;

    public MasonWallBuilderGoal(MasonGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world)) return false;
        if (!guard.isAlive()) return false;
        if (guard.isMiningSessionActive()) return false;
        if (guard.getPairedChestPos() == null) return false;
        if (world.getTime() < nextScanTick) return false;

        // Resolve village anchor from nearest QM chest — no bell required
        Optional<BlockPos> anchorOpt = VillageAnchorState.get(world.getServer())
                .getNearestQmChest(world, guard.getBlockPos(), (int) PEER_SCAN_RANGE);
        if (anchorOpt.isEmpty()) return false;

        nextScanTick = world.getTime() + SCAN_INTERVAL_TICKS;

        return tryInitiateBuildCycle(world, anchorOpt.get());
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && guard.isAlive();
    }

    @Override
    public boolean canStop() {
        // Keep the elected builder focused until the current build cycle finishes.
        return !isElectedBuilder || stage == Stage.DONE || stage == Stage.IDLE || conversionWaitActive;
    }

    @Override
    public void start() {
        currentSegmentIndex = 0;
        currentTransferIndex = 0;
        lastProgressLoggedSegmentCount = 0;
        activeMoveTarget = null;
        activeMoveTargetTicks = 0;
        lastMoveDistSq = Double.MAX_VALUE;
        failedPathAttempts = 0;
        firstPathFailureTick = -1L;
        lastTriedNavTarget = null;
        localSortieQueue.clear();
        skippedSegments.clear();
        skippedSegmentReasons.clear();
        hardUnreachableRetryQueue.clear();
        sortiePlacements = 0;
        placedSegments = 0;
        plannedPlacementCount = (int) pendingSegments.stream().filter(pos -> !isGatePosition(pos)).count();
        hardRetryPassStarted = false;
        conversionWaitActive = false;
        resetWaitForStockProceedLogState();
        cycleEndReason = CycleEndReason.WALL_COMPLETE;
        retryLogRateLimitTickBySignature.clear();
        claimedSortieSegments.clear();
        sortieActive = false;
        sortieActiveLayer = -1;
        sortieTransientRetriesAttempted = 0;
        sortieFallbackTargetsTried = 0;
        sortieHardUnreachableMarked = 0;
        sortieCandidatesConsidered = 0;
        sortieCandidatesAccepted = 0;
        sortieCandidatesPreflightPass = 0;
        sortieCandidatesPreflightRejected = 0;
        sortieCandidatesFallbackAccepted = 0;
        obstaclesCleared = 0;
        protectedObstaclesSkipped = 0;
        unbreakableObstaclesSkipped = 0;
        obstacleBreakTick = Long.MIN_VALUE;
        obstacleBreaksThisTick = 0;
        nextCompletionSweepAllowedTick = 0L;
        consecutiveDeferredSweeps = 0;
        lastSweepLogSignature = null;
        lastSweepLogTick = Long.MIN_VALUE;
        suppressedSweepInfoLogCount = 0;
        sweepInfoSuppressionDebugEmitted = false;
        ServerWorld world = worldOrNull();
        lastPlacementTick = world != null ? world.getTime() : 0L;
        placementsSinceCycleStart = 0;
        skippedSegmentReasons.clear();
        if (isElectedBuilder && !pendingTransfers.isEmpty()) {
            stage = Stage.TRANSFER_FROM_PEERS;
        } else if (isElectedBuilder && !pendingSegments.isEmpty()) {
            stage = Stage.WAIT_FOR_WALL_STOCK;
        } else {
            resetWaitForStockProceedLogState();
            stage = Stage.DONE;
        }
        if (isElectedBuilder) {
            String firstTarget = pendingSegments.isEmpty() ? "none" : pendingSegments.get(0).toShortString();
            if (cycleWallRect != null) {
                LOGGER.info(
                        "MasonWallBuilder {}: build cycle started (segments={}, transfers={}, firstTarget={}, rect=[x:{}..{} z:{}..{} y:{}], width={}, depth={})",
                        guard.getUuidAsString(), pendingSegments.size(), pendingTransfers.size(), firstTarget,
                        cycleWallRect.minX(), cycleWallRect.maxX(), cycleWallRect.minZ(), cycleWallRect.maxZ(), cycleWallRect.y(),
                        cycleWallRect.maxX() - cycleWallRect.minX() + 1,
                        cycleWallRect.maxZ() - cycleWallRect.minZ() + 1
                );
            } else {
                LOGGER.info("MasonWallBuilder {}: build cycle started (segments={}, transfers={}, firstTarget={})",
                        guard.getUuidAsString(), pendingSegments.size(), pendingTransfers.size(), firstTarget);
            }
        }
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        if (isElectedBuilder && currentSegmentIndex > 0 && currentSegmentIndex < pendingSegments.size()) {
            LOGGER.info("MasonWallBuilder {}: build cycle paused/interrupted at {}/{}",
                    guard.getUuidAsString(), currentSegmentIndex, pendingSegments.size());
        }
        stage = Stage.IDLE;
        isElectedBuilder = false;
        resetPathFailureState();
        hardUnreachableRetryQueue.clear();
        hardRetryPassStarted = false;
        conversionWaitActive = false;
        resetWaitForStockProceedLogState();
        retryLogRateLimitTickBySignature.clear();
        releaseAllSegmentClaims(worldOrNull());
        claimedSortieSegments.clear();
        sortieActive = false;
        sortieActiveLayer = -1;
        sortieTransientRetriesAttempted = 0;
        sortieFallbackTargetsTried = 0;
        sortieHardUnreachableMarked = 0;
        nextCompletionSweepAllowedTick = 0L;
        consecutiveDeferredSweeps = 0;
        lastSweepLogSignature = null;
        lastSweepLogTick = Long.MIN_VALUE;
        suppressedSweepInfoLogCount = 0;
        sweepInfoSuppressionDebugEmitted = false;
        lastPlacementTick = -1L;
        placementsSinceCycleStart = 0;
        activeAnchorPos = null;
        cycleWallRect = null;
        pendingSegments.clear();
        pendingTransfers.clear();
        guard.setWallBuildPending(false, 0);
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            resetWaitForStockProceedLogState();
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case TRANSFER_FROM_PEERS -> tickTransfer(world);
            case WAIT_FOR_WALL_STOCK -> tickWaitForWallStock(world);
            case MOVE_TO_SEGMENT -> tickMoveToSegment(world);
            case PLACE_BLOCK -> tickPlaceBlock(world);
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Initiation — election + planning
    // -------------------------------------------------------------------------

    /**
     * Attempts to start a wall-build cycle.  Returns true if this mason has a role to play
     * (either elected builder or stone donor).
     *
     * @param anchorPos the QM chest position — used as the geographic village center
     */
    private boolean tryInitiateBuildCycle(ServerWorld world, BlockPos anchorPos) {
        cycleWallRect = null;
        GuardVillagersConfig.MasonWallPoiMode poiMode = resolveWallPoiMode();
        if (poiMode != lastLoggedPoiMode) {
            LOGGER.info("MasonWallBuilder {}: wall POI scan mode={}", guard.getUuidAsString(), poiMode);
            LOGGER.info("MasonWallBuilder {}: wall profile=deterministic 3-layer perimeter (layer-first build order)",
                    guard.getUuidAsString());
            lastLoggedPoiMode = poiMode;
        }

        // 1. Compute current POI footprint signature near the anchor using configured POI mode.
        // Recompute the wall rectangle whenever this signature changes, even if the anchor is unchanged.
        Optional<PoiFootprintSignature> signatureOpt = computePoiFootprintSignature(world, anchorPos);
        if (signatureOpt.isEmpty()) {
            LOGGER.debug("MasonWallBuilder {}: no village bounds found near anchor {}",
                    guard.getUuidAsString(), anchorPos.toShortString());
            cachedWallRect = null;
            cachedWallRectAnchor = null;
            cachedPoiFootprintSignature = null;
            return false;
        }

        PoiFootprintSignature currentSignature = signatureOpt.get();

        // 2. Compute (or reuse cached) wall rectangle.
        WallRect rect;
        boolean cacheValid = cachedWallRect != null
                && anchorPos.equals(cachedWallRectAnchor)
                && currentSignature.equals(cachedPoiFootprintSignature);
        if (cacheValid) {
            rect = cachedWallRect;
        } else {
            rect = computeWallRect(anchorPos, currentSignature);
            cachedWallRect = rect;
            cachedWallRectAnchor = anchorPos.toImmutable();
            cachedPoiFootprintSignature = currentSignature;
        }
        VillageWallProjectState wallProjectState = VillageWallProjectState.get(world.getServer());
        VillageWallProjectState.PerimeterBounds perimeterBounds = new VillageWallProjectState.PerimeterBounds(
                rect.minX(), rect.maxX(), rect.minZ(), rect.maxZ()
        );
        VillageWallProjectState.PerimeterSignature perimeterSignature =
                new VillageWallProjectState.PerimeterSignature(currentSignature.poiCount(), currentSignature.poiHash());
        wallProjectState.upsertProject(world.getRegistryKey(), anchorPos, perimeterBounds, perimeterSignature);
        if (wallProjectState.isProjectComplete(world.getRegistryKey(), anchorPos)) {
            LOGGER.debug("MasonWallBuilder {}: wall project already complete for anchor {}",
                    guard.getUuidAsString(), anchorPos.toShortString());
            return false;
        }

        // 3. Compute all wall segment positions for the rectangle
        List<BlockPos> allSegments = computeWallSegments(world, rect);
        if (allSegments.isEmpty()) {
            return false;
        }

        // Filter to unbuilt segments only
        List<BlockPos> unbuilt = allSegments.stream()
                .filter(pos -> !isPlacedWallBlock(world.getBlockState(pos)))
                .toList();
        if (unbuilt.isEmpty()) {
            LOGGER.debug("MasonWallBuilder {}: wall already complete", guard.getUuidAsString());
            wallProjectState.markAllLayersComplete(world.getRegistryKey(), anchorPos);
            return false;
        }

        // Determine gate reservations before computing required stone so gate exclusions
        // are reflected in the build threshold.
        List<BlockPos> gatePositions = pickGatePositions(rect, unbuilt);
        Set<BlockPos> gateSet = new HashSet<>(gatePositions);

        int requiredWallSegments = unbuilt.stream()
                .filter(pos -> !isGateColumnPosition(pos, gateSet))
                .mapToInt(pos -> STONE_PER_SEGMENT)
                .sum();
        if (requiredWallSegments < 1) {
            LOGGER.debug("MasonWallBuilder {}: no non-gate wall segments remain (available={}, required={})",
                    guard.getUuidAsString(), 0, requiredWallSegments);
            return false;
        }

        // 4. Find all peer masons near the anchor
        List<MasonGuardEntity> peers = getPeerMasons(world, anchorPos);

        // 5. Count cobblestone across all peers (including self)
        int myWalls = countItemInChest(world, guard.getPairedChestPos(), Items.COBBLESTONE_WALL);
        int myCobblestone = countItemInChest(world, guard.getPairedChestPos(), Items.COBBLESTONE);
        int totalWalls = myWalls;
        int totalCobblestone = myCobblestone;
        for (MasonGuardEntity peer : peers) {
            if (peer == guard) continue;
            if (peer.getPairedChestPos() != null) {
                totalWalls += countItemInChest(world, peer.getPairedChestPos(), Items.COBBLESTONE_WALL);
                totalCobblestone += countItemInChest(world, peer.getPairedChestPos(), Items.COBBLESTONE);
            }
        }

        int totalConvertibleWalls = totalWalls + totalCobblestone;
        int requiredToStartSession = Math.max(1, Math.min(MAX_SEGMENTS_PER_SORTIE, requiredWallSegments));
        if (totalConvertibleWalls < requiredToStartSession) {
            LOGGER.info("MasonWallBuilder {}: insufficient wall material to start session (walls={}, cobblestone={}, requiredToStart={}, requiredTotal={})",
                    guard.getUuidAsString(), totalWalls, totalCobblestone, requiredToStartSession, requiredWallSegments);
            return false;
        }
        int plannedConversions = Math.max(0, requiredToStartSession - totalWalls);
        LOGGER.info("MasonWallBuilder {}: readiness check passed (requiredToStart={}, requiredTotal={}, wallsAvailable={}, cobblestoneConvertible={}, plannedStartConversions={})",
                guard.getUuidAsString(), requiredToStartSession, requiredWallSegments, totalWalls, totalCobblestone, plannedConversions);

        // 6. Elect builder — only masons with both chest + job pairing are eligible.
        List<ElectionCandidateSnapshot> electionCandidates = new ArrayList<>();
        electionCandidates.add(new ElectionCandidateSnapshot(
                guard.getUuidAsString(),
                guard.getPairedChestPos() != null,
                guard.getPairedJobPos() != null,
                myWalls + myCobblestone
        ));
        for (MasonGuardEntity peer : peers) {
            if (peer == guard) continue;
            BlockPos peerChestPos = peer.getPairedChestPos();
            int peerStone = peerChestPos == null ? 0 :
                    countItemInChest(world, peerChestPos, Items.COBBLESTONE_WALL)
                            + countItemInChest(world, peerChestPos, Items.COBBLESTONE);
            electionCandidates.add(new ElectionCandidateSnapshot(
                    peer.getUuidAsString(),
                    peerChestPos != null,
                    peer.getPairedJobPos() != null,
                    peerStone
            ));
        }
        ElectionDecision electionDecision = electBuilderCandidate(electionCandidates);
        for (ElectionCandidateSnapshot excluded : electionDecision.excludedCandidates()) {
            LOGGER.debug("MasonWallBuilder {}: election exclusion candidate={} reason=missing pairing (hasChest={}, hasJob={})",
                    guard.getUuidAsString(),
                    excluded.candidateId(),
                    excluded.hasPairedChest(),
                    excluded.hasPairedJob());
        }

        MasonGuardEntity electedBuilder = null;
        int electedStone = electionDecision.electedStoneCount();
        String electedCandidateId = electionDecision.electedCandidateId();
        if (electedCandidateId != null) {
            if (guard.getUuidAsString().equals(electedCandidateId)) {
                electedBuilder = guard;
            } else {
                for (MasonGuardEntity peer : peers) {
                    if (peer.getUuidAsString().equals(electedCandidateId)) {
                        electedBuilder = peer;
                        break;
                    }
                }
            }
        }

        if (electionDecision.shouldLogNoEligibleBuilder() || electedBuilder == null) {
            LOGGER.info("MasonWallBuilder {}: election skipped; no eligible mason with paired chest+job near anchor {}",
                    guard.getUuidAsString(), anchorPos.toShortString());
            return false;
        }

        if (electedBuilder != guard) {
            // This mason is a donor — it will offer its stone to the elected builder
            // The elected builder's own goal tick will handle collecting
            // We just need to mark ourselves as a donor (no goal action needed this cycle)
            LOGGER.debug("MasonWallBuilder {}: acting as donor to elected builder {}",
                    guard.getUuidAsString(), electedBuilder.getUuidAsString());
            return false; // Non-builder masons don't run this goal this cycle
        }

        // This mason is the elected builder
        isElectedBuilder = true;

        // 7. Build transfer list from peers
        pendingTransfers = new ArrayList<>();
        for (MasonGuardEntity peer : peers) {
            if (peer == guard) continue;
            BlockPos peerChestPos = peer.getPairedChestPos();
            if (peerChestPos == null) {
                LOGGER.debug("MasonWallBuilder {}: transfer exclusion candidate={} reason=missing paired chest",
                        guard.getUuidAsString(), peer.getUuidAsString());
                continue;
            }
            if (peer.getPairedJobPos() == null) {
                LOGGER.debug("MasonWallBuilder {}: transfer donor candidate={} has no paired job but remains eligible (paired chest present)",
                        guard.getUuidAsString(), peer.getUuidAsString());
            }
            int peerStone = countItemInChest(world, peerChestPos, Items.COBBLESTONE_WALL)
                    + countItemInChest(world, peerChestPos, Items.COBBLESTONE);
            if (peerStone > 0) {
                pendingTransfers.add(new TransferTask(peerChestPos, guard.getPairedChestPos(), peerStone));
            }
        }

        // 8. Store segments on guard for NBT persistence
        pendingSegments = new ArrayList<>(unbuilt);
        activeAnchorPos = anchorPos.toImmutable();
        cycleWallRect = rect;
        guard.setWallSegments(pendingSegments);

        // 9. Store gate reservation positions (1 per face) on the guard
        guard.setWallGatePositions(gatePositions);
        guard.setWallBuildPending(true, Math.min(MAX_SEGMENTS_PER_SORTIE, requiredWallSegments));

        LOGGER.info("MasonWallBuilder {}: elected builder={} (stone={}); {} segments to place, {} transfers pending",
                guard.getUuidAsString(), electedBuilder.getUuidAsString(), electedStone, pendingSegments.size(), pendingTransfers.size());
        return true;
    }

    // -------------------------------------------------------------------------
    // Stage ticks
    // -------------------------------------------------------------------------

    private void tickTransfer(ServerWorld world) {
        if (currentTransferIndex >= pendingTransfers.size()) {
            stage = pendingSegments.isEmpty() ? Stage.DONE : Stage.WAIT_FOR_WALL_STOCK;
            return;
        }

        TransferTask task = pendingTransfers.get(currentTransferIndex);

        // Walk to the source chest
        double distSq = guard.squaredDistanceTo(
                task.sourceChestPos().getX() + 0.5,
                task.sourceChestPos().getY() + 0.5,
                task.sourceChestPos().getZ() + 0.5);

        if (distSq > REACH_SQ) {
            guard.getNavigation().startMovingTo(
                    task.sourceChestPos().getX() + 0.5,
                    task.sourceChestPos().getY() + 0.5,
                    task.sourceChestPos().getZ() + 0.5,
                    MOVE_SPEED);
            return;
        }

        // Close enough — transfer stone
        transferCobblestone(world, task.sourceChestPos(), task.destChestPos(), task.amount());
        currentTransferIndex++;
    }


    private void tickWaitForWallStock(ServerWorld world) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            resetWaitForStockProceedLogState();
            stage = Stage.DONE;
            return;
        }

        int threshold = computeCurrentSortieThreshold(world);
        guard.setWallBuildPending(true, threshold);
        int availableWalls = countItemInChest(world, chestPos, Items.COBBLESTONE_WALL);
        int availableCobblestone = countItemInChest(world, chestPos, Items.COBBLESTONE);
        LOGGER.debug("MasonWallBuilder {}: WAIT_FOR_WALL_STOCK inventory (availableWalls={}, availableCobblestone={}, threshold={})",
                guard.getUuidAsString(), availableWalls, availableCobblestone, threshold);

        WaitForStockDecision decision = decideWaitForStockTransition(
                availableWalls,
                availableCobblestone,
                threshold
        );
        if (decision == WaitForStockDecision.MOVE_TO_SEGMENT) {
            boolean conversionWaitEnded = conversionWaitActive;
            if (conversionWaitEnded) {
                LOGGER.info("MasonWallBuilder {}: conversion success detected (walls_now={} >= threshold={})",
                        guard.getUuidAsString(), availableWalls, threshold);
            }
            conversionWaitActive = false;
            boolean shouldLogProceed = conversionWaitEnded
                    || !waitForStockProceedLogged
                    || availableWalls != lastLoggedProceedWalls
                    || threshold != lastLoggedProceedThreshold;
            if (shouldLogProceed) {
                LOGGER.info("MasonWallBuilder {}: proceeding with existing wall stock without stonecutter (availableWalls={}, threshold={})",
                        guard.getUuidAsString(), availableWalls, threshold);
                waitForStockProceedLogged = true;
                lastLoggedProceedWalls = availableWalls;
                lastLoggedProceedThreshold = threshold;
            }
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }
        if (decision == WaitForStockDecision.DONE) {
            conversionWaitActive = false;
            resetWaitForStockProceedLogState();
            completeCycle(CycleEndReason.OUT_OF_MATERIALS);
            return;
        }
        if (decision == WaitForStockDecision.WAIT_FOR_CONVERSION) {
            if (!conversionWaitActive) {
                LOGGER.info("MasonWallBuilder {}: entering conversion wait (walls={}, cobblestone={}, threshold={})",
                        guard.getUuidAsString(), availableWalls, availableCobblestone, threshold);
            }
            conversionWaitActive = true;
            resetWaitForStockProceedLogState();
            guard.getNavigation().stop();
            LOGGER.debug("MasonWallBuilder {}: waiting for stonecutting conversion (availableWalls={}, availableCobblestone={}, threshold={})",
                    guard.getUuidAsString(), availableWalls, availableCobblestone, threshold);
            return;
        }
    }

    private void tickMoveToSegment(ServerWorld world) {
        maybeRecoverStalledCycle(world, "move_tick");
        pruneSortieQueue(world);
        if (localSortieQueue.isEmpty() || sortiePlacements >= MAX_SEGMENTS_PER_SORTIE) {
            logSortieSummaryIfActive();
            if (!startNextSortie(world)) {
                if (world.getTime() < nextCompletionSweepAllowedTick) {
                    stage = Stage.WAIT_FOR_WALL_STOCK;
                    return;
                }
                runCompletionSweepAndFinish(world);
                return;
            }
        }

        BlockPos target = localSortieQueue.peekFirst();
        if (target == null) {
            resetWaitForStockProceedLogState();
            stage = Stage.DONE;
            return;
        }

        BlockPos navigationTarget = resolveSegmentNavigationTarget(world, target);
        if (!target.equals(activeMoveTarget)) {
            activeMoveTarget = target;
            activeMoveTargetTicks = 0;
            lastMoveDistSq = Double.MAX_VALUE;
            resetPathFailureState();
        }
        double distSq = guard.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        if (distSq <= REACH_SQ) {
            activeMoveTarget = null;
            activeMoveTargetTicks = 0;
            lastMoveDistSq = Double.MAX_VALUE;
            resetPathFailureState();
            stage = Stage.PLACE_BLOCK;
        } else {
            boolean pathStarted = guard.getNavigation().startMovingTo(
                    navigationTarget.getX() + 0.5,
                    navigationTarget.getY() + 0.5,
                    navigationTarget.getZ() + 0.5,
                    MOVE_SPEED
            );
            if (!pathStarted) {
                registerPathFailure(world, target, navigationTarget);
                return;
            }
            if (failedPathAttempts > 0) {
                resetPathFailureState();
            }

            activeMoveTargetTicks++;
            if (distSq < lastMoveDistSq - 0.01D) {
                lastMoveDistSq = distSq;
                activeMoveTargetTicks = 0;
            } else if (activeMoveTargetTicks > 200) {
                LOGGER.info("MasonWallBuilder {}: skipping stalled segment {} after {} ticks (distSq={})",
                        guard.getUuidAsString(), target.toShortString(), activeMoveTargetTicks, String.format("%.2f", distSq));
                localSortieQueue.pollFirst();
                releaseSegmentClaim(world, target, "stalled");
                markSkippedSegment(target, "stalled_segment");
                activeMoveTarget = null;
                activeMoveTargetTicks = 0;
                lastMoveDistSq = Double.MAX_VALUE;
                resetPathFailureState();
            }
        }
    }

    /**
     * Wall segments are frequently air blocks one block above terrain. Pathfinding directly
     * to those coordinates often fails because entities cannot stand in that space. Route
     * navigation to a nearby standable position instead, while still placing on the original
     * segment when the guard is within placement reach.
     */
    private BlockPos resolveSegmentNavigationTarget(ServerWorld world, BlockPos segmentPos) {
        List<BlockPos> candidates = buildNavigationTargetCandidates(world, segmentPos);
        int index = Math.min(Math.max(0, failedPathAttempts), Math.max(0, candidates.size() - 1));
        return candidates.get(index);
    }

    private List<BlockPos> buildNavigationTargetCandidates(ServerWorld world, BlockPos segmentPos) {
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos below = segmentPos.down();
        if (isStandable(world, below)) {
            candidates.add(below.toImmutable());
        }
        BlockPos twoBelow = segmentPos.down(2);
        if (isStandable(world, twoBelow) && !candidates.contains(twoBelow)) {
            candidates.add(twoBelow.toImmutable());
        }
        candidates.add(segmentPos.toImmutable());
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos offset = segmentPos.offset(direction);
            if (isStandable(world, offset) && !candidates.contains(offset)) {
                candidates.add(offset.toImmutable());
            }
        }
        BlockPos nearestStandable = findNearestStandable(world, segmentPos, NEAREST_STANDABLE_SEARCH_RADIUS);
        if (nearestStandable != null && !candidates.contains(nearestStandable)) {
            candidates.add(nearestStandable);
        }
        return candidates;
    }

    private BlockPos findNearestStandable(ServerWorld world, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = center.add(dx, dy, dz);
                    if (!isStandable(world, candidate)) continue;
                    double distSq = center.getSquaredDistance(candidate);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = candidate.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean isStandable(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isSolidBlock(world, pos)) return false;
        BlockPos above = pos.up();
        BlockPos twoAbove = above.up();
        return world.getBlockState(above).getCollisionShape(world, above).isEmpty()
                && world.getBlockState(twoAbove).getCollisionShape(world, twoAbove).isEmpty()
                && world.getFluidState(above).isEmpty()
                && world.getFluidState(twoAbove).isEmpty();
    }

    private void registerPathFailure(ServerWorld world, BlockPos segmentTarget, BlockPos attemptedNavTarget) {
        if (firstPathFailureTick < 0L) {
            firstPathFailureTick = world.getTime();
        }
        failedPathAttempts++;
        lastTriedNavTarget = attemptedNavTarget.toImmutable();

        List<BlockPos> candidates = buildNavigationTargetCandidates(world, segmentTarget);
        if (failedPathAttempts < candidates.size()) {
            BlockPos fallback = candidates.get(failedPathAttempts);
            sortieFallbackTargetsTried++;
            LOGGER.debug("MasonWallBuilder {}: fallback_target_used segment={} attempted={} fallback={}",
                    guard.getUuidAsString(), segmentTarget.toShortString(),
                    attemptedNavTarget.toShortString(), fallback.toShortString());
        }

        boolean allFallbackTargetsFailed = failedPathAttempts >= candidates.size();
        if (!(allFallbackTargetsFailed && shouldMarkHardUnreachable(failedPathAttempts, firstPathFailureTick, world.getTime()))) {
            sortieTransientRetriesAttempted++;
            LOGGER.debug("MasonWallBuilder {}: transient_path_fail_retrying segment={} attempt={} navTarget={} windowTicks={} allFallbacksFailed={}",
                    guard.getUuidAsString(),
                    segmentTarget.toShortString(),
                    failedPathAttempts,
                    attemptedNavTarget.toShortString(),
                    (world.getTime() - firstPathFailureTick),
                    allFallbackTargetsFailed);
            return;
        }

        if (tryClearObstructionsForSegment(world, segmentTarget, attemptedNavTarget)) {
            LOGGER.debug("MasonWallBuilder {}: movement_obstruction_cleared_retrying segment={} navTarget={}",
                    guard.getUuidAsString(),
                    segmentTarget.toShortString(),
                    attemptedNavTarget.toShortString());
            activeMoveTarget = null;
            activeMoveTargetTicks = 0;
            lastMoveDistSq = Double.MAX_VALUE;
            resetPathFailureState();
            return;
        }

        sortieHardUnreachableMarked++;
        if (shouldEmitHardUnreachableInfo(world, segmentTarget)) {
            LOGGER.info("MasonWallBuilder {}: hard_unreachable_after_retries segment={} attempts={} firstFailTick={} lastNavTarget={}",
                    guard.getUuidAsString(),
                    segmentTarget.toShortString(),
                    failedPathAttempts,
                    firstPathFailureTick,
                    lastTriedNavTarget == null ? "none" : lastTriedNavTarget.toShortString());
        }
        localSortieQueue.pollFirst();
        releaseSegmentClaim(world, segmentTarget, "hard_unreachable");
        markSkippedSegment(segmentTarget, "hard_unreachable");
        if (!hardRetryPassStarted) {
            hardUnreachableRetryQueue.offerLast(segmentTarget.toImmutable());
        }
        activeMoveTarget = null;
        activeMoveTargetTicks = 0;
        lastMoveDistSq = Double.MAX_VALUE;
        resetPathFailureState();
    }

    private boolean tryClearObstructionsForSegment(ServerWorld world, BlockPos segmentTarget, BlockPos attemptedNavTarget) {
        Set<BlockPos> candidates = new HashSet<>();

        // Segment column at placement Y plus headroom.
        candidates.add(segmentTarget.toImmutable());
        candidates.add(segmentTarget.up().toImmutable());

        // Attempted navigation node and immediate neighbors, including headroom blocks.
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos neighbor = attemptedNavTarget.offset(direction);
            candidates.add(neighbor.toImmutable());
            candidates.add(neighbor.up().toImmutable());
        }
        candidates.add(attemptedNavTarget.toImmutable());
        candidates.add(attemptedNavTarget.up().toImmutable());

        int beforeCleared = obstaclesCleared;
        for (BlockPos candidate : candidates) {
            if (world.isAir(candidate)) {
                continue;
            }
            clearObstacleAt(world, segmentTarget, candidate);
            if (obstaclesCleared > beforeCleared) {
                return true;
            }
        }
        return false;
    }

    private void resetPathFailureState() {
        failedPathAttempts = 0;
        firstPathFailureTick = -1L;
        lastTriedNavTarget = null;
    }

    private void tickPlaceBlock(ServerWorld world) {
        BlockPos target = localSortieQueue.peekFirst();
        if (target == null) {
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }

        // Verify still unbuilt
        if (isPlacedWallBlock(world.getBlockState(target)) || isGatePosition(target)) {
            localSortieQueue.pollFirst();
            releaseSegmentClaim(world, target, "already_resolved");
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }

        ObstacleClearanceResult clearance = prepareSegmentForPlacement(world, target);
        if (clearance == ObstacleClearanceResult.CLEARED_THIS_TICK) {
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }
        if (clearance == ObstacleClearanceResult.PROTECTED_OBSTACLE
                || clearance == ObstacleClearanceResult.UNBREAKABLE_OBSTACLE) {
            localSortieQueue.pollFirst();
            releaseSegmentClaim(world, target, clearance == ObstacleClearanceResult.PROTECTED_OBSTACLE
                    ? "protected_obstacle"
                    : "unbreakable_obstacle");
            markSkippedSegment(target, clearance == ObstacleClearanceResult.PROTECTED_OBSTACLE
                    ? "protected_obstacle"
                    : "unbreakable_obstacle");
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }

        // Check we still have wall blocks in our chest
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            resetWaitForStockProceedLogState();
            stage = Stage.DONE;
            return;
        }

        int sortieThreshold = computeCurrentSortieThreshold(world);
        int availableWalls = countItemInChest(world, chestPos, Items.COBBLESTONE_WALL);
        if (availableWalls < sortieThreshold) {
            stage = Stage.WAIT_FOR_WALL_STOCK;
            return;
        }

        // Consume 1 unit of wall placement material from chest.
        WallPlacementMaterial placementMaterial = consumeWallMaterialFromChest(world, chestPos);
        if (placementMaterial == null) {
            resetWaitForStockProceedLogState();
            stage = Stage.DONE;
            return;
        }

        // Place the block
        world.setBlockState(target, placementMaterial.blockState());
        recordPlacementProgress(world);
        LOGGER.debug("MasonWallBuilder {}: placed {} at {}",
                guard.getUuidAsString(), placementMaterial.item().toString(), target.toShortString());

        localSortieQueue.pollFirst();
        releaseSegmentClaim(world, target, "placed");
        sortiePlacements++;
        placedSegments++;
        guard.setWallBuildPending(hasRemainingSegments(world), computeCurrentSortieThreshold(world));
        maybeLogPlacementProgress();
        stage = Stage.MOVE_TO_SEGMENT;
    }

    private void maybeLogPlacementProgress() {
        if (!isElectedBuilder || plannedPlacementCount <= 0) return;
        int placed = placedSegments;
        int total = plannedPlacementCount;
        if (placed >= total || placed <= 10 || placed - lastProgressLoggedSegmentCount >= 25) {
            lastProgressLoggedSegmentCount = placed;
            int percent = (int) Math.floor((placed * 100.0) / total);
            LOGGER.info("MasonWallBuilder {}: placement progress {}/{} ({}%)",
                    guard.getUuidAsString(), placed, total, percent);
        }
    }

    private ObstacleClearanceResult prepareSegmentForPlacement(ServerWorld world, BlockPos target) {
        int layer = getSegmentLayer(world, target);
        if (layer <= 0) {
            return ObstacleClearanceResult.UNBREAKABLE_OBSTACLE;
        }

        int topClearanceY = target.getY() + Math.max(0, 3 - layer);
        for (int y = target.getY(); y <= topClearanceY; y++) {
            BlockPos obstructionPos = new BlockPos(target.getX(), y, target.getZ());
            if (isPlacedWallBlock(world.getBlockState(obstructionPos))) {
                continue;
            }
            ObstacleClearanceResult result = clearObstacleAt(world, target, obstructionPos);
            if (result != ObstacleClearanceResult.CLEAR) {
                return result;
            }
        }
        return ObstacleClearanceResult.CLEAR;
    }

    private ObstacleClearanceResult clearObstacleAt(ServerWorld world, BlockPos segmentPos, BlockPos obstaclePos) {
        BlockState state = world.getBlockState(obstaclePos);
        if (state.isAir()) {
            return ObstacleClearanceResult.CLEAR;
        }
        if (state.getBlock() == Blocks.COBBLESTONE_WALL) {
            return ObstacleClearanceResult.CLEAR;
        }
        if (isProtectedObstacle(world, obstaclePos, state)) {
            protectedObstaclesSkipped++;
            return ObstacleClearanceResult.PROTECTED_OBSTACLE;
        }
        if (!isBreakableWallObstacle(world, obstaclePos, state)) {
            unbreakableObstaclesSkipped++;
            return ObstacleClearanceResult.UNBREAKABLE_OBSTACLE;
        }
        if (!canBreakObstacleThisTick(world)) {
            return ObstacleClearanceResult.CLEARED_THIS_TICK;
        }

        boolean broken = world.breakBlock(obstaclePos, true, guard);
        if (!broken && !world.isAir(obstaclePos)) {
            unbreakableObstaclesSkipped++;
            return ObstacleClearanceResult.UNBREAKABLE_OBSTACLE;
        }
        obstaclesCleared++;
        LOGGER.debug("MasonWallBuilder {}: cleared_obstacle segment={} obstacle={} block={}",
                guard.getUuidAsString(),
                segmentPos.toShortString(),
                obstaclePos.toShortString(),
                state.getBlock().getTranslationKey());
        return ObstacleClearanceResult.CLEARED_THIS_TICK;
    }

    private boolean isProtectedObstacle(ServerWorld world, BlockPos pos, BlockState state) {
        if (isBlacklistedObstacle(state)) {
            return true;
        }
        if (state.isOf(Blocks.STRUCTURE_BLOCK) || state.isOf(Blocks.STRUCTURE_VOID) || state.isOf(Blocks.JIGSAW)) {
            return true;
        }
        return cachedPoiFootprintSignature != null
                && cachedPoiFootprintSignature.protectedStructureColumns().contains(packXZ(pos.getX(), pos.getZ()));
    }

    private boolean isBlacklistedObstacle(BlockState state) {
        if (state.getBlock() instanceof ChestBlock) return true;
        if (state.getBlock() instanceof BedBlock) return true;
        if (state.getBlock() instanceof DoorBlock) return true;
        return state.isOf(Blocks.BARREL)
                || state.isOf(Blocks.LECTERN)
                || state.isOf(Blocks.CARTOGRAPHY_TABLE)
                || state.isOf(Blocks.SMITHING_TABLE)
                || state.isOf(Blocks.FLETCHING_TABLE)
                || state.isOf(Blocks.STONECUTTER)
                || state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.SMOKER)
                || state.isOf(Blocks.GRINDSTONE)
                || state.isOf(Blocks.LOOM)
                || state.isOf(Blocks.COMPOSTER);
    }

    private boolean isBreakableWallObstacle(ServerWorld world, BlockPos pos, BlockState state) {
        boolean isLeafOrLog = state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS);
        boolean isPickaxeBreakable = state.isIn(BlockTags.PICKAXE_MINEABLE);
        if (!isLeafOrLog && !isPickaxeBreakable) return false;
        return state.getHardness(world, pos) >= 0.0F;
    }

    private boolean canBreakObstacleThisTick(ServerWorld world) {
        long now = world.getTime();
        if (obstacleBreakTick != now) {
            obstacleBreakTick = now;
            obstacleBreaksThisTick = 0;
        }
        if (obstacleBreaksThisTick >= MAX_OBSTACLE_BREAKS_PER_TICK) {
            return false;
        }
        obstacleBreaksThisTick++;
        return true;
    }

    private void markSkippedSegment(BlockPos pos, String reason) {
        skippedSegments.add(pos);
        skippedSegmentReasons.put(pos.toImmutable(), reason);
    }

    private void pruneSortieQueue(ServerWorld world) {
        while (!localSortieQueue.isEmpty()) {
            BlockPos head = localSortieQueue.peekFirst();
            if (head == null || isGatePosition(head) || isPlacedWallBlock(world.getBlockState(head))) {
                BlockPos removed = localSortieQueue.pollFirst();
                if (removed != null) {
                    releaseSegmentClaim(world, removed, "queue_prune");
                }
            } else {
                break;
            }
        }
    }

    private boolean startNextSortie(ServerWorld world) {
        releaseLocalSortieClaims(world, "sortie_reset");
        localSortieQueue.clear();
        sortiePlacements = 0;
        sortieCandidatesConsidered = 0;
        sortieCandidatesAccepted = 0;
        sortieCandidatesPreflightPass = 0;
        sortieCandidatesPreflightRejected = 0;
        sortieCandidatesFallbackAccepted = 0;
        int activeLayer = findLowestPendingLayer(world);
        if (activeLayer < 1) {
            return false;
        }

        int anchorAttempts = 0;
        BlockPos selectedAnchor = null;
        List<BlockPos> selectedBatch = List.of();
        while (anchorAttempts < MAX_SORTIE_ANCHOR_ATTEMPTS_PER_TICK) {
            BlockPos anchor = findNextIndexAnchor(world, activeLayer);
            if (anchor == null) {
                break;
            }

            List<BlockPos> anchorBatch = buildLocalSortieCandidates(world, anchor, activeLayer);
            if (anchorBatch.size() < MIN_SEGMENTS_PER_SORTIE) {
                BlockPos nearest = findNearestUnbuiltSegment(world, guard.getBlockPos(), activeLayer);
                if (nearest != null && !nearest.equals(anchor)) {
                    List<BlockPos> nearestBatch = buildLocalSortieCandidates(world, nearest, activeLayer);
                    if (!nearestBatch.isEmpty()) {
                        anchor = nearest;
                        anchorBatch = nearestBatch;
                    }
                }
            }
            if (!anchorBatch.isEmpty()) {
                selectedAnchor = anchor;
                selectedBatch = anchorBatch;
                break;
            }
            markSkippedSegment(anchor, "anchor_no_candidates");
            anchorAttempts++;
        }
        if (selectedBatch.isEmpty() || selectedAnchor == null) {
            return false;
        }
        localSortieQueue.addAll(selectedBatch);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MasonWallBuilder {}: sortie_start_preflight_summary anchor={} layer={} considered={} accepted={} preflight_pass={} preflightRejected={} preflight_fail_fallback_accept={}",
                    guard.getUuidAsString(),
                    selectedAnchor.toShortString(),
                    activeLayer,
                    sortieCandidatesConsidered,
                    sortieCandidatesAccepted,
                    sortieCandidatesPreflightPass,
                    sortieCandidatesPreflightRejected,
                    sortieCandidatesFallbackAccepted);
        }
        sortieActive = true;
        sortieActiveLayer = activeLayer;
        sortieTransientRetriesAttempted = 0;
        sortieFallbackTargetsTried = 0;
        sortieHardUnreachableMarked = 0;
        consecutiveDeferredSweeps = 0;
        return true;
    }

    private BlockPos findNextIndexAnchor(ServerWorld world, int activeLayer) {
        if (!hardUnreachableRetryQueue.isEmpty()) {
            hardRetryPassStarted = true;
        }
        while (!hardUnreachableRetryQueue.isEmpty()) {
            BlockPos retryCandidate = hardUnreachableRetryQueue.pollFirst();
            if (retryCandidate == null) continue;
            skippedSegments.remove(retryCandidate);
            skippedSegmentReasons.remove(retryCandidate);
            if (getSegmentLayer(world, retryCandidate) == activeLayer && isBuildableCandidate(world, retryCandidate)) {
                return retryCandidate;
            }
        }
        while (currentSegmentIndex < pendingSegments.size()) {
            BlockPos candidate = pendingSegments.get(currentSegmentIndex++);
            if (getSegmentLayer(world, candidate) == activeLayer && isBuildableCandidate(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private BlockPos findNearestUnbuiltSegment(ServerWorld world, BlockPos from, int activeLayer) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : pendingSegments) {
            if (getSegmentLayer(world, pos) != activeLayer) continue;
            if (!isBuildableCandidate(world, pos)) continue;
            double distSq = from.getSquaredDistance(pos);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = pos;
            }
        }
        return best;
    }

    private List<BlockPos> buildLocalSortieCandidates(ServerWorld world, BlockPos anchor, int activeLayer) {
        int radiusSq = LOCAL_SORTIE_RADIUS * LOCAL_SORTIE_RADIUS;
        List<BlockPos> local = new ArrayList<>();
        for (BlockPos pos : pendingSegments) {
            if (getSegmentLayer(world, pos) != activeLayer) continue;
            if (!isBuildableCandidate(world, pos)) continue;
            if (anchor.getSquaredDistance(pos) <= radiusSq) {
                local.add(pos);
            }
        }
        local.sort(Comparator.comparingDouble(anchor::getSquaredDistance));
        List<BlockPos> bounded = local;
        if (local.size() > MAX_SEGMENTS_PER_SORTIE) {
            bounded = new ArrayList<>(local.subList(0, MAX_SEGMENTS_PER_SORTIE));
        }
        List<BlockPos> accepted = new ArrayList<>(bounded.size());
        List<BlockPos> preflightFailed = new ArrayList<>(bounded.size());
        for (BlockPos segment : bounded) {
            sortieCandidatesConsidered++;
            if (isSegmentClaimedByOther(world, segment)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("MasonWallBuilder {}: claim_conflict_skip segment={} anchor={} holder=other_guard",
                            guard.getUuidAsString(),
                            segment.toShortString(),
                            activeAnchorPos == null ? "none" : activeAnchorPos.toShortString());
                }
                continue;
            }
            if (!passesSortiePreflight(world, segment)) {
                sortieCandidatesPreflightRejected++;
                preflightFailed.add(segment);
                continue;
            }
            sortieCandidatesPreflightPass++;
            if (!tryClaimSegment(world, segment)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("MasonWallBuilder {}: claim_conflict_enqueue segment={} anchor={} holder=other_guard",
                            guard.getUuidAsString(),
                            segment.toShortString(),
                            activeAnchorPos == null ? "none" : activeAnchorPos.toShortString());
                }
                continue;
            }
            accepted.add(segment);
            sortieCandidatesAccepted++;
        }
        if (accepted.isEmpty() && !preflightFailed.isEmpty()) {
            int fallbackLimit = Math.min(MAX_SEGMENTS_PER_SORTIE, preflightFailed.size());
            for (BlockPos candidate : preflightFailed) {
                if (accepted.size() >= fallbackLimit) {
                    break;
                }
                if (isSegmentClaimedByOther(world, candidate)) {
                    continue;
                }
                if (!tryClaimSegment(world, candidate)) {
                    continue;
                }
                accepted.add(candidate);
                sortieCandidatesAccepted++;
                sortieCandidatesFallbackAccepted++;
            }
        }
        return accepted;
    }

    private boolean passesSortiePreflight(ServerWorld world, BlockPos segment) {
        BlockPos navigationTarget = resolveSegmentNavigationTarget(world, segment);
        if (navigationTarget == null) {
            return false;
        }
        if (!isStandable(world, navigationTarget)) {
            return false;
        }
        Path preflightPath = guard.getNavigation().findPathTo(navigationTarget, 0);
        // Path miss is a soft signal: fallback sortie admission and runtime retries
        // determine actual reachability while moving.
        return preflightPath != null && preflightPath.reachesTarget();
    }

    private int computeCurrentSortieThreshold(ServerWorld world) {
        int activeLayer = findLowestPendingLayer(world);
        if (activeLayer < 1) return 1;
        int remaining = 0;
        for (BlockPos pos : pendingSegments) {
            if (getSegmentLayer(world, pos) != activeLayer) continue;
            if (isBuildableCandidate(world, pos)) remaining++;
        }
        return Math.max(1, Math.min(MAX_SEGMENTS_PER_SORTIE, remaining));
    }

    private int findLowestPendingLayer(ServerWorld world) {
        int lowestLayer = Integer.MAX_VALUE;
        for (BlockPos pos : pendingSegments) {
            if (isBuildableCandidate(world, pos)) {
                int layer = getSegmentLayer(world, pos);
                if (layer > 0 && layer < lowestLayer) {
                    lowestLayer = layer;
                }
            }
        }
        return lowestLayer == Integer.MAX_VALUE ? -1 : lowestLayer;
    }

    private int getSegmentLayer(ServerWorld world, BlockPos pos) {
        int groundY = getPerimeterColumnGroundY(world, pos.getX(), pos.getZ());
        return pos.getY() - groundY;
    }

    private boolean hasRemainingSegments(ServerWorld world) {
        for (BlockPos pos : pendingSegments) {
            if (isBuildableCandidate(world, pos)) return true;
        }
        return false;
    }

    private boolean isBuildableCandidate(ServerWorld world, BlockPos pos) {
        return !isGatePosition(pos)
                && !isPlacedWallBlock(world.getBlockState(pos))
                && !skippedSegments.contains(pos);
    }

    private void runCompletionSweepAndFinish(ServerWorld world) {
        maybeRecoverStalledCycle(world, "completion_sweep");
        logSortieSummaryIfActive();
        releaseLocalSortieClaims(world, "completion_sweep");
        SweepSummary summary = runCompletionSweep(world);
        if (summary.filledDuringSweep > 0) {
            consecutiveDeferredSweeps = 0;
        }
        if (summary.remainingAfterSweep == 0) {
            completeCycle(CycleEndReason.WALL_COMPLETE);
            return;
        }

        if (!hasTerminalIrrecoverableReasons(summary.irrecoverableReasons)) {
            if (summary.deferredCount > 0 && summary.irrecoverableCount == 0) {
                consecutiveDeferredSweeps++;
                long backoffTicks = computeDeferredSweepBackoffTicks(consecutiveDeferredSweeps);
                nextCompletionSweepAllowedTick = world.getTime() + backoffTicks;
                LOGGER.debug("MasonWallBuilder {}: deferring completion sweep retry (deferred={}, streak={}, backoffTicks={}, retryAt={})",
                        guard.getUuidAsString(),
                        summary.deferredCount,
                        consecutiveDeferredSweeps,
                        backoffTicks,
                        nextCompletionSweepAllowedTick);
            }
            String sweepDeferredSignature = "deferred_resume|" + buildSweepLogSignature(
                    summary.remainingAfterSweep,
                    summary.filledDuringSweep,
                    summary.deferredCount,
                    summary.deferredReasons,
                    summary.irrecoverableCount,
                    summary.irrecoverableReasons
            );
            if (shouldEmitSweepInfoLog(world, sweepDeferredSignature)) {
                LOGGER.info("MasonWallBuilder {}: sweep deferred {} segments, resuming sorties",
                        guard.getUuidAsString(),
                        summary.remainingAfterSweep);
            }
            stage = canResumeMoveToSegmentFromSweep(world)
                    ? Stage.MOVE_TO_SEGMENT
                    : Stage.WAIT_FOR_WALL_STOCK;
            return;
        }

        LOGGER.warn("MasonWallBuilder {}: completion sweep unresolved segments remain={} deferred={} deferredReasons={} irrecoverable={} irrecoverableReasons={}",
                guard.getUuidAsString(),
                summary.remainingAfterSweep,
                summary.deferredCount,
                summary.deferredReasons,
                summary.irrecoverableCount,
                summary.irrecoverableReasons);
        completeCycle(CycleEndReason.UNREACHABLE_SEGMENTS);
    }

    private long computeDeferredSweepBackoffTicks(int deferredSweepCount) {
        if (deferredSweepCount <= 1) return 40L;
        if (deferredSweepCount == 2) return 100L;
        if (deferredSweepCount == 3) return 200L;
        return Math.min(600L, 200L + ((long) (deferredSweepCount - 3) * 100L));
    }

    private boolean canResumeMoveToSegmentFromSweep(ServerWorld world) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            return false;
        }
        int threshold = computeCurrentSortieThreshold(world);
        int availableWalls = countItemInChest(world, chestPos, Items.COBBLESTONE_WALL);
        int availableCobblestone = countItemInChest(world, chestPos, Items.COBBLESTONE);
        WaitForStockDecision decision = decideWaitForStockTransition(
                availableWalls,
                availableCobblestone,
                threshold
        );
        return decision == WaitForStockDecision.MOVE_TO_SEGMENT;
    }

    private boolean hasTerminalIrrecoverableReasons(Map<String, Integer> irrecoverableReasons) {
        if (irrecoverableReasons.isEmpty()) {
            return false;
        }
        for (String reason : irrecoverableReasons.keySet()) {
            if (!isDeferredReason(reason)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeferredReason(String reason) {
        return "out_of_reach".equals(reason) || "deferred_out_of_reach".equals(reason);
    }

    private SweepSummary runCompletionSweep(ServerWorld world) {
        List<BlockPos> remainingUnbuilt = findRemainingUnbuiltSegments(world);
        if (remainingUnbuilt.isEmpty()) {
            Map<String, Integer> noDeferredReasons = Map.of();
            Map<String, Integer> noIrrecoverableReasons = Map.of();
            String completionSignature = "completion_summary|" + buildSweepLogSignature(
                    0,
                    0,
                    0,
                    noDeferredReasons,
                    0,
                    noIrrecoverableReasons
            );
            if (shouldEmitSweepInfoLog(world, completionSignature)) {
                LOGGER.info("MasonWallBuilder {}: completion sweep summary before=0 filled=0 irrecoverable=0 reasons={}",
                        guard.getUuidAsString(), Map.of());
            }
            return new SweepSummary(0, 0, 0, Map.<String, Integer>of(), 0, Map.<String, Integer>of());
        }

        int filledDuringSweep = 0;
        int deferredCount = 0;
        Map<String, Integer> deferredReasons = new HashMap<>();
        int irrecoverableCount = 0;
        Map<String, Integer> irrecoverableReasons = new HashMap<>();
        BlockPos chestPos = guard.getPairedChestPos();

        for (BlockPos segment : remainingUnbuilt) {
            skippedSegments.remove(segment);
            skippedSegmentReasons.remove(segment);
            hardUnreachableRetryQueue.remove(segment);

            if (isPlacedWallBlock(world.getBlockState(segment))) {
                continue;
            }
            if (chestPos == null) {
                irrecoverableCount++;
                incrementReason(irrecoverableReasons, "missing_chest");
                continue;
            }

            boolean filled = false;
            boolean deferred = false;
            String failureReason = "placement_unresolved";
            for (int attempt = 1; attempt <= COMPLETION_SWEEP_MAX_RETRIES_PER_SEGMENT; attempt++) {
                if (isPlacedWallBlock(world.getBlockState(segment))) {
                    filled = true;
                    break;
                }

                boolean inReach = guard.squaredDistanceTo(
                        segment.getX() + 0.5, segment.getY() + 0.5, segment.getZ() + 0.5
                ) <= REACH_SQ;
                if (!inReach) {
                    BlockPos navigationTarget = resolveSegmentNavigationTarget(world, segment);
                    guard.getNavigation().startMovingTo(
                            navigationTarget.getX() + 0.5,
                            navigationTarget.getY() + 0.5,
                            navigationTarget.getZ() + 0.5,
                            MOVE_SPEED
                    );
                    deferredCount++;
                    incrementReason(deferredReasons, "out_of_reach");
                    deferred = true;
                    break;
                }

                ObstacleClearanceResult clearance = prepareSegmentForPlacement(world, segment);
                if (clearance == ObstacleClearanceResult.CLEARED_THIS_TICK) {
                    deferredCount++;
                    incrementReason(deferredReasons, "deferred_obstacle_clear");
                    deferred = true;
                    break;
                }
                if (clearance == ObstacleClearanceResult.PROTECTED_OBSTACLE) {
                    failureReason = "protected_obstacle";
                    break;
                }
                if (clearance == ObstacleClearanceResult.UNBREAKABLE_OBSTACLE) {
                    failureReason = "unbreakable_obstacle";
                    break;
                }

                WallPlacementMaterial material = consumeWallMaterialFromChest(world, chestPos);
                if (material == null) {
                    failureReason = "out_of_materials";
                    break;
                }

                world.setBlockState(segment, material.blockState());
                recordPlacementProgress(world);
                filled = true;
                filledDuringSweep++;
                break;
            }

            if (deferred) {
                continue;
            }
            if (!filled) {
                irrecoverableCount++;
                incrementReason(irrecoverableReasons, failureReason);
            }
        }

        int remainingAfterSweep = findRemainingUnbuiltSegments(world).size();
        String completionSignature = "completion_summary|" + buildSweepLogSignature(
                remainingAfterSweep,
                filledDuringSweep,
                deferredCount,
                deferredReasons,
                irrecoverableCount,
                irrecoverableReasons
        );
        if (shouldEmitSweepInfoLog(world, completionSignature)) {
            LOGGER.info("MasonWallBuilder {}: completion sweep summary before={} filled={} deferred={} deferredReasons={} irrecoverable={} irrecoverableReasons={}",
                    guard.getUuidAsString(),
                    remainingUnbuilt.size(),
                    filledDuringSweep,
                    deferredCount,
                    deferredReasons,
                    irrecoverableCount,
                    irrecoverableReasons);
        }

        return new SweepSummary(
                remainingAfterSweep,
                filledDuringSweep,
                deferredCount,
                Map.copyOf(deferredReasons),
                irrecoverableCount,
                Map.copyOf(irrecoverableReasons)
        );
    }

    private List<BlockPos> findRemainingUnbuiltSegments(ServerWorld world) {
        List<BlockPos> remaining = new ArrayList<>();
        for (BlockPos pos : pendingSegments) {
            if (isGatePosition(pos)) continue;
            if (isPlacedWallBlock(world.getBlockState(pos))) continue;
            remaining.add(pos);
        }
        return remaining;
    }

    private void incrementReason(Map<String, Integer> reasons, String reason) {
        reasons.merge(reason, 1, Integer::sum);
    }

    private String buildSweepLogSignature(
            int remainingCount,
            int filledCount,
            int deferredCount,
            Map<String, Integer> deferredReasons,
            int irrecoverableCount,
            Map<String, Integer> irrecoverableReasons
    ) {
        return "remaining=" + remainingCount
                + "|filled=" + filledCount
                + "|deferred=" + deferredCount
                + "|deferredReasonsHash=" + new TreeMap<>(deferredReasons).hashCode()
                + "|irrecoverable=" + irrecoverableCount
                + "|irrecoverableReasonsHash=" + new TreeMap<>(irrecoverableReasons).hashCode();
    }

    private boolean shouldEmitSweepInfoLog(ServerWorld world, String signature) {
        long now = world.getTime();
        boolean signatureChanged = !signature.equals(lastSweepLogSignature);
        boolean outsideRateLimitWindow = now - lastSweepLogTick >= SWEEP_LOG_RATE_LIMIT_TICKS;
        if (signatureChanged || outsideRateLimitWindow) {
            if (suppressedSweepInfoLogCount > 0) {
                LOGGER.debug("MasonWallBuilder {}: suppressed {} sweep INFO logs during rate-limit window",
                        guard.getUuidAsString(),
                        suppressedSweepInfoLogCount);
            }
            suppressedSweepInfoLogCount = 0;
            sweepInfoSuppressionDebugEmitted = false;
            lastSweepLogSignature = signature;
            lastSweepLogTick = now;
            return true;
        }

        suppressedSweepInfoLogCount++;
        if (!sweepInfoSuppressionDebugEmitted) {
            LOGGER.debug("MasonWallBuilder {}: suppressing repetitive sweep INFO logs (rateLimit={} ticks)",
                    guard.getUuidAsString(),
                    SWEEP_LOG_RATE_LIMIT_TICKS);
            sweepInfoSuppressionDebugEmitted = true;
        }
        return false;
    }

    private void completeCycle(CycleEndReason reason) {
        logSortieSummaryIfActive();
        cycleEndReason = reason;
        if (reason == CycleEndReason.WALL_COMPLETE
                && guard.getWorld() instanceof ServerWorld world
                && activeAnchorPos != null) {
            VillageWallProjectState.get(world.getServer()).markAllLayersComplete(world.getRegistryKey(), activeAnchorPos);
        }
        releaseAllSegmentClaims(worldOrNull());
        resetWaitForStockProceedLogState();
        stage = Stage.DONE;
        guard.clearWallSegments();
        guard.setWallBuildPending(false, 0);
        activeAnchorPos = null;
        cachedWallRect = null;
        cachedWallRectAnchor = null;
        cachedPoiFootprintSignature = null;
        cycleWallRect = null;
        LOGGER.info("MasonWallBuilder {}: build cycle ended reason={}", guard.getUuidAsString(), cycleEndReason.logValue);
    }

    private void resetWaitForStockProceedLogState() {
        waitForStockProceedLogged = false;
        lastLoggedProceedWalls = -1;
        lastLoggedProceedThreshold = -1;
    }

    private void recordPlacementProgress(ServerWorld world) {
        lastPlacementTick = world.getTime();
        placementsSinceCycleStart++;
    }

    private void maybeRecoverStalledCycle(ServerWorld world, String context) {
        if (!isElectedBuilder || stage == Stage.DONE || stage == Stage.IDLE) {
            return;
        }
        if (placementsSinceCycleStart <= 0 || !hasAnyWallMaterial(world)) {
            return;
        }
        if (lastPlacementTick < 0L || (world.getTime() - lastPlacementTick) < PROGRESS_WATCHDOG_TIMEOUT_TICKS) {
            return;
        }

        int activeLayer = findLowestPendingLayer(world);
        if (activeLayer < 1) {
            return;
        }

        int pendingLayerCount = 0;
        int buildableLayerCount = 0;
        int skippedLayerCount = 0;
        for (BlockPos segment : pendingSegments) {
            if (getSegmentLayer(world, segment) != activeLayer) continue;
            if (isGatePosition(segment) || isPlacedWallBlock(world.getBlockState(segment))) continue;
            pendingLayerCount++;
            if (skippedSegments.contains(segment)) {
                skippedLayerCount++;
                continue;
            }
            buildableLayerCount++;
        }

        skippedSegments.removeIf(segment -> getSegmentLayer(world, segment) == activeLayer);
        skippedSegmentReasons.entrySet().removeIf(entry -> getSegmentLayer(world, entry.getKey()) == activeLayer);
        hardUnreachableRetryQueue.clear();
        releaseLocalSortieClaims(world, "progress_watchdog_recover");
        localSortieQueue.clear();
        activeMoveTarget = null;
        activeMoveTargetTicks = 0;
        lastMoveDistSq = Double.MAX_VALUE;
        resetPathFailureState();
        sortiePlacements = MAX_SEGMENTS_PER_SORTIE;
        currentSegmentIndex = findNearestPendingSegmentIndex(world, guard.getBlockPos(), activeLayer);
        lastPlacementTick = world.getTime();

        LOGGER.warn("MasonWallBuilder {}: progress_watchdog_recovery context={} stalledTicks={} placementsSinceCycleStart={} layer={} pending={} buildable={} skipped={} resetIndex={}",
                guard.getUuidAsString(),
                context,
                PROGRESS_WATCHDOG_TIMEOUT_TICKS,
                placementsSinceCycleStart,
                activeLayer,
                pendingLayerCount,
                buildableLayerCount,
                skippedLayerCount,
                currentSegmentIndex);
    }

    private int findNearestPendingSegmentIndex(ServerWorld world, BlockPos from, int layer) {
        int nearestIndex = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos candidate = pendingSegments.get(i);
            if (getSegmentLayer(world, candidate) != layer) continue;
            if (isGatePosition(candidate) || isPlacedWallBlock(world.getBlockState(candidate))) continue;
            double distSq = from.getSquaredDistance(candidate);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private boolean hasAnyWallMaterial(ServerWorld world) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) return false;
        return countItemInChest(world, chestPos, Items.COBBLESTONE_WALL) > 0
                || countItemInChest(world, chestPos, Items.COBBLESTONE) > 0;
    }

    private boolean shouldEmitHardUnreachableInfo(ServerWorld world, BlockPos segmentTarget) {
        String key = buildRetryLogSignature(world, segmentTarget);
        long now = world.getTime();
        Long last = retryLogRateLimitTickBySignature.get(key);
        if (last != null && (now - last) < HARD_UNREACHABLE_LOG_RATE_LIMIT_TICKS) {
            return false;
        }
        retryLogRateLimitTickBySignature.put(key, now);
        return true;
    }

    private String buildRetryLogSignature(ServerWorld world, BlockPos segmentPos) {
        String anchor = activeAnchorPos == null ? "none" : activeAnchorPos.toShortString();
        int layer = getSegmentLayer(world, segmentPos);
        return guard.getUuidAsString() + "|" + anchor + "|layer=" + layer;
    }

    private void logSortieSummaryIfActive() {
        if (!sortieActive) return;
        LOGGER.info("MasonWallBuilder {}: sortie_end_summary anchor={} layer={} transientRetries={} fallbackTargets={} hard_unreachable_marked={} obstaclesCleared={} protectedObstaclesSkipped={} unbreakableObstaclesSkipped={}",
                guard.getUuidAsString(),
                activeAnchorPos == null ? "none" : activeAnchorPos.toShortString(),
                sortieActiveLayer,
                sortieTransientRetriesAttempted,
                sortieFallbackTargetsTried,
                sortieHardUnreachableMarked,
                obstaclesCleared,
                protectedObstaclesSkipped,
                unbreakableObstaclesSkipped);
        LOGGER.debug("MasonWallBuilder {}: sortie_preflight_summary anchor={} layer={} candidatesConsidered={} accepted={} preflight_pass={} preflightRejected={} preflight_fail_fallback_accept={} hard_unreachable_marked={}",
                guard.getUuidAsString(),
                activeAnchorPos == null ? "none" : activeAnchorPos.toShortString(),
                sortieActiveLayer,
                sortieCandidatesConsidered,
                sortieCandidatesAccepted,
                sortieCandidatesPreflightPass,
                sortieCandidatesPreflightRejected,
                sortieCandidatesFallbackAccepted,
                sortieHardUnreachableMarked);
        sortieActive = false;
        sortieActiveLayer = -1;
        sortieTransientRetriesAttempted = 0;
        sortieFallbackTargetsTried = 0;
        sortieHardUnreachableMarked = 0;
        sortieCandidatesConsidered = 0;
        sortieCandidatesAccepted = 0;
        sortieCandidatesPreflightPass = 0;
        sortieCandidatesPreflightRejected = 0;
        sortieCandidatesFallbackAccepted = 0;
        obstaclesCleared = 0;
        protectedObstaclesSkipped = 0;
        unbreakableObstaclesSkipped = 0;
    }

    private boolean isSegmentClaimedByOther(ServerWorld world, BlockPos segment) {
        if (activeAnchorPos == null) {
            return false;
        }
        VillageWallProjectState state = VillageWallProjectState.get(world.getServer());
        return state.isSegmentClaimedByOther(
                world.getRegistryKey(),
                activeAnchorPos,
                segment,
                guard.getUuid(),
                world.getTime()
        );
    }

    private boolean tryClaimSegment(ServerWorld world, BlockPos segment) {
        if (activeAnchorPos == null) {
            return true;
        }
        VillageWallProjectState state = VillageWallProjectState.get(world.getServer());
        boolean claimed = state.claimSegment(
                world.getRegistryKey(),
                activeAnchorPos,
                segment,
                guard.getUuid(),
                world.getTime(),
                SEGMENT_CLAIM_DURATION_TICKS
        );
        if (claimed) {
            claimedSortieSegments.add(segment.toImmutable());
        }
        return claimed;
    }

    private void releaseSegmentClaim(ServerWorld world, BlockPos segment, String reason) {
        if (activeAnchorPos == null || segment == null) {
            return;
        }
        if (!claimedSortieSegments.remove(segment)) {
            return;
        }
        VillageWallProjectState.get(world.getServer()).releaseSegmentClaim(
                world.getRegistryKey(),
                activeAnchorPos,
                segment,
                guard.getUuid()
        );
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MasonWallBuilder {}: segment_claim_release segment={} reason={}",
                    guard.getUuidAsString(), segment.toShortString(), reason);
        }
    }

    private void releaseLocalSortieClaims(ServerWorld world, String reason) {
        if (claimedSortieSegments.isEmpty()) {
            return;
        }
        List<BlockPos> releaseTargets = new ArrayList<>(claimedSortieSegments);
        for (BlockPos claimed : releaseTargets) {
            releaseSegmentClaim(world, claimed, reason);
        }
    }

    private void releaseAllSegmentClaims(ServerWorld world) {
        if (world == null || activeAnchorPos == null) {
            return;
        }
        VillageWallProjectState.get(world.getServer()).releaseClaimsForGuard(
                world.getRegistryKey(),
                activeAnchorPos,
                guard.getUuid()
        );
    }

    private ServerWorld worldOrNull() {
        return guard.getWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
    }

    // -------------------------------------------------------------------------
    // Wall geometry
    // -------------------------------------------------------------------------

    /**
     * Scans the configured POI subset within BELL_EFFECT_RANGE of the QM chest anchor,
     * computes their bounding box, applies configurable expansion/caps, and returns a rectangle.
     */
    private Optional<PoiFootprintSignature> computePoiFootprintSignature(ServerWorld world, BlockPos anchorPos) {
        int range = VillageGuardStandManager.BELL_EFFECT_RANGE;
        Box searchBox = new Box(anchorPos).expand(range);

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int occupancyMinX = Integer.MAX_VALUE, occupancyMinZ = Integer.MAX_VALUE;
        int occupancyMaxX = Integer.MIN_VALUE, occupancyMaxZ = Integer.MIN_VALUE;
        int count = 0;
        int hash = 1;
        int occupancyHash = 1;
        int occupancyCount = 0;
        boolean found = false;
        Set<Long> protectedStructureColumns = new HashSet<>();

        GuardVillagersConfig.MasonWallPoiMode poiMode = resolveWallPoiMode();

        // Scan configured POI set via the POI storage. This is O(POI count), not O(volume).
        PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        Stream<BlockPos> poiStream = poiStorage.getInSquare(
                type -> switch (poiMode) {
                    case JOB_SITES_ONLY -> type.isIn(PointOfInterestTypeTags.ACQUIRABLE_JOB_SITE);
                    case JOBS_AND_BEDS -> type.isIn(PointOfInterestTypeTags.ACQUIRABLE_JOB_SITE) || type.matchesKey(PointOfInterestTypes.HOME);
                    case ALL_POIS -> true;
                },
                anchorPos,
                range,
                PointOfInterestStorage.OccupationStatus.ANY
        ).map(poi -> poi.getPos());

        for (BlockPos pos : (Iterable<BlockPos>) poiStream::iterator) {
            if (!searchBox.contains(pos.getX(), pos.getY(), pos.getZ())) continue;
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
            count++;
            int posHash = 31 * (31 * pos.getX() + pos.getY()) + pos.getZ();
            hash = 31 * hash + posHash;
            found = true;
            if (pos.getX() < occupancyMinX) occupancyMinX = pos.getX();
            if (pos.getX() > occupancyMaxX) occupancyMaxX = pos.getX();
            if (pos.getZ() < occupancyMinZ) occupancyMinZ = pos.getZ();
            if (pos.getZ() > occupancyMaxZ) occupancyMaxZ = pos.getZ();

            for (BlockPos sample : BlockPos.iterate(
                    pos.add(-STRUCTURE_SAMPLE_HORIZONTAL_RADIUS, -STRUCTURE_SAMPLE_VERTICAL_RADIUS, -STRUCTURE_SAMPLE_HORIZONTAL_RADIUS),
                    pos.add(STRUCTURE_SAMPLE_HORIZONTAL_RADIUS, STRUCTURE_SAMPLE_VERTICAL_RADIUS, STRUCTURE_SAMPLE_HORIZONTAL_RADIUS))) {
                if (!searchBox.contains(sample.getX(), sample.getY(), sample.getZ())) continue;
                BlockState sampleState = world.getBlockState(sample);
                if (!isVillageStructureBlock(sampleState)) continue;
                if (sample.getX() < occupancyMinX) occupancyMinX = sample.getX();
                if (sample.getX() > occupancyMaxX) occupancyMaxX = sample.getX();
                if (sample.getZ() < occupancyMinZ) occupancyMinZ = sample.getZ();
                if (sample.getZ() > occupancyMaxZ) occupancyMaxZ = sample.getZ();
                long packedColumn = packXZ(sample.getX(), sample.getZ());
                if (protectedStructureColumns.add(packedColumn)) {
                    occupancyCount++;
                    occupancyHash = 31 * occupancyHash + Long.hashCode(packedColumn);
                }
            }
        }

        if (!found) {
            return Optional.empty();
        }

        int mergedMinX = Math.min(minX, occupancyMinX);
        int mergedMinZ = Math.min(minZ, occupancyMinZ);
        int mergedMaxX = Math.max(maxX, occupancyMaxX);
        int mergedMaxZ = Math.max(maxZ, occupancyMaxZ);

        return Optional.of(new PoiFootprintSignature(
                mergedMinX,
                mergedMinZ,
                mergedMaxX,
                mergedMaxZ,
                count + occupancyCount,
                31 * hash + occupancyHash,
                protectedStructureColumns
        ));
    }

    private WallRect computeWallRect(BlockPos anchorPos, PoiFootprintSignature signature) {
        int occupancyMinX = signature.minX();
        int occupancyMinZ = signature.minZ();
        int occupancyMaxX = signature.maxX();
        int occupancyMaxZ = signature.maxZ();
        int footprintWidth = occupancyMaxX - occupancyMinX + 1;
        int footprintDepth = occupancyMaxZ - occupancyMinZ + 1;
        int configuredExpand = Math.max(0, GuardVillagersConfig.masonWallExpandBlocks);
        int baseMargin = Math.max(STRUCTURE_MIN_ENCLOSURE_MARGIN, configuredExpand);
        int expandX = adjustedAxisExpansion(baseMargin, footprintWidth);
        int expandZ = adjustedAxisExpansion(baseMargin, footprintDepth);
        expandX = clampExpansionForMaxSize(footprintWidth, expandX, GuardVillagersConfig.masonWallMaxWidth);
        expandZ = clampExpansionForMaxSize(footprintDepth, expandZ, GuardVillagersConfig.masonWallMaxDepth);

        int minX = occupancyMinX - expandX;
        int minZ = occupancyMinZ - expandZ;
        int maxX = occupancyMaxX + expandX;
        int maxZ = occupancyMaxZ + expandZ;

        int expandNorth = 0;
        int expandSouth = 0;
        int expandWest = 0;
        int expandEast = 0;

        int maxSidePush = Math.max(0, STRUCTURE_PROTECTION_EXPANSION_CAP);
        Set<Long> protectedColumns = signature.protectedStructureColumns();
        while (intersectsProtectedColumns(minX, minZ, maxX, maxZ, protectedColumns) && maxSidePush > 0) {
            boolean changed = false;
            if (edgeOverlapsProtectedColumns(minX, minZ, maxX, Direction.NORTH, protectedColumns) && expandNorth < STRUCTURE_PROTECTION_EXPANSION_CAP) {
                minZ -= 1;
                expandNorth++;
                maxSidePush--;
                changed = true;
            }
            if (edgeOverlapsProtectedColumns(minX, maxZ, maxX, Direction.SOUTH, protectedColumns) && expandSouth < STRUCTURE_PROTECTION_EXPANSION_CAP) {
                maxZ += 1;
                expandSouth++;
                maxSidePush--;
                changed = true;
            }
            if (edgeOverlapsProtectedColumns(minZ, minX, maxZ, Direction.WEST, protectedColumns) && expandWest < STRUCTURE_PROTECTION_EXPANSION_CAP) {
                minX -= 1;
                expandWest++;
                maxSidePush--;
                changed = true;
            }
            if (edgeOverlapsProtectedColumns(minZ, maxX, maxZ, Direction.EAST, protectedColumns) && expandEast < STRUCTURE_PROTECTION_EXPANSION_CAP) {
                maxX += 1;
                expandEast++;
                maxSidePush--;
                changed = true;
            }
            if (!changed) {
                break;
            }
        }

        if (GuardVillagersConfig.masonWallMaxWidth > 0) {
            int allowedHalfSpan = Math.max(0, (GuardVillagersConfig.masonWallMaxWidth - footprintWidth) / 2);
            minX = Math.max(minX, occupancyMinX - allowedHalfSpan);
            maxX = Math.min(maxX, occupancyMaxX + allowedHalfSpan);
        }
        if (GuardVillagersConfig.masonWallMaxDepth > 0) {
            int allowedHalfSpan = Math.max(0, (GuardVillagersConfig.masonWallMaxDepth - footprintDepth) / 2);
            minZ = Math.max(minZ, occupancyMinZ - allowedHalfSpan);
            maxZ = Math.min(maxZ, occupancyMaxZ + allowedHalfSpan);
        }

        int wallY = anchorPos.getY(); // wall is at anchor Y level
        LOGGER.info(
                "MasonWallBuilder {}: wall rectangle finalized [x:{}..{} z:{}..{} y:{}], side_expansions(north={}, south={}, west={}, east={}), occupancy_bounds=[x:{}..{} z:{}..{}]",
                guard.getUuidAsString(),
                minX, maxX, minZ, maxZ, wallY,
                expandNorth, expandSouth, expandWest, expandEast,
                occupancyMinX, occupancyMaxX, occupancyMinZ, occupancyMaxZ
        );

        return new WallRect(
                minX,
                minZ,
                maxX,
                maxZ,
                wallY
        );
    }

    private boolean intersectsProtectedColumns(int minX, int minZ, int maxX, int maxZ, Set<Long> protectedColumns) {
        if (protectedColumns.isEmpty()) return false;
        for (int x = minX; x <= maxX; x++) {
            if (protectedColumns.contains(packXZ(x, minZ)) || protectedColumns.contains(packXZ(x, maxZ))) {
                return true;
            }
        }
        for (int z = minZ; z <= maxZ; z++) {
            if (protectedColumns.contains(packXZ(minX, z)) || protectedColumns.contains(packXZ(maxX, z))) {
                return true;
            }
        }
        return false;
    }

    private boolean edgeOverlapsProtectedColumns(int minAxis, int fixedAxis, int maxAxis, Direction edgeDirection, Set<Long> protectedColumns) {
        for (int axis = minAxis; axis <= maxAxis; axis++) {
            long packed = switch (edgeDirection) {
                case NORTH, SOUTH -> packXZ(axis, fixedAxis);
                case EAST, WEST -> packXZ(fixedAxis, axis);
                default -> Long.MIN_VALUE;
            };
            if (protectedColumns.contains(packed)) {
                return true;
            }
        }
        return false;
    }

    private long packXZ(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private boolean isVillageStructureBlock(BlockState state) {
        if (state.isAir()) return false;
        if (state.isIn(BlockTags.LEAVES)
                || state.isIn(BlockTags.FLOWERS)
                || state.isIn(BlockTags.SAPLINGS)
                || state.isIn(BlockTags.REPLACEABLE_BY_TREES)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.PODZOL)
                || state.isOf(Blocks.MYCELIUM)
                || state.isOf(Blocks.STONE)
                || state.isOf(Blocks.ANDESITE)
                || state.isOf(Blocks.DIORITE)
                || state.isOf(Blocks.GRANITE)
                || state.isOf(Blocks.DEEPSLATE)
                || state.isOf(Blocks.SAND)
                || state.isOf(Blocks.RED_SAND)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.CLAY)
                || state.isOf(Blocks.WATER)
                || state.isOf(Blocks.LAVA)) {
            return false;
        }

        if (state.isIn(BlockTags.PLANKS)
                || state.isIn(BlockTags.LOGS)
                || state.isIn(BlockTags.STAIRS)
                || state.isIn(BlockTags.SLABS)
                || state.isIn(BlockTags.DOORS)
                || state.isIn(BlockTags.FENCES)
                || state.isIn(BlockTags.FENCE_GATES)
                || state.isIn(BlockTags.WALLS)
                || state.isOf(Blocks.STONE_BRICKS)
                || state.isOf(Blocks.MOSSY_STONE_BRICKS)
                || state.isOf(Blocks.CRACKED_STONE_BRICKS)
                || state.isOf(Blocks.CHISELED_STONE_BRICKS)
                || state.isOf(Blocks.GLASS)
                || state.isOf(Blocks.WHITE_STAINED_GLASS)) {
            return true;
        }

        return state.blocksMovement();
    }

    private int adjustedAxisExpansion(int configuredExpand, int footprintAxisSize) {
        if (configuredExpand <= 0) {
            return 0;
        }
        if (footprintAxisSize <= 12) {
            return Math.max(1, configuredExpand - 2);
        }
        if (footprintAxisSize <= 24) {
            return Math.max(1, configuredExpand - 1);
        }
        return configuredExpand;
    }

    private int clampExpansionForMaxSize(int footprintAxisSize, int expansion, int maxAxisSize) {
        if (maxAxisSize <= 0) {
            return expansion;
        }
        int maxExpansion = Math.max(0, (maxAxisSize - footprintAxisSize) / 2);
        return Math.max(0, Math.min(expansion, maxExpansion));
    }

    private GuardVillagersConfig.MasonWallPoiMode resolveWallPoiMode() {
        GuardVillagersConfig.MasonWallPoiMode configured = GuardVillagersConfig.masonWallPoiMode;
        return configured != null ? configured : GuardVillagersConfig.MasonWallPoiMode.JOBS_AND_BEDS;
    }

    /**
     * Returns deterministic 3-layer wall placements around the rectangle perimeter.
     * Each perimeter X/Z column uses its own local terrain baseline:
     * groundY+1, groundY+2, and groundY+3.
     * Layers are appended in that order so planning remains layer-first.
     */
    private List<BlockPos> computeWallSegments(ServerWorld world, WallRect rect) {
        List<BlockPos> segments = new ArrayList<>();
        List<BlockPos> perimeterColumns = computePerimeterTraversal(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
        Map<BlockPos, Integer> groundByColumn = new HashMap<>(perimeterColumns.size());

        for (BlockPos column : perimeterColumns) {
            groundByColumn.put(column, getPerimeterColumnGroundY(world, column.getX(), column.getZ()));
        }

        for (int layer = 1; layer <= 3; layer++) {
            for (BlockPos perimeterColumn : perimeterColumns) {
                int groundY = groundByColumn.get(perimeterColumn);
                BlockPos targetPos = new BlockPos(perimeterColumn.getX(), groundY + layer, perimeterColumn.getZ());
                if (world.getBlockState(targetPos).isOf(Blocks.DIRT_PATH)) continue;
                if (world.getBlockState(targetPos).isOf(Blocks.COBBLESTONE)) continue;
                segments.add(targetPos.toImmutable());
            }
        }

        logPerimeterSampleColumns(world, perimeterColumns, groundByColumn);

        // Ensure at least 1 forced gap (remove last segment if the wall would be fully closed)
        if (!segments.isEmpty()) {
            int perimeterTotal = 2 * (rect.maxX() - rect.minX() + rect.maxZ() - rect.minZ()) * 3;
            if (segments.size() >= perimeterTotal) {
                segments.remove(segments.size() - 1);
            }
        }

        return segments;
    }

    private int getPerimeterColumnGroundY(ServerWorld world, int x, int z) {
        return world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private void logPerimeterSampleColumns(ServerWorld world, List<BlockPos> perimeterColumns, Map<BlockPos, Integer> groundByColumn) {
        if (!LOGGER.isDebugEnabled() || perimeterColumns.isEmpty()) return;
        int sampleCount = Math.min(5, perimeterColumns.size());
        for (int i = 0; i < sampleCount; i++) {
            BlockPos column = perimeterColumns.get(i);
            int groundY = groundByColumn.getOrDefault(column, getPerimeterColumnGroundY(world, column.getX(), column.getZ()));
            LOGGER.debug("MasonWallBuilder {}: terrain_relative_column_sample column={} groundY={} targets=[{},{},{}]",
                    guard.getUuidAsString(),
                    column.toShortString(),
                    groundY,
                    groundY + 1,
                    groundY + 2,
                    groundY + 3);
        }
    }

    static List<BlockPos> computePerimeterTraversal(int minX, int minZ, int maxX, int maxZ) {
        List<BlockPos> perimeter = new ArrayList<>();

        // North face: minX -> maxX at minZ
        for (int x = minX; x <= maxX; x++) {
            perimeter.add(new BlockPos(x, 0, minZ));
        }

        // East face: minZ+1 -> maxZ-1 at maxX (corners already included)
        for (int z = minZ + 1; z < maxZ; z++) {
            perimeter.add(new BlockPos(maxX, 0, z));
        }

        // South face: maxX -> minX at maxZ
        for (int x = maxX; x >= minX; x--) {
            perimeter.add(new BlockPos(x, 0, maxZ));
        }

        // West face: maxZ-1 -> minZ+1 at minX (corners already included)
        for (int z = maxZ - 1; z > minZ; z--) {
            perimeter.add(new BlockPos(minX, 0, z));
        }

        return perimeter;
    }

    static TraversalSimulationResult simulateSequentialTraversal(List<BlockPos> orderedSegments, BlockPos startPos) {
        if (orderedSegments.isEmpty()) return new TraversalSimulationResult(0.0D, 0, 0);
        double travel = 0.0D;
        BlockPos current = startPos;
        for (BlockPos segment : orderedSegments) {
            travel += manhattan2d(current, segment);
            current = segment;
        }
        return new TraversalSimulationResult(travel / orderedSegments.size(), orderedSegments.size(), orderedSegments.size());
    }

    static TraversalSimulationResult simulateBatchedTraversal(List<BlockPos> orderedSegments, BlockPos startPos) {
        if (orderedSegments.isEmpty()) return new TraversalSimulationResult(0.0D, 0, 0);

        List<BlockPos> remaining = new ArrayList<>(orderedSegments);
        BlockPos current = startPos;
        double travel = 0.0D;
        int placed = 0;
        int sortiesMeetingMin = 0;
        int sortiesWithMinCandidates = 0;
        int index = 0;
        int radiusSq = LOCAL_SORTIE_RADIUS * LOCAL_SORTIE_RADIUS;

        while (!remaining.isEmpty()) {
            if (index >= remaining.size()) index = 0;
            BlockPos anchor = remaining.get(index);
            List<BlockPos> local = buildSimulationLocalBatch(remaining, anchor, radiusSq);
            if (local.size() < MIN_SEGMENTS_PER_SORTIE) {
                anchor = findNearestSimulationCandidate(remaining, current);
                local = buildSimulationLocalBatch(remaining, anchor, radiusSq);
            }
            if (local.size() >= MIN_SEGMENTS_PER_SORTIE) {
                sortiesWithMinCandidates++;
            }
            int batchSize = Math.min(local.size(), MAX_SEGMENTS_PER_SORTIE);
            if (batchSize >= MIN_SEGMENTS_PER_SORTIE) sortiesMeetingMin++;
            for (int i = 0; i < batchSize; i++) {
                BlockPos target = local.get(i);
                travel += manhattan2d(current, target);
                current = target;
                remaining.remove(target);
                placed++;
            }
        }
        return new TraversalSimulationResult(travel / Math.max(1, placed), sortiesMeetingMin, sortiesWithMinCandidates);
    }

    static PathRetrySimulationResult simulatePathRetryPolicy(List<Boolean> pathStartSuccessPerTick,
                                                             int maxAttempts,
                                                             int retryWindowTicks) {
        int failedAttempts = 0;
        long firstFailureTick = -1L;
        for (int tick = 0; tick < pathStartSuccessPerTick.size(); tick++) {
            if (pathStartSuccessPerTick.get(tick)) {
                return new PathRetrySimulationResult(false, tick + 1, failedAttempts);
            }
            if (firstFailureTick < 0L) {
                firstFailureTick = tick;
            }
            failedAttempts++;
            if (failedAttempts >= maxAttempts || (tick - firstFailureTick) >= retryWindowTicks) {
                return new PathRetrySimulationResult(true, tick + 1, failedAttempts);
            }
        }
        return new PathRetrySimulationResult(false, pathStartSuccessPerTick.size(), failedAttempts);
    }

    static WaitForStockDecision decideWaitForStockTransition(int availableWalls,
                                                            int availableCobblestone,
                                                            int threshold) {
        if (availableWalls >= threshold) {
            return WaitForStockDecision.MOVE_TO_SEGMENT;
        }
        if (availableCobblestone > 0) {
            return WaitForStockDecision.WAIT_FOR_CONVERSION;
        }
        return WaitForStockDecision.DONE;
    }

    static ElectionDecision electBuilderCandidate(List<ElectionCandidateSnapshot> candidates) {
        ElectionCandidateSnapshot elected = null;
        List<ElectionCandidateSnapshot> excluded = new ArrayList<>();
        for (ElectionCandidateSnapshot candidate : candidates) {
            if (!candidate.hasPairedChest() || !candidate.hasPairedJob()) {
                excluded.add(candidate);
                continue;
            }
            if (elected == null || candidate.stoneCount() > elected.stoneCount()) {
                elected = candidate;
            }
        }
        return new ElectionDecision(
                elected == null ? null : elected.candidateId(),
                elected == null ? -1 : elected.stoneCount(),
                excluded,
                elected == null
        );
    }

    private static boolean shouldMarkHardUnreachable(int failedAttempts, long firstFailureTick, long currentTick) {
        if (firstFailureTick < 0L) return false;
        return failedAttempts >= PATH_RETRY_MAX_ATTEMPTS || (currentTick - firstFailureTick) >= PATH_RETRY_WINDOW_TICKS;
    }

    private static List<BlockPos> buildSimulationLocalBatch(List<BlockPos> remaining, BlockPos anchor, int radiusSq) {
        List<BlockPos> local = new ArrayList<>();
        for (BlockPos candidate : remaining) {
            if (anchor.getSquaredDistance(candidate) <= radiusSq) local.add(candidate);
        }
        local.sort(Comparator.comparingDouble(anchor::getSquaredDistance));
        return local;
    }

    private static BlockPos findNearestSimulationCandidate(List<BlockPos> remaining, BlockPos from) {
        BlockPos best = remaining.get(0);
        int bestDist = Integer.MAX_VALUE;
        for (BlockPos candidate : remaining) {
            int dist = manhattan2d(from, candidate);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    private static int manhattan2d(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    static net.minecraft.item.Item selectPlacementItemForCounts(int wallCount, int cobblestoneCount) {
        if (wallCount > 0) {
            return Items.COBBLESTONE_WALL;
        }
        if (ALLOW_COBBLESTONE_PLACEMENT_FALLBACK && cobblestoneCount > 0) {
            return Items.COBBLESTONE;
        }
        return null;
    }

    /**
     * Picks one gate position per wall face (N/S/E/W) — the unbuilt segment closest to the
     * midpoint of each face. Gate reservation is tracked per X/Z column and applies across
     * all 3 planned layers.
     *
     * These are stored on the guard entity for lumberjack fence gate placement later.
     */
    private List<BlockPos> pickGatePositions(WallRect rect, List<BlockPos> unbuiltSegments) {
        List<BlockPos> gates = new ArrayList<>();

        // Midpoint X and Z coordinates for each face
        int northMidX = (rect.minX() + rect.maxX()) / 2;
        int southMidX = northMidX;
        int westMidZ  = (rect.minZ() + rect.maxZ()) / 2;
        int eastMidZ  = westMidZ;

        // For each face, find the segment whose (X,Z) is closest to the face midpoint
        BlockPos northGate = findClosestSegmentOnFace(unbuiltSegments, northMidX, rect.minZ(), true);
        BlockPos southGate = findClosestSegmentOnFace(unbuiltSegments, southMidX, rect.maxZ(), true);
        BlockPos westGate  = findClosestSegmentOnFace(unbuiltSegments, rect.minX(), westMidZ,  false);
        BlockPos eastGate  = findClosestSegmentOnFace(unbuiltSegments, rect.maxX(), eastMidZ,  false);

        if (northGate != null) gates.add(northGate);
        if (southGate != null) gates.add(southGate);
        if (westGate  != null) gates.add(westGate);
        if (eastGate  != null) gates.add(eastGate);

        return gates;
    }

    /**
     * Finds the segment in {@code segments} that lies on the face defined by the fixed
     * coordinate ({@code fixedZ} when {@code fixedIsZ} is true, otherwise {@code fixedX})
     * and whose variable coordinate is closest to the midpoint value.
     *
     * @param segments    list of unbuilt wall segment positions
     * @param midX        target X coordinate (used as midpoint when fixedIsZ = true)
     * @param midZ        target Z coordinate (used as midpoint when fixedIsZ = false)
     * @param fixedIsZ    true → match segments where Z == midZ; false → match where X == midX
     */
    private BlockPos findClosestSegmentOnFace(List<BlockPos> segments, int midX, int midZ, boolean fixedIsZ) {
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (BlockPos pos : segments) {
            if (fixedIsZ) {
                if (pos.getZ() != midZ) continue;
                int dist = Math.abs(pos.getX() - midX);
                if (dist < bestDist) { bestDist = dist; best = pos; }
            } else {
                if (pos.getX() != midX) continue;
                int dist = Math.abs(pos.getZ() - midZ);
                if (dist < bestDist) { bestDist = dist; best = pos; }
            }
        }
        return best;
    }

    private boolean isGatePosition(BlockPos pos) {
        List<BlockPos> gates = guard.getWallGatePositions();
        if (gates == null || gates.isEmpty()) return false;
        for (BlockPos gate : gates) {
            if (isSameColumn(pos, gate)) return true;
        }
        return false;
    }

    private boolean isGateColumnPosition(BlockPos pos, Set<BlockPos> gates) {
        for (BlockPos gate : gates) {
            if (isSameColumn(pos, gate)) return true;
        }
        return false;
    }

    private boolean isSameColumn(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    // -------------------------------------------------------------------------
    // Peer mason utilities
    // -------------------------------------------------------------------------

    private List<MasonGuardEntity> getPeerMasons(ServerWorld world, BlockPos anchorPos) {
        Box searchBox = new Box(anchorPos).expand(PEER_SCAN_RANGE);
        return world.getEntitiesByClass(MasonGuardEntity.class, searchBox,
                mason -> mason.isAlive() && mason.getPairedChestPos() != null);
    }

    // -------------------------------------------------------------------------
    // Inventory utilities
    // -------------------------------------------------------------------------

    private int countWallMaterialUnitsInChest(ServerWorld world, BlockPos chestPos) {
        Optional<Inventory> inv = getInventory(world, chestPos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        Inventory inventory = inv.get();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && isWallMaterialItem(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countItemInChest(ServerWorld world, BlockPos chestPos, net.minecraft.item.Item item) {
        Optional<Inventory> inv = getInventory(world, chestPos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        Inventory inventory = inv.get();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private WallPlacementMaterial consumeWallMaterialFromChest(ServerWorld world, BlockPos chestPos) {
        Optional<Inventory> inv = getInventory(world, chestPos);
        if (inv.isEmpty()) return null;
        Inventory inventory = inv.get();
        WallPlacementMaterial[] priority = ALLOW_COBBLESTONE_PLACEMENT_FALLBACK
                ? WallPlacementMaterial.values()
                : new WallPlacementMaterial[]{WallPlacementMaterial.COBBLESTONE_WALL};
        for (WallPlacementMaterial material : priority) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (!stack.isEmpty() && stack.isOf(material.item())) {
                    stack.decrement(1);
                    if (stack.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
                    inventory.markDirty();
                    return material;
                }
            }
        }
        return null;
    }

    private void transferCobblestone(ServerWorld world, BlockPos sourcePos, BlockPos destPos, int maxAmount) {
        Optional<Inventory> srcOpt = getInventory(world, sourcePos);
        Optional<Inventory> dstOpt = getInventory(world, destPos);
        if (srcOpt.isEmpty() || dstOpt.isEmpty()) return;

        Inventory src = srcOpt.get();
        Inventory dst = dstOpt.get();
        int transferred = 0;

        for (int i = 0; i < src.size() && transferred < maxAmount; i++) {
            ItemStack stack = src.getStack(i);
            if (stack.isEmpty() || !isWallMaterialItem(stack.getItem())) continue;

            int toMove = Math.min(stack.getCount(), maxAmount - transferred);
            ItemStack moving = stack.copyWithCount(toMove);
            ItemStack remainder = insertIntoInventory(dst, moving);
            int moved = toMove - remainder.getCount();

            if (moved > 0) {
                stack.decrement(moved);
                if (stack.isEmpty()) src.setStack(i, ItemStack.EMPTY);
                transferred += moved;
            }
        }

        if (transferred > 0) {
            src.markDirty();
            dst.markDirty();
            LOGGER.debug("MasonWallBuilder {}: transferred {} wall material from {} to {}",
                    guard.getUuidAsString(), transferred, sourcePos.toShortString(), destPos.toShortString());
        }
    }

    private boolean isPlacedWallBlock(BlockState state) {
        return state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.COBBLESTONE_WALL);
    }

    private boolean isWallMaterialItem(net.minecraft.item.Item item) {
        return item == Items.COBBLESTONE || item == Items.COBBLESTONE_WALL;
    }

    private ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing.isEmpty()) {
                if (!inventory.isValid(i, remaining)) continue;
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                inventory.setStack(i, remaining.copyWithCount(moved));
                remaining.decrement(moved);
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining) && inventory.isValid(i, remaining)) {
                int space = existing.getMaxCount() - existing.getCount();
                if (space <= 0) continue;
                int moved = Math.min(space, remaining.getCount());
                existing.increment(moved);
                remaining.decrement(moved);
            }
        }
        return remaining;
    }

    private Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
        if (pos == null) return Optional.empty();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, pos, false));
    }

    // -------------------------------------------------------------------------
    // Records / enums
    // -------------------------------------------------------------------------

    private enum Stage {
        IDLE,
        TRANSFER_FROM_PEERS,
        WAIT_FOR_WALL_STOCK,
        MOVE_TO_SEGMENT,
        PLACE_BLOCK,
        DONE
    }

    private enum ObstacleClearanceResult {
        CLEAR,
        CLEARED_THIS_TICK,
        PROTECTED_OBSTACLE,
        UNBREAKABLE_OBSTACLE
    }

    private record WallRect(int minX, int minZ, int maxX, int maxZ, int y) {}

    private record PoiFootprintSignature(
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            int poiCount,
            int poiHash,
            Set<Long> protectedStructureColumns
    ) {}

    private record TransferTask(BlockPos sourceChestPos, BlockPos destChestPos, int amount) {}

    private enum WallPlacementMaterial {
        COBBLESTONE_WALL(Items.COBBLESTONE_WALL, Blocks.COBBLESTONE_WALL.getDefaultState());

        private final net.minecraft.item.Item item;
        private final BlockState blockState;

        WallPlacementMaterial(net.minecraft.item.Item item, BlockState blockState) {
            this.item = item;
            this.blockState = blockState;
        }

        net.minecraft.item.Item item() {
            return item;
        }

        BlockState blockState() {
            return blockState;
        }
    }

    record TraversalSimulationResult(double averageTravelPerPlacement, int sortiesMeetingMinPlacements, int sortiesWithMinCandidates) {}

    record PathRetrySimulationResult(boolean skippedAsHardUnreachable, int decisionTick, int failedAttempts) {}

    record ElectionCandidateSnapshot(String candidateId, boolean hasPairedChest, boolean hasPairedJob, int stoneCount) {}

    record ElectionDecision(String electedCandidateId,
                            int electedStoneCount,
                            List<ElectionCandidateSnapshot> excludedCandidates,
                            boolean shouldLogNoEligibleBuilder) {}

    enum WaitForStockDecision {
        MOVE_TO_SEGMENT,
        WAIT_FOR_CONVERSION,
        DONE
    }

    private record SweepSummary(
            int remainingAfterSweep,
            int filledDuringSweep,
            int deferredCount,
            Map<String, Integer> deferredReasons,
            int irrecoverableCount,
            Map<String, Integer> irrecoverableReasons
    ) {
    }

    private enum CycleEndReason {
        WALL_COMPLETE("wall_complete"),
        OUT_OF_MATERIALS("out_of_materials"),
        UNREACHABLE_SEGMENTS("unreachable_segments");

        private final String logValue;

        CycleEndReason(String logValue) {
            this.logValue = logValue;
        }
    }
}
