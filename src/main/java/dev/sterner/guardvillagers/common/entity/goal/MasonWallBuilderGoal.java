package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.util.VillageWallProjectState;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
import java.util.stream.Stream;

/**
 * Cluster 4 — Mason Defensive Wall Builder.
 *
 * <p>Behaviour summary:
 * <ol>
 *   <li>Scan configured POI set (job sites only, jobs+beds, or all POIs) within
 *       BELL_EFFECT_RANGE of the nearest QM chest
 *       → compute axis-aligned bounding rectangle → expand 10 blocks outward.</li>
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
    // Blocks to expand the village bounding box outward to form the wall rectangle
    private static final int WALL_EXPAND = 10;
    // Minimum stone needed before the elected builder starts placing
    // (required total is per planned block placement, including anti-gap vertical fill columns).
    private static final int STONE_PER_SEGMENT = 1;
    // Movement speed
    private static final double MOVE_SPEED = 0.55D;
    // Reach distance squared (close enough to place)
    private static final double REACH_SQ = 3.5D * 3.5D;
    private static final int PATH_RETRY_MAX_ATTEMPTS = 4;
    private static final int PATH_RETRY_WINDOW_TICKS = 40;
    private static final long HARD_UNREACHABLE_LOG_RATE_LIMIT_TICKS = 200L;
    private static final int NEAREST_STANDABLE_SEARCH_RADIUS = 4;
    // Gameplay cadence: placements happen in short local sorties of 3-5 segments
    // before the guard picks a new nearby anchor.
    static final int MIN_SEGMENTS_PER_SORTIE = 3;
    static final int MAX_SEGMENTS_PER_SORTIE = 5;
    static final int LOCAL_SORTIE_RADIUS = 5;
    static final boolean ALLOW_COBBLESTONE_PLACEMENT_FALLBACK = false;
    private static final int COMPLETION_SWEEP_MAX_RETRIES_PER_SEGMENT = 3;
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
    private int plannedWallBaseY = 0;
    private boolean hardRetryPassStarted = false;
    private boolean conversionWaitActive = false;
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
    private int sortieCandidatesPreflightSkipped = 0;

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
        hardUnreachableRetryQueue.clear();
        sortiePlacements = 0;
        placedSegments = 0;
        plannedPlacementCount = (int) pendingSegments.stream().filter(pos -> !isGatePosition(pos)).count();
        hardRetryPassStarted = false;
        conversionWaitActive = false;
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
        sortieCandidatesPreflightSkipped = 0;
        if (isElectedBuilder && !pendingTransfers.isEmpty()) {
            stage = Stage.TRANSFER_FROM_PEERS;
        } else if (isElectedBuilder && !pendingSegments.isEmpty()) {
            stage = Stage.WAIT_FOR_WALL_STOCK;
        } else {
            stage = Stage.DONE;
        }
        if (isElectedBuilder) {
            String firstTarget = pendingSegments.isEmpty() ? "none" : pendingSegments.get(0).toShortString();
            LOGGER.info("MasonWallBuilder {}: build cycle started (segments={}, transfers={}, firstTarget={})",
                    guard.getUuidAsString(), pendingSegments.size(), pendingTransfers.size(), firstTarget);
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
        retryLogRateLimitTickBySignature.clear();
        releaseAllSegmentClaims(worldOrNull());
        claimedSortieSegments.clear();
        sortieActive = false;
        sortieActiveLayer = -1;
        sortieTransientRetriesAttempted = 0;
        sortieFallbackTargetsTried = 0;
        sortieHardUnreachableMarked = 0;
        activeAnchorPos = null;
        plannedWallBaseY = 0;
        pendingSegments.clear();
        pendingTransfers.clear();
        guard.setWallBuildPending(false, 0);
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
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
        plannedWallBaseY = rect.y();
        activeAnchorPos = anchorPos.toImmutable();
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
            if (conversionWaitActive) {
                LOGGER.info("MasonWallBuilder {}: conversion success detected (walls_now={} >= threshold={})",
                        guard.getUuidAsString(), availableWalls, threshold);
            }
            conversionWaitActive = false;
            LOGGER.info("MasonWallBuilder {}: proceeding with existing wall stock without stonecutter (availableWalls={}, threshold={})",
                    guard.getUuidAsString(), availableWalls, threshold);
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }
        if (decision == WaitForStockDecision.DONE) {
            conversionWaitActive = false;
            completeCycle(CycleEndReason.OUT_OF_MATERIALS);
            return;
        }
        if (decision == WaitForStockDecision.WAIT_FOR_CONVERSION) {
            if (!conversionWaitActive) {
                LOGGER.info("MasonWallBuilder {}: entering conversion wait (walls={}, cobblestone={}, threshold={})",
                        guard.getUuidAsString(), availableWalls, availableCobblestone, threshold);
            }
            conversionWaitActive = true;
            guard.getNavigation().stop();
            LOGGER.debug("MasonWallBuilder {}: waiting for stonecutting conversion (availableWalls={}, availableCobblestone={}, threshold={})",
                    guard.getUuidAsString(), availableWalls, availableCobblestone, threshold);
            return;
        }
    }

    private void tickMoveToSegment(ServerWorld world) {
        pruneSortieQueue(world);
        if (localSortieQueue.isEmpty() || sortiePlacements >= MAX_SEGMENTS_PER_SORTIE) {
            logSortieSummaryIfActive();
            if (!startNextSortie(world)) {
                runCompletionSweepAndFinish(world);
                return;
            }
        }

        BlockPos target = localSortieQueue.peekFirst();
        if (target == null) {
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
                skippedSegments.add(target);
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
        skippedSegments.add(segmentTarget);
        if (!hardRetryPassStarted) {
            hardUnreachableRetryQueue.offerLast(segmentTarget.toImmutable());
        }
        activeMoveTarget = null;
        activeMoveTargetTicks = 0;
        lastMoveDistSq = Double.MAX_VALUE;
        resetPathFailureState();
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

        // Check we still have wall blocks in our chest
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
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
            stage = Stage.DONE;
            return;
        }

        // Place the block
        world.setBlockState(target, placementMaterial.blockState());
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
        sortieCandidatesPreflightSkipped = 0;
        int activeLayer = findLowestPendingLayer(world);
        if (activeLayer < 1) {
            return false;
        }

        BlockPos anchor = findNextIndexAnchor(world, activeLayer);
        if (anchor == null) {
            return false;
        }

        List<BlockPos> anchorBatch = buildLocalSortieCandidates(world, anchor, activeLayer);
        if (anchorBatch.size() < MIN_SEGMENTS_PER_SORTIE) {
            BlockPos nearest = findNearestUnbuiltSegment(world, guard.getBlockPos(), activeLayer);
            if (nearest != null) {
                anchorBatch = buildLocalSortieCandidates(world, nearest, activeLayer);
            }
        }
        if (anchorBatch.isEmpty()) {
            skippedSegments.add(anchor);
            return hasRemainingSegments(world) && startNextSortie(world);
        }
        localSortieQueue.addAll(anchorBatch);
        sortieActive = true;
        sortieActiveLayer = activeLayer;
        sortieTransientRetriesAttempted = 0;
        sortieFallbackTargetsTried = 0;
        sortieHardUnreachableMarked = 0;
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
            if (getSegmentLayer(retryCandidate) == activeLayer && isBuildableCandidate(world, retryCandidate)) {
                return retryCandidate;
            }
        }
        while (currentSegmentIndex < pendingSegments.size()) {
            BlockPos candidate = pendingSegments.get(currentSegmentIndex++);
            if (getSegmentLayer(candidate) == activeLayer && isBuildableCandidate(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private BlockPos findNearestUnbuiltSegment(ServerWorld world, BlockPos from, int activeLayer) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : pendingSegments) {
            if (getSegmentLayer(pos) != activeLayer) continue;
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
            if (getSegmentLayer(pos) != activeLayer) continue;
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
        for (BlockPos segment : bounded) {
            sortieCandidatesConsidered++;
            if (isSegmentClaimedByOther(world, segment)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("MasonWallBuilder {}: claim_conflict_skip segment={} anchor={} holder=other_guard",
                            guard.getUuidAsString(),
                            segment.toShortString(),
                            activeAnchorPos == null ? "none" : activeAnchorPos.toShortString());
                }
                sortieCandidatesPreflightSkipped++;
                continue;
            }
            if (!passesSortiePreflight(world, segment)) {
                skippedSegments.add(segment.toImmutable());
                sortieCandidatesPreflightSkipped++;
                continue;
            }
            if (!tryClaimSegment(world, segment)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("MasonWallBuilder {}: claim_conflict_enqueue segment={} anchor={} holder=other_guard",
                            guard.getUuidAsString(),
                            segment.toShortString(),
                            activeAnchorPos == null ? "none" : activeAnchorPos.toShortString());
                }
                sortieCandidatesPreflightSkipped++;
                continue;
            }
            accepted.add(segment);
            sortieCandidatesAccepted++;
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
        return preflightPath != null && preflightPath.reachesTarget();
    }

    private int computeCurrentSortieThreshold(ServerWorld world) {
        int activeLayer = findLowestPendingLayer(world);
        if (activeLayer < 1) return 1;
        int remaining = 0;
        for (BlockPos pos : pendingSegments) {
            if (getSegmentLayer(pos) != activeLayer) continue;
            if (isBuildableCandidate(world, pos)) remaining++;
        }
        return Math.max(1, Math.min(MAX_SEGMENTS_PER_SORTIE, remaining));
    }

    private int findLowestPendingLayer(ServerWorld world) {
        for (BlockPos pos : pendingSegments) {
            if (isBuildableCandidate(world, pos)) {
                return getSegmentLayer(pos);
            }
        }
        return -1;
    }

    private int getSegmentLayer(BlockPos pos) {
        return (pos.getY() - plannedWallBaseY) + 1;
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
        logSortieSummaryIfActive();
        releaseLocalSortieClaims(world, "completion_sweep");
        SweepSummary summary = runCompletionSweep(world);
        if (summary.remainingAfterSweep == 0) {
            completeCycle(CycleEndReason.WALL_COMPLETE);
            return;
        }

        LOGGER.warn("MasonWallBuilder {}: completion sweep unresolved segments remain={} irrecoverableReasons={}",
                guard.getUuidAsString(), summary.remainingAfterSweep, summary.irrecoverableReasons);
        completeCycle(CycleEndReason.UNREACHABLE_SEGMENTS);
    }

    private SweepSummary runCompletionSweep(ServerWorld world) {
        List<BlockPos> remainingUnbuilt = findRemainingUnbuiltSegments(world);
        if (remainingUnbuilt.isEmpty()) {
            LOGGER.info("MasonWallBuilder {}: completion sweep summary before=0 filled=0 irrecoverable=0 reasons={}",
                    guard.getUuidAsString(), Map.of());
            return new SweepSummary(0, 0, 0, Map.of());
        }

        int filledDuringSweep = 0;
        int irrecoverableCount = 0;
        Map<String, Integer> irrecoverableReasons = new HashMap<>();
        BlockPos chestPos = guard.getPairedChestPos();

        for (BlockPos segment : remainingUnbuilt) {
            skippedSegments.remove(segment);
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
                    boolean startedPath = guard.getNavigation().startMovingTo(
                            navigationTarget.getX() + 0.5,
                            navigationTarget.getY() + 0.5,
                            navigationTarget.getZ() + 0.5,
                            MOVE_SPEED
                    );
                    failureReason = startedPath ? "not_in_reach" : "navigation_failed";
                    continue;
                }

                WallPlacementMaterial material = consumeWallMaterialFromChest(world, chestPos);
                if (material == null) {
                    failureReason = "out_of_materials";
                    break;
                }

                world.setBlockState(segment, material.blockState());
                filled = true;
                filledDuringSweep++;
                break;
            }

            if (!filled) {
                irrecoverableCount++;
                incrementReason(irrecoverableReasons, failureReason);
            }
        }

        int remainingAfterSweep = findRemainingUnbuiltSegments(world).size();
        LOGGER.info("MasonWallBuilder {}: completion sweep summary before={} filled={} irrecoverable={} reasons={}",
                guard.getUuidAsString(),
                remainingUnbuilt.size(),
                filledDuringSweep,
                irrecoverableCount,
                irrecoverableReasons);

        return new SweepSummary(
                remainingAfterSweep,
                filledDuringSweep,
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

    private void completeCycle(CycleEndReason reason) {
        logSortieSummaryIfActive();
        cycleEndReason = reason;
        if (reason == CycleEndReason.WALL_COMPLETE
                && guard.getWorld() instanceof ServerWorld world
                && activeAnchorPos != null) {
            VillageWallProjectState.get(world.getServer()).markAllLayersComplete(world.getRegistryKey(), activeAnchorPos);
        }
        releaseAllSegmentClaims(worldOrNull());
        stage = Stage.DONE;
        guard.clearWallSegments();
        guard.setWallBuildPending(false, 0);
        activeAnchorPos = null;
        cachedWallRect = null;
        cachedWallRectAnchor = null;
        cachedPoiFootprintSignature = null;
        LOGGER.info("MasonWallBuilder {}: build cycle ended reason={}", guard.getUuidAsString(), cycleEndReason.logValue);
    }

    private boolean shouldEmitHardUnreachableInfo(ServerWorld world, BlockPos segmentTarget) {
        String key = buildRetryLogSignature(segmentTarget);
        long now = world.getTime();
        Long last = retryLogRateLimitTickBySignature.get(key);
        if (last != null && (now - last) < HARD_UNREACHABLE_LOG_RATE_LIMIT_TICKS) {
            return false;
        }
        retryLogRateLimitTickBySignature.put(key, now);
        return true;
    }

    private String buildRetryLogSignature(BlockPos segmentPos) {
        String anchor = activeAnchorPos == null ? "none" : activeAnchorPos.toShortString();
        int layer = getSegmentLayer(segmentPos);
        return guard.getUuidAsString() + "|" + anchor + "|layer=" + layer;
    }

    private void logSortieSummaryIfActive() {
        if (!sortieActive) return;
        LOGGER.info("MasonWallBuilder {}: sortie_end_summary anchor={} layer={} transientRetries={} fallbackTargets={} hardUnreachableMarked={}",
                guard.getUuidAsString(),
                activeAnchorPos == null ? "none" : activeAnchorPos.toShortString(),
                sortieActiveLayer,
                sortieTransientRetriesAttempted,
                sortieFallbackTargetsTried,
                sortieHardUnreachableMarked);
        LOGGER.debug("MasonWallBuilder {}: sortie_preflight_summary anchor={} layer={} candidatesConsidered={} accepted={} preflightSkipped={}",
                guard.getUuidAsString(),
                activeAnchorPos == null ? "none" : activeAnchorPos.toShortString(),
                sortieActiveLayer,
                sortieCandidatesConsidered,
                sortieCandidatesAccepted,
                sortieCandidatesPreflightSkipped);
        sortieActive = false;
        sortieActiveLayer = -1;
        sortieTransientRetriesAttempted = 0;
        sortieFallbackTargetsTried = 0;
        sortieHardUnreachableMarked = 0;
        sortieCandidatesConsidered = 0;
        sortieCandidatesAccepted = 0;
        sortieCandidatesPreflightSkipped = 0;
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
     * computes their bounding box, expands by WALL_EXPAND, and returns a rectangle.
     */
    private Optional<PoiFootprintSignature> computePoiFootprintSignature(ServerWorld world, BlockPos anchorPos) {
        int range = VillageGuardStandManager.BELL_EFFECT_RANGE;
        Box searchBox = new Box(anchorPos).expand(range);

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int count = 0;
        int hash = 1;
        boolean found = false;

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
        }

        if (!found) {
            return Optional.empty();
        }

        return Optional.of(new PoiFootprintSignature(minX, minZ, maxX, maxZ, count, hash));
    }

    private WallRect computeWallRect(BlockPos anchorPos, PoiFootprintSignature signature) {
        // Expand by WALL_EXPAND and snap to a rectangle
        int wallY = anchorPos.getY(); // wall is at anchor Y level

        return new WallRect(
                signature.minX() - WALL_EXPAND,
                signature.minZ() - WALL_EXPAND,
                signature.maxX() + WALL_EXPAND,
                signature.maxZ() + WALL_EXPAND,
                wallY
        );
    }

    private GuardVillagersConfig.MasonWallPoiMode resolveWallPoiMode() {
        GuardVillagersConfig.MasonWallPoiMode configured = GuardVillagersConfig.masonWallPoiMode;
        return configured != null ? configured : GuardVillagersConfig.MasonWallPoiMode.JOBS_AND_BEDS;
    }

    /**
     * Returns deterministic 3-layer wall placements around the rectangle perimeter:
     * baseY, baseY+1, and baseY+2. Layers are appended in that order so planning
     * is stable and layer-first.
     */
    private List<BlockPos> computeWallSegments(ServerWorld world, WallRect rect) {
        List<BlockPos> segments = new ArrayList<>();
        int baseY = rect.y();

        for (int layer = 0; layer < 3; layer++) {
            int layerY = baseY + layer;
            for (BlockPos perimeterPos : computePerimeterTraversal(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ(), layerY)) {
                if (world.getBlockState(perimeterPos).isOf(Blocks.DIRT_PATH)) continue;
                if (world.getBlockState(perimeterPos).isOf(Blocks.COBBLESTONE)) continue;
                segments.add(perimeterPos.toImmutable());
            }
        }

        // Ensure at least 1 forced gap (remove last segment if the wall would be fully closed)
        if (!segments.isEmpty()) {
            int perimeterTotal = 2 * (rect.maxX() - rect.minX() + rect.maxZ() - rect.minZ()) * 3;
            if (segments.size() >= perimeterTotal) {
                segments.remove(segments.size() - 1);
            }
        }

        return segments;
    }

    static List<BlockPos> computePerimeterTraversal(int minX, int minZ, int maxX, int maxZ, int y) {
        List<BlockPos> perimeter = new ArrayList<>();

        // North face: minX -> maxX at minZ
        for (int x = minX; x <= maxX; x++) {
            perimeter.add(new BlockPos(x, y, minZ));
        }

        // East face: minZ+1 -> maxZ-1 at maxX (corners already included)
        for (int z = minZ + 1; z < maxZ; z++) {
            perimeter.add(new BlockPos(maxX, y, z));
        }

        // South face: maxX -> minX at maxZ
        for (int x = maxX; x >= minX; x--) {
            perimeter.add(new BlockPos(x, y, maxZ));
        }

        // West face: maxZ-1 -> minZ+1 at minX (corners already included)
        for (int z = maxZ - 1; z > minZ; z--) {
            perimeter.add(new BlockPos(minX, y, z));
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

    private record WallRect(int minX, int minZ, int maxX, int maxZ, int y) {}

    private record PoiFootprintSignature(int minX, int minZ, int maxX, int maxZ, int poiCount, int poiHash) {}

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
