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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Cluster 4 — Mason Defensive Wall Builder.
 *
 * <p>Behaviour summary:
 * <ol>
 *   <li>Scan configured POI set (job sites only, jobs+beds, or all POIs) within
 *       the configured mason wall footprint radius of the nearest QM chest
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
    private static final int BAND_FAILURE_WINDOW_TICKS = 80;
    private static final int BAND_FAILURE_REPEAT_THRESHOLD = 3;
    private static final int VEGETATION_DEFER_COOLDOWN_TICKS = 60;
    private static final int VEGETATION_CLEAR_PASS_BUDGET_PER_TICK = 2;
    private static final long SEGMENT_CLAIM_DURATION_TICKS = 160L;
    private static final long HARD_UNREACHABLE_LOG_RATE_LIMIT_TICKS = 200L;
    private static final long PERIODIC_INFO_INTERVAL_TICKS = 200L;
    private static final int NEAREST_STANDABLE_SEARCH_RADIUS = 4;
    private static final int PATH_PROBE_BUDGET_PER_TICK = 12;
    private static final int PATH_PROBE_BUDGET_PER_SORTIE = 48;
    private static final long PREFLIGHT_CACHE_BUCKET_TICKS = 20L;
    private static final long PREFLIGHT_CACHE_TTL_TICKS = 80L;
    // Gameplay cadence: placements happen in short local sorties of 3-5 segments
    // before the guard picks a new nearby anchor.
    static final int MIN_SEGMENTS_PER_SORTIE = 3;
    static final int MAX_SEGMENTS_PER_SORTIE = 5;
    static final int LOCAL_SORTIE_RADIUS = 5;
    static final boolean ALLOW_COBBLESTONE_PLACEMENT_FALLBACK = true;
    private static final long WAIT_FOR_STOCK_ESCALATION_TIMEOUT_TICKS = 160L;
    private static final long WAIT_FOR_STOCK_DIAGNOSTIC_INTERVAL_TICKS = 40L;
    private static final int MAX_SORTIE_ANCHOR_ATTEMPTS_PER_TICK = 8;
    private static final int COMPLETION_SWEEP_MAX_RETRIES_PER_SEGMENT = 3;
    private static final int MAX_OBSTACLE_BREAKS_PER_TICK = 1;
    private static final int PLACE_BLOCK_NON_PLACEMENT_ESCALATION_THRESHOLD = 4;
    private static final long PLANT_CLEAR_RETRY_WINDOW_TICKS = 6L;
    private static final int UNBREAKABLE_ESCALATION_BAND_WINDOW_TICKS = 200;
    private static final int UNBREAKABLE_ESCALATION_BAND_REPEAT_THRESHOLD = 3;
    private static final int CRITICAL_BUILDABLE_POOL_SIZE = 2;
    private static final long SKIPPED_SEGMENT_RECONCILIATION_INTERVAL_TICKS = 80L;
    private static final long MOVE_MEANINGFUL_PROGRESS_TIMEOUT_TICKS = 200L;
    private static final long SORTIE_NO_NET_PROGRESS_TIMEOUT_TICKS = 240L;
    private static final long SORTIE_NO_NET_PROGRESS_STARTUP_GRACE_TICKS = 60L;
    private static final int SORTIE_NO_NET_PROGRESS_MIN_PLACEMENT_DELTA = 1;
    private static final double MEANINGFUL_PROGRESS_DELTA_DIST_SQ = 0.64D;
    private static final int WATCHDOG_REPEAT_SEGMENT_THRESHOLD = 2;
    private static final long WATCHDOG_REPEAT_SEGMENT_DEFER_COOLDOWN_TICKS = 200L;
    private static final int WATCHDOG_REPEAT_RECOVERY_TERMINAL_THRESHOLD = 3;
    private static final long SEGMENT_COOLDOWN_TICKS = 120L;
    private static final long SORTIE_ABORT_BACKOFF_MAX_COOLDOWN_TICKS = 960L;
    private static final long BAND_QUARANTINE_COOLDOWN_TICKS = 600L;
    private static final int EARLY_CYCLE_CANDIDATE_INDEX_BAND_SIZE = MAX_SEGMENTS_PER_SORTIE;
    private static final long EARLY_CYCLE_ABORT_COOLDOWN_TICKS = 360L;
    private static final int BAND_NO_PROGRESS_ABORT_THRESHOLD = 3;
    private static final int BAND_NO_PROGRESS_ABORT_WINDOW_TICKS = 240;
    private static final long NAV_TARGET_BACKOFF_TICKS = 100L;
    private static final int STAGNATION_PROGRESS_WINDOW_TICKS = 200;
    private static final int STAGNATION_RETRY_DELTA_THRESHOLD = 7;
    private static final int STAGNATION_HARD_UNREACHABLE_DELTA_THRESHOLD = 3;
    private static final int STAGNATION_PIVOT_RANGE_WIDTH = 8;
    private static final long STAGNATION_PIVOT_INTERVAL_TICKS = 200L;
    private static final long STAGNATION_PIVOT_MIN_ACTIVE_TICKS = 60L;
    private static final long STAGNATION_PIVOT_MAX_ACTIVE_TICKS = 320L;
    private static final long STAGNATION_PIVOT_REACTIVATION_COOLDOWN_TICKS = 80L;
    private static final int STAGNATION_PIVOT_CLEAR_PLACEMENT_STREAK = 3;
    private static final int STAGNATION_PIVOT_CLEAR_RETRY_RECOVERY_DELTA = 2;
    private static final int STAGNATION_PIVOT_CLEAR_HARD_RECOVERY_DELTA = 1;
    private static final int RETRY_DENSITY_WINDOW_TICKS = 200;
    private static final double RETRY_DENSITY_TRIGGER_THRESHOLD = 2.0D;
    private static final int RETRY_DENSITY_LOW_PLACEMENT_DELTA_THRESHOLD = 1;
    private static final int RETRY_DENSITY_HARD_UNREACHABLE_TRIGGER_DELTA = 2;
    private static final int RETRY_DENSITY_EXIT_PLACEMENT_STREAK = 3;
    private static final int STAGNATION_PIVOT_SUPPRESSION_MIN_SHIFT = 2;
    private static final int STAGNATION_REGION_BAND_BUCKETS = 4;
    private static final long STAGNATION_PIVOT_SECTION_COOLDOWN_TICKS = 600L;
    private static final long STAGNATION_REGION_SUPPRESSION_BASE_COOLDOWN_TICKS = STAGNATION_PIVOT_INTERVAL_TICKS;
    private static final long STAGNATION_REGION_SUPPRESSION_MAX_COOLDOWN_TICKS = STAGNATION_PIVOT_SECTION_COOLDOWN_TICKS;
    private static final long STAGNATION_PIVOT_LIFECYCLE_LOG_INTERVAL_TICKS = 20L;
    private static final double LAYER_ONE_REGION_DOMINANCE_COMPLETION_THRESHOLD = 0.95D;
    private static final int MAX_CONSECUTIVE_SORTIES_PER_REGION = 2;
    private static final int MAX_RETRY_ATTEMPTS_PER_REGION_BEFORE_ROTATION = 3;
    private static final int LAYER_ONE_FACE_ARC_BUCKETS = 4;
    private static final List<String> LAYER_ONE_CLOCKWISE_FACES = List.of("north", "east", "south", "west");
    private static final int EARLY_HARD_UNREACHABLE_FAIL_THRESHOLD = 4;
    private static final int EARLY_HARD_UNREACHABLE_DISTINCT_TARGET_THRESHOLD = 2;
    private static final int STRUCTURE_SAMPLE_VERTICAL_RADIUS = 3;
    private static final int STRUCTURE_ENVELOPE_SCAN_MARGIN = 8;
    private static final int STRUCTURE_ENVELOPE_MAX_COLUMNS = 640;
    private static final int STRUCTURE_ENVELOPE_MAX_BLOCK_CHECKS = 6144;
    private static final int STRUCTURE_ENVELOPE_INTERIOR_STRIDE = 3;
    private static final int STRUCTURE_ENVELOPE_VERTICAL_STEP = 2;
    private static final int STRUCTURE_ENVELOPE_ANCHOR_DISTANCE_CAP = 72;
    private static final int STRUCTURE_TRACE_MAX_COLUMNS_VISITED = 2048;
    private static final int STRUCTURE_TRACE_MAX_BLOCK_CHECKS = 16384;
    private static final int STRUCTURE_TRACE_ANCHOR_RADIUS_CAP = 88;
    private static final int STRUCTURE_TRACE_STOP_AFTER_EMPTY_LAYERS = 2;
    private static final int STRUCTURE_PROTECTION_EXPANSION_CAP = 12;
    private static final int STRUCTURE_MIN_ENCLOSURE_MARGIN = 2;
    private static final int MASON_WALL_FOOTPRINT_HARD_MAX_SPAN = 256;
    private static final int POI_CLUSTER_LINK_DISTANCE = 16;
    private static final int MIN_VIABLE_POI_CLUSTER_SIZE = 2;
    private static final double MATERIAL_CLUSTER_SCORE_DELTA = 1.5D;
    private static final int CLUSTER_ANCHOR_VICINITY_RADIUS = 8;
    private static final int CLUSTER_STRUCTURE_PROXIMITY_RADIUS = 2;
    private static final long WAIT_FOR_STOCK_REPLAN_COOLDOWN_TICKS = 200L;
    private static final long WAIT_FOR_STOCK_MIN_REPLAN_INTERVAL_TICKS = 40L;
    private static final long WAIT_FOR_STOCK_CYCLE_VALIDATION_INTERVAL_TICKS = 40L;
    private static final long SAME_CYCLE_IN_FLIGHT_LOCK_TICKS = 600L;
    private static final long FOOTPRINT_PLANNING_DEBUG_LOG_INTERVAL_TICKS = 200L;
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
    private double targetBestDistSq = Double.MAX_VALUE;
    private long targetLastMeaningfulProgressTick = -1L;
    private int failedPathAttempts = 0;
    private long firstPathFailureTick = -1L;
    private BlockPos lastTriedNavTarget = null;
    private final Deque<BlockPos> localSortieQueue = new ArrayDeque<>();
    private final Set<BlockPos> skippedSegments = new HashSet<>();
    private final Deque<BlockPos> hardUnreachableRetryQueue = new ArrayDeque<>();
    private int sortiePlacements = 0;
    private int sortiePlacementsAtStart = 0;
    private long sortieStartTick = -1L;
    private long sortieActivationTick = -1L;
    private long cycleStartTick = -1L;
    private boolean sortieNoNetProgressGraceSuppressedLogged = false;
    private int placedSegments = 0;
    private int plannedPlacementCount = 0;
    private boolean hardRetryPassStarted = false;
    private boolean conversionWaitActive = false;
    private long waitForStockStartedTick = -1L;
    private long nextWaitForStockDiagnosticTick = 0L;
    private boolean waitForStockTimeoutEscalated = false;
    private boolean waitForStockProceedLogged = false;
    private int lastLoggedProceedWalls = -1;
    private int lastLoggedProceedThreshold = -1;
    private CycleEndReason cycleEndReason = CycleEndReason.WALL_COMPLETE;
    private BlockPos activeAnchorPos = null;
    private final Map<String, Long> retryLogRateLimitTickBySignature = new HashMap<>();
    private final Map<String, FailureBandWindow> failureBandWindows = new HashMap<>();
    private final Map<String, FailureBandWindow> noProgressAbortBandWindows = new HashMap<>();
    private final Map<String, Long> quarantinedBandUntilTick = new HashMap<>();
    private final Map<String, Integer> reinsertionPlacementGateByBand = new HashMap<>();
    private final Map<BlockPos, Integer> segmentCandidateIndexByPos = new HashMap<>();
    private final Map<String, FailureBandWindow> unbreakableEscalationBandWindows = new HashMap<>();
    private final Map<Integer, Set<String>> cycleExcludedBandsByLayer = new HashMap<>();
    private final Deque<DeferredSegmentRetry> deferredSegmentRetryQueue = new ArrayDeque<>();
    private boolean vegetationDeferredThenRequeuedLogged = false;
    private final Set<BlockPos> claimedSortieSegments = new HashSet<>();
    private final Map<BlockPos, SegmentState> segmentStates = new HashMap<>();
    private final Map<PreflightCacheKey, PreflightCacheEntry> sortiePreflightCache = new HashMap<>();
    private final Set<BlockPos> deferredPreflightSegmentsThisSortie = new HashSet<>();
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
    private boolean sortieNearestFallbackSuppressedByLayerOneLap = false;
    private boolean lastPreflightDeferredByBudget = false;
    private long pathProbeBudgetTick = Long.MIN_VALUE;
    private int pathProbeBudgetRemainingThisTick = PATH_PROBE_BUDGET_PER_TICK;
    private int pathProbeBudgetRemainingThisSortie = PATH_PROBE_BUDGET_PER_SORTIE;
    private int pathBudgetUsedSinceLastPeriodic = 0;
    private int pathBudgetDeferredSinceLastPeriodic = 0;
    private int sortieCandidateCountSamples = 0;
    private int sortieCandidateCountSum = 0;
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
    private final Map<BlockPos, SegmentFailMetadata> skippedSegmentFailMetadata = new HashMap<>();
    private final Map<BlockPos, Long> segmentCooldownUntilTick = new HashMap<>();
    private final Map<BlockPos, Map<Integer, Long>> segmentNavTargetBackoffUntilTick = new HashMap<>();
    private long nextSkippedSegmentReconciliationTick = 0L;
    private long nextPeriodicInfoTick = 0L;
    private int pathRetriesSinceCycleStart = 0;
    private int hardUnreachableSinceCycleStart = 0;
    private int deferredOrSkippedSinceCycleStart = 0;
    private BlockPos placeBlockFailureTarget = null;
    private int placeBlockConsecutiveNonPlacementTicks = 0;
    private String placeBlockLastNonPlacementReason = null;
    private BlockPos lastPlantClearedTarget = null;
    private long lastPlantClearedTick = -1L;
    private int lastPeriodicSummaryPlacementCount = 0;
    private boolean firstPlacementLoggedThisCycle = false;
    private final Map<String, Long> infoLogRateLimitTickByType = new HashMap<>();
    private BlockPos watchdogLastRecoverySegment = null;
    private int watchdogSameRecoverySegmentCount = 0;
    private int watchdogPlacementCountAtLastRecovery = 0;
    private final Map<BlockPos, Integer> segmentRepeatRecoveryCounts = new HashMap<>();
    private final Map<BlockPos, Integer> sortieAbortSegmentRepeatCounts = new HashMap<>();
    private final Map<String, Integer> sortieAbortBandRepeatCounts = new HashMap<>();
    private final Map<BlockPos, String> sortieAbortSegmentBandKeys = new HashMap<>();
    private final Map<BlockPos, String> terminalSegmentReasons = new HashMap<>();
    private final Deque<ProgressSnapshot> progressSnapshots = new ArrayDeque<>();
    private final Deque<ProgressSnapshot> retryDensitySnapshots = new ArrayDeque<>();
    private boolean retryDensityFallbackModeActive = false;
    private int retryDensityFallbackPlacementStreak = 0;
    private boolean stagnationPivotActive = false;
    private SuppressionRegionKey stagnationPivotActiveSuppressedRegionKey = null;
    private long stagnationPivotSuppressUntilTick = 0L;
    private int stagnationPivotPlacementStreak = 0;
    private int stagnationPivotPlacementsAtActivation = 0;
    private String stagnationPivotExcludedBandSignature = null;
    private int lastPivotActivationRetryDelta = 0;
    private int lastPivotActivationHardDelta = 0;
    private long stagnationPivotActivatedAtTick = 0L;
    private long stagnationPivotCooldownUntilTick = 0L;
    private long nextPivotLifecycleLogTick = 0L;
    private final Map<SuppressionRegionKey, StagnationRegionSuppressionState> stagnationSuppressionByRegion = new HashMap<>();
    private long lastSuppressionAppliedTick = -1L;
    private int placementsAtLastSuppression = 0;
    private String lastSortieRegionKey = null;
    private int consecutiveSortiesSameRegion = 0;
    private final Map<String, Integer> sortieRetryAttemptsByRegion = new HashMap<>();
    private int layerOneLapStartIndex = -1;
    private int layerOneCursorIndex = -1;
    private boolean layerOneLapCompleted = false;
    private boolean layerOneLapWrapped = false;
    private final Set<BlockPos> layerOneLapConsideredSegments = new HashSet<>();
    private final Map<String, LayerOneFaceCoverageState> layerOneFaceCoverageByFace = new HashMap<>();

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
    /** Identity for the currently active cycle. */
    private CycleIdentity activeCycleIdentity = null;
    /** Debounce timer used to avoid repeatedly replanning while waiting for stock on the same cycle identity. */
    private long waitForStockReplanCooldownUntilTick = 0L;
    /** Next tick at which WAIT_FOR_WALL_STOCK cycle validity should be re-validated. */
    private long nextWaitForStockCycleValidationTick = 0L;
    /** Cached WAIT_FOR_WALL_STOCK cycle validity status between validation ticks. */
    private boolean waitForStockCycleStillValid = true;
    /** Gate replan attempts (including invalidated cycles) to avoid burst loops under lag. */
    private long nextWaitForStockReplanAttemptTick = 0L;
    /** Last footprint planning identity that emitted an INFO log. */
    private CycleIdentity lastFootprintPlanningLogIdentity = null;
    /** Last tick footprint-planning DEBUG suppression summary was emitted. */
    private long lastFootprintPlanningDebugLogTick = Long.MIN_VALUE;
    /** Last non-complete cycle identity used for same-cycle restart suppression. */
    private CycleIdentity lastFailedCycleIdentity = null;
    /** In-flight lock expiration for same-cycle restart suppression when no placement happened. */
    private long sameCycleInFlightSuppressUntilTick = 0L;
    /** Material availability signature captured when same-cycle suppression was armed. */
    private int sameCycleSuppressionMaterialSignature = 0;
    /** Tick after which suppression may clear due to retry cooldown eligibility changes. */
    private long sameCycleSuppressionRetryCooldownUntilTick = 0L;

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

        if (stage == Stage.WAIT_FOR_WALL_STOCK && activeCycleIdentity != null && isWaitForStockCycleStillValid(world)) {
            return false;
        }
        if (world.getTime() < nextWaitForStockReplanAttemptTick) {
            return false;
        }

        BlockPos origin = resolveAnchorOrigin();
        Optional<BlockPos> anchorOpt;
        boolean hasActiveWallCycle = activeAnchorPos != null && stage != Stage.IDLE && stage != Stage.DONE;
        if (hasActiveWallCycle) {
            anchorOpt = Optional.of(activeAnchorPos);
            LOGGER.debug("MasonWallBuilderGoal: reusing active anchor; origin={} anchor={}", origin, activeAnchorPos);
        } else {
            // Resolve village anchor from nearest QM chest — no bell required
            anchorOpt = VillageAnchorState.get(world.getServer())
                    .getNearestQmChest(world, origin, (int) PEER_SCAN_RANGE);
            if (anchorOpt.isEmpty()) {
                LOGGER.debug("MasonWallBuilderGoal: failed to resolve nearest QM chest anchor; origin={}", origin);
                return false;
            }
            LOGGER.debug("MasonWallBuilderGoal: resolved anchor; origin={} anchor={}", origin, anchorOpt.get());
        }

        nextScanTick = world.getTime() + SCAN_INTERVAL_TICKS;

        return tryInitiateBuildCycle(world, anchorOpt.get());
    }

    private BlockPos resolveAnchorOrigin() {
        BlockPos pairedJobPos = guard.getPairedJobPos();
        if (pairedJobPos != null) {
            return pairedJobPos;
        }

        BlockPos pairedChestPos = guard.getPairedChestPos();
        if (pairedChestPos != null) {
            return pairedChestPos;
        }

        return guard.getBlockPos();
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && guard.isAlive();
    }

    @Override
    public boolean canStop() {
        // Keep the elected builder focused until the current build cycle finishes.
        if (!isElectedBuilder || stage == Stage.DONE || stage == Stage.IDLE) {
            return true;
        }
        if (stage != Stage.WAIT_FOR_WALL_STOCK) {
            return false;
        }
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return true;
        }
        return !isWaitForStockCycleStillValid(world);
    }

    @Override
    public void start() {
        currentSegmentIndex = 0;
        currentTransferIndex = 0;
        lastProgressLoggedSegmentCount = 0;
        activeMoveTarget = null;
        resetActiveMoveProgressTracking(worldOrNull());
        failedPathAttempts = 0;
        firstPathFailureTick = -1L;
        lastTriedNavTarget = null;
        localSortieQueue.clear();
        skippedSegments.clear();
        skippedSegmentReasons.clear();
        skippedSegmentFailMetadata.clear();
        segmentCooldownUntilTick.clear();
        segmentNavTargetBackoffUntilTick.clear();
        hardUnreachableRetryQueue.clear();
        sortiePlacements = 0;
        placedSegments = 0;
        plannedPlacementCount = (int) pendingSegments.stream().filter(pos -> !isGatePosition(pos)).count();
        hardRetryPassStarted = false;
        conversionWaitActive = false;
        waitForStockStartedTick = -1L;
        nextWaitForStockDiagnosticTick = 0L;
        waitForStockTimeoutEscalated = false;
        resetWaitForStockProceedLogState();
        cycleEndReason = CycleEndReason.WALL_COMPLETE;
        waitForStockReplanCooldownUntilTick = 0L;
        nextWaitForStockCycleValidationTick = 0L;
        waitForStockCycleStillValid = true;
        nextWaitForStockReplanAttemptTick = 0L;
        retryLogRateLimitTickBySignature.clear();
        failureBandWindows.clear();
        noProgressAbortBandWindows.clear();
        quarantinedBandUntilTick.clear();
        reinsertionPlacementGateByBand.clear();
        segmentCandidateIndexByPos.clear();
        unbreakableEscalationBandWindows.clear();
        cycleExcludedBandsByLayer.clear();
        deferredSegmentRetryQueue.clear();
        vegetationDeferredThenRequeuedLogged = false;
        claimedSortieSegments.clear();
        segmentStates.clear();
        sortiePreflightCache.clear();
        deferredPreflightSegmentsThisSortie.clear();
        for (int i = 0; i < pendingSegments.size(); i++) {
            segmentCandidateIndexByPos.put(pendingSegments.get(i).toImmutable(), i);
        }
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
        sortieNearestFallbackSuppressedByLayerOneLap = false;
        lastPreflightDeferredByBudget = false;
        pathProbeBudgetTick = Long.MIN_VALUE;
        pathProbeBudgetRemainingThisTick = PATH_PROBE_BUDGET_PER_TICK;
        pathProbeBudgetRemainingThisSortie = PATH_PROBE_BUDGET_PER_SORTIE;
        pathBudgetUsedSinceLastPeriodic = 0;
        pathBudgetDeferredSinceLastPeriodic = 0;
        sortieCandidateCountSamples = 0;
        sortieCandidateCountSum = 0;
        sortiePlacementsAtStart = 0;
        sortieStartTick = -1L;
        sortieActivationTick = -1L;
        sortieNoNetProgressGraceSuppressedLogged = false;
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
        cycleStartTick = world != null ? world.getTime() : -1L;
        lastPlacementTick = world != null ? world.getTime() : 0L;
        placementsSinceCycleStart = 0;
        nextSkippedSegmentReconciliationTick = world != null ? world.getTime() : 0L;
        nextPeriodicInfoTick = world != null ? world.getTime() + PERIODIC_INFO_INTERVAL_TICKS : 0L;
        pathRetriesSinceCycleStart = 0;
        hardUnreachableSinceCycleStart = 0;
        deferredOrSkippedSinceCycleStart = 0;
        placeBlockFailureTarget = null;
        placeBlockConsecutiveNonPlacementTicks = 0;
        placeBlockLastNonPlacementReason = null;
        lastPeriodicSummaryPlacementCount = 0;
        firstPlacementLoggedThisCycle = false;
        infoLogRateLimitTickByType.clear();
        watchdogLastRecoverySegment = null;
        watchdogSameRecoverySegmentCount = 0;
        watchdogPlacementCountAtLastRecovery = 0;
        segmentRepeatRecoveryCounts.clear();
        sortieAbortSegmentRepeatCounts.clear();
        sortieAbortBandRepeatCounts.clear();
        sortieAbortSegmentBandKeys.clear();
        terminalSegmentReasons.clear();
        clearStagnationPivotState();
        stagnationSuppressionByRegion.clear();
        lastSuppressionAppliedTick = -1L;
        placementsAtLastSuppression = 0;
        lastSortieRegionKey = null;
        consecutiveSortiesSameRegion = 0;
        sortieRetryAttemptsByRegion.clear();
        resetLayerOneLapState();
        seedSegmentStateModel(world);
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
        waitForStockStartedTick = -1L;
        nextWaitForStockDiagnosticTick = 0L;
        waitForStockTimeoutEscalated = false;
        resetWaitForStockProceedLogState();
        retryLogRateLimitTickBySignature.clear();
        failureBandWindows.clear();
        noProgressAbortBandWindows.clear();
        quarantinedBandUntilTick.clear();
        reinsertionPlacementGateByBand.clear();
        segmentCandidateIndexByPos.clear();
        unbreakableEscalationBandWindows.clear();
        cycleExcludedBandsByLayer.clear();
        deferredSegmentRetryQueue.clear();
        vegetationDeferredThenRequeuedLogged = false;
        releaseAllSegmentClaims(worldOrNull());
        claimedSortieSegments.clear();
        segmentStates.clear();
        sortiePreflightCache.clear();
        deferredPreflightSegmentsThisSortie.clear();
        sortieActive = false;
        sortieActiveLayer = -1;
        sortieTransientRetriesAttempted = 0;
        sortieFallbackTargetsTried = 0;
        sortieHardUnreachableMarked = 0;
        sortiePlacementsAtStart = 0;
        sortieStartTick = -1L;
        sortieActivationTick = -1L;
        cycleStartTick = -1L;
        lastPreflightDeferredByBudget = false;
        pathProbeBudgetTick = Long.MIN_VALUE;
        pathProbeBudgetRemainingThisTick = PATH_PROBE_BUDGET_PER_TICK;
        pathProbeBudgetRemainingThisSortie = PATH_PROBE_BUDGET_PER_SORTIE;
        pathBudgetUsedSinceLastPeriodic = 0;
        pathBudgetDeferredSinceLastPeriodic = 0;
        sortieCandidateCountSamples = 0;
        sortieCandidateCountSum = 0;
        sortieNoNetProgressGraceSuppressedLogged = false;
        nextCompletionSweepAllowedTick = 0L;
        consecutiveDeferredSweeps = 0;
        lastSweepLogSignature = null;
        lastSweepLogTick = Long.MIN_VALUE;
        suppressedSweepInfoLogCount = 0;
        sweepInfoSuppressionDebugEmitted = false;
        lastPlacementTick = -1L;
        placementsSinceCycleStart = 0;
        skippedSegmentFailMetadata.clear();
        segmentCooldownUntilTick.clear();
        segmentNavTargetBackoffUntilTick.clear();
        nextSkippedSegmentReconciliationTick = 0L;
        nextPeriodicInfoTick = 0L;
        pathRetriesSinceCycleStart = 0;
        hardUnreachableSinceCycleStart = 0;
        deferredOrSkippedSinceCycleStart = 0;
        placeBlockFailureTarget = null;
        placeBlockConsecutiveNonPlacementTicks = 0;
        placeBlockLastNonPlacementReason = null;
        lastPeriodicSummaryPlacementCount = 0;
        firstPlacementLoggedThisCycle = false;
        infoLogRateLimitTickByType.clear();
        watchdogLastRecoverySegment = null;
        watchdogSameRecoverySegmentCount = 0;
        watchdogPlacementCountAtLastRecovery = 0;
        segmentRepeatRecoveryCounts.clear();
        sortieAbortSegmentRepeatCounts.clear();
        sortieAbortBandRepeatCounts.clear();
        sortieAbortSegmentBandKeys.clear();
        terminalSegmentReasons.clear();
        clearStagnationPivotState();
        stagnationSuppressionByRegion.clear();
        lastSuppressionAppliedTick = -1L;
        placementsAtLastSuppression = 0;
        lastSortieRegionKey = null;
        consecutiveSortiesSameRegion = 0;
        sortieRetryAttemptsByRegion.clear();
        resetLayerOneLapState();
        activeAnchorPos = null;
        cycleWallRect = null;
        activeCycleIdentity = null;
        waitForStockReplanCooldownUntilTick = 0L;
        nextWaitForStockCycleValidationTick = 0L;
        waitForStockCycleStillValid = true;
        nextWaitForStockReplanAttemptTick = 0L;
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

        refreshPathProbeBudgetForTick(world);
        pruneExpiredSortiePreflightCache(world);
        maybeEmitPeriodicInfo(world);
        maybeTriggerRetryDensityFallback(world);
        maybeActivateStagnationPivot(world);

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
            LOGGER.debug("MasonWallBuilder {}: wall POI scan mode={}", guard.getUuidAsString(), poiMode);
            LOGGER.debug("MasonWallBuilder {}: wall profile=deterministic 3-layer perimeter (layer-first build order)",
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
        int perimeterSignatureHash = computePerimeterSignatureHash(currentSignature);
        CycleIdentity candidateCycleIdentity = new CycleIdentity(anchorPos.toImmutable(), perimeterSignatureHash, 0, world.getTime());
        if (shouldDebounceReplanForSameCycleIdentity(world, anchorPos, perimeterSignatureHash)) {
            LOGGER.debug("MasonWallBuilder {}: replan_skipped_same_cycle_identity identity={} cooldownRemainingTicks={}",
                    guard.getUuidAsString(),
                    activeCycleIdentity,
                    waitForStockReplanCooldownUntilTick - world.getTime());
            return false;
        }

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
        int boundsHash = computeWallBoundsHash(rect);
        candidateCycleIdentity = new CycleIdentity(anchorPos.toImmutable(), perimeterSignatureHash, boundsHash, world.getTime());
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
        int materialAvailabilitySignature = computeMaterialAvailabilitySignature(totalWalls, totalCobblestone);
        if (shouldSuppressSameCycleRestart(world, candidateCycleIdentity, materialAvailabilitySignature)) {
            logInfoRateLimited(
                    world,
                    "cycle_restart_suppressed_same_identity",
                    PERIODIC_INFO_INTERVAL_TICKS,
                    "MasonWallBuilder {}: cycle_restart_suppressed_same_identity identity={} stage={} suppressionRemainingTicks={}",
                    guard.getUuidAsString(),
                    candidateCycleIdentity,
                    stage,
                    Math.max(0L, sameCycleInFlightSuppressUntilTick - world.getTime()));
            return false;
        }
        int requiredToStartSession = Math.max(1, Math.min(MAX_SEGMENTS_PER_SORTIE, requiredWallSegments));
        if (totalConvertibleWalls < requiredToStartSession) {
            logInfoRateLimited(world, "material_exhaustion_precheck", PERIODIC_INFO_INTERVAL_TICKS,
                    "MasonWallBuilder {}: insufficient wall material to start session (walls={}, cobblestone={}, requiredToStart={}, requiredTotal={})",
                    guard.getUuidAsString(), totalWalls, totalCobblestone, requiredToStartSession, requiredWallSegments);
            return false;
        }
        int plannedConversions = Math.max(0, requiredToStartSession - totalWalls);
        logDetailed("MasonWallBuilder {}: readiness check passed (requiredToStart={}, requiredTotal={}, wallsAvailable={}, cobblestoneConvertible={}, plannedStartConversions={})",
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
            logInfoRateLimited(world, "election_skipped", PERIODIC_INFO_INTERVAL_TICKS,
                    "MasonWallBuilder {}: election skipped; no eligible mason with paired chest+job near anchor {}",
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
        activeCycleIdentity = candidateCycleIdentity;
        waitForStockReplanCooldownUntilTick = 0L;
        waitForStockCycleStillValid = true;
        nextWaitForStockCycleValidationTick = 0L;
        nextWaitForStockReplanAttemptTick = world.getTime() + WAIT_FOR_STOCK_MIN_REPLAN_INTERVAL_TICKS;
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
            clearWaitForStockState();
            resetWaitForStockProceedLogState();
            stage = Stage.DONE;
            return;
        }

        long now = world.getTime();
        if (waitForStockStartedTick < 0L) {
            waitForStockStartedTick = now;
            nextWaitForStockDiagnosticTick = now;
            waitForStockTimeoutEscalated = false;
        }

        int baseThreshold = computeCurrentSortieThreshold(world);
        int threshold = computeEffectiveSortieThresholdForAvailableStock(world, chestPos, baseThreshold, now);
        guard.setWallBuildPending(true, threshold);
        int availableWalls = countItemInChest(world, chestPos, Items.COBBLESTONE_WALL);
        int availableCobblestone = countItemInChest(world, chestPos, Items.COBBLESTONE);
        boolean conversionCapable = availableCobblestone > 0;
        String fallbackMode = describeWaitFallbackMode(baseThreshold, threshold, conversionCapable, availableWalls);
        maybeLogWaitForStockDiagnostics(world, availableWalls, availableCobblestone, conversionCapable, fallbackMode, baseThreshold, threshold);

        WaitForStockDecision decision = decideWaitForStockTransition(
                availableWalls,
                availableCobblestone,
                threshold,
                ALLOW_COBBLESTONE_PLACEMENT_FALLBACK
        );
        if (decision == WaitForStockDecision.MOVE_TO_SEGMENT) {
            boolean conversionWaitEnded = conversionWaitActive;
            if (conversionWaitEnded) {
                logDetailed("MasonWallBuilder {}: conversion success detected (walls_now={} >= threshold={})",
                        guard.getUuidAsString(), availableWalls, threshold);
            }
            conversionWaitActive = false;
            boolean shouldLogProceed = conversionWaitEnded
                    || !waitForStockProceedLogged
                    || availableWalls != lastLoggedProceedWalls
                    || threshold != lastLoggedProceedThreshold;
            if (shouldLogProceed) {
                logDetailed("MasonWallBuilder {}: proceeding with existing wall stock without stonecutter (availableWalls={}, threshold={})",
                        guard.getUuidAsString(), availableWalls, threshold);
                waitForStockProceedLogged = true;
                lastLoggedProceedWalls = availableWalls;
                lastLoggedProceedThreshold = threshold;
            }
            stage = Stage.MOVE_TO_SEGMENT;
            waitForStockReplanCooldownUntilTick = 0L;
            clearWaitForStockState();
            return;
        }
        if (decision == WaitForStockDecision.DONE) {
            conversionWaitActive = false;
            clearWaitForStockState();
            resetWaitForStockProceedLogState();
            completeCycle(CycleEndReason.OUT_OF_MATERIALS);
            return;
        }
        if (decision == WaitForStockDecision.WAIT_FOR_CONVERSION) {
            if (!conversionWaitActive) {
                logDetailed("MasonWallBuilder {}: entering conversion wait (walls={}, cobblestone={}, threshold={})",
                        guard.getUuidAsString(), availableWalls, availableCobblestone, threshold);
            }
            conversionWaitActive = true;
            waitForStockReplanCooldownUntilTick = Math.max(
                    waitForStockReplanCooldownUntilTick,
                    world.getTime() + WAIT_FOR_STOCK_REPLAN_COOLDOWN_TICKS
            );
            resetWaitForStockProceedLogState();
            guard.getNavigation().stop();
            LOGGER.debug("MasonWallBuilder {}: waiting for stonecutting conversion (availableWalls={}, availableCobblestone={}, threshold={})",
                    guard.getUuidAsString(), availableWalls, availableCobblestone, threshold);
            return;
        }
    }

    private void tickMoveToSegment(ServerWorld world) {
        maybeRecoverStalledCycle(world, "move_tick");
        requeueDeferredSegmentsIfReady(world);
        maybeReconcileSkippedSegments(world);
        pruneSortieQueue(world);
        if (maybeAbortSortieForNoNetProgress(world, Stage.MOVE_TO_SEGMENT)) {
            return;
        }
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
            resetActiveMoveProgressTracking(world);
            resetPathFailureState();
        }
        double distSq = guard.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        if (distSq <= REACH_SQ) {
            activeMoveTarget = null;
            resetActiveMoveProgressTracking(world);
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
            if (distSq + MEANINGFUL_PROGRESS_DELTA_DIST_SQ <= targetBestDistSq) {
                targetBestDistSq = distSq;
                targetLastMeaningfulProgressTick = world.getTime();
            }
            if (distSq < lastMoveDistSq - 0.01D) {
                lastMoveDistSq = distSq;
            }

            long elapsedNoMeaningfulProgress = Math.max(0L, world.getTime() - targetLastMeaningfulProgressTick);
            if (elapsedNoMeaningfulProgress > MOVE_MEANINGFUL_PROGRESS_TIMEOUT_TICKS) {
                LOGGER.info("MasonWallBuilder {}: move_livelock_guard segment={} bestDistSq={} elapsedTicksNoMeaningfulProgress={}",
                        guard.getUuidAsString(),
                        target.toShortString(),
                        String.format("%.2f", targetBestDistSq),
                        elapsedNoMeaningfulProgress);
                registerSegmentFailureMetadata(world, target, navigationTarget, "move_livelock_no_meaningful_progress");
                registerNavTargetFailureBackoff(world, target, navigationTarget, "move_livelock_no_meaningful_progress");
                localSortieQueue.pollFirst();
                requeueSegmentWithCooldown(
                        world,
                        target,
                        "move_livelock_no_meaningful_progress",
                        SEGMENT_COOLDOWN_TICKS,
                        "cooldown_requeue",
                        -1
                );
                activeMoveTarget = null;
                resetActiveMoveProgressTracking(world);
                resetPathFailureState();
            }
        }
    }

    private void resetActiveMoveProgressTracking(ServerWorld world) {
        activeMoveTargetTicks = 0;
        lastMoveDistSq = Double.MAX_VALUE;
        targetBestDistSq = Double.MAX_VALUE;
        targetLastMeaningfulProgressTick = world == null ? -1L : world.getTime();
    }

    /**
     * Wall segments are frequently air blocks one block above terrain. Pathfinding directly
     * to those coordinates often fails because entities cannot stand in that space. Route
     * navigation to a nearby standable position instead, while still placing on the original
     * segment when the guard is within placement reach.
     */
    private BlockPos resolveSegmentNavigationTarget(ServerWorld world, BlockPos segmentPos) {
        List<BlockPos> candidates = buildNavigationTargetCandidates(world, segmentPos, true);
        int index = Math.min(Math.max(0, failedPathAttempts), Math.max(0, candidates.size() - 1));
        return candidates.get(index);
    }

    private void refreshPathProbeBudgetForTick(ServerWorld world) {
        long now = world.getTime();
        if (pathProbeBudgetTick == now) {
            return;
        }
        pathProbeBudgetTick = now;
        pathProbeBudgetRemainingThisTick = PATH_PROBE_BUDGET_PER_TICK;
    }

    private boolean tryConsumePathProbeBudget(ServerWorld world) {
        refreshPathProbeBudgetForTick(world);
        if (pathProbeBudgetRemainingThisTick <= 0 || pathProbeBudgetRemainingThisSortie <= 0) {
            return false;
        }
        pathProbeBudgetRemainingThisTick--;
        pathProbeBudgetRemainingThisSortie--;
        pathBudgetUsedSinceLastPeriodic++;
        return true;
    }

    private void noteDeferredPathProbe() {
        pathBudgetDeferredSinceLastPeriodic++;
    }

    private long coarseTickBucket(long tick) {
        return Math.max(0L, tick / Math.max(1L, PREFLIGHT_CACHE_BUCKET_TICKS));
    }

    private void pruneExpiredSortiePreflightCache(ServerWorld world) {
        long now = world.getTime();
        sortiePreflightCache.entrySet().removeIf(entry -> entry.getValue().expiresAtTick() <= now);
    }

    private PreflightCacheEntry findCachedPreflightResult(BlockPos segment, BlockPos navTarget, long now) {
        long currentBucket = coarseTickBucket(now);
        int maxBucketsBack = Math.max(1, (int) Math.ceil((double) PREFLIGHT_CACHE_TTL_TICKS / (double) Math.max(1L, PREFLIGHT_CACHE_BUCKET_TICKS)));
        for (int offset = 0; offset <= maxBucketsBack; offset++) {
            long bucket = currentBucket - offset;
            if (bucket < 0L) {
                break;
            }
            PreflightCacheEntry cached = sortiePreflightCache.get(new PreflightCacheKey(segment, navTarget, bucket));
            if (cached != null && cached.expiresAtTick() > now) {
                return cached;
            }
        }
        return null;
    }

    private boolean isSegmentInCooldownOrQuarantine(ServerWorld world, BlockPos segment, long now) {
        return isSegmentInCooldown(segment, now) || isSegmentBandQuarantined(world, segment, now);
    }

    private PreflightProbeResult evaluatePreflightPath(ServerWorld world, BlockPos segment, BlockPos navigationTarget) {
        long now = world.getTime();
        BlockPos segmentKey = segment.toImmutable();
        BlockPos navKey = navigationTarget.toImmutable();
        PreflightCacheEntry cached = findCachedPreflightResult(segmentKey, navKey, now);
        if (cached != null) {
            return cached.reachesTarget() ? PreflightProbeResult.PASS : PreflightProbeResult.FAIL;
        }
        if (isSegmentInCooldownOrQuarantine(world, segmentKey, now)) {
            return PreflightProbeResult.FAIL;
        }
        if (!tryConsumePathProbeBudget(world)) {
            noteDeferredPathProbe();
            return PreflightProbeResult.DEFERRED;
        }
        Path preflightPath = guard.getNavigation().findPathTo(navigationTarget, 0);
        boolean reaches = preflightPath != null && preflightPath.reachesTarget();
        long expiresAt = now + PREFLIGHT_CACHE_TTL_TICKS;
        sortiePreflightCache.put(
                new PreflightCacheKey(segmentKey, navKey, coarseTickBucket(now)),
                new PreflightCacheEntry(reaches, expiresAt)
        );
        return reaches ? PreflightProbeResult.PASS : PreflightProbeResult.FAIL;
    }

    private List<BlockPos> buildNavigationTargetCandidates(ServerWorld world, BlockPos segmentPos, boolean applyBackoff) {
        Map<BlockPos, NavigationCandidate> deduped = new HashMap<>();

        boolean budgetExhausted = false;
        budgetExhausted |= addNavigationCandidate(world, segmentPos, segmentPos.down(), deduped);
        budgetExhausted |= addNavigationCandidate(world, segmentPos, segmentPos.down(2), deduped);
        budgetExhausted |= addNavigationCandidate(world, segmentPos, segmentPos, deduped);

        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (budgetExhausted) {
                break;
            }
            budgetExhausted |= addNavigationCandidate(world, segmentPos, segmentPos.offset(direction), deduped);
            budgetExhausted |= addNavigationCandidate(world, segmentPos, segmentPos.down().offset(direction), deduped);
            budgetExhausted |= addNavigationCandidate(world, segmentPos, segmentPos.offset(direction).down(), deduped);
        }

        for (int radius = 1; radius <= NEAREST_STANDABLE_SEARCH_RADIUS; radius++) {
            if (budgetExhausted) {
                break;
            }
            for (int dy = -2; dy <= 2; dy++) {
                if (budgetExhausted) {
                    break;
                }
                for (int dx = -radius; dx <= radius; dx++) {
                    if (budgetExhausted) {
                        break;
                    }
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos candidate = segmentPos.add(dx, dy, dz);
                        budgetExhausted |= addNavigationCandidate(world, segmentPos, candidate, deduped);
                        if (budgetExhausted) {
                            break;
                        }
                    }
                }
            }
        }

        List<NavigationCandidate> ranked = new ArrayList<>(deduped.values());
        if (applyBackoff) {
            long now = world.getTime();
            List<NavigationCandidate> backoffFiltered = new ArrayList<>(ranked.size());
            int filteredCount = 0;
            for (NavigationCandidate candidate : ranked) {
                if (isNavTargetInBackoff(segmentPos, candidate.pos(), now)) {
                    filteredCount++;
                    continue;
                }
                backoffFiltered.add(candidate);
            }
            if (!backoffFiltered.isEmpty()) {
                if (filteredCount > 0 && LOGGER.isDebugEnabled()) {
                    LOGGER.debug("MasonWallBuilder {}: candidate_backoff segment={} filtered={} reason=candidate_backoff",
                            guard.getUuidAsString(),
                            segmentPos.toShortString(),
                            filteredCount);
                }
                ranked = backoffFiltered;
            }
        }
        ranked.sort(Comparator
                .comparing(NavigationCandidate::pathAvailable).reversed()
                .thenComparingDouble(NavigationCandidate::distanceSqToSegment)
                .thenComparingInt(NavigationCandidate::obstacleCount));

        List<BlockPos> candidates = new ArrayList<>(ranked.size());
        for (NavigationCandidate candidate : ranked) {
            candidates.add(candidate.pos());
        }
        if (candidates.isEmpty()) {
            candidates.add(segmentPos.toImmutable());
        }
        return candidates;
    }

    private boolean addNavigationCandidate(ServerWorld world, BlockPos segmentPos, BlockPos candidatePos, Map<BlockPos, NavigationCandidate> deduped) {
        StandabilityAssessment standability = assessStandabilityForNavigation(world, candidatePos);
        if (!standability.standable()) {
            return false;
        }
        PreflightProbeResult preflight = evaluatePreflightPath(world, segmentPos, candidatePos);
        if (preflight == PreflightProbeResult.DEFERRED) {
            return true;
        }
        boolean pathAvailable = preflight == PreflightProbeResult.PASS;
        NavigationCandidate candidate = new NavigationCandidate(
                candidatePos.toImmutable(),
                pathAvailable,
                segmentPos.getSquaredDistance(candidatePos),
                standability.obstacleCount()
        );
        NavigationCandidate existing = deduped.get(candidate.pos());
        if (existing == null || isBetterNavigationCandidate(candidate, existing)) {
            deduped.put(candidate.pos(), candidate);
        }
        return false;
    }

    private boolean isBetterNavigationCandidate(NavigationCandidate candidate, NavigationCandidate existing) {
        if (candidate.pathAvailable() != existing.pathAvailable()) {
            return candidate.pathAvailable();
        }
        int distanceCompare = Double.compare(candidate.distanceSqToSegment(), existing.distanceSqToSegment());
        if (distanceCompare != 0) {
            return distanceCompare < 0;
        }
        return candidate.obstacleCount() < existing.obstacleCount();
    }

    private boolean isStandable(ServerWorld world, BlockPos pos) {
        return assessStandabilityForNavigation(world, pos).standable();
    }

    private StandabilityAssessment assessStandabilityForNavigation(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isSolidBlock(world, pos)) return new StandabilityAssessment(false, Integer.MAX_VALUE);
        BlockPos above = pos.up();
        BlockPos twoAbove = above.up();
        int obstructionCount = 0;
        int clearableLeafObstructions = 0;
        for (BlockPos headPos : new BlockPos[]{above, twoAbove}) {
            BlockState headState = world.getBlockState(headPos);
            if (!world.getFluidState(headPos).isEmpty()) {
                return new StandabilityAssessment(false, Integer.MAX_VALUE);
            }
            if (headState.getCollisionShape(world, headPos).isEmpty()) {
                continue;
            }
            if (isLowCostClearableWallObstacle(headState) && isBreakableWallObstacle(world, headPos, headState)) {
                continue;
            }
            if (headState.isIn(BlockTags.LEAVES)
                    && clearableLeafObstructions < 1
                    && isBreakableWallObstacle(world, headPos, headState)) {
                clearableLeafObstructions++;
                obstructionCount++;
                continue;
            }
            return new StandabilityAssessment(false, Integer.MAX_VALUE);
        }
        return new StandabilityAssessment(true, obstructionCount);
    }

    private void registerPathFailure(ServerWorld world, BlockPos segmentTarget, BlockPos attemptedNavTarget) {
        noteRetryDensityNonPlacementProgress();
        SegmentFailMetadata failMetadata = registerSegmentFailureMetadata(world, segmentTarget, attemptedNavTarget, "path_start_failed");
        if (firstPathFailureTick < 0L) {
            firstPathFailureTick = world.getTime();
        }
        failedPathAttempts++;
        lastTriedNavTarget = attemptedNavTarget.toImmutable();
        registerNavTargetFailureBackoff(world, segmentTarget, attemptedNavTarget, "path_start_failed");
        String bandSignature = segmentBandSignature(world, segmentTarget);
        int bandFailures = registerFailureBandAttempt(world, bandSignature);

        List<BlockPos> candidates = buildNavigationTargetCandidates(world, segmentTarget, true);
        if (failedPathAttempts < candidates.size()) {
            BlockPos fallback = candidates.get(failedPathAttempts);
            sortieFallbackTargetsTried++;
            LOGGER.debug("MasonWallBuilder {}: fallback_target_used segment={} attempted={} fallback={}",
                    guard.getUuidAsString(), segmentTarget.toShortString(),
                    attemptedNavTarget.toShortString(), fallback.toShortString());
        }

        boolean allFallbackTargetsFailed = failedPathAttempts >= candidates.size();
        boolean repeatedLivelockHardUnreachable = shouldPromoteRepeatedLivelockHardUnreachable(failMetadata);
        if (!(repeatedLivelockHardUnreachable
                || (allFallbackTargetsFailed && shouldMarkHardUnreachable(failedPathAttempts, firstPathFailureTick, world.getTime())))) {
            sortieTransientRetriesAttempted++;
            pathRetriesSinceCycleStart++;
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
            resetActiveMoveProgressTracking(world);
            resetPathFailureState();
            return;
        }

        if (bandFailures >= BAND_FAILURE_REPEAT_THRESHOLD
                && isVegetationDenseAroundSegment(world, segmentTarget)
                && deferSegmentForVegetationPass(world, segmentTarget, attemptedNavTarget)) {
            return;
        }

        sortieHardUnreachableMarked++;
        hardUnreachableSinceCycleStart++;
        if (shouldEmitHardUnreachableInfo(world, segmentTarget) || verboseMasonWallLogging()) {
            LOGGER.debug("MasonWallBuilder {}: hard_unreachable_after_retries segment={} attempts={} firstFailTick={} lastNavTarget={} reason={}",
                    guard.getUuidAsString(),
                    segmentTarget.toShortString(),
                    failedPathAttempts,
                    firstPathFailureTick,
                    lastTriedNavTarget == null ? "none" : lastTriedNavTarget.toShortString(),
                    repeatedLivelockHardUnreachable
                            ? "repeated_livelock_hard_unreachable"
                            : "fallbacks_exhausted");
        }
        localSortieQueue.pollFirst();
        releaseSegmentClaim(world, segmentTarget, "hard_unreachable");
        markSkippedSegment(world, segmentTarget, "hard_unreachable");
        if (!hardRetryPassStarted) {
            transitionSegmentState(world, segmentTarget, SegmentState.HARD_UNREACHABLE, "hard_unreachable");
            queueHardUnreachableRetry(segmentTarget.toImmutable());
        }
        activeMoveTarget = null;
        resetActiveMoveProgressTracking(world);
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

    private int registerFailureBandAttempt(ServerWorld world, String bandSignature) {
        long now = world.getTime();
        FailureBandWindow state = failureBandWindows.get(bandSignature);
        if (state == null || (now - state.firstFailureTick()) > BAND_FAILURE_WINDOW_TICKS) {
            failureBandWindows.put(bandSignature, new FailureBandWindow(1, now));
            return 1;
        }
        FailureBandWindow updated = new FailureBandWindow(state.count() + 1, state.firstFailureTick());
        failureBandWindows.put(bandSignature, updated);
        return updated.count();
    }

    private int registerNoProgressBandAbort(ServerWorld world, String bandSignature) {
        long now = world.getTime();
        FailureBandWindow state = noProgressAbortBandWindows.get(bandSignature);
        if (state == null || (now - state.firstFailureTick()) > BAND_NO_PROGRESS_ABORT_WINDOW_TICKS) {
            noProgressAbortBandWindows.put(bandSignature, new FailureBandWindow(1, now));
            return 1;
        }
        FailureBandWindow updated = new FailureBandWindow(state.count() + 1, state.firstFailureTick());
        noProgressAbortBandWindows.put(bandSignature, updated);
        return updated.count();
    }

    private int registerUnbreakableEscalationBandAttempt(ServerWorld world, String bandSignature) {
        long now = world.getTime();
        FailureBandWindow state = unbreakableEscalationBandWindows.get(bandSignature);
        if (state == null || (now - state.firstFailureTick()) > UNBREAKABLE_ESCALATION_BAND_WINDOW_TICKS) {
            unbreakableEscalationBandWindows.put(bandSignature, new FailureBandWindow(1, now));
            return 1;
        }
        FailureBandWindow updated = new FailureBandWindow(state.count() + 1, state.firstFailureTick());
        unbreakableEscalationBandWindows.put(bandSignature, updated);
        return updated.count();
    }

    private String localExclusionBandSignature(BlockPos segmentTarget, int layer) {
        return segmentTarget.getX() + "|" + segmentTarget.getZ() + "|" + layer;
    }

    private boolean isCycleExcludedBand(int layer, String bandSignature) {
        Set<String> excludedBands = cycleExcludedBandsByLayer.get(layer);
        return excludedBands != null && excludedBands.contains(bandSignature);
    }

    private boolean isSegmentInCycleExcludedBand(BlockPos segment, int layer) {
        return isCycleExcludedBand(layer, localExclusionBandSignature(segment, layer));
    }

    private boolean hasNonCycleExcludedBuildableSegments(ServerWorld world, int activeLayer, boolean preferNonQuarantinedBands) {
        long now = world.getTime();
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos pos = pendingSegments.get(i);
            if (getSegmentLayer(world, pos) != activeLayer) continue;
            if (isPivotExcludedSegment(world, i, pos)) continue;
            if (isRetryDensitySuppressedSegment(world, pos)) continue;
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, pos, now)) continue;
            if (isSegmentInCycleExcludedBand(pos, activeLayer)) continue;
            if (isBuildableCandidate(world, pos)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRetryDensitySuppressedSegment(ServerWorld world, BlockPos segment) {
        return retryDensityFallbackModeActive && isRegionSuppressed(world, segment);
    }

    private void maybeExcludeBandForUnbreakableEscalation(ServerWorld world, BlockPos triggerSegment, int repeatEscalationsInWindow) {
        int layer = getSegmentLayer(world, triggerSegment);
        if (layer < 1) {
            return;
        }
        String bandSignature = localExclusionBandSignature(triggerSegment, layer);
        if (repeatEscalationsInWindow < UNBREAKABLE_ESCALATION_BAND_REPEAT_THRESHOLD
                || isCycleExcludedBand(layer, bandSignature)) {
            return;
        }
        cycleExcludedBandsByLayer.computeIfAbsent(layer, ignored -> new HashSet<>()).add(bandSignature);
        int removedFromQueue = 0;
        for (java.util.Iterator<BlockPos> it = localSortieQueue.iterator(); it.hasNext(); ) {
            BlockPos queued = it.next();
            if (getSegmentLayer(world, queued) != layer) {
                continue;
            }
            if (!bandSignature.equals(localExclusionBandSignature(queued, layer))) {
                continue;
            }
            it.remove();
            releaseSegmentClaim(world, queued, "band_cycle_excluded");
            removedFromQueue++;
        }
        int affectedSegments = 0;
        for (BlockPos segment : pendingSegments) {
            if (getSegmentLayer(world, segment) != layer) {
                continue;
            }
            if (!bandSignature.equals(localExclusionBandSignature(segment, layer))) {
                continue;
            }
            if (isTerminalSegment(segment)) {
                continue;
            }
            markSegmentCycleExcluded(world, segment, "place_block_escalation_band:unbreakable_obstacle_repeat", repeatEscalationsInWindow);
            affectedSegments++;
        }
        LOGGER.warn("MasonWallBuilder {}: band_cycle_excluded layer={} band={} escalationsInWindow={} affectedSegments={} removedQueuedCandidates={}",
                guard.getUuidAsString(),
                layer,
                bandSignature,
                repeatEscalationsInWindow,
                affectedSegments,
                removedFromQueue);
    }

    private void maybeQuarantineBandForNoProgress(ServerWorld world, String bandSignature, int bandAbortHits, boolean forceEarlyCycle) {
        long now = world.getTime();
        if (isBandQuarantined(bandSignature, now)) {
            return;
        }
        if (!forceEarlyCycle && bandAbortHits < BAND_NO_PROGRESS_ABORT_THRESHOLD) {
            return;
        }
        long cooldownTicks = forceEarlyCycle
                ? Math.max(BAND_QUARANTINE_COOLDOWN_TICKS, EARLY_CYCLE_ABORT_COOLDOWN_TICKS)
                : BAND_QUARANTINE_COOLDOWN_TICKS;
        long quarantineUntil = now + cooldownTicks;
        quarantinedBandUntilTick.put(bandSignature, quarantineUntil);
        if (forceEarlyCycle) {
            int requiredPlacements = placementsSinceCycleStart + 1;
            reinsertionPlacementGateByBand.put(bandSignature, requiredPlacements);
            LOGGER.warn("MasonWallBuilder {}: early_cycle_band_quarantine band={} hits={} cooldownTicks={} quarantineUntilTick={} placementGate={}",
                    guard.getUuidAsString(),
                    bandSignature,
                    bandAbortHits,
                    cooldownTicks,
                    quarantineUntil,
                    requiredPlacements);
        } else {
            LOGGER.warn("MasonWallBuilder {}: band_quarantined band={} hits={} cooldownTicks={} quarantineUntilTick={}",
                    guard.getUuidAsString(),
                    bandSignature,
                    bandAbortHits,
                    cooldownTicks,
                    quarantineUntil);
        }
    }

    private boolean isBandQuarantined(String bandSignature, long now) {
        Long until = quarantinedBandUntilTick.get(bandSignature);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            quarantinedBandUntilTick.remove(bandSignature);
            Integer placementGate = reinsertionPlacementGateByBand.get(bandSignature);
            if (placementGate == null || placementsSinceCycleStart >= placementGate) {
                reinsertionPlacementGateByBand.remove(bandSignature);
                return false;
            }
        }
        Integer placementGate = reinsertionPlacementGateByBand.get(bandSignature);
        if (placementGate != null && placementsSinceCycleStart < placementGate) {
            return true;
        }
        reinsertionPlacementGateByBand.remove(bandSignature);
        return until > now;
    }

    private boolean isSegmentBandQuarantined(ServerWorld world, BlockPos segment, long now) {
        return isBandQuarantined(segmentBandSignature(world, segment), now);
    }

    private String segmentBandSignature(ServerWorld world, BlockPos segmentTarget) {
        return segmentTarget.getX() + "|" + segmentTarget.getZ() + "|" + getSegmentLayer(world, segmentTarget);
    }

    private boolean isVegetationDenseAroundSegment(ServerWorld world, BlockPos segmentTarget) {
        int blocked = 0;
        int vegetation = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos sample = segmentTarget.add(dx, dy, dz);
                    BlockState state = world.getBlockState(sample);
                    if (state.isAir()) {
                        continue;
                    }
                    blocked++;
                    if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                        vegetation++;
                    }
                }
            }
        }
        if (blocked < 4) {
            return false;
        }
        return vegetation * 10 >= blocked * 6;
    }

    private boolean deferSegmentForVegetationPass(ServerWorld world, BlockPos segmentTarget, BlockPos attemptedNavTarget) {
        int before = obstaclesCleared;
        performVegetationClearPass(world, segmentTarget, attemptedNavTarget);
        localSortieQueue.pollFirst();
        registerSegmentFailureMetadata(world, segmentTarget, attemptedNavTarget, "vegetation_deferred");
        registerNavTargetFailureBackoff(world, segmentTarget, attemptedNavTarget, "vegetation_deferred");
        requeueSegmentWithCooldown(world, segmentTarget, "vegetation_deferred", VEGETATION_DEFER_COOLDOWN_TICKS, "cooldown_requeue", -1);
        activeMoveTarget = null;
        resetActiveMoveProgressTracking(world);
        resetPathFailureState();
        LOGGER.debug("MasonWallBuilder {}: vegetation_path_defer segment={} navTarget={} clearedNow={} retryAt={}",
                guard.getUuidAsString(),
                segmentTarget.toShortString(),
                attemptedNavTarget.toShortString(),
                Math.max(0, obstaclesCleared - before),
                world.getTime() + VEGETATION_DEFER_COOLDOWN_TICKS);
        return true;
    }

    private void performVegetationClearPass(ServerWorld world, BlockPos segmentTarget, BlockPos attemptedNavTarget) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = 0; dy <= 2; dy++) {
            candidates.add(segmentTarget.up(dy).toImmutable());
            candidates.add(attemptedNavTarget.up(dy).toImmutable());
        }
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos near = segmentTarget.offset(direction);
            candidates.add(near.toImmutable());
            candidates.add(near.up().toImmutable());
        }
        int clearedThisPass = 0;
        for (BlockPos candidate : candidates) {
            if (clearedThisPass >= VEGETATION_CLEAR_PASS_BUDGET_PER_TICK) {
                break;
            }
            BlockState state = world.getBlockState(candidate);
            if (!(state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS))) {
                continue;
            }
            ObstacleClearanceResult result = clearObstacleAt(world, segmentTarget, candidate);
            if (result == ObstacleClearanceResult.CLEARED_THIS_TICK) {
                clearedThisPass++;
            }
        }
    }

    private void requeueDeferredSegmentsIfReady(ServerWorld world) {
        if (deferredSegmentRetryQueue.isEmpty()) {
            return;
        }
        long now = world.getTime();
        boolean enforceLayerOneFirstLap = isLayerOneFirstLapEnforced(world);
        List<DeferredSegmentRetry> readyLayerOne = enforceLayerOneFirstLap ? new ArrayList<>() : null;
        List<DeferredSegmentRetry> readyOtherLayers = enforceLayerOneFirstLap ? new ArrayList<>() : null;
        int queueSize = deferredSegmentRetryQueue.size();
        for (int i = 0; i < queueSize; i++) {
            DeferredSegmentRetry deferred = deferredSegmentRetryQueue.pollFirst();
            if (deferred == null) {
                continue;
            }
            if (deferred.requeueTick() > now) {
                deferredSegmentRetryQueue.offerLast(deferred);
                continue;
            }
            if (isTerminalSegment(deferred.segment())) {
                continue;
            }
            if (enforceLayerOneFirstLap) {
                if (getSegmentLayer(world, deferred.segment()) == 1) {
                    readyLayerOne.add(deferred);
                } else {
                    readyOtherLayers.add(deferred);
                }
                continue;
            }
            transitionSegmentState(world, deferred.segment(), SegmentState.AVAILABLE, "deferred_requeue");
            segmentCooldownUntilTick.remove(deferred.segment());
            queueHardUnreachableRetry(deferred.segment());
            if (!vegetationDeferredThenRequeuedLogged) {
                LOGGER.debug("MasonWallBuilder {}: vegetation_deferred_then_requeued segment={}",
                        guard.getUuidAsString(),
                        deferred.segment().toShortString());
                vegetationDeferredThenRequeuedLogged = true;
            }
        }
        if (enforceLayerOneFirstLap) {
            applyDeferredRequeuesInOrder(world, readyLayerOne);
            applyDeferredRequeuesInOrder(world, readyOtherLayers);
        }
    }

    private void applyDeferredRequeuesInOrder(ServerWorld world, List<DeferredSegmentRetry> deferredRetries) {
        if (deferredRetries == null || deferredRetries.isEmpty()) {
            return;
        }
        for (DeferredSegmentRetry deferred : deferredRetries) {
            transitionSegmentState(world, deferred.segment(), SegmentState.AVAILABLE, "deferred_requeue");
            segmentCooldownUntilTick.remove(deferred.segment());
            queueHardUnreachableRetry(deferred.segment());
            if (!vegetationDeferredThenRequeuedLogged) {
                LOGGER.debug("MasonWallBuilder {}: vegetation_deferred_then_requeued segment={}",
                        guard.getUuidAsString(),
                        deferred.segment().toShortString());
                vegetationDeferredThenRequeuedLogged = true;
            }
        }
    }

    private void resetPathFailureState() {
        failedPathAttempts = 0;
        firstPathFailureTick = -1L;
        lastTriedNavTarget = null;
    }

    private void clearPlaceBlockFailureTracking() {
        placeBlockFailureTarget = null;
        placeBlockConsecutiveNonPlacementTicks = 0;
        placeBlockLastNonPlacementReason = null;
    }

    private void clearLastPlantClearedTracking() {
        lastPlantClearedTarget = null;
        lastPlantClearedTick = -1L;
    }

    private boolean shouldPrioritizePlantClearRetry(ServerWorld world, BlockPos target) {
        if (lastPlantClearedTarget == null || !lastPlantClearedTarget.equals(target)) {
            return false;
        }
        long now = world.getTime();
        long delta = now - lastPlantClearedTick;
        return delta > 0L && delta <= PLANT_CLEAR_RETRY_WINDOW_TICKS;
    }

    private void markPlantClearedForRetry(ServerWorld world, BlockPos target) {
        lastPlantClearedTarget = target.toImmutable();
        lastPlantClearedTick = world.getTime();
    }

    private boolean recordPlaceBlockNonPlacementAndMaybeEscalate(ServerWorld world, BlockPos target, String reasonCode) {
        noteRetryDensityNonPlacementProgress();
        BlockPos immutableTarget = target.toImmutable();
        if (!immutableTarget.equals(placeBlockFailureTarget)) {
            placeBlockFailureTarget = immutableTarget;
            placeBlockConsecutiveNonPlacementTicks = 0;
        }
        placeBlockConsecutiveNonPlacementTicks++;
        placeBlockLastNonPlacementReason = reasonCode;
        if (placeBlockConsecutiveNonPlacementTicks < PLACE_BLOCK_NON_PLACEMENT_ESCALATION_THRESHOLD) {
            return false;
        }

        localSortieQueue.pollFirst();
        markSegmentCycleExcluded(world, immutableTarget, "place_block_escalation:" + reasonCode, placeBlockConsecutiveNonPlacementTicks);
        if ("unbreakable_obstacle_repeat".equals(reasonCode)) {
            int layer = getSegmentLayer(world, immutableTarget);
            if (layer > 0) {
                String localBand = localExclusionBandSignature(immutableTarget, layer);
                int escalationCount = registerUnbreakableEscalationBandAttempt(world, localBand);
                maybeExcludeBandForUnbreakableEscalation(world, immutableTarget, escalationCount);
            }
        }
        LOGGER.info("MasonWallBuilder {}: place_block_escalation target={} reason={}",
                guard.getUuidAsString(),
                immutableTarget.toShortString(),
                reasonCode);
        clearPlaceBlockFailureTracking();
        return true;
    }

    private void tickPlaceBlock(ServerWorld world) {
        if (maybeAbortSortieForNoNetProgress(world, Stage.PLACE_BLOCK)) {
            return;
        }
        BlockPos target = localSortieQueue.peekFirst();
        if (target == null) {
            noteRetryDensityNonPlacementProgress();
            clearPlaceBlockFailureTracking();
            clearLastPlantClearedTracking();
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }

        if (lastPlantClearedTarget != null && !lastPlantClearedTarget.equals(target)) {
            clearLastPlantClearedTracking();
        }

        boolean prioritizePlantClearRetry = shouldPrioritizePlantClearRetry(world, target);

        // Verify still unbuilt
        if (isPlacedWallBlock(world.getBlockState(target)) || isGatePosition(target)) {
            localSortieQueue.pollFirst();
            releaseSegmentClaim(world, target, "already_resolved");
            transitionSegmentState(world, target, SegmentState.PLACED, "already_resolved");
            clearSegmentFailureState(target);
            clearPlaceBlockFailureTracking();
            clearLastPlantClearedTracking();
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }

        ObstacleClearanceResult clearance = prepareSegmentForPlacement(world, target);
        if (clearance == ObstacleClearanceResult.CLEARED_THIS_TICK) {
            if (prioritizePlantClearRetry) {
                clearLastPlantClearedTracking();
                stage = Stage.MOVE_TO_SEGMENT;
                return;
            }
            if (recordPlaceBlockNonPlacementAndMaybeEscalate(world, target, "clearance_retry")) {
                stage = Stage.MOVE_TO_SEGMENT;
                return;
            }
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }
        if (clearance == ObstacleClearanceResult.PROTECTED_OBSTACLE
                || clearance == ObstacleClearanceResult.UNBREAKABLE_OBSTACLE) {
            String reasonCode = clearance == ObstacleClearanceResult.PROTECTED_OBSTACLE
                    ? "protected_obstacle_repeat"
                    : "unbreakable_obstacle_repeat";
            if (recordPlaceBlockNonPlacementAndMaybeEscalate(world, target, reasonCode)) {
                stage = Stage.MOVE_TO_SEGMENT;
                return;
            }
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

        int availablePlacementUnits = countWallMaterialUnitsInChest(world, chestPos);
        if (availablePlacementUnits < 1) {
            noteRetryDensityNonPlacementProgress();
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
        recordPlacementProgress(world, target);
        clearPlaceBlockFailureTracking();
        clearLastPlantClearedTracking();
        if (!firstPlacementLoggedThisCycle) {
            logInfoRateLimited(world, "first_placement", PERIODIC_INFO_INTERVAL_TICKS,
                    "MasonWallBuilder {}: first placement in cycle at {}",
                    guard.getUuidAsString(), target.toShortString());
            firstPlacementLoggedThisCycle = true;
        }
        LOGGER.debug("MasonWallBuilder {}: placed {} at {}",
                guard.getUuidAsString(), placementMaterial.item().toString(), target.toShortString());

        localSortieQueue.pollFirst();
        releaseSegmentClaim(world, target, "placed");
        transitionSegmentState(world, target, SegmentState.PLACED, "placed");
        clearSegmentFailureState(target);
        resetSortieAbortBackoffAfterBandPlacement(world, target);
        sortiePlacements++;
        placedSegments++;
        resetSortieNoNetProgressGuard(world);
        guard.setWallBuildPending(hasRemainingSegments(world), computeCurrentSortieThreshold(world));
        maybeLogPlacementProgress();
        stage = Stage.MOVE_TO_SEGMENT;
    }

    private boolean maybeAbortSortieForNoNetProgress(ServerWorld world, Stage activeStage) {
        if (!sortieActive || sortieStartTick < 0L) {
            return false;
        }
        long now = world.getTime();
        long elapsedTicks = Math.max(0L, now - sortieStartTick);
        if (elapsedTicks <= SORTIE_NO_NET_PROGRESS_TIMEOUT_TICKS) {
            return false;
        }

        int placementDelta = placedSegments - sortiePlacementsAtStart;
        if (placementDelta >= SORTIE_NO_NET_PROGRESS_MIN_PLACEMENT_DELTA) {
            return false;
        }
        if (isNoNetProgressAbortSuppressedByStartupGrace(world)) {
            if (!sortieNoNetProgressGraceSuppressedLogged) {
                long cycleElapsedTicks = cycleStartTick < 0L ? -1L : Math.max(0L, now - cycleStartTick);
                long sortieElapsedTicks = sortieActivationTick < 0L ? -1L : Math.max(0L, now - sortieActivationTick);
                LOGGER.info("MasonWallBuilder {}: sortie_abort_no_net_progress_suppressed stage={} elapsedTicks={} placementDelta={} queueSize={} cycleElapsedTicks={} sortieElapsedTicks={} abortSuppressedByStartupGrace=true",
                        guard.getUuidAsString(),
                        activeStage,
                        elapsedTicks,
                        placementDelta,
                        localSortieQueue.size(),
                        cycleElapsedTicks,
                        sortieElapsedTicks);
                sortieNoNetProgressGraceSuppressedLogged = true;
            }
            return false;
        }
        sortieNoNetProgressGraceSuppressedLogged = false;

        LOGGER.info("MasonWallBuilder {}: sortie_abort_no_net_progress stage={} elapsedTicks={} placementDelta={} queueSize={}",
                guard.getUuidAsString(),
                activeStage,
                elapsedTicks,
                placementDelta,
                localSortieQueue.size());

        List<BlockPos> cooldownTargets = new ArrayList<>(localSortieQueue);
        releaseLocalSortieClaims(world, "sortie_abort_no_net_progress");
        localSortieQueue.clear();
        Map<String, Integer> bandRepeatCounts = new HashMap<>();
        Set<String> earlyCycleBands = new HashSet<>();
        for (BlockPos segment : cooldownTargets) {
            String bandKey = segmentBandSignature(world, segment);
            int bandAbortHits = registerNoProgressBandAbort(world, bandKey);
            boolean earlyCycleBand = isEarlyCycleAbortBand(segment);
            if (earlyCycleBand) {
                earlyCycleBands.add(bandKey);
            }
            maybeQuarantineBandForNoProgress(world, bandKey, bandAbortHits, earlyCycleBand);
            bandRepeatCounts.computeIfAbsent(bandKey, ignored -> incrementSortieAbortBandRepeatCount(bandKey));
        }
        for (BlockPos segment : cooldownTargets) {
            String bandKey = segmentBandSignature(world, segment);
            boolean quarantined = isBandQuarantined(bandKey, now);
            int segmentRepeatCount = incrementSortieAbortSegmentRepeatCount(segment, bandKey);
            int bandRepeatCount = bandRepeatCounts.getOrDefault(bandKey, 1);
            int repeatCount = Math.max(segmentRepeatCount, bandRepeatCount);
            long adaptiveCooldown = computeSortieAbortAdaptiveCooldownTicks(repeatCount);
            long cooldown = quarantined
                    ? BAND_QUARANTINE_COOLDOWN_TICKS
                    : adaptiveCooldown;
            if (earlyCycleBands.contains(bandKey)) {
                cooldown = Math.max(cooldown, EARLY_CYCLE_ABORT_COOLDOWN_TICKS);
            }
            requeueSegmentWithCooldown(
                    world,
                    segment,
                    "sortie_abort_no_net_progress",
                    cooldown,
                    "sortie_abort_cooldown_requeue",
                    repeatCount
            );
        }

        activeMoveTarget = null;
        resetActiveMoveProgressTracking(world);
        resetPathFailureState();
        logSortieSummaryIfActive();
        if (!startNextSortie(world)) {
            stage = Stage.WAIT_FOR_WALL_STOCK;
        }
        return true;
    }

    private boolean isEarlyCycleAbortBand(BlockPos segment) {
        if (placementsSinceCycleStart > 0) {
            return false;
        }
        Integer candidateIndex = segmentCandidateIndexByPos.get(segment.toImmutable());
        if (candidateIndex == null) {
            return false;
        }
        return candidateIndex < EARLY_CYCLE_CANDIDATE_INDEX_BAND_SIZE;
    }

    private boolean isNoNetProgressAbortSuppressedByStartupGrace(ServerWorld world) {
        if (placementsSinceCycleStart > 0) {
            return false;
        }
        long now = world.getTime();
        long cycleElapsedTicks = cycleStartTick < 0L ? Long.MAX_VALUE : Math.max(0L, now - cycleStartTick);
        long sortieElapsedTicks = sortieActivationTick < 0L ? Long.MAX_VALUE : Math.max(0L, now - sortieActivationTick);
        return cycleElapsedTicks < SORTIE_NO_NET_PROGRESS_STARTUP_GRACE_TICKS
                || sortieElapsedTicks < SORTIE_NO_NET_PROGRESS_STARTUP_GRACE_TICKS;
    }

    private void resetSortieNoNetProgressGuard(ServerWorld world) {
        sortiePlacementsAtStart = placedSegments;
        sortieStartTick = world == null ? -1L : world.getTime();
    }

    private void maybeLogPlacementProgress() {
        if (!isElectedBuilder || plannedPlacementCount <= 0) return;
        int placed = placedSegments;
        int total = plannedPlacementCount;
        if (placed >= total || placed <= 10 || placed - lastProgressLoggedSegmentCount >= 25) {
            lastProgressLoggedSegmentCount = placed;
            int percent = (int) Math.floor((placed * 100.0) / total);
            logDetailed("MasonWallBuilder {}: placement progress {}/{} ({}%)",
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
            BlockState obstructionState = world.getBlockState(obstructionPos);
            if (!isLowCostClearableWallObstacle(obstructionState)) {
                continue;
            }
            ObstacleClearanceResult result = clearObstacleAt(world, target, obstructionPos);
            if (result != ObstacleClearanceResult.CLEAR && result != ObstacleClearanceResult.CLEARED_THIS_TICK) {
                return result;
            }
        }

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
        String reasonCode = "cleared_obstacle";
        if (isLowCostClearableWallObstacle(state) && obstaclePos.getY() == segmentPos.getY()) {
            reasonCode = "cleared_ground_plant";
            markPlantClearedForRetry(world, segmentPos);
        }
        LOGGER.debug("MasonWallBuilder {}: cleared_obstacle segment={} obstacle={} block={} reason={}",
                guard.getUuidAsString(),
                segmentPos.toShortString(),
                obstaclePos.toShortString(),
                state.getBlock().getTranslationKey(),
                reasonCode);
        return ObstacleClearanceResult.CLEARED_THIS_TICK;
    }

    private boolean isProtectedObstacle(ServerWorld world, BlockPos pos, BlockState state) {
        if (isLowCostClearableWallObstacle(state)) {
            return false;
        }
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
        if (isLowCostClearableWallObstacle(state)) {
            return state.getHardness(world, pos) >= 0.0F;
        }
        boolean isLeafOrLog = state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS);
        boolean isPickaxeBreakable = state.isIn(BlockTags.PICKAXE_MINEABLE);
        if (!isLeafOrLog && !isPickaxeBreakable) return false;
        return state.getHardness(world, pos) >= 0.0F;
    }

    private boolean isLowCostClearableWallObstacle(BlockState state) {
        return state.isReplaceable()
                || state.isIn(BlockTags.FLOWERS)
                || state.isIn(BlockTags.SMALL_FLOWERS)
                || state.isIn(BlockTags.SAPLINGS)
                || state.isOf(Blocks.SHORT_GRASS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.DEAD_BUSH);
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

    private void markSkippedSegment(ServerWorld world, BlockPos pos, String reason) {
        BlockPos immutablePos = pos.toImmutable();
        transitionSegmentState(world, immutablePos, SegmentState.SKIPPED_TEMP, reason);
        registerSegmentFailureMetadata(world, immutablePos, null, reason);
    }

    private void clearSegmentFailureState(BlockPos pos) {
        BlockPos immutablePos = pos.toImmutable();
        transitionSegmentState(worldOrNull(), immutablePos, SegmentState.AVAILABLE, "clear_failure_state");
        skippedSegmentFailMetadata.remove(immutablePos);
        segmentCooldownUntilTick.remove(immutablePos);
        segmentNavTargetBackoffUntilTick.remove(immutablePos);
        segmentRepeatRecoveryCounts.remove(immutablePos);
        terminalSegmentReasons.remove(immutablePos);
    }

    private int incrementSortieAbortBandRepeatCount(String bandKey) {
        int next = sortieAbortBandRepeatCounts.getOrDefault(bandKey, 0) + 1;
        sortieAbortBandRepeatCounts.put(bandKey, next);
        return next;
    }

    private int incrementSortieAbortSegmentRepeatCount(BlockPos segment, String bandKey) {
        BlockPos immutableSegment = segment.toImmutable();
        int next = sortieAbortSegmentRepeatCounts.getOrDefault(immutableSegment, 0) + 1;
        sortieAbortSegmentRepeatCounts.put(immutableSegment, next);
        sortieAbortSegmentBandKeys.put(immutableSegment, bandKey);
        return next;
    }

    private long computeSortieAbortAdaptiveCooldownTicks(int repeatCount) {
        return computeAdaptiveCooldown(repeatCount, SEGMENT_COOLDOWN_TICKS, SORTIE_ABORT_BACKOFF_MAX_COOLDOWN_TICKS);
    }

    private void resetSortieAbortBackoffAfterBandPlacement(ServerWorld world, BlockPos placedSegment) {
        String placedBandKey = segmentBandSignature(world, placedSegment);
        sortieAbortBandRepeatCounts.remove(placedBandKey);
        sortieAbortSegmentRepeatCounts.remove(placedSegment.toImmutable());
        sortieAbortSegmentBandKeys.remove(placedSegment.toImmutable());
        sortieAbortSegmentBandKeys.entrySet().removeIf(entry -> {
            if (!placedBandKey.equals(entry.getValue())) {
                return false;
            }
            sortieAbortSegmentRepeatCounts.remove(entry.getKey());
            return true;
        });
    }

    private SegmentFailMetadata registerSegmentFailureMetadata(ServerWorld world, BlockPos segment, BlockPos attemptedNavTarget, String reason) {
        BlockPos immutableSegment = segment.toImmutable();
        long now = world == null ? -1L : world.getTime();
        int navHash = attemptedNavTarget == null ? Integer.MIN_VALUE : attemptedNavTarget.toImmutable().hashCode();
        SegmentFailMetadata previous = skippedSegmentFailMetadata.get(immutableSegment);
        SegmentFailMetadata next = previous == null
                ? SegmentFailMetadata.create(now, reason, navHash, placementsSinceCycleStart)
                : previous.withFailure(now, reason, navHash);
        skippedSegmentFailMetadata.put(immutableSegment, next);
        return next;
    }

    private void registerNavTargetFailureBackoff(ServerWorld world, BlockPos segment, BlockPos navTarget, String reasonTag) {
        if (world == null || navTarget == null) {
            return;
        }
        BlockPos immutableSegment = segment.toImmutable();
        Map<Integer, Long> backoffByHash = segmentNavTargetBackoffUntilTick.computeIfAbsent(immutableSegment, ignored -> new HashMap<>());
        int navHash = navTarget.toImmutable().hashCode();
        long until = world.getTime() + NAV_TARGET_BACKOFF_TICKS;
        backoffByHash.put(navHash, until);
        LOGGER.debug("MasonWallBuilder {}: candidate_backoff segment={} navTarget={} ttl={} reason={}",
                guard.getUuidAsString(),
                immutableSegment.toShortString(),
                navTarget.toShortString(),
                NAV_TARGET_BACKOFF_TICKS,
                reasonTag);
    }

    private boolean isNavTargetInBackoff(BlockPos segment, BlockPos navTarget, long now) {
        Map<Integer, Long> backoffByHash = segmentNavTargetBackoffUntilTick.get(segment.toImmutable());
        if (backoffByHash == null || backoffByHash.isEmpty()) {
            return false;
        }
        int navHash = navTarget.toImmutable().hashCode();
        Long until = backoffByHash.get(navHash);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            backoffByHash.remove(navHash);
            if (backoffByHash.isEmpty()) {
                segmentNavTargetBackoffUntilTick.remove(segment.toImmutable());
            }
            return false;
        }
        return true;
    }

    private boolean isSegmentInCooldown(BlockPos segment, long now) {
        Long until = segmentCooldownUntilTick.get(segment.toImmutable());
        if (until == null) {
            return false;
        }
        if (until <= now) {
            segmentCooldownUntilTick.remove(segment.toImmutable());
            return false;
        }
        return true;
    }

    private void requeueSegmentWithCooldown(ServerWorld world, BlockPos segment, String reason, long cooldownTicks, String reasonTag, int repeatRecoveryCount) {
        long requeueTick = world.getTime() + cooldownTicks;
        BlockPos immutableSegment = segment.toImmutable();
        segmentCooldownUntilTick.put(immutableSegment, requeueTick);
        releaseSegmentClaim(world, immutableSegment, reasonTag);
        markSkippedSegment(world, immutableSegment, reason);
        transitionSegmentState(world, immutableSegment, SegmentState.DEFERRED, reasonTag);
        deferredSegmentRetryQueue.offerLast(new DeferredSegmentRetry(immutableSegment, requeueTick));
        LOGGER.info("MasonWallBuilder {}: {} segment={} reason={} requeueTick={} cooldownTicks={} repeatRecoveryCount={}",
                guard.getUuidAsString(),
                reasonTag,
                immutableSegment.toShortString(),
                reason,
                requeueTick,
                cooldownTicks,
                repeatRecoveryCount);
    }

    private int incrementRepeatRecoveryCount(BlockPos segment) {
        BlockPos immutableSegment = segment.toImmutable();
        int next = segmentRepeatRecoveryCounts.getOrDefault(immutableSegment, 0) + 1;
        segmentRepeatRecoveryCounts.put(immutableSegment, next);
        return next;
    }

    private long computeWatchdogRepeatRecoveryCooldownTicks(int repeatRecoveryCount) {
        if (repeatRecoveryCount <= 1) {
            return WATCHDOG_REPEAT_SEGMENT_DEFER_COOLDOWN_TICKS;
        }
        if (repeatRecoveryCount == 2) {
            return WATCHDOG_REPEAT_SEGMENT_DEFER_COOLDOWN_TICKS * 3L;
        }
        return WATCHDOG_REPEAT_SEGMENT_DEFER_COOLDOWN_TICKS * 6L;
    }

    private void markSegmentCycleExcluded(ServerWorld world, BlockPos segment, String reason, int repeatRecoveryCount) {
        BlockPos immutableSegment = segment.toImmutable();
        terminalSegmentReasons.put(immutableSegment, reason);
        segmentCooldownUntilTick.remove(immutableSegment);
        deferredSegmentRetryQueue.removeIf(deferred -> deferred.segment().equals(immutableSegment));
        hardUnreachableRetryQueue.remove(immutableSegment);
        localSortieQueue.remove(immutableSegment);
        releaseSegmentClaim(world, immutableSegment, "cycle_excluded");
        skippedSegmentFailMetadata.remove(immutableSegment);
        skippedSegments.remove(immutableSegment);
        skippedSegmentReasons.remove(immutableSegment);
        transitionSegmentState(world, immutableSegment, SegmentState.CYCLE_EXCLUDED, "cycle_excluded");
        LOGGER.warn("MasonWallBuilder {}: watchdog_cycle_excluded segment={} reason={} repeatRecoveryCount={} requeueSuppressed=true",
                guard.getUuidAsString(),
                immutableSegment.toShortString(),
                reason,
                repeatRecoveryCount);
    }

    private boolean shouldPromoteRepeatedLivelockHardUnreachable(SegmentFailMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        if (metadata.failCount() < EARLY_HARD_UNREACHABLE_FAIL_THRESHOLD) {
            return false;
        }
        if (metadata.distinctFailedNavTargetCount() < EARLY_HARD_UNREACHABLE_DISTINCT_TARGET_THRESHOLD) {
            return false;
        }
        return placementsSinceCycleStart <= metadata.placementsAtFirstFailure();
    }

    private void seedSegmentStateModel(ServerWorld world) {
        for (BlockPos segment : pendingSegments) {
            if (isGatePosition(segment) || (world != null && isPlacedWallBlock(world.getBlockState(segment)))) {
                transitionSegmentState(world, segment, SegmentState.PLACED, "seed_resolved");
            } else {
                transitionSegmentState(world, segment, SegmentState.AVAILABLE, "seed_available");
            }
        }
        debugLogSegmentStateSanity(world, "seed");
    }

    private void transitionSegmentState(ServerWorld world, BlockPos pos, SegmentState nextState, String reason) {
        BlockPos immutablePos = pos.toImmutable();
        SegmentState previous = segmentStates.put(immutablePos, nextState);
        if (didEnterDeferredOrSkippedState(previous, nextState)) {
            deferredOrSkippedSinceCycleStart++;
        }

        skippedSegments.remove(immutablePos);
        skippedSegmentReasons.remove(immutablePos);
        hardUnreachableRetryQueue.remove(immutablePos);

        if (nextState == SegmentState.SKIPPED_TEMP || nextState == SegmentState.HARD_UNREACHABLE || nextState == SegmentState.DEFERRED) {
            skippedSegments.add(immutablePos);
            skippedSegmentReasons.put(immutablePos, reason);
        }
        if (nextState != SegmentState.CYCLE_EXCLUDED && nextState != SegmentState.IRRECOVERABLE) {
            terminalSegmentReasons.remove(immutablePos);
        }

        if (nextState != SegmentState.CLAIMED) {
            claimedSortieSegments.remove(immutablePos);
        }

        if (LOGGER.isDebugEnabled() && previous != nextState) {
            LOGGER.debug("MasonWallBuilder {}: segment_state_transition segment={} {} -> {} reason={}",
                    guard.getUuidAsString(),
                    immutablePos.toShortString(),
                    previous == null ? "null" : previous,
                    nextState,
                    reason);
        }
    }

    private boolean didEnterDeferredOrSkippedState(SegmentState previous, SegmentState next) {
        boolean nextIsDeferredOrSkipped = next == SegmentState.SKIPPED_TEMP
                || next == SegmentState.HARD_UNREACHABLE
                || next == SegmentState.DEFERRED;
        if (!nextIsDeferredOrSkipped) {
            return false;
        }
        return previous != SegmentState.SKIPPED_TEMP
                && previous != SegmentState.HARD_UNREACHABLE
                && previous != SegmentState.DEFERRED;
    }

    private void queueHardUnreachableRetry(BlockPos pos) {
        BlockPos immutablePos = pos.toImmutable();
        if (isTerminalSegment(immutablePos)) {
            return;
        }
        hardUnreachableRetryQueue.remove(immutablePos);
        hardUnreachableRetryQueue.offerFirst(immutablePos);
    }

    private void markSegmentClaimed(ServerWorld world, BlockPos segment, String reason) {
        BlockPos immutable = segment.toImmutable();
        claimedSortieSegments.add(immutable);
        transitionSegmentState(world, immutable, SegmentState.CLAIMED, reason);
    }

    private boolean unmarkSegmentClaimed(ServerWorld world, BlockPos segment, String reason) {
        BlockPos immutable = segment.toImmutable();
        if (!claimedSortieSegments.remove(immutable)) {
            return false;
        }
        transitionSegmentState(world, immutable, SegmentState.AVAILABLE, reason);
        return true;
    }

    private void recoverSegmentStatesForLayerAfterWatchdog(ServerWorld world, int activeLayer) {
        for (BlockPos segment : pendingSegments) {
            if (getSegmentLayer(world, segment) != activeLayer) continue;
            SegmentState state = segmentStates.getOrDefault(segment, SegmentState.AVAILABLE);
            if (state != SegmentState.SKIPPED_TEMP && state != SegmentState.HARD_UNREACHABLE) continue;
            if (isTerminalSegment(segment)) continue;
            if (isGatePosition(segment) || isPlacedWallBlock(world.getBlockState(segment))) {
                transitionSegmentState(world, segment, SegmentState.PLACED, "watchdog_resolved");
                skippedSegmentFailMetadata.remove(segment);
                segmentCooldownUntilTick.remove(segment);
                segmentNavTargetBackoffUntilTick.remove(segment);
                continue;
            }
            if (hasPathCandidateForSegment(world, segment)) {
                transitionSegmentState(world, segment, SegmentState.AVAILABLE, "watchdog_recover_available");
            } else {
                transitionSegmentState(world, segment, SegmentState.DEFERRED, "watchdog_recover_deferred");
            }
        }
    }

    private void debugLogSegmentStateSanity(ServerWorld world, String source) {
        if (!LOGGER.isDebugEnabled()) return;
        Map<SegmentState, Integer> counts = new EnumMap<>(SegmentState.class);
        for (SegmentState state : SegmentState.values()) {
            counts.put(state, 0);
        }
        for (BlockPos segment : pendingSegments) {
            SegmentState state = segmentStates.getOrDefault(segment, SegmentState.AVAILABLE);
            counts.put(state, counts.getOrDefault(state, 0) + 1);
        }
        int trackedTotal = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (trackedTotal != pendingSegments.size()) {
            LOGGER.debug("MasonWallBuilder {}: segment_state_sanity_mismatch source={} trackedTotal={} pending={}",
                    guard.getUuidAsString(), source, trackedTotal, pendingSegments.size());
        }
        LOGGER.debug("MasonWallBuilder {}: segment_state_sanity source={} counts={}",
                guard.getUuidAsString(), source, counts);
    }

    private boolean tryRecoverSkippedSegmentIfEligible(ServerWorld world, BlockPos pos, String source) {
        BlockPos immutablePos = pos.toImmutable();
        if (!skippedSegments.contains(immutablePos)) {
            return true;
        }
        if (isTerminalSegment(immutablePos)) {
            return false;
        }
        if (isGatePosition(immutablePos) || isPlacedWallBlock(world.getBlockState(immutablePos))) {
            clearSegmentFailureState(immutablePos);
            return false;
        }
        if (!hasPathCandidateForSegment(world, immutablePos)) {
            return false;
        }
        SegmentFailMetadata failMetadata = skippedSegmentFailMetadata.get(immutablePos);
        transitionSegmentState(world, immutablePos, SegmentState.AVAILABLE, "recovered:" + source);
        queueHardUnreachableRetry(immutablePos);
        LOGGER.debug("MasonWallBuilder {}: recovered_skipped_segment segment={} source={} failMeta={}",
                guard.getUuidAsString(),
                immutablePos.toShortString(),
                source,
                failMetadata);
        return true;
    }

    private boolean hasPathCandidateForSegment(ServerWorld world, BlockPos segment) {
        List<BlockPos> candidates = buildNavigationTargetCandidates(world, segment, true);
        for (BlockPos navigationTarget : candidates) {
            if (!isStandable(world, navigationTarget)) {
                continue;
            }
            Path preflightPath = guard.getNavigation().findPathTo(navigationTarget, 0);
            if (preflightPath != null && preflightPath.reachesTarget()) {
                return true;
            }
        }
        return false;
    }

    private void maybeReconcileSkippedSegments(ServerWorld world) {
        if (world.getTime() < nextSkippedSegmentReconciliationTick) {
            return;
        }
        nextSkippedSegmentReconciliationTick = world.getTime() + SKIPPED_SEGMENT_RECONCILIATION_INTERVAL_TICKS;
        int activeLayer = findLowestPendingLayer(world);
        if (activeLayer < 1 || skippedSegments.isEmpty()) {
            return;
        }
        reconcileSkippedSegmentsForLayer(world, activeLayer, "periodic_reconcile");
    }

    private void reconcileSkippedSegmentsForLayer(ServerWorld world, int activeLayer, String source) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos segment : skippedSegments) {
            if (getSegmentLayer(world, segment) == activeLayer) {
                candidates.add(segment);
            }
        }
        for (BlockPos segment : candidates) {
            tryRecoverSkippedSegmentIfEligible(world, segment, source);
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
        sortieCandidatesPreflightPass = 0;
        sortieCandidatesPreflightRejected = 0;
        sortieCandidatesFallbackAccepted = 0;
        sortieNearestFallbackSuppressedByLayerOneLap = false;
        deferredPreflightSegmentsThisSortie.clear();
        pathProbeBudgetRemainingThisSortie = PATH_PROBE_BUDGET_PER_SORTIE;
        int activeLayer = findLowestPendingLayer(world);
        if (activeLayer < 1) {
            return false;
        }
        boolean preferNonQuarantinedBands = hasNonQuarantinedBuildableSegments(world, activeLayer);
        int buildablePoolSize = countBuildableSegmentsForLayer(world, activeLayer, preferNonQuarantinedBands, false);
        boolean preferNonCycleExcludedBands = hasNonCycleExcludedBuildableSegments(world, activeLayer, preferNonQuarantinedBands);
        if (buildablePoolSize <= CRITICAL_BUILDABLE_POOL_SIZE) {
            preferNonCycleExcludedBands = false;
        }
        reconcileSkippedSegmentsForLayer(world, activeLayer, "sortie_start");
        debugLogSegmentStateSanity(world, "sortie_start");
        LayerOneDominanceContext layerOneDominance = buildLayerOneDominanceContext(world, activeLayer);

        int anchorAttempts = 0;
        BlockPos selectedAnchor = null;
        List<BlockPos> selectedBatch = List.of();
        String forcedRotationFromRegion = null;
        while (anchorAttempts < MAX_SORTIE_ANCHOR_ATTEMPTS_PER_TICK) {
            boolean enforceLayerOneFirstLap = isLayerOneFirstLapEnforced(world);
            DominantAnchorSelection dominantSelection = null;
            if (layerOneDominance.active() && !enforceLayerOneFirstLap) {
                dominantSelection = findDominantLayerOneAnchor(world, activeLayer, preferNonQuarantinedBands, preferNonCycleExcludedBands, layerOneDominance);
            }
            BlockPos anchor = dominantSelection != null
                    ? dominantSelection.anchor()
                    : findNextIndexAnchor(world, activeLayer, preferNonQuarantinedBands, preferNonCycleExcludedBands);
            if (anchor == null) {
                break;
            }
            if (dominantSelection != null && dominantSelection.forcedFromRegion() != null) {
                forcedRotationFromRegion = dominantSelection.forcedFromRegion();
            }

            List<BlockPos> anchorBatch = buildLocalSortieCandidates(world, anchor, activeLayer, preferNonQuarantinedBands, preferNonCycleExcludedBands);
            if (anchorBatch.size() < MIN_SEGMENTS_PER_SORTIE) {
                boolean suppressNearestFallbackForFirstLap = enforceLayerOneFirstLap || (activeLayer == 1 && !isLayerOneAggressiveHeuristicsEnabled(activeLayer));
                if (suppressNearestFallbackForFirstLap) {
                    sortieNearestFallbackSuppressedByLayerOneLap = true;
                } else {
                    BlockPos nearest = findNearestUnbuiltSegment(world, guard.getBlockPos(), activeLayer, preferNonQuarantinedBands, preferNonCycleExcludedBands);
                    if (nearest != null && !nearest.equals(anchor)) {
                        List<BlockPos> nearestBatch = buildLocalSortieCandidates(world, nearest, activeLayer, preferNonQuarantinedBands, preferNonCycleExcludedBands);
                        if (!nearestBatch.isEmpty()) {
                            anchor = nearest;
                            anchorBatch = nearestBatch;
                        }
                    }
                }
            }
            if (!anchorBatch.isEmpty()) {
                selectedAnchor = anchor;
                selectedBatch = anchorBatch;
                break;
            }
            markSkippedSegment(world, anchor, "anchor_no_candidates");
            anchorAttempts++;
        }
        if (selectedBatch.isEmpty() || selectedAnchor == null) {
            return false;
        }
        if (layerOneDominance.active()) {
            recordLayerOneSortieRegionSelection(world, selectedAnchor, layerOneDominance.regionProgressByKey(), forcedRotationFromRegion);
        }
        localSortieQueue.addAll(selectedBatch);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MasonWallBuilder {}: sortie_start_preflight_summary anchor={} layer={} considered={} accepted={} preflight_pass={} preflightRejected={} preflight_fail_fallback_accept={} nearestFallbackSuppressedByLayerOneLap={}",
                    guard.getUuidAsString(),
                    selectedAnchor.toShortString(),
                    activeLayer,
                    sortieCandidatesConsidered,
                    sortieCandidatesAccepted,
                    sortieCandidatesPreflightPass,
                    sortieCandidatesPreflightRejected,
                    sortieCandidatesFallbackAccepted,
                    sortieNearestFallbackSuppressedByLayerOneLap);
        }
        sortieCandidateCountSamples++;
        sortieCandidateCountSum += sortieCandidatesConsidered;
        sortieActive = true;
        sortieActiveLayer = activeLayer;
        sortieTransientRetriesAttempted = 0;
        sortieFallbackTargetsTried = 0;
        sortieHardUnreachableMarked = 0;
        sortieActivationTick = world.getTime();
        sortieNoNetProgressGraceSuppressedLogged = false;
        resetSortieNoNetProgressGuard(world);
        consecutiveDeferredSweeps = 0;
        debugLogSegmentStateSanity(world, "sortie_selected");
        return true;
    }

    private BlockPos findNextIndexAnchor(ServerWorld world, int activeLayer, boolean preferNonQuarantinedBands, boolean preferNonCycleExcludedBands) {
        if (isLayerOneFirstLapEnforced(world)) {
            return findNextLayerOneLapAnchor(world, preferNonQuarantinedBands, preferNonCycleExcludedBands);
        }
        if (!hardUnreachableRetryQueue.isEmpty()) {
            hardRetryPassStarted = true;
        }
        while (!hardUnreachableRetryQueue.isEmpty()) {
            BlockPos retryCandidate = hardUnreachableRetryQueue.pollFirst();
            if (retryCandidate == null) continue;
            if (getSegmentLayer(world, retryCandidate) != activeLayer) {
                continue;
            }
            if (isRetryDensitySuppressedSegment(world, retryCandidate)) {
                continue;
            }
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, retryCandidate, world.getTime())) {
                continue;
            }
            if (preferNonCycleExcludedBands && isSegmentInCycleExcludedBand(retryCandidate, activeLayer)) {
                continue;
            }
            if (tryRecoverSkippedSegmentIfEligible(world, retryCandidate, "hard_queue_retry")
                    && isBuildableCandidate(world, retryCandidate)) {
                return retryCandidate;
            }
        }
        if (activeLayer == 1) {
            BlockPos layerOneLapCandidate = findNextLayerOneLapAnchor(world, preferNonQuarantinedBands, preferNonCycleExcludedBands);
            if (layerOneLapCandidate != null) {
                return layerOneLapCandidate;
            }
            BlockPos rescannedLayerOne = findFirstAvailableSegmentForLayer(world, activeLayer, preferNonQuarantinedBands, preferNonCycleExcludedBands);
            if (rescannedLayerOne != null) {
                return rescannedLayerOne;
            }
        } else {
            BlockPos rescannedAvailable = findFirstAvailableSegmentForLayer(world, activeLayer, preferNonQuarantinedBands, preferNonCycleExcludedBands);
            if (rescannedAvailable != null) {
                return rescannedAvailable;
            }
        }
        if (stagnationPivotActive) {
            BlockPos pivotCandidate = findPivotAnchorCandidate(world, activeLayer, preferNonQuarantinedBands);
            if (pivotCandidate != null) {
                if (preferNonCycleExcludedBands && isSegmentInCycleExcludedBand(pivotCandidate, activeLayer)) {
                    // continue scanning for a non-excluded anchor when possible
                } else {
                    return pivotCandidate;
                }
            }
        }
        while (currentSegmentIndex < pendingSegments.size()) {
            int candidateIndex = currentSegmentIndex++;
            BlockPos candidate = pendingSegments.get(candidateIndex);
            if (isPivotExcludedSegment(world, candidateIndex, candidate)) {
                continue;
            }
            if (isRetryDensitySuppressedSegment(world, candidate)) {
                continue;
            }
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, candidate, world.getTime())) {
                continue;
            }
            if (preferNonCycleExcludedBands && isSegmentInCycleExcludedBand(candidate, activeLayer)) {
                continue;
            }
            if (getSegmentLayer(world, candidate) == activeLayer && isBuildableCandidate(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private BlockPos findNextLayerOneLapAnchor(ServerWorld world, boolean preferNonQuarantinedBands, boolean preferNonCycleExcludedBands) {
        if (pendingSegments.isEmpty()) {
            return null;
        }
        initializeLayerOneLapStateIfNeeded();
        if (layerOneCursorIndex < 0 || layerOneCursorIndex >= pendingSegments.size()) {
            layerOneCursorIndex = 0;
        }

        int visited = 0;
        long now = world.getTime();
        while (visited < pendingSegments.size()) {
            int candidateIndex = layerOneCursorIndex;
            BlockPos candidate = pendingSegments.get(candidateIndex);
            layerOneCursorIndex = (layerOneCursorIndex + 1) % pendingSegments.size();
            if (layerOneCursorIndex == layerOneLapStartIndex) {
                layerOneLapWrapped = true;
                LOGGER.info("MasonWallBuilder {}: layer_one_lap_wrap start={} cursor={} considered={}",
                        guard.getUuidAsString(),
                        layerOneLapStartIndex,
                        layerOneCursorIndex,
                        layerOneLapConsideredSegments.size());
            }
            visited++;

            if (getSegmentLayer(world, candidate) != 1) {
                continue;
            }
            if (isGatePosition(candidate) || isTerminalSegment(candidate)) {
                continue;
            }
            recordLayerOneFaceCandidateVisited(candidateIndex, candidate);
            layerOneLapConsideredSegments.add(candidate.toImmutable());

            if (isPivotExcludedSegment(world, candidateIndex, candidate)) {
                continue;
            }
            if (isRetryDensitySuppressedSegment(world, candidate)) {
                continue;
            }
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, candidate, now)) {
                continue;
            }
            if (preferNonCycleExcludedBands && isSegmentInCycleExcludedBand(candidate, 1)) {
                continue;
            }
            if (isBuildableCandidate(world, candidate)) {
                maybeMarkLayerOneLapComplete(world);
                return candidate;
            }
        }

        maybeMarkLayerOneLapComplete(world);
        return null;
    }

    private void initializeLayerOneLapStateIfNeeded() {
        if (layerOneLapStartIndex >= 0 && layerOneCursorIndex >= 0 && layerOneCursorIndex < pendingSegments.size()) {
            return;
        }
        int normalizedIndex = pendingSegments.isEmpty() ? -1 : Math.max(0, Math.min(currentSegmentIndex, pendingSegments.size() - 1));
        layerOneLapStartIndex = normalizedIndex;
        layerOneCursorIndex = normalizedIndex;
        layerOneLapCompleted = false;
        layerOneLapWrapped = false;
        layerOneLapConsideredSegments.clear();
        layerOneFaceCoverageByFace.clear();
        for (String face : LAYER_ONE_CLOCKWISE_FACES) {
            layerOneFaceCoverageByFace.put(face, new LayerOneFaceCoverageState());
        }
        LOGGER.info("MasonWallBuilder {}: layer_one_lap_start start={} cursor={} pending={}",
                guard.getUuidAsString(),
                layerOneLapStartIndex,
                layerOneCursorIndex,
                pendingSegments.size());
    }

    private void maybeMarkLayerOneLapComplete(ServerWorld world) {
        if (layerOneLapCompleted || !layerOneLapWrapped || layerOneLapStartIndex < 0) {
            return;
        }
        if (!hasVisitedAllLayerOneFaces()) {
            return;
        }
        layerOneLapCompleted = true;
        LOGGER.info("MasonWallBuilder {}: layer_one_lap_complete start={} considered={} facesVisited={}/4",
                guard.getUuidAsString(),
                layerOneLapStartIndex,
                layerOneLapConsideredSegments.size(),
                countVisitedLayerOneFaces());
    }

    private int countLayerOneLapEligibleSegments(ServerWorld world) {
        int count = 0;
        for (BlockPos candidate : pendingSegments) {
            if (getSegmentLayer(world, candidate) != 1) {
                continue;
            }
            if (isGatePosition(candidate) || isTerminalSegment(candidate)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private void resetLayerOneLapState() {
        layerOneLapStartIndex = -1;
        layerOneCursorIndex = -1;
        layerOneLapCompleted = false;
        layerOneLapWrapped = false;
        layerOneLapConsideredSegments.clear();
        layerOneFaceCoverageByFace.clear();
    }

    private void recordLayerOneFaceCandidateVisited(int candidateIndex, BlockPos candidate) {
        String face = resolveLayerOneRegion(candidate).face();
        LayerOneFaceCoverageState state = layerOneFaceCoverageByFace.computeIfAbsent(face, ignored -> new LayerOneFaceCoverageState());
        if (!state.firstCandidateVisited) {
            state.firstCandidateVisited = true;
            state.firstCandidateIndex = candidateIndex;
        }
    }

    private void recordLayerOneFacePlacement(ServerWorld world, BlockPos placedSegment) {
        if (getSegmentLayer(world, placedSegment) != 1 || isGatePosition(placedSegment) || isTerminalSegment(placedSegment)) {
            return;
        }
        String face = resolveLayerOneRegion(placedSegment).face();
        LayerOneFaceCoverageState state = layerOneFaceCoverageByFace.computeIfAbsent(face, ignored -> new LayerOneFaceCoverageState());
        if (!state.firstSuccessfulPlacement) {
            state.firstSuccessfulPlacement = true;
            state.firstPlacementPos = placedSegment.toImmutable();
        }
        state.faceFullyBuilt = isLayerOneFaceFullyBuilt(world, face);
    }

    private boolean isLayerOneFaceFullyBuilt(ServerWorld world, String face) {
        for (BlockPos segment : pendingSegments) {
            if (getSegmentLayer(world, segment) != 1 || isGatePosition(segment) || isTerminalSegment(segment)) {
                continue;
            }
            if (!face.equals(resolveLayerOneRegion(segment).face())) {
                continue;
            }
            if (!isPlacedWallBlock(world.getBlockState(segment))) {
                return false;
            }
        }
        return true;
    }

    private int countVisitedLayerOneFaces() {
        int count = 0;
        for (String face : LAYER_ONE_CLOCKWISE_FACES) {
            LayerOneFaceCoverageState state = layerOneFaceCoverageByFace.get(face);
            if (state != null && state.firstCandidateVisited) {
                count++;
            }
        }
        return count;
    }

    private int countPlacedLayerOneFaces() {
        int count = 0;
        for (String face : LAYER_ONE_CLOCKWISE_FACES) {
            LayerOneFaceCoverageState state = layerOneFaceCoverageByFace.get(face);
            if (state != null && state.firstSuccessfulPlacement) {
                count++;
            }
        }
        return count;
    }

    private int countFullyBuiltLayerOneFaces() {
        int count = 0;
        for (String face : LAYER_ONE_CLOCKWISE_FACES) {
            LayerOneFaceCoverageState state = layerOneFaceCoverageByFace.get(face);
            if (state != null && state.faceFullyBuilt) {
                count++;
            }
        }
        return count;
    }

    private boolean hasVisitedAllLayerOneFaces() {
        return countVisitedLayerOneFaces() >= LAYER_ONE_CLOCKWISE_FACES.size();
    }

    private boolean isLayerOneAggressiveHeuristicsEnabled(int activeLayer) {
        if (activeLayer != 1) {
            return true;
        }
        return layerOneLapCompleted && hasVisitedAllLayerOneFaces();
    }

    private boolean isLayerOneFirstLapEnforced(ServerWorld world) {
        if (world == null || layerOneLapCompleted) {
            return false;
        }
        if (findLowestPendingLayer(world) != 1) {
            return false;
        }
        return countBuildableSegmentsForLayer(world, 1, false, false) > 0;
    }

    private BlockPos findFirstAvailableSegmentForLayer(ServerWorld world, int activeLayer, boolean preferNonQuarantinedBands, boolean preferNonCycleExcludedBands) {
        long now = world.getTime();
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos candidate = pendingSegments.get(i);
            if (getSegmentLayer(world, candidate) != activeLayer) continue;
            if (isPivotExcludedSegment(world, i, candidate)) continue;
            if (isRetryDensitySuppressedSegment(world, candidate)) continue;
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, candidate, now)) continue;
            if (preferNonCycleExcludedBands && isSegmentInCycleExcludedBand(candidate, activeLayer)) continue;
            if (segmentStates.getOrDefault(candidate, SegmentState.AVAILABLE) != SegmentState.AVAILABLE) continue;
            if (isBuildableCandidate(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private BlockPos findNearestUnbuiltSegment(ServerWorld world, BlockPos from, int activeLayer, boolean preferNonQuarantinedBands, boolean preferNonCycleExcludedBands) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        long now = world.getTime();
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos pos = pendingSegments.get(i);
            if (getSegmentLayer(world, pos) != activeLayer) continue;
            if (isPivotExcludedSegment(world, i, pos)) continue;
            if (isRetryDensitySuppressedSegment(world, pos)) continue;
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, pos, now)) continue;
            if (preferNonCycleExcludedBands && isSegmentInCycleExcludedBand(pos, activeLayer)) continue;
            if (!isBuildableCandidate(world, pos)) continue;
            double distSq = from.getSquaredDistance(pos);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = pos;
            }
        }
        return best;
    }

    private boolean hasNonQuarantinedBuildableSegments(ServerWorld world, int activeLayer) {
        long now = world.getTime();
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos pos = pendingSegments.get(i);
            if (getSegmentLayer(world, pos) != activeLayer) continue;
            if (isPivotExcludedSegment(world, i, pos)) continue;
            if (isRetryDensitySuppressedSegment(world, pos)) continue;
            if (isSegmentBandQuarantined(world, pos, now)) continue;
            if (isBuildableCandidate(world, pos)) {
                return true;
            }
        }
        return false;
    }

    private int countBuildableSegmentsForLayer(ServerWorld world, int activeLayer, boolean preferNonQuarantinedBands, boolean requireNonCycleExcludedBand) {
        int count = 0;
        long now = world.getTime();
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos pos = pendingSegments.get(i);
            if (getSegmentLayer(world, pos) != activeLayer) continue;
            if (isPivotExcludedSegment(world, i, pos)) continue;
            if (isRetryDensitySuppressedSegment(world, pos)) continue;
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, pos, now)) continue;
            if (requireNonCycleExcludedBand && isSegmentInCycleExcludedBand(pos, activeLayer)) continue;
            if (isBuildableCandidate(world, pos)) {
                count++;
            }
        }
        return count;
    }

    private List<BlockPos> preferDistinctBands(List<BlockPos> candidates, ServerWorld world) {
        if (candidates.size() <= 2) {
            return candidates;
        }
        List<BlockPos> ordered = new ArrayList<>(candidates.size());
        Set<String> usedBands = new HashSet<>();
        for (BlockPos candidate : candidates) {
            String band = segmentBandSignature(world, candidate);
            if (usedBands.add(band)) {
                ordered.add(candidate);
            }
        }
        for (BlockPos candidate : candidates) {
            if (ordered.size() >= candidates.size()) {
                break;
            }
            if (!ordered.contains(candidate)) {
                ordered.add(candidate);
            }
        }
        return ordered;
    }

    private List<BlockPos> buildLocalSortieCandidates(ServerWorld world, BlockPos anchor, int activeLayer, boolean preferNonQuarantinedBands, boolean preferNonCycleExcludedBands) {
        int radiusSq = LOCAL_SORTIE_RADIUS * LOCAL_SORTIE_RADIUS;
        List<BlockPos> local = new ArrayList<>();
        long now = world.getTime();
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos pos = pendingSegments.get(i);
            if (getSegmentLayer(world, pos) != activeLayer) continue;
            if (isPivotExcludedSegment(world, i, pos)) continue;
            if (isRetryDensitySuppressedSegment(world, pos)) continue;
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, pos, now)) continue;
            if (preferNonCycleExcludedBands && isSegmentInCycleExcludedBand(pos, activeLayer)) continue;
            if (isSegmentInCooldown(pos, now)) continue;
            if (!isBuildableCandidate(world, pos)) continue;
            if (anchor.getSquaredDistance(pos) <= radiusSq) {
                local.add(pos);
            }
        }
        LayerOneDominanceContext dominance = buildLayerOneDominanceContext(world, activeLayer);
        local.sort(Comparator
                .comparingDouble((BlockPos pos) -> computeSortieCandidateScore(world, anchor, pos, dominance))
                .thenComparingDouble(anchor::getSquaredDistance));
        List<BlockPos> bounded = preferDistinctBands(local, world);
        if (bounded.size() > MAX_SEGMENTS_PER_SORTIE) {
            bounded = new ArrayList<>(bounded.subList(0, MAX_SEGMENTS_PER_SORTIE));
        }
        List<BlockPos> accepted = new ArrayList<>(bounded.size());
        List<BlockPos> preflightFailed = new ArrayList<>(bounded.size());
        for (int idx = 0; idx < bounded.size(); idx++) {
            BlockPos segment = bounded.get(idx);
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
                if (lastPreflightDeferredByBudget) {
                    deferredPreflightSegmentsThisSortie.add(segment.toImmutable());
                    int remaining = bounded.size() - (idx + 1);
                    if (remaining > 0) {
                        pathBudgetDeferredSinceLastPeriodic += remaining;
                        for (int tail = idx + 1; tail < bounded.size(); tail++) {
                            deferredPreflightSegmentsThisSortie.add(bounded.get(tail).toImmutable());
                        }
                    }
                    break;
                }
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
            if (retryDensityFallbackModeActive) {
                return accepted;
            }
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

    private double computeSortieCandidateScore(ServerWorld world, BlockPos anchor, BlockPos candidate, LayerOneDominanceContext dominance) {
        double distanceScore = anchor.getSquaredDistance(candidate);
        if (!dominance.active()) {
            return distanceScore;
        }
        LayerOneRegionProgress progress = dominance.regionProgressByKey().get(resolveLayerOneRegion(candidate).key());
        double completionRatio = progress == null ? 1.0D : progress.completionRatio();
        double dominancePenalty = completionRatio * 1000.0D;
        return dominancePenalty + distanceScore;
    }

    private LayerOneDominanceContext buildLayerOneDominanceContext(ServerWorld world, int activeLayer) {
        if (activeLayer != 1) {
            return LayerOneDominanceContext.inactive();
        }
        Map<String, LayerOneRegionProgress> progressByKey = computeLayerOneRegionProgress(world);
        if (progressByKey.isEmpty()) {
            return LayerOneDominanceContext.inactive();
        }
        int total = 0;
        int placed = 0;
        for (LayerOneRegionProgress progress : progressByKey.values()) {
            total += progress.total();
            placed += progress.placed();
        }
        if (total <= 0) {
            return LayerOneDominanceContext.inactive();
        }
        double completionRatio = (double) placed / (double) total;
        return new LayerOneDominanceContext(completionRatio < LAYER_ONE_REGION_DOMINANCE_COMPLETION_THRESHOLD, completionRatio, progressByKey);
    }

    private Map<String, LayerOneRegionProgress> computeLayerOneRegionProgress(ServerWorld world) {
        Map<String, MutableLayerOneProgress> mutable = new HashMap<>();
        for (BlockPos segment : pendingSegments) {
            if (getSegmentLayer(world, segment) != 1) continue;
            if (isGatePosition(segment) || isTerminalSegment(segment)) continue;
            LayerOneRegion region = resolveLayerOneRegion(segment);
            MutableLayerOneProgress state = mutable.computeIfAbsent(region.key(),
                    ignored -> new MutableLayerOneProgress(region.face(), region.segmentRangeStart(), region.segmentRangeEnd()));
            state.total++;
            if (isPlacedWallBlock(world.getBlockState(segment))) {
                state.placed++;
            }
        }
        Map<String, LayerOneRegionProgress> result = new HashMap<>();
        for (Map.Entry<String, MutableLayerOneProgress> entry : mutable.entrySet()) {
            MutableLayerOneProgress state = entry.getValue();
            int remaining = Math.max(0, state.total - state.placed);
            result.put(entry.getKey(), new LayerOneRegionProgress(
                    entry.getKey(),
                    state.face,
                    state.segmentRangeStart,
                    state.segmentRangeEnd,
                    state.placed,
                    remaining,
                    state.total
            ));
        }
        return result;
    }

    private DominantAnchorSelection findDominantLayerOneAnchor(ServerWorld world,
                                                               int activeLayer,
                                                               boolean preferNonQuarantinedBands,
                                                               boolean preferNonCycleExcludedBands,
                                                               LayerOneDominanceContext dominance) {
        List<BlockPos> candidates = new ArrayList<>();
        long now = world.getTime();
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos candidate = pendingSegments.get(i);
            if (getSegmentLayer(world, candidate) != activeLayer) continue;
            if (isPivotExcludedSegment(world, i, candidate)) continue;
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, candidate, now)) continue;
            if (preferNonCycleExcludedBands && isSegmentInCycleExcludedBand(candidate, activeLayer)) continue;
            if (segmentStates.getOrDefault(candidate, SegmentState.AVAILABLE) != SegmentState.AVAILABLE) continue;
            if (!isBuildableCandidate(world, candidate)) continue;
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            return null;
        }

        String forcedFromRegion = null;
        String avoidedRegion = null;
        if (lastSortieRegionKey != null) {
            int retries = sortieRetryAttemptsByRegion.getOrDefault(lastSortieRegionKey, 0);
            boolean consecutiveCapHit = consecutiveSortiesSameRegion >= MAX_CONSECUTIVE_SORTIES_PER_REGION;
            boolean retryCapHit = retries >= MAX_RETRY_ATTEMPTS_PER_REGION_BEFORE_ROTATION;
            if (consecutiveCapHit || retryCapHit) {
                boolean hasAlternativeRegion = candidates.stream()
                        .map(this::resolveLayerOneRegion)
                        .map(LayerOneRegion::key)
                        .anyMatch(key -> !lastSortieRegionKey.equals(key));
                if (hasAlternativeRegion) {
                    avoidedRegion = lastSortieRegionKey;
                    forcedFromRegion = lastSortieRegionKey;
                }
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> {
                    LayerOneRegionProgress progress = dominance.regionProgressByKey().get(resolveLayerOneRegion(pos).key());
                    double completion = progress == null ? 1.0D : progress.completionRatio();
                    int retries = sortieRetryAttemptsByRegion.getOrDefault(resolveLayerOneRegion(pos).key(), 0);
                    return (completion * 1000.0D) + (retries * 10.0D) + guard.getBlockPos().getSquaredDistance(pos);
                }));
        if (avoidedRegion != null) {
            for (BlockPos candidate : candidates) {
                if (!avoidedRegion.equals(resolveLayerOneRegion(candidate).key())) {
                    return new DominantAnchorSelection(candidate, forcedFromRegion);
                }
            }
        }
        return new DominantAnchorSelection(candidates.get(0), null);
    }

    private void recordLayerOneSortieRegionSelection(ServerWorld world,
                                                     BlockPos selectedAnchor,
                                                     Map<String, LayerOneRegionProgress> progressByKey,
                                                     String forcedRotationFromRegion) {
        LayerOneRegion selectedRegion = resolveLayerOneRegion(selectedAnchor);
        String selectedRegionKey = selectedRegion.key();
        sortieRetryAttemptsByRegion.merge(selectedRegionKey, 1, Integer::sum);
        if (selectedRegionKey.equals(lastSortieRegionKey)) {
            consecutiveSortiesSameRegion++;
        } else {
            consecutiveSortiesSameRegion = 1;
        }
        if (forcedRotationFromRegion != null && !forcedRotationFromRegion.equals(selectedRegionKey)) {
            LayerOneRegionProgress fromProgress = progressByKey.get(forcedRotationFromRegion);
            LayerOneRegionProgress toProgress = progressByKey.get(selectedRegionKey);
            double fromRatio = fromProgress == null ? 1.0D : fromProgress.completionRatio();
            double toRatio = toProgress == null ? 1.0D : toProgress.completionRatio();
            LOGGER.info("MasonWallBuilder {}: forced_region_rotation from={} to={} fromCompletion={} toCompletion={}",
                    guard.getUuidAsString(),
                    forcedRotationFromRegion,
                    selectedRegionKey,
                    String.format("%.3f", fromRatio),
                    String.format("%.3f", toRatio));
        }
        lastSortieRegionKey = selectedRegionKey;
    }

    private LayerOneRegion resolveLayerOneRegion(BlockPos segment) {
        if (cycleWallRect == null) {
            String fallback = "fallback|" + segment.getX() + "|" + segment.getZ();
            return new LayerOneRegion(fallback, "fallback", 0, 0);
        }
        int minX = cycleWallRect.minX();
        int maxX = cycleWallRect.maxX();
        int minZ = cycleWallRect.minZ();
        int maxZ = cycleWallRect.maxZ();
        if (segment.getZ() == minZ) {
            return buildLayerOneFaceRegion("north", segment.getX() - minX, maxX - minX + 1);
        }
        if (segment.getX() == maxX) {
            return buildLayerOneFaceRegion("east", segment.getZ() - minZ - 1, Math.max(1, maxZ - minZ - 1));
        }
        if (segment.getZ() == maxZ) {
            return buildLayerOneFaceRegion("south", maxX - segment.getX(), maxX - minX + 1);
        }
        return buildLayerOneFaceRegion("west", maxZ - segment.getZ() - 1, Math.max(1, maxZ - minZ - 1));
    }

    private LayerOneRegion buildLayerOneFaceRegion(String face, int rawFaceIndex, int faceLength) {
        int clampedFaceLength = Math.max(1, faceLength);
        int faceIndex = Math.max(0, Math.min(rawFaceIndex, clampedFaceLength - 1));
        int arcCount = Math.max(1, Math.min(LAYER_ONE_FACE_ARC_BUCKETS, clampedFaceLength));
        int arcIndex = Math.min(arcCount - 1, (faceIndex * arcCount) / clampedFaceLength);
        int rangeStart = (arcIndex * clampedFaceLength) / arcCount;
        int rangeEnd = Math.max(rangeStart, (((arcIndex + 1) * clampedFaceLength) / arcCount) - 1);
        String key = face + "#" + arcIndex;
        return new LayerOneRegion(key, face, rangeStart, rangeEnd);
    }

    private boolean passesSortiePreflight(ServerWorld world, BlockPos segment) {
        lastPreflightDeferredByBudget = false;
        BlockPos navigationTarget = resolveSegmentNavigationTarget(world, segment);
        if (navigationTarget == null) {
            return false;
        }
        if (!isStandable(world, navigationTarget)) {
            return false;
        }
        PreflightProbeResult preflight = evaluatePreflightPath(world, segment, navigationTarget);
        if (preflight == PreflightProbeResult.DEFERRED) {
            lastPreflightDeferredByBudget = true;
            return false;
        }
        // Path miss is a soft signal: fallback sortie admission and runtime retries
        // determine actual reachability while moving.
        return preflight == PreflightProbeResult.PASS;
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

    private int computeEffectiveSortieThresholdForAvailableStock(ServerWorld world,
                                                                 BlockPos chestPos,
                                                                 int baseThreshold,
                                                                 long currentTick) {
        int totalPlacementUnits = countWallMaterialUnitsInChest(world, chestPos);
        if (totalPlacementUnits <= 0) {
            waitForStockTimeoutEscalated = false;
            return baseThreshold;
        }
        long waitedTicks = waitForStockStartedTick < 0L ? 0L : Math.max(0L, currentTick - waitForStockStartedTick);
        if (waitedTicks >= WAIT_FOR_STOCK_ESCALATION_TIMEOUT_TICKS) {
            waitForStockTimeoutEscalated = true;
            return Math.max(1, Math.min(baseThreshold, totalPlacementUnits));
        }
        return baseThreshold;
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
        if (isTerminalSegment(pos)) {
            return false;
        }
        if (isSegmentInCooldown(pos, world.getTime())) {
            return false;
        }
        if (isGatePosition(pos) || isPlacedWallBlock(world.getBlockState(pos))) {
            clearSegmentFailureState(pos);
            return false;
        }
        if (skippedSegments.contains(pos)) {
            return tryRecoverSkippedSegmentIfEligible(world, pos, "buildable_check");
        }
        return true;
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
                threshold,
                ALLOW_COBBLESTONE_PLACEMENT_FALLBACK
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
        Map<String, Integer> terminalReasons = summarizeTerminalSegmentReasons();
        int terminalCount = terminalSegmentReasons.size();
        if (remainingUnbuilt.isEmpty()) {
            Map<String, Integer> noDeferredReasons = Map.of();
            String completionSignature = "completion_summary|" + buildSweepLogSignature(
                    0,
                    0,
                    0,
                    noDeferredReasons,
                    terminalCount,
                    terminalReasons
            );
            if (shouldEmitSweepInfoLog(world, completionSignature)) {
                LOGGER.info("MasonWallBuilder {}: completion sweep summary before=0 filled=0 irrecoverable={} reasons={}",
                        guard.getUuidAsString(),
                        terminalCount,
                        terminalReasons);
            }
            return new SweepSummary(0, 0, 0, Map.<String, Integer>of(), terminalCount, terminalReasons);
        }

        int filledDuringSweep = 0;
        int deferredCount = 0;
        Map<String, Integer> deferredReasons = new HashMap<>();
        int irrecoverableCount = 0;
        Map<String, Integer> irrecoverableReasons = new HashMap<>();
        BlockPos chestPos = guard.getPairedChestPos();

        for (BlockPos segment : remainingUnbuilt) {
            clearSegmentFailureState(segment);

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
                recordPlacementProgress(world, segment);
                resetSortieAbortBackoffAfterBandPlacement(world, segment);
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
                terminalSegmentReasons.put(segment.toImmutable(), failureReason);
                transitionSegmentState(world, segment, SegmentState.IRRECOVERABLE, "completion_sweep_irrecoverable");
            }
        }

        if (!terminalReasons.isEmpty()) {
            for (Map.Entry<String, Integer> entry : terminalReasons.entrySet()) {
                irrecoverableReasons.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            irrecoverableCount += terminalCount;
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
            if (isTerminalSegment(pos)) continue;
            remaining.add(pos);
        }
        return remaining;
    }

    private boolean isTerminalSegment(BlockPos pos) {
        SegmentState state = segmentStates.getOrDefault(pos.toImmutable(), SegmentState.AVAILABLE);
        return state == SegmentState.CYCLE_EXCLUDED || state == SegmentState.IRRECOVERABLE;
    }

    private Map<String, Integer> summarizeTerminalSegmentReasons() {
        if (terminalSegmentReasons.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> reasons = new HashMap<>();
        for (String reason : terminalSegmentReasons.values()) {
            incrementReason(reasons, reason == null ? "terminal_unspecified" : reason);
        }
        return Map.copyOf(reasons);
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
        ServerWorld world = worldOrNull();
        if (reason != CycleEndReason.WALL_COMPLETE && activeCycleIdentity != null && placementsSinceCycleStart <= 0) {
            armSameCycleRestartSuppression(world, activeCycleIdentity);
        }
        if (reason == CycleEndReason.WALL_COMPLETE
                && world != null
                && activeAnchorPos != null) {
            VillageWallProjectState.get(world.getServer()).markAllLayersComplete(world.getRegistryKey(), activeAnchorPos);
        }
        releaseAllSegmentClaims(worldOrNull());
        clearWaitForStockState();
        resetWaitForStockProceedLogState();
        stage = Stage.DONE;
        guard.clearWallSegments();
        guard.setWallBuildPending(false, 0);
        activeAnchorPos = null;
        cachedWallRect = null;
        cachedWallRectAnchor = null;
        cachedPoiFootprintSignature = null;
        failureBandWindows.clear();
        noProgressAbortBandWindows.clear();
        quarantinedBandUntilTick.clear();
        deferredSegmentRetryQueue.clear();
        skippedSegmentFailMetadata.clear();
        segmentCooldownUntilTick.clear();
        segmentNavTargetBackoffUntilTick.clear();
        segmentRepeatRecoveryCounts.clear();
        sortieAbortSegmentRepeatCounts.clear();
        sortieAbortBandRepeatCounts.clear();
        sortieAbortSegmentBandKeys.clear();
        terminalSegmentReasons.clear();
        segmentStates.clear();
        clearStagnationPivotState();
        sortiePlacementsAtStart = 0;
        sortieStartTick = -1L;
        sortieActivationTick = -1L;
        cycleStartTick = -1L;
        sortieNoNetProgressGraceSuppressedLogged = false;
        nextSkippedSegmentReconciliationTick = 0L;
        vegetationDeferredThenRequeuedLogged = false;
        cycleWallRect = null;
        activeCycleIdentity = null;
        resetLayerOneLapState();
        waitForStockReplanCooldownUntilTick = 0L;
        nextWaitForStockCycleValidationTick = 0L;
        waitForStockCycleStillValid = true;
        nextWaitForStockReplanAttemptTick = world != null
                ? world.getTime() + WAIT_FOR_STOCK_MIN_REPLAN_INTERVAL_TICKS
                : 0L;
        LOGGER.info("MasonWallBuilder {}: build cycle ended reason={} placements={} retries={} hard_unreachable={} deferred_or_skipped={}",
                guard.getUuidAsString(),
                cycleEndReason.logValue,
                placedSegments,
                pathRetriesSinceCycleStart,
                hardUnreachableSinceCycleStart,
                deferredOrSkippedSinceCycleStart);
    }

    private void resetWaitForStockProceedLogState() {
        waitForStockProceedLogged = false;
        lastLoggedProceedWalls = -1;
        lastLoggedProceedThreshold = -1;
    }

    private void clearWaitForStockState() {
        waitForStockStartedTick = -1L;
        nextWaitForStockDiagnosticTick = 0L;
        waitForStockTimeoutEscalated = false;
    }

    private String describeWaitFallbackMode(int baseThreshold,
                                            int effectiveThreshold,
                                            boolean conversionCapable,
                                            int availableWalls) {
        if (!ALLOW_COBBLESTONE_PLACEMENT_FALLBACK) {
            return "walls_only";
        }
        if (effectiveThreshold < baseThreshold) {
            return "threshold_relaxed_to_" + effectiveThreshold;
        }
        if (availableWalls < effectiveThreshold && conversionCapable) {
            return "direct_cobble_fallback";
        }
        return "wall_preferred";
    }

    private void maybeLogWaitForStockDiagnostics(ServerWorld world,
                                                 int availableWalls,
                                                 int availableCobblestone,
                                                 boolean conversionCapable,
                                                 String fallbackMode,
                                                 int baseThreshold,
                                                 int effectiveThreshold) {
        long now = world.getTime();
        if (now < nextWaitForStockDiagnosticTick) {
            return;
        }
        long waitedTicks = waitForStockStartedTick < 0L ? 0L : Math.max(0L, now - waitForStockStartedTick);
        LOGGER.debug("MasonWallBuilder {}: WAIT diagnostics (availableWalls={}, availableCobble={}, conversionCapable={}, fallbackMode={}, baseThreshold={}, effectiveThreshold={}, waitTicks={}, timeoutEscalated={})",
                guard.getUuidAsString(),
                availableWalls,
                availableCobblestone,
                conversionCapable,
                fallbackMode,
                baseThreshold,
                effectiveThreshold,
                waitedTicks,
                waitForStockTimeoutEscalated);
        nextWaitForStockDiagnosticTick = now + WAIT_FOR_STOCK_DIAGNOSTIC_INTERVAL_TICKS;
    }

    private boolean shouldDebounceReplanForSameCycleIdentity(ServerWorld world, BlockPos anchorPos, int perimeterSignatureHash) {
        if (activeCycleIdentity == null) {
            return false;
        }
        if (world.getTime() >= waitForStockReplanCooldownUntilTick) {
            return false;
        }
        if (stage != Stage.WAIT_FOR_WALL_STOCK) {
            return false;
        }
        return activeCycleIdentity.anchorPos().equals(anchorPos)
                && activeCycleIdentity.perimeterSignatureHash() == perimeterSignatureHash;
    }

    private boolean isWaitForStockCycleStillValid(ServerWorld world) {
        if (stage != Stage.WAIT_FOR_WALL_STOCK || activeAnchorPos == null || activeCycleIdentity == null) {
            return true;
        }
        long now = world.getTime();
        if (now < nextWaitForStockCycleValidationTick) {
            return waitForStockCycleStillValid;
        }

        waitForStockCycleStillValid = false;
        nextWaitForStockCycleValidationTick = now + WAIT_FOR_STOCK_CYCLE_VALIDATION_INTERVAL_TICKS;

        Optional<PoiFootprintSignature> signatureOpt = computePoiFootprintSignature(world, activeAnchorPos);
        if (signatureOpt.isEmpty()) {
            LOGGER.debug("MasonWallBuilder {}: WAIT_FOR_WALL_STOCK invalidated; no footprint for active anchor {}",
                    guard.getUuidAsString(), activeAnchorPos.toShortString());
            markWaitForStockReplanInvalidated(now);
            return false;
        }

        int currentPerimeterSignatureHash = computePerimeterSignatureHash(signatureOpt.get());
        if (currentPerimeterSignatureHash != activeCycleIdentity.perimeterSignatureHash()) {
            LOGGER.debug("MasonWallBuilder {}: WAIT_FOR_WALL_STOCK invalidated; signature changed old={} new={} anchor={}",
                    guard.getUuidAsString(),
                    activeCycleIdentity.perimeterSignatureHash(),
                    currentPerimeterSignatureHash,
                    activeAnchorPos.toShortString());
            markWaitForStockReplanInvalidated(now);
            return false;
        }

        BlockPos origin = resolveAnchorOrigin();
        Optional<BlockPos> latestAnchorOpt = VillageAnchorState.get(world.getServer())
                .getNearestQmChest(world, origin, (int) PEER_SCAN_RANGE);
        if (latestAnchorOpt.isPresent() && !latestAnchorOpt.get().equals(activeCycleIdentity.anchorPos())) {
            LOGGER.debug("MasonWallBuilder {}: WAIT_FOR_WALL_STOCK invalidated; anchor changed old={} new={}",
                    guard.getUuidAsString(),
                    activeCycleIdentity.anchorPos().toShortString(),
                    latestAnchorOpt.get().toShortString());
            markWaitForStockReplanInvalidated(now);
            return false;
        }

        waitForStockCycleStillValid = true;
        return true;
    }

    private int computePerimeterSignatureHash(PoiFootprintSignature signature) {
        return Objects.hash(
                signature.minX(),
                signature.minZ(),
                signature.maxX(),
                signature.maxZ(),
                signature.poiCount(),
                signature.poiHash()
        );
    }

    private int computeWallBoundsHash(WallRect rect) {
        return Objects.hash(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ(), rect.y());
    }

    private void markWaitForStockReplanInvalidated(long now) {
        waitForStockReplanCooldownUntilTick = 0L;
        nextWaitForStockReplanAttemptTick = Math.max(nextWaitForStockReplanAttemptTick, now + WAIT_FOR_STOCK_MIN_REPLAN_INTERVAL_TICKS);
        sameCycleSuppressionRetryCooldownUntilTick = Math.max(
                sameCycleSuppressionRetryCooldownUntilTick,
                nextWaitForStockReplanAttemptTick
        );
    }

    private boolean verboseMasonWallLogging() {
        return GuardVillagersConfig.masonWallVerboseLogging;
    }

    private void logDetailed(String message, Object... args) {
        if (verboseMasonWallLogging()) {
            LOGGER.info(message, args);
        } else {
            LOGGER.debug(message, args);
        }
    }

    private void logInfoRateLimited(ServerWorld world, String messageType, long intervalTicks, String message, Object... args) {
        long now = world.getTime();
        Long lastTick = infoLogRateLimitTickByType.get(messageType);
        if (lastTick != null && (now - lastTick) < intervalTicks) {
            if (verboseMasonWallLogging()) {
                LOGGER.debug("MasonWallBuilder {}: suppressed INFO log type={} due to rate limit ({} ticks)",
                        guard.getUuidAsString(), messageType, intervalTicks);
            }
            return;
        }
        infoLogRateLimitTickByType.put(messageType, now);
        LOGGER.info(message, args);
    }

    private void maybeEmitPeriodicInfo(ServerWorld world) {
        if (!isElectedBuilder || stage == Stage.IDLE || stage == Stage.DONE) {
            return;
        }
        long now = world.getTime();
        if (now < nextPeriodicInfoTick) {
            return;
        }
        nextPeriodicInfoTick = now + PERIODIC_INFO_INTERVAL_TICKS;
        int placementDeltaSinceLastSummary = placedSegments - lastPeriodicSummaryPlacementCount;
        int suppressedRegionCount = countActiveSuppressedRegions(now);
        long oldestSuppressionAge = oldestSuppressionAgeTicks(now);
        int placementsSinceLastSuppression = placementsSinceCycleStart - placementsAtLastSuppression;
        double averageCandidatesPerSortie = sortieCandidateCountSamples <= 0
                ? 0.0D
                : (double) sortieCandidateCountSum / (double) sortieCandidateCountSamples;
        if (stage == Stage.PLACE_BLOCK && placementDeltaSinceLastSummary <= 0) {
            String stalledTarget = placeBlockFailureTarget == null ? "none" : placeBlockFailureTarget.toShortString();
            String stalledReason = placeBlockLastNonPlacementReason == null ? "none" : placeBlockLastNonPlacementReason;
            logInfoRateLimited(
                    world,
                    "periodic_cycle_summary",
                    PERIODIC_INFO_INTERVAL_TICKS,
                    "MasonWallBuilder {}: periodic summary placements={} retries={} hard_unreachable={} deferred_or_skipped={} stage={} placeBlockTarget={} placeBlockReason={} suppressedRegions={} oldestSuppressionAgeTicks={} placementsSinceLastSuppression={} pathBudgetUsed={} pathBudgetDeferred={} avgCandidatesPerSortie={}",
                    guard.getUuidAsString(),
                    placedSegments,
                    pathRetriesSinceCycleStart,
                    hardUnreachableSinceCycleStart,
                    deferredOrSkippedSinceCycleStart,
                    stage,
                    stalledTarget,
                    stalledReason,
                    suppressedRegionCount,
                    oldestSuppressionAge,
                    Math.max(0, placementsSinceLastSuppression),
                    pathBudgetUsedSinceLastPeriodic,
                    pathBudgetDeferredSinceLastPeriodic,
                    String.format("%.2f", averageCandidatesPerSortie)
            );
        } else {
            logInfoRateLimited(
                    world,
                    "periodic_cycle_summary",
                    PERIODIC_INFO_INTERVAL_TICKS,
                    "MasonWallBuilder {}: periodic summary placements={} retries={} hard_unreachable={} deferred_or_skipped={} stage={} suppressedRegions={} oldestSuppressionAgeTicks={} placementsSinceLastSuppression={} pathBudgetUsed={} pathBudgetDeferred={} avgCandidatesPerSortie={}",
                    guard.getUuidAsString(),
                    placedSegments,
                    pathRetriesSinceCycleStart,
                    hardUnreachableSinceCycleStart,
                    deferredOrSkippedSinceCycleStart,
                    stage,
                    suppressedRegionCount,
                    oldestSuppressionAge,
                    Math.max(0, placementsSinceLastSuppression),
                    pathBudgetUsedSinceLastPeriodic,
                    pathBudgetDeferredSinceLastPeriodic,
                    String.format("%.2f", averageCandidatesPerSortie)
            );
        }
        maybeEmitLayerOneFaceCoverageInfo(world);
        lastPeriodicSummaryPlacementCount = placedSegments;
        pathBudgetUsedSinceLastPeriodic = 0;
        pathBudgetDeferredSinceLastPeriodic = 0;
    }

    private void maybeEmitLayerOneFaceCoverageInfo(ServerWorld world) {
        int activeLayer = findLowestPendingLayer(world);
        if (activeLayer != 1 || layerOneFaceCoverageByFace.isEmpty()) {
            return;
        }
        for (String face : LAYER_ONE_CLOCKWISE_FACES) {
            LayerOneFaceCoverageState state = layerOneFaceCoverageByFace.get(face);
            if (state != null) {
                state.faceFullyBuilt = isLayerOneFaceFullyBuilt(world, face);
            }
        }
        logInfoRateLimited(
                world,
                "periodic_layer_one_face_coverage",
                PERIODIC_INFO_INTERVAL_TICKS,
                "MasonWallBuilder {}: layer_one_face_coverage facesVisited={}/4 facesPlaced={}/4 facesBuilt={}/4 lapWrapped={} lapComplete={} aggressiveHeuristicsEnabled={}",
                guard.getUuidAsString(),
                countVisitedLayerOneFaces(),
                countPlacedLayerOneFaces(),
                countFullyBuiltLayerOneFaces(),
                layerOneLapWrapped,
                layerOneLapCompleted,
                isLayerOneAggressiveHeuristicsEnabled(1));
    }

    private void recordPlacementProgress(ServerWorld world, BlockPos placedSegment) {
        lastPlacementTick = world.getTime();
        placementsSinceCycleStart++;
        clearSameCycleRestartSuppression("placement_progress");
        recordLayerOneFacePlacement(world, placedSegment);
        if (retryDensityFallbackModeActive) {
            retryDensityFallbackPlacementStreak++;
            if (retryDensityFallbackPlacementStreak >= RETRY_DENSITY_EXIT_PLACEMENT_STREAK) {
                exitRetryDensityFallbackMode(world, "placement_streak");
            }
        }
        maybeClearSuppressedRegionsAfterProgress(world, placedSegment);
        if (stagnationPivotActive) {
            stagnationPivotPlacementStreak++;
        }
    }

    private void noteRetryDensityNonPlacementProgress() {
        if (retryDensityFallbackModeActive) {
            retryDensityFallbackPlacementStreak = 0;
        }
    }

    private int computeMaterialAvailabilitySignature(int totalWalls, int totalCobblestone) {
        return Objects.hash(totalWalls, totalCobblestone);
    }

    private boolean shouldSuppressSameCycleRestart(ServerWorld world, CycleIdentity candidateIdentity, int materialAvailabilitySignature) {
        long now = world.getTime();
        if (now >= sameCycleInFlightSuppressUntilTick) {
            clearSameCycleRestartSuppression("lock_expired");
            return false;
        }

        boolean matchesActive = activeCycleIdentity != null
                && sameCycleIdentity(activeCycleIdentity, candidateIdentity);
        boolean matchesLastFailed = lastFailedCycleIdentity != null
                && sameCycleIdentity(lastFailedCycleIdentity, candidateIdentity);
        if (!matchesActive && !matchesLastFailed) {
            return false;
        }

        if (materialAvailabilitySignature != sameCycleSuppressionMaterialSignature) {
            clearSameCycleRestartSuppression("material_changed");
            return false;
        }

        if (now >= sameCycleSuppressionRetryCooldownUntilTick) {
            clearSameCycleRestartSuppression("retry_cooldown_expired");
            return false;
        }

        return true;
    }

    private boolean sameCycleIdentity(CycleIdentity left, CycleIdentity right) {
        return left.anchorPos().equals(right.anchorPos())
                && left.perimeterSignatureHash() == right.perimeterSignatureHash()
                && left.boundsHash() == right.boundsHash();
    }

    private void armSameCycleRestartSuppression(ServerWorld world, CycleIdentity identity) {
        long now = world == null ? 0L : world.getTime();
        lastFailedCycleIdentity = identity;
        sameCycleInFlightSuppressUntilTick = now + SAME_CYCLE_IN_FLIGHT_LOCK_TICKS;
        int walls = 0;
        int cobble = 0;
        BlockPos chestPos = guard.getPairedChestPos();
        if (world != null && chestPos != null) {
            walls = countItemInChest(world, chestPos, Items.COBBLESTONE_WALL);
            cobble = countItemInChest(world, chestPos, Items.COBBLESTONE);
        }
        sameCycleSuppressionMaterialSignature = computeMaterialAvailabilitySignature(walls, cobble);
        sameCycleSuppressionRetryCooldownUntilTick = Math.max(
                now + WAIT_FOR_STOCK_MIN_REPLAN_INTERVAL_TICKS,
                nextWaitForStockReplanAttemptTick
        );
    }

    private void clearSameCycleRestartSuppression(String reason) {
        if (verboseMasonWallLogging() && sameCycleInFlightSuppressUntilTick > 0L) {
            LOGGER.debug("MasonWallBuilder {}: clearing same-cycle restart suppression reason={}",
                    guard.getUuidAsString(), reason);
        }
        sameCycleInFlightSuppressUntilTick = 0L;
        sameCycleSuppressionMaterialSignature = 0;
        sameCycleSuppressionRetryCooldownUntilTick = 0L;
        lastFailedCycleIdentity = null;
    }

    private void maybeTriggerRetryDensityFallback(ServerWorld world) {
        if (!isElectedBuilder || stage == Stage.IDLE || stage == Stage.DONE || pendingSegments.isEmpty()) {
            return;
        }
        if (isLayerOneFirstLapEnforced(world)) {
            return;
        }
        if (retryDensityFallbackModeActive) {
            return;
        }
        long now = world.getTime();
        retryDensitySnapshots.addLast(new ProgressSnapshot(now, placementsSinceCycleStart, pathRetriesSinceCycleStart, hardUnreachableSinceCycleStart));
        while (!retryDensitySnapshots.isEmpty() && (now - retryDensitySnapshots.peekFirst().tick()) > RETRY_DENSITY_WINDOW_TICKS) {
            retryDensitySnapshots.pollFirst();
        }
        ProgressSnapshot baseline = retryDensitySnapshots.peekFirst();
        if (baseline == null) {
            return;
        }
        int placementDelta = placementsSinceCycleStart - baseline.placements();
        int retryDelta = pathRetriesSinceCycleStart - baseline.pathRetries();
        int hardDelta = hardUnreachableSinceCycleStart - baseline.hardUnreachable();
        double retryDensity = retryDelta / (double) Math.max(1, placementDelta);
        boolean lowPlacementDelta = placementDelta <= RETRY_DENSITY_LOW_PLACEMENT_DELTA_THRESHOLD;
        if (!lowPlacementDelta) {
            return;
        }
        if (retryDensity < RETRY_DENSITY_TRIGGER_THRESHOLD && hardDelta < RETRY_DENSITY_HARD_UNREACHABLE_TRIGGER_DELTA) {
            return;
        }
        enterRetryDensityFallbackMode(world, placementDelta, retryDelta, hardDelta, retryDensity);
    }

    private void enterRetryDensityFallbackMode(ServerWorld world,
                                               int placementDelta,
                                               int retryDelta,
                                               int hardDelta,
                                               double retryDensity) {
        retryDensityFallbackModeActive = true;
        retryDensityFallbackPlacementStreak = 0;
        releaseLocalSortieClaims(world, "retry_density_fallback_enter");
        localSortieQueue.clear();
        activeMoveTarget = null;
        resetActiveMoveProgressTracking(world);
        resetPathFailureState();
        maybeAdvanceCurrentIndexPastSuppressedRegion(world);
        int activeLayer = findLowestPendingLayer(world);
        if (activeLayer > 0) {
            reconcileSkippedSegmentsForLayer(world, activeLayer, "retry_density_fallback_enter");
        }
        if (stage == Stage.PLACE_BLOCK) {
            stage = Stage.MOVE_TO_SEGMENT;
        }
        LOGGER.info("MasonWallBuilder {}: fallback_mode_enter placementDelta={} retryDelta={} hardUnreachableDelta={} retryDensity={} stage={} suppressedRegions={}",
                guard.getUuidAsString(),
                placementDelta,
                retryDelta,
                hardDelta,
                String.format("%.2f", retryDensity),
                stage,
                countActiveSuppressedRegions(world.getTime()));
    }

    private void exitRetryDensityFallbackMode(ServerWorld world, String reason) {
        if (!retryDensityFallbackModeActive) {
            return;
        }
        retryDensityFallbackModeActive = false;
        retryDensityFallbackPlacementStreak = 0;
        retryDensitySnapshots.clear();
        LOGGER.info("MasonWallBuilder {}: fallback_mode_exit reason={} placements={} retries={} hard_unreachable={}",
                guard.getUuidAsString(),
                reason,
                placementsSinceCycleStart,
                pathRetriesSinceCycleStart,
                hardUnreachableSinceCycleStart);
    }

    private void maybeActivateStagnationPivot(ServerWorld world) {
        if (!isElectedBuilder || stage == Stage.IDLE || stage == Stage.DONE || pendingSegments.isEmpty()) {
            return;
        }
        if (isLayerOneFirstLapEnforced(world)) {
            return;
        }
        long now = world.getTime();
        progressSnapshots.addLast(new ProgressSnapshot(now, placementsSinceCycleStart, pathRetriesSinceCycleStart, hardUnreachableSinceCycleStart));
        while (!progressSnapshots.isEmpty() && (now - progressSnapshots.peekFirst().tick()) > STAGNATION_PROGRESS_WINDOW_TICKS) {
            progressSnapshots.pollFirst();
        }
        ProgressSnapshot baseline = progressSnapshots.peekFirst();
        if (baseline == null) {
            return;
        }

        int placementDelta = placementsSinceCycleStart - baseline.placements();
        int retryDelta = pathRetriesSinceCycleStart - baseline.pathRetries();
        int hardDelta = hardUnreachableSinceCycleStart - baseline.hardUnreachable();
        if (stagnationPivotActive) {
            long activeDuration = Math.max(0L, now - stagnationPivotActivatedAtTick);
            int cooldownRemaining = (int) Math.max(0L, stagnationPivotCooldownUntilTick - now);
            boolean strongPlacementStreak = stagnationPivotPlacementStreak >= STAGNATION_PIVOT_CLEAR_PLACEMENT_STREAK;
            boolean progressInOtherRegions = hasConfirmedProgressInOtherRegions(world);
            boolean clearConditionMet = progressInOtherRegions
                    && (strongPlacementStreak || (activeDuration >= STAGNATION_PIVOT_MIN_ACTIVE_TICKS && hasMeaningfulPivotRecovery(retryDelta, hardDelta)));
            maybeLogPivotLifecycle(world, activeDuration, cooldownRemaining, clearConditionMet, retryDelta, hardDelta);
            if (clearConditionMet) {
                clearStagnationPivot(world, strongPlacementStreak ? "placement_streak" : "recovered_window", activeDuration, cooldownRemaining, clearConditionMet);
                return;
            }
            maybeEscalatePivotStall(world, activeDuration);
            if (!stagnationPivotActive) {
                return;
            }
            if (now >= stagnationPivotSuppressUntilTick) {
                maybeRefreshStagnationPivotSuppressionRange(world);
                stagnationPivotSuppressUntilTick = now + STAGNATION_PIVOT_INTERVAL_TICKS;
            }
            return;
        }
        if (now < stagnationPivotCooldownUntilTick) {
            maybeLogPivotLifecycle(world, 0L, (int) (stagnationPivotCooldownUntilTick - now), false, retryDelta, hardDelta);
            return;
        }
        if (placementDelta > 0) {
            return;
        }
        if (retryDelta < STAGNATION_RETRY_DELTA_THRESHOLD && hardDelta < STAGNATION_HARD_UNREACHABLE_DELTA_THRESHOLD) {
            return;
        }
        activateStagnationPivot(world, retryDelta, hardDelta);
    }

    private void activateStagnationPivot(ServerWorld world, int retryDelta, int hardDelta) {
        SuppressionRegionKey suppressedRegionKey = resolveSuppressionRegionKey(world, resolveCurrentPivotSegment(world));
        long suppressionUntilTick = applyRegionSuppression(world, suppressedRegionKey);
        stagnationPivotActiveSuppressedRegionKey = suppressedRegionKey;
        stagnationPivotSuppressUntilTick = suppressionUntilTick;
        stagnationPivotPlacementStreak = 0;
        stagnationPivotPlacementsAtActivation = placementsSinceCycleStart;
        stagnationPivotExcludedBandSignature = resolvePivotExcludedBandSignature(world);
        stagnationPivotActive = true;
        stagnationPivotActivatedAtTick = world.getTime();
        nextPivotLifecycleLogTick = world.getTime();
        lastPivotActivationRetryDelta = retryDelta;
        lastPivotActivationHardDelta = hardDelta;
        releaseLocalSortieClaims(world, "stagnation_pivot");
        localSortieQueue.clear();
        activeMoveTarget = null;
        resetActiveMoveProgressTracking(world);
        resetPathFailureState();
        maybeAdvanceCurrentIndexPastSuppressedRegion(world);
        LOGGER.info("MasonWallBuilder {}: stagnation_pivot_activated placementsDelta=0 retryDelta={} hardUnreachableDelta={} suppressedRegion={} excludedBand={} until={}",
                guard.getUuidAsString(),
                retryDelta,
                hardDelta,
                stagnationPivotActiveSuppressedRegionKey == null ? "none" : stagnationPivotActiveSuppressedRegionKey.asKey(),
                stagnationPivotExcludedBandSignature == null ? "none" : stagnationPivotExcludedBandSignature,
                stagnationPivotSuppressUntilTick);
    }

    private void clearStagnationPivot(ServerWorld world, String reason, long activeDuration, int cooldownRemaining, boolean clearConditionMet) {
        if (!stagnationPivotActive) {
            return;
        }
        int placementsGained = Math.max(0, placementsSinceCycleStart - stagnationPivotPlacementsAtActivation);
        LOGGER.info("MasonWallBuilder {}: stagnation_pivot_summary reason={} placementsGained={} placementStreak={} retryDelta={} hardUnreachableDelta={} activeDuration={} cooldownRemaining={} clearConditionMet={} excludedBand={}",
                guard.getUuidAsString(),
                reason,
                placementsGained,
                stagnationPivotPlacementStreak,
                lastPivotActivationRetryDelta,
                lastPivotActivationHardDelta,
                activeDuration,
                cooldownRemaining,
                clearConditionMet,
                stagnationPivotExcludedBandSignature == null ? "none" : stagnationPivotExcludedBandSignature);
        long now = world == null ? 0L : world.getTime();
        stagnationPivotCooldownUntilTick = now + STAGNATION_PIVOT_REACTIVATION_COOLDOWN_TICKS;
        clearStagnationPivotState();
        stagnationPivotSuppressUntilTick = now;
    }

    private void clearStagnationPivotState() {
        progressSnapshots.clear();
        retryDensitySnapshots.clear();
        retryDensityFallbackModeActive = false;
        retryDensityFallbackPlacementStreak = 0;
        stagnationPivotActive = false;
        stagnationPivotActiveSuppressedRegionKey = null;
        stagnationPivotSuppressUntilTick = 0L;
        stagnationPivotPlacementStreak = 0;
        stagnationPivotPlacementsAtActivation = 0;
        stagnationPivotExcludedBandSignature = null;
        lastPivotActivationRetryDelta = 0;
        lastPivotActivationHardDelta = 0;
        stagnationPivotActivatedAtTick = 0L;
        nextPivotLifecycleLogTick = 0L;
    }

    private void maybeRefreshStagnationPivotSuppressionRange(ServerWorld world) {
        SuppressionRegionKey refreshedRegion = resolveSuppressionRegionKey(world, resolveCurrentPivotSegment(world));
        if (!isMaterialPivotSuppressionShift(refreshedRegion)) {
            return;
        }
        long suppressionUntilTick = applyRegionSuppression(world, refreshedRegion);
        stagnationPivotActiveSuppressedRegionKey = refreshedRegion;
        stagnationPivotSuppressUntilTick = suppressionUntilTick;
        maybeAdvanceCurrentIndexPastSuppressedRegion(world);
        LOGGER.info("MasonWallBuilder {}: stagnation_pivot_suppression_updated suppressedRegion={} until={}",
                guard.getUuidAsString(),
                stagnationPivotActiveSuppressedRegionKey == null ? "none" : stagnationPivotActiveSuppressedRegionKey.asKey(),
                stagnationPivotSuppressUntilTick);
    }

    private boolean isMaterialPivotSuppressionShift(SuppressionRegionKey refreshedRegion) {
        if (stagnationPivotActiveSuppressedRegionKey == null) {
            return true;
        }
        return !stagnationPivotActiveSuppressedRegionKey.equals(refreshedRegion);
    }

    private boolean hasMeaningfulPivotRecovery(int retryDelta, int hardDelta) {
        return (lastPivotActivationRetryDelta - retryDelta) >= STAGNATION_PIVOT_CLEAR_RETRY_RECOVERY_DELTA
                || (lastPivotActivationHardDelta - hardDelta) >= STAGNATION_PIVOT_CLEAR_HARD_RECOVERY_DELTA;
    }

    private void maybeLogPivotLifecycle(ServerWorld world,
                                        long activeDuration,
                                        int cooldownRemaining,
                                        boolean clearConditionMet,
                                        int retryDelta,
                                        int hardDelta) {
        long now = world.getTime();
        if (now < nextPivotLifecycleLogTick) {
            return;
        }
        nextPivotLifecycleLogTick = now + STAGNATION_PIVOT_LIFECYCLE_LOG_INTERVAL_TICKS;
        LOGGER.info("MasonWallBuilder {}: stagnation_pivot_lifecycle active={} activeDuration={} cooldownRemaining={} clearConditionMet={} placementStreak={} retryDelta={} hardUnreachableDelta={}",
                guard.getUuidAsString(),
                stagnationPivotActive,
                activeDuration,
                cooldownRemaining,
                clearConditionMet,
                stagnationPivotPlacementStreak,
                retryDelta,
                hardDelta);
    }

    private boolean isPivotSuppressedIndex(ServerWorld world, int index, BlockPos segment) {
        if (!stagnationPivotActive || world.getTime() >= stagnationPivotSuppressUntilTick) {
            return false;
        }
        BlockPos target = segment;
        if (target == null && index >= 0 && index < pendingSegments.size()) {
            target = pendingSegments.get(index);
        }
        SuppressionRegionKey activeRegion = stagnationPivotActiveSuppressedRegionKey;
        if (activeRegion == null || target == null) {
            return false;
        }
        return activeRegion.equals(resolveSuppressionRegionKey(world, target));
    }

    private boolean isPivotHardExcludedBand(ServerWorld world, BlockPos segment) {
        if (!stagnationPivotActive || stagnationPivotExcludedBandSignature == null || segment == null) {
            return false;
        }
        return stagnationPivotExcludedBandSignature.equals(segmentBandSignature(world, segment));
    }

    private boolean isPivotExcludedSegment(ServerWorld world, int index, BlockPos segment) {
        if (segment != null && !isLayerOneAggressiveHeuristicsEnabled(getSegmentLayer(world, segment))) {
            return false;
        }
        return isRegionSuppressed(world, segment)
                || isPivotSuppressedIndex(world, index, segment)
                || isPivotHardExcludedBand(world, segment);
    }

    private String resolvePivotExcludedBandSignature(ServerWorld world) {
        BlockPos localHead = localSortieQueue.peekFirst();
        if (localHead != null) {
            return segmentBandSignature(world, localHead);
        }
        if (activeMoveTarget != null) {
            return segmentBandSignature(world, activeMoveTarget);
        }
        int candidateIndex = Math.max(0, Math.min(currentSegmentIndex, Math.max(0, pendingSegments.size() - 1)));
        if (!pendingSegments.isEmpty() && candidateIndex < pendingSegments.size()) {
            return segmentBandSignature(world, pendingSegments.get(candidateIndex));
        }
        return null;
    }

    private void maybeEscalatePivotStall(ServerWorld world, long activeDuration) {
        if (!stagnationPivotActive || activeDuration < STAGNATION_PIVOT_MAX_ACTIVE_TICKS) {
            return;
        }
        int placementsGained = Math.max(0, placementsSinceCycleStart - stagnationPivotPlacementsAtActivation);
        if (placementsGained > 0) {
            return;
        }
        if (stagnationPivotExcludedBandSignature != null) {
            long cooldownUntil = world.getTime() + STAGNATION_PIVOT_SECTION_COOLDOWN_TICKS;
            quarantinedBandUntilTick.put(stagnationPivotExcludedBandSignature, cooldownUntil);
            LOGGER.warn("MasonWallBuilder {}: stagnation_pivot_section_cooldown band={} cooldownTicks={} cooldownUntilTick={}",
                    guard.getUuidAsString(),
                    stagnationPivotExcludedBandSignature,
                    STAGNATION_PIVOT_SECTION_COOLDOWN_TICKS,
                    cooldownUntil);
        }
        maybeAdvanceCurrentIndexPastSuppressedRegion(world);
        clearStagnationPivot(world, "max_duration_no_placements", activeDuration, 0, false);
    }

    private BlockPos resolveCurrentPivotSegment(ServerWorld world) {
        BlockPos localHead = localSortieQueue.peekFirst();
        if (localHead != null) {
            return localHead;
        }
        if (activeMoveTarget != null) {
            return activeMoveTarget;
        }
        int candidateIndex = Math.max(0, Math.min(currentSegmentIndex, Math.max(0, pendingSegments.size() - 1)));
        if (!pendingSegments.isEmpty() && candidateIndex < pendingSegments.size()) {
            return pendingSegments.get(candidateIndex);
        }
        return guard.getBlockPos();
    }

    private long applyRegionSuppression(ServerWorld world, SuppressionRegionKey regionKey) {
        long now = world.getTime();
        StagnationRegionSuppressionState existing = stagnationSuppressionByRegion.get(regionKey);
        int nextHitCount = existing == null ? 1 : existing.hitCount() + 1;
        long cooldown = Math.min(
                STAGNATION_REGION_SUPPRESSION_MAX_COOLDOWN_TICKS,
                STAGNATION_REGION_SUPPRESSION_BASE_COOLDOWN_TICKS * (1L << Math.min(4, Math.max(0, nextHitCount - 1))));
        long untilTick = now + cooldown;
        long firstSuppressedAt = existing == null ? now : existing.firstSuppressedAtTick();
        stagnationSuppressionByRegion.put(regionKey, new StagnationRegionSuppressionState(firstSuppressedAt, now, untilTick, nextHitCount));
        lastSuppressionAppliedTick = now;
        placementsAtLastSuppression = placementsSinceCycleStart;
        return untilTick;
    }

    private boolean isRegionSuppressed(ServerWorld world, BlockPos segment) {
        if (segment == null) {
            return false;
        }
        long now = world.getTime();
        SuppressionRegionKey key = resolveSuppressionRegionKey(world, segment);
        StagnationRegionSuppressionState state = stagnationSuppressionByRegion.get(key);
        if (state == null) {
            return false;
        }
        if (state.suppressedUntilTick() <= now) {
            stagnationSuppressionByRegion.remove(key);
            return false;
        }
        return true;
    }

    private SuppressionRegionKey resolveSuppressionRegionKey(ServerWorld world, BlockPos segment) {
        int layer = Math.max(1, getSegmentLayer(world, segment));
        if (cycleWallRect == null) {
            return new SuppressionRegionKey("fallback", 0, layer);
        }
        int minX = cycleWallRect.minX();
        int maxX = cycleWallRect.maxX();
        int minZ = cycleWallRect.minZ();
        int maxZ = cycleWallRect.maxZ();
        String face;
        int faceIndex;
        int faceLength;
        if (segment.getZ() <= minZ) {
            face = "north";
            faceIndex = segment.getX() - minX;
            faceLength = Math.max(1, maxX - minX + 1);
        } else if (segment.getX() >= maxX) {
            face = "east";
            faceIndex = segment.getZ() - minZ;
            faceLength = Math.max(1, maxZ - minZ + 1);
        } else if (segment.getZ() >= maxZ) {
            face = "south";
            faceIndex = maxX - segment.getX();
            faceLength = Math.max(1, maxX - minX + 1);
        } else {
            face = "west";
            faceIndex = maxZ - segment.getZ();
            faceLength = Math.max(1, maxZ - minZ + 1);
        }
        int clampedIndex = Math.max(0, Math.min(faceIndex, faceLength - 1));
        int bucketCount = Math.max(1, Math.min(STAGNATION_REGION_BAND_BUCKETS, faceLength));
        int bandIndex = Math.min(bucketCount - 1, (clampedIndex * bucketCount) / faceLength);
        return new SuppressionRegionKey(face, bandIndex, layer);
    }

    private void maybeAdvanceCurrentIndexPastSuppressedRegion(ServerWorld world) {
        if (pendingSegments.isEmpty()) {
            return;
        }
        int nextCandidate = Math.max(0, currentSegmentIndex);
        while (nextCandidate < pendingSegments.size() && isRegionSuppressed(world, pendingSegments.get(nextCandidate))) {
            nextCandidate++;
        }
        currentSegmentIndex = Math.min(pendingSegments.size(), nextCandidate);
    }

    private int countActiveSuppressedRegions(long now) {
        int active = 0;
        for (Map.Entry<SuppressionRegionKey, StagnationRegionSuppressionState> entry : stagnationSuppressionByRegion.entrySet()) {
            if (entry.getValue().suppressedUntilTick() > now) {
                active++;
            }
        }
        return active;
    }

    private long oldestSuppressionAgeTicks(long now) {
        long oldestTick = Long.MAX_VALUE;
        for (StagnationRegionSuppressionState state : stagnationSuppressionByRegion.values()) {
            if (state.suppressedUntilTick() <= now) {
                continue;
            }
            oldestTick = Math.min(oldestTick, state.firstSuppressedAtTick());
        }
        if (oldestTick == Long.MAX_VALUE) {
            return 0L;
        }
        return Math.max(0L, now - oldestTick);
    }

    private boolean hasConfirmedProgressInOtherRegions(ServerWorld world) {
        if (stagnationPivotActiveSuppressedRegionKey == null) {
            return true;
        }
        if (activeMoveTarget == null) {
            return false;
        }
        SuppressionRegionKey activeRegion = resolveSuppressionRegionKey(world, activeMoveTarget);
        return !stagnationPivotActiveSuppressedRegionKey.equals(activeRegion);
    }

    private void maybeClearSuppressedRegionsAfterProgress(ServerWorld world, BlockPos placedSegment) {
        if (placedSegment == null || stagnationSuppressionByRegion.isEmpty()) {
            return;
        }
        SuppressionRegionKey progressedRegion = resolveSuppressionRegionKey(world, placedSegment);
        List<SuppressionRegionKey> cleared = new ArrayList<>();
        for (Map.Entry<SuppressionRegionKey, StagnationRegionSuppressionState> entry : stagnationSuppressionByRegion.entrySet()) {
            SuppressionRegionKey key = entry.getKey();
            if (key.equals(progressedRegion)) {
                continue;
            }
            cleared.add(key);
        }
        for (SuppressionRegionKey key : cleared) {
            stagnationSuppressionByRegion.remove(key);
        }
        if (!cleared.isEmpty()) {
            LOGGER.debug("MasonWallBuilder {}: cleared_suppressed_regions_after_progress count={} progressedRegion={}",
                    guard.getUuidAsString(),
                    cleared.size(),
                    progressedRegion.asKey());
        }
    }

    private BlockPos findPivotAnchorCandidate(ServerWorld world, int activeLayer, boolean preferNonQuarantinedBands) {
        long now = world.getTime();
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos candidate = pendingSegments.get(i);
            if (isPivotExcludedSegment(world, i, candidate)) continue;
            if (getSegmentLayer(world, candidate) != activeLayer) continue;
            if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, candidate, now)) continue;
            if (isBuildableCandidate(world, candidate)) {
                return candidate;
            }
        }
        for (int layer = activeLayer + 1; layer <= 3; layer++) {
            for (int i = 0; i < pendingSegments.size(); i++) {
                BlockPos candidate = pendingSegments.get(i);
                if (isPivotExcludedSegment(world, i, candidate)) continue;
                if (getSegmentLayer(world, candidate) != layer) continue;
                if (preferNonQuarantinedBands && isSegmentBandQuarantined(world, candidate, now)) continue;
                if (isBuildableCandidate(world, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
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
            if (isGatePosition(segment) || isPlacedWallBlock(world.getBlockState(segment)) || isTerminalSegment(segment)) continue;
            pendingLayerCount++;
            if (skippedSegments.contains(segment)) {
                skippedLayerCount++;
                continue;
            }
            buildableLayerCount++;
        }

        recoverSegmentStatesForLayerAfterWatchdog(world, activeLayer);
        deferredSegmentRetryQueue.removeIf(deferred -> getSegmentLayer(world, deferred.segment()) == activeLayer);
        releaseLocalSortieClaims(world, "progress_watchdog_recover");
        localSortieQueue.clear();
        activeMoveTarget = null;
        resetActiveMoveProgressTracking(world);
        resetPathFailureState();
        sortiePlacements = MAX_SEGMENTS_PER_SORTIE;
        currentSegmentIndex = findNearestPendingSegmentIndex(world, guard.getBlockPos(), activeLayer);

        BlockPos recoverySegment = getPendingSegmentAt(currentSegmentIndex);
        if (recoverySegment != null && placementsSinceCycleStart <= watchdogPlacementCountAtLastRecovery) {
            if (recoverySegment.equals(watchdogLastRecoverySegment)) {
                watchdogSameRecoverySegmentCount++;
            } else {
                watchdogSameRecoverySegmentCount = 1;
            }
        } else {
            watchdogSameRecoverySegmentCount = 1;
        }
        watchdogLastRecoverySegment = recoverySegment;
        watchdogPlacementCountAtLastRecovery = placementsSinceCycleStart;

        if (recoverySegment != null && watchdogSameRecoverySegmentCount >= WATCHDOG_REPEAT_SEGMENT_THRESHOLD) {
            localSortieQueue.remove(recoverySegment);
            int repeatRecoveryCount = incrementRepeatRecoveryCount(recoverySegment);
            registerSegmentFailureMetadata(world, recoverySegment, null, "watchdog_repeat_recovery_segment");
            if (repeatRecoveryCount >= WATCHDOG_REPEAT_RECOVERY_TERMINAL_THRESHOLD) {
                markSegmentCycleExcluded(world, recoverySegment, "watchdog_repeat_recovery_terminal", repeatRecoveryCount);
            } else {
                long cooldownTicks = computeWatchdogRepeatRecoveryCooldownTicks(repeatRecoveryCount);
                requeueSegmentWithCooldown(
                        world,
                        recoverySegment,
                        "watchdog_repeat_recovery_segment",
                        cooldownTicks,
                        "cooldown_requeue",
                        repeatRecoveryCount
                );
                LOGGER.info("MasonWallBuilder {}: watchdog_repeat_recovery_segment segment={} sameRecoveryHits={} repeatRecoveryCount={} cooldownTicks={}",
                        guard.getUuidAsString(),
                        recoverySegment.toShortString(),
                        watchdogSameRecoverySegmentCount,
                        repeatRecoveryCount,
                        cooldownTicks);
            }
        }
        lastPlacementTick = world.getTime();

        LOGGER.info("MasonWallBuilder {}: progress_watchdog_recovery context={} stalledTicks={} placementsSinceCycleStart={} layer={} pending={} buildable={} skipped={} resetIndex={}",
                guard.getUuidAsString(),
                context,
                PROGRESS_WATCHDOG_TIMEOUT_TICKS,
                placementsSinceCycleStart,
                activeLayer,
                pendingLayerCount,
                buildableLayerCount,
                skippedLayerCount,
                currentSegmentIndex);
        debugLogSegmentStateSanity(world, "watchdog_recover");
    }

    private int findNearestPendingSegmentIndex(ServerWorld world, BlockPos from, int layer) {
        int nearestIndex = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < pendingSegments.size(); i++) {
            BlockPos candidate = pendingSegments.get(i);
            if (getSegmentLayer(world, candidate) != layer) continue;
            if (isGatePosition(candidate) || isPlacedWallBlock(world.getBlockState(candidate)) || isTerminalSegment(candidate)) continue;
            double distSq = from.getSquaredDistance(candidate);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private BlockPos getPendingSegmentAt(int index) {
        if (index < 0 || index >= pendingSegments.size()) {
            return null;
        }
        return pendingSegments.get(index);
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
        logDetailed("MasonWallBuilder {}: sortie_end_summary anchor={} layer={} transientRetries={} fallbackTargets={} hard_unreachable_marked={} obstaclesCleared={} protectedObstaclesSkipped={} unbreakableObstaclesSkipped={} nearestFallbackSuppressedByLayerOneLap={}",
                guard.getUuidAsString(),
                activeAnchorPos == null ? "none" : activeAnchorPos.toShortString(),
                sortieActiveLayer,
                sortieTransientRetriesAttempted,
                sortieFallbackTargetsTried,
                sortieHardUnreachableMarked,
                obstaclesCleared,
                protectedObstaclesSkipped,
                unbreakableObstaclesSkipped,
                sortieNearestFallbackSuppressedByLayerOneLap);
        LOGGER.debug("MasonWallBuilder {}: sortie_preflight_summary anchor={} layer={} candidatesConsidered={} accepted={} preflight_pass={} preflightRejected={} preflight_fail_fallback_accept={} hard_unreachable_marked={} nearestFallbackSuppressedByLayerOneLap={}",
                guard.getUuidAsString(),
                activeAnchorPos == null ? "none" : activeAnchorPos.toShortString(),
                sortieActiveLayer,
                sortieCandidatesConsidered,
                sortieCandidatesAccepted,
                sortieCandidatesPreflightPass,
                sortieCandidatesPreflightRejected,
                sortieCandidatesFallbackAccepted,
                sortieHardUnreachableMarked,
                sortieNearestFallbackSuppressedByLayerOneLap);
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
        sortieNearestFallbackSuppressedByLayerOneLap = false;
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
            markSegmentClaimed(world, segment, "claim_success");
        }
        return claimed;
    }

    private void releaseSegmentClaim(ServerWorld world, BlockPos segment, String reason) {
        if (activeAnchorPos == null || segment == null) {
            return;
        }
        if (!unmarkSegmentClaimed(world, segment, "claim_release:" + reason)) {
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
     * Scans the configured POI subset within the wall footprint scan radius of the QM chest anchor,
     * computes their bounding box, and returns a footprint signature used for wall planning.
     */
    private Optional<PoiFootprintSignature> computePoiFootprintSignature(ServerWorld world, BlockPos anchorPos) {
        int range = GuardVillagersConfig.masonWallFootprintRadius;
        Box searchBox = new Box(anchorPos).expand(range);

        int selectedMinX = Integer.MAX_VALUE, selectedMinZ = Integer.MAX_VALUE;
        int selectedMaxX = Integer.MIN_VALUE, selectedMaxZ = Integer.MIN_VALUE;
        int occupancyMinX = Integer.MAX_VALUE, occupancyMinZ = Integer.MAX_VALUE;
        int occupancyMaxX = Integer.MIN_VALUE, occupancyMaxZ = Integer.MIN_VALUE;
        int count = 0;
        int hash = 1;
        int occupancyHash = 1;
        int occupancyCount = 0;
        Set<Long> protectedStructureColumns = new HashSet<>();
        List<BlockPos> candidatePois = new ArrayList<>();

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
            candidatePois.add(pos.toImmutable());
        }

        if (candidatePois.isEmpty()) {
            return Optional.empty();
        }

        List<List<BlockPos>> clusters = buildPoiClusters(candidatePois, POI_CLUSTER_LINK_DISTANCE);
        ClusterSelectionDecision clusterSelection = selectAnchorCluster(world, anchorPos, clusters, range);
        List<BlockPos> selectedCluster = clusterSelection.cluster();
        if (selectedCluster.isEmpty()) {
            return Optional.empty();
        }

        for (BlockPos pos : selectedCluster) {
            if (pos.getX() < selectedMinX) selectedMinX = pos.getX();
            if (pos.getX() > selectedMaxX) selectedMaxX = pos.getX();
            if (pos.getZ() < selectedMinZ) selectedMinZ = pos.getZ();
            if (pos.getZ() > selectedMaxZ) selectedMaxZ = pos.getZ();
            count++;
            int posHash = 31 * (31 * pos.getX() + pos.getY()) + pos.getZ();
            hash = 31 * hash + posHash;
            if (pos.getX() < occupancyMinX) occupancyMinX = pos.getX();
            if (pos.getX() > occupancyMaxX) occupancyMaxX = pos.getX();
            if (pos.getZ() < occupancyMinZ) occupancyMinZ = pos.getZ();
            if (pos.getZ() > occupancyMaxZ) occupancyMaxZ = pos.getZ();
        }

        StructureEnvelopeScanResult structureEnvelope = clusterSelection.structureEnvelope();

        if (structureEnvelope.foundAny()) {
            occupancyMinX = Math.min(occupancyMinX, structureEnvelope.minX());
            occupancyMinZ = Math.min(occupancyMinZ, structureEnvelope.minZ());
            occupancyMaxX = Math.max(occupancyMaxX, structureEnvelope.maxX());
            occupancyMaxZ = Math.max(occupancyMaxZ, structureEnvelope.maxZ());
            for (long packedColumn : structureEnvelope.protectedColumns()) {
                if (protectedStructureColumns.add(packedColumn)) {
                    occupancyCount++;
                    occupancyHash = 31 * occupancyHash + Long.hashCode(packedColumn);
                }
            }
        }

        int mergedMinX = Math.min(selectedMinX, occupancyMinX);
        int mergedMinZ = Math.min(selectedMinZ, occupancyMinZ);
        int mergedMaxX = Math.max(selectedMaxX, occupancyMaxX);
        int mergedMaxZ = Math.max(selectedMaxZ, occupancyMaxZ);
        int mergedWidth = mergedMaxX - mergedMinX + 1;
        int mergedDepth = mergedMaxZ - mergedMinZ + 1;

        if (mergedWidth > MASON_WALL_FOOTPRINT_HARD_MAX_SPAN || mergedDepth > MASON_WALL_FOOTPRINT_HARD_MAX_SPAN) {
            LOGGER.warn(
                    "MasonWallBuilderGoal: rejecting oversized POI footprint; anchor={} bounds=[minX={}, minZ={}, maxX={}, maxZ={}] width={} depth={} hardMaxSpan={}",
                    anchorPos,
                    mergedMinX,
                    mergedMinZ,
                    mergedMaxX,
                    mergedMaxZ,
                    mergedWidth,
                    mergedDepth,
                    MASON_WALL_FOOTPRINT_HARD_MAX_SPAN
            );
            return Optional.empty();
        }

        String structureBoundsLog = structureEnvelope.foundAny()
                ? String.format("[minX=%d, minZ=%d, maxX=%d, maxZ=%d]", structureEnvelope.minX(), structureEnvelope.minZ(), structureEnvelope.maxX(), structureEnvelope.maxZ())
                : "none";
        int perimeterSignatureHash = Objects.hash(
                mergedMinX,
                mergedMinZ,
                mergedMaxX,
                mergedMaxZ,
                count + occupancyCount,
                31 * hash + occupancyHash
        );
        int boundsHash = Objects.hash(mergedMinX, mergedMinZ, mergedMaxX, mergedMaxZ);
        CycleIdentity planningIdentity = new CycleIdentity(anchorPos.toImmutable(), perimeterSignatureHash, boundsHash, 0L);
        if (!planningIdentity.equals(lastFootprintPlanningLogIdentity)) {
            LOGGER.info(
                    "MasonWallBuilderGoal: wall footprint planning anchor={} total_pois_scanned={} cluster_count={} selected_cluster_id={} selected_cluster_size={} selected_cluster_score={} runner_up_score={} poi_bounds=[minX={}, minZ={}, maxX={}, maxZ={}] sampled_structure_columns={} traced_structure_columns={} structure_bounds={} merged_bounds=[minX={}, minZ={}, maxX={}, maxZ={}]",
                    anchorPos,
                    candidatePois.size(),
                    clusters.size(),
                    clusterSelection.clusterId(),
                    selectedCluster.size(),
                    String.format("%.3f", clusterSelection.score()),
                    clusterSelection.runnerUpScore() == null ? "none" : String.format("%.3f", clusterSelection.runnerUpScore()),
                    selectedMinX,
                    selectedMinZ,
                    selectedMaxX,
                    selectedMaxZ,
                    structureEnvelope.sampledColumnCount(),
                    structureEnvelope.tracedColumnCount(),
                    structureBoundsLog,
                    mergedMinX,
                    mergedMinZ,
                    mergedMaxX,
                    mergedMaxZ
            );
            lastFootprintPlanningLogIdentity = planningIdentity;
            lastFootprintPlanningDebugLogTick = Long.MIN_VALUE;
        } else if (guard.getWorld() instanceof ServerWorld serverWorld) {
            long now = serverWorld.getTime();
            if (now - lastFootprintPlanningDebugLogTick >= FOOTPRINT_PLANNING_DEBUG_LOG_INTERVAL_TICKS) {
                LOGGER.debug("MasonWallBuilder {}: suppressed repeated wall footprint planning info for identity={}",
                        guard.getUuidAsString(), planningIdentity);
                lastFootprintPlanningDebugLogTick = now;
            }
        }

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

    private List<List<BlockPos>> buildPoiClusters(List<BlockPos> candidatePois, int linkDistance) {
        int thresholdSq = linkDistance * linkDistance;
        List<List<BlockPos>> clusters = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos seed : candidatePois) {
            if (!visited.add(seed)) {
                continue;
            }
            List<BlockPos> cluster = new ArrayList<>();
            ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
            frontier.add(seed);
            cluster.add(seed);

            while (!frontier.isEmpty()) {
                BlockPos current = frontier.removeFirst();
                for (BlockPos candidate : candidatePois) {
                    if (visited.contains(candidate)) {
                        continue;
                    }
                    int dx = current.getX() - candidate.getX();
                    int dz = current.getZ() - candidate.getZ();
                    if ((dx * dx) + (dz * dz) > thresholdSq) {
                        continue;
                    }
                    visited.add(candidate);
                    frontier.addLast(candidate);
                    cluster.add(candidate);
                }
            }
            clusters.add(cluster);
        }

        return clusters;
    }

    private StructureEnvelopeScanResult scanStructureEnvelope(
            ServerWorld world,
            BlockPos anchorPos,
            int range,
            int poiMinX,
            int poiMinZ,
            int poiMaxX,
            int poiMaxZ
    ) {
        int scanMinX = Math.max(anchorPos.getX() - range, poiMinX - STRUCTURE_ENVELOPE_SCAN_MARGIN);
        int scanMaxX = Math.min(anchorPos.getX() + range, poiMaxX + STRUCTURE_ENVELOPE_SCAN_MARGIN);
        int scanMinZ = Math.max(anchorPos.getZ() - range, poiMinZ - STRUCTURE_ENVELOPE_SCAN_MARGIN);
        int scanMaxZ = Math.min(anchorPos.getZ() + range, poiMaxZ + STRUCTURE_ENVELOPE_SCAN_MARGIN);

        int anchorDistanceCapSq = STRUCTURE_ENVELOPE_ANCHOR_DISTANCE_CAP * STRUCTURE_ENVELOPE_ANCHOR_DISTANCE_CAP;
        int minY = anchorPos.getY() - Math.max(range, STRUCTURE_SAMPLE_VERTICAL_RADIUS);
        int maxY = anchorPos.getY() + Math.max(range, STRUCTURE_SAMPLE_VERTICAL_RADIUS);
        int columnsScanned = 0;
        int blockChecks = 0;
        Set<Long> sampledColumns = new HashSet<>();

        for (int x = scanMinX; x <= scanMaxX && columnsScanned < STRUCTURE_ENVELOPE_MAX_COLUMNS && blockChecks < STRUCTURE_ENVELOPE_MAX_BLOCK_CHECKS; x++) {
            for (int z = scanMinZ; z <= scanMaxZ && columnsScanned < STRUCTURE_ENVELOPE_MAX_COLUMNS && blockChecks < STRUCTURE_ENVELOPE_MAX_BLOCK_CHECKS; z++) {
                if (!shouldSampleEnvelopeColumn(x, z, scanMinX, scanMinZ, scanMaxX, scanMaxZ)) {
                    continue;
                }
                int dxAnchor = x - anchorPos.getX();
                int dzAnchor = z - anchorPos.getZ();
                if ((dxAnchor * dxAnchor) + (dzAnchor * dzAnchor) > anchorDistanceCapSq) {
                    continue;
                }
                columnsScanned++;
                for (int y = minY; y <= maxY && blockChecks < STRUCTURE_ENVELOPE_MAX_BLOCK_CHECKS; y += STRUCTURE_ENVELOPE_VERTICAL_STEP) {
                    blockChecks++;
                    BlockPos samplePos = new BlockPos(x, y, z);
                    if (!isVillageStructureBlock(world.getBlockState(samplePos))) {
                        continue;
                    }
                    sampledColumns.add(packXZ(x, z));
                    break;
                }
            }
        }

        return traceStructureEnvelope(
                world,
                anchorPos,
                minY,
                maxY,
                sampledColumns
        );
    }

    private StructureEnvelopeScanResult traceStructureEnvelope(
            ServerWorld world,
            BlockPos anchorPos,
            int minY,
            int maxY,
            Set<Long> sampledColumns
    ) {
        if (sampledColumns.isEmpty()) {
            return StructureEnvelopeScanResult.empty();
        }

        Set<Long> tracedColumns = new HashSet<>(sampledColumns);
        Set<Long> visitedColumns = new HashSet<>(sampledColumns);
        ArrayDeque<Long> frontier = new ArrayDeque<>(sampledColumns);
        int[] blockChecks = new int[]{0};
        int anchorRadiusSq = STRUCTURE_TRACE_ANCHOR_RADIUS_CAP * STRUCTURE_TRACE_ANCHOR_RADIUS_CAP;
        int visitedCount = visitedColumns.size();
        int emptyLayerCount = 0;

        while (!frontier.isEmpty()
                && visitedCount < STRUCTURE_TRACE_MAX_COLUMNS_VISITED
                && blockChecks[0] < STRUCTURE_TRACE_MAX_BLOCK_CHECKS) {
            int layerSize = frontier.size();
            int layerNewColumns = 0;
            for (int i = 0; i < layerSize
                    && visitedCount < STRUCTURE_TRACE_MAX_COLUMNS_VISITED
                    && blockChecks[0] < STRUCTURE_TRACE_MAX_BLOCK_CHECKS; i++) {
                long packed = frontier.removeFirst();
                int x = unpackX(packed);
                int z = unpackZ(packed);
                for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                    int nx = x + direction.getOffsetX();
                    int nz = z + direction.getOffsetZ();
                    long neighborPacked = packXZ(nx, nz);
                    if (!visitedColumns.add(neighborPacked)) {
                        continue;
                    }
                    visitedCount++;
                    int dxAnchor = nx - anchorPos.getX();
                    int dzAnchor = nz - anchorPos.getZ();
                    if ((dxAnchor * dxAnchor) + (dzAnchor * dzAnchor) > anchorRadiusSq) {
                        continue;
                    }
                    if (!columnHasVillageStructure(world, nx, nz, minY, maxY, blockChecks, STRUCTURE_TRACE_MAX_BLOCK_CHECKS)) {
                        continue;
                    }
                    tracedColumns.add(neighborPacked);
                    frontier.addLast(neighborPacked);
                    layerNewColumns++;
                }
            }
            if (layerNewColumns == 0) {
                emptyLayerCount++;
                if (emptyLayerCount >= STRUCTURE_TRACE_STOP_AFTER_EMPTY_LAYERS) {
                    break;
                }
            } else {
                emptyLayerCount = 0;
            }
        }
        return buildStructureEnvelopeScanResult(sampledColumns, tracedColumns);
    }

    private boolean columnHasVillageStructure(
            ServerWorld world,
            int x,
            int z,
            int minY,
            int maxY,
            int[] blockChecks,
            int maxBlockChecks
    ) {
        for (int y = minY; y <= maxY && blockChecks[0] < maxBlockChecks; y += STRUCTURE_ENVELOPE_VERTICAL_STEP) {
            blockChecks[0]++;
            if (isVillageStructureBlock(world.getBlockState(new BlockPos(x, y, z)))) {
                return true;
            }
        }
        return false;
    }

    private StructureEnvelopeScanResult buildStructureEnvelopeScanResult(Set<Long> sampledColumns, Set<Long> tracedColumns) {
        int foundMinX = Integer.MAX_VALUE, foundMinZ = Integer.MAX_VALUE;
        int foundMaxX = Integer.MIN_VALUE, foundMaxZ = Integer.MIN_VALUE;
        for (long packedColumn : tracedColumns) {
            int x = unpackX(packedColumn);
            int z = unpackZ(packedColumn);
            if (x < foundMinX) foundMinX = x;
            if (x > foundMaxX) foundMaxX = x;
            if (z < foundMinZ) foundMinZ = z;
            if (z > foundMaxZ) foundMaxZ = z;
        }
        return new StructureEnvelopeScanResult(
                foundMinX,
                foundMinZ,
                foundMaxX,
                foundMaxZ,
                Set.copyOf(tracedColumns),
                sampledColumns.size(),
                tracedColumns.size()
        );
    }

    private boolean shouldSampleEnvelopeColumn(int x, int z, int minX, int minZ, int maxX, int maxZ) {
        boolean perimeter = x == minX || x == maxX || z == minZ || z == maxZ;
        if (perimeter) {
            return true;
        }
        return ((x - minX) % STRUCTURE_ENVELOPE_INTERIOR_STRIDE == 0)
                && ((z - minZ) % STRUCTURE_ENVELOPE_INTERIOR_STRIDE == 0);
    }

    private ClusterSelectionDecision selectAnchorCluster(ServerWorld world, BlockPos anchorPos, List<List<BlockPos>> clusters, int range) {
        if (clusters.isEmpty()) {
            return ClusterSelectionDecision.empty();
        }

        List<ClusterScore> scoredClusters = new ArrayList<>(clusters.size());
        int largestClusterSize = 0;
        for (int clusterId = 0; clusterId < clusters.size(); clusterId++) {
            List<BlockPos> cluster = clusters.get(clusterId);
            if (cluster.isEmpty()) {
                continue;
            }
            largestClusterSize = Math.max(largestClusterSize, cluster.size());
            scoredClusters.add(scoreCluster(world, anchorPos, range, clusterId, cluster));
        }

        if (scoredClusters.isEmpty()) {
            return ClusterSelectionDecision.empty();
        }

        scoredClusters.sort(Comparator.comparingDouble(ClusterScore::score).reversed());
        ClusterScore topScored = scoredClusters.get(0);
        ClusterScore nearestCluster = scoredClusters.stream()
                .min(Comparator.comparingDouble(ClusterScore::nearestDistanceSq))
                .orElse(topScored);
        ClusterScore selected = nearestCluster;
        boolean enforceViableClusterThreshold = largestClusterSize >= MIN_VIABLE_POI_CLUSTER_SIZE;
        ClusterScore bestViableCluster = enforceViableClusterThreshold
                ? scoredClusters.stream()
                .filter(clusterScore -> clusterScore.cluster().size() >= MIN_VIABLE_POI_CLUSTER_SIZE)
                .max(Comparator.comparingDouble(ClusterScore::score))
                .orElse(null)
                : topScored;

        if (enforceViableClusterThreshold
                && nearestCluster.cluster().size() < MIN_VIABLE_POI_CLUSTER_SIZE
                && bestViableCluster != null
                && (bestViableCluster.score() - nearestCluster.score()) >= MATERIAL_CLUSTER_SCORE_DELTA) {
            selected = bestViableCluster;
        }

        final int selectedId = selected.clusterId();
        Double runnerUpScore = scoredClusters.stream()
                .filter(clusterScore -> clusterScore.clusterId() != selectedId)
                .map(ClusterScore::score)
                .max(Double::compareTo)
                .orElse(null);
        LOGGER.info(
                "MasonWallBuilderGoal: cluster selection anchor={} selected_cluster_id={} selected_size={} selected_score={} runner_up_score={} nearest_cluster_id={} nearest_size={} nearest_score={} viable_threshold={} largest_cluster_size={}",
                anchorPos,
                selected.clusterId(),
                selected.cluster().size(),
                String.format("%.3f", selected.score()),
                runnerUpScore == null ? "none" : String.format("%.3f", runnerUpScore),
                nearestCluster.clusterId(),
                nearestCluster.cluster().size(),
                String.format("%.3f", nearestCluster.score()),
                MIN_VIABLE_POI_CLUSTER_SIZE,
                largestClusterSize
        );

        return new ClusterSelectionDecision(
                selected.cluster(),
                selected.clusterId(),
                selected.score(),
                runnerUpScore,
                selected.structureEnvelope()
        );
    }

    private ClusterScore scoreCluster(ServerWorld world, BlockPos anchorPos, int range, int clusterId, List<BlockPos> cluster) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        double nearestDistanceSq = Double.MAX_VALUE;
        int anchorVicinityCount = 0;
        int anchorVicinityRadiusSq = CLUSTER_ANCHOR_VICINITY_RADIUS * CLUSTER_ANCHOR_VICINITY_RADIUS;

        for (BlockPos pos : cluster) {
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
            double distanceSq = pos.getSquaredDistance(anchorPos);
            if (distanceSq < nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
            }
            int dx = pos.getX() - anchorPos.getX();
            int dz = pos.getZ() - anchorPos.getZ();
            if ((dx * dx) + (dz * dz) <= anchorVicinityRadiusSq) {
                anchorVicinityCount++;
            }
        }

        StructureEnvelopeScanResult structureEnvelope = scanStructureEnvelope(
                world,
                anchorPos,
                range,
                minX,
                minZ,
                maxX,
                maxZ
        );
        int structureAffinity = computeStructureAffinity(cluster, structureEnvelope.protectedColumns());
        double score = (cluster.size() * 4.0D)
                - (Math.sqrt(nearestDistanceSq) * 0.35D)
                + (structureAffinity * 0.75D)
                + Math.min(2.0D, anchorVicinityCount * 0.5D);
        return new ClusterScore(clusterId, cluster, score, nearestDistanceSq, structureEnvelope);
    }

    private int computeStructureAffinity(List<BlockPos> cluster, Set<Long> protectedColumns) {
        if (protectedColumns.isEmpty()) {
            return 0;
        }
        int affinity = 0;
        int proximityRadiusSq = CLUSTER_STRUCTURE_PROXIMITY_RADIUS * CLUSTER_STRUCTURE_PROXIMITY_RADIUS;
        for (BlockPos poi : cluster) {
            long packed = packXZ(poi.getX(), poi.getZ());
            if (protectedColumns.contains(packed)) {
                affinity += 2;
                continue;
            }
            for (int dx = -CLUSTER_STRUCTURE_PROXIMITY_RADIUS; dx <= CLUSTER_STRUCTURE_PROXIMITY_RADIUS; dx++) {
                for (int dz = -CLUSTER_STRUCTURE_PROXIMITY_RADIUS; dz <= CLUSTER_STRUCTURE_PROXIMITY_RADIUS; dz++) {
                    if ((dx * dx) + (dz * dz) > proximityRadiusSq) {
                        continue;
                    }
                    if (protectedColumns.contains(packXZ(poi.getX() + dx, poi.getZ() + dz))) {
                        affinity += 1;
                        dx = CLUSTER_STRUCTURE_PROXIMITY_RADIUS + 1;
                        break;
                    }
                }
            }
        }
        return affinity;
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

        double footprintComplexityMetric = computeFootprintComplexityMetric(signature, footprintWidth, footprintDepth);
        int finalPadding = deriveAdaptivePadding(footprintComplexityMetric);
        expandX += finalPadding;
        expandZ += finalPadding;

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
        logDetailed(
                "MasonWallBuilder {}: wall rectangle finalized [x:{}..{} z:{}..{} y:{}], side_expansions(north={}, south={}, west={}, east={}), occupancy_bounds=[x:{}..{} z:{}..{}], dims(occupancy={}x{}, wall={}x{}), adaptive_padding(final={}, complexity_metric={})",
                guard.getUuidAsString(),
                minX, maxX, minZ, maxZ, wallY,
                expandNorth, expandSouth, expandWest, expandEast,
                occupancyMinX, occupancyMaxX, occupancyMinZ, occupancyMaxZ,
                footprintWidth, footprintDepth,
                maxX - minX + 1, maxZ - minZ + 1,
                finalPadding, footprintComplexityMetric
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

    private int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private int unpackZ(long packed) {
        return (int) packed;
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

    private double computeFootprintComplexityMetric(PoiFootprintSignature signature, int footprintWidth, int footprintDepth) {
        Set<Long> occupiedColumns = signature.protectedStructureColumns();
        int footprintArea = Math.max(1, footprintWidth * footprintDepth);
        if (occupiedColumns.isEmpty()) {
            return (2.0 * (footprintWidth + footprintDepth)) / footprintArea;
        }

        int exposedEdgeCount = 0;
        for (long packed : occupiedColumns) {
            int x = (int) (packed >> 32);
            int z = (int) packed;
            if (!occupiedColumns.contains(packXZ(x + 1, z))) exposedEdgeCount++;
            if (!occupiedColumns.contains(packXZ(x - 1, z))) exposedEdgeCount++;
            if (!occupiedColumns.contains(packXZ(x, z + 1))) exposedEdgeCount++;
            if (!occupiedColumns.contains(packXZ(x, z - 1))) exposedEdgeCount++;
        }

        double perimeterToArea = (double) exposedEdgeCount / Math.max(1, occupiedColumns.size());
        double fillRatio = Math.min(1.0, (double) occupiedColumns.size() / footprintArea);
        return perimeterToArea + (1.0 - fillRatio);
    }

    private int deriveAdaptivePadding(double footprintComplexityMetric) {
        if (footprintComplexityMetric >= 3.25D) {
            return 3;
        }
        if (footprintComplexityMetric >= 2.35D) {
            return 2;
        }
        return 1;
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

    static List<BlockPos> simulateLayerOneFirstLapAnchorOrder(List<BlockPos> orderedSegments, int startIndex, int selections) {
        List<BlockPos> selected = new ArrayList<>();
        if (orderedSegments.isEmpty() || selections <= 0) {
            return selected;
        }
        int size = orderedSegments.size();
        int cursor = Math.floorMod(startIndex, size);
        int maxSelections = Math.min(selections, size);
        for (int i = 0; i < maxSelections; i++) {
            selected.add(orderedSegments.get(cursor));
            cursor = (cursor + 1) % size;
        }
        return selected;
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

    static EarlyCycleAbortPolicyDecision simulateEarlyCycleAbortPolicy(boolean earlyCycleBand, int repeatCount, boolean quarantined) {
        long adaptiveCooldown = computeAdaptiveCooldown(repeatCount, SEGMENT_COOLDOWN_TICKS, SORTIE_ABORT_BACKOFF_MAX_COOLDOWN_TICKS);
        long cooldown = quarantined ? BAND_QUARANTINE_COOLDOWN_TICKS : adaptiveCooldown;
        if (earlyCycleBand) {
            cooldown = Math.max(cooldown, EARLY_CYCLE_ABORT_COOLDOWN_TICKS);
        }
        boolean shouldQuarantine = earlyCycleBand || repeatCount >= BAND_NO_PROGRESS_ABORT_THRESHOLD;
        return new EarlyCycleAbortPolicyDecision(cooldown, shouldQuarantine);
    }

    static RetryDensityFallbackDecision simulateRetryDensityFallbackPolicy(List<Integer> placements,
                                                                           List<Integer> retries,
                                                                           List<Integer> hardUnreachable,
                                                                           int windowTicks) {
        int ticks = Math.min(placements.size(), Math.min(retries.size(), hardUnreachable.size()));
        Deque<ProgressSnapshot> snapshots = new ArrayDeque<>();
        for (int tick = 0; tick < ticks; tick++) {
            snapshots.addLast(new ProgressSnapshot(tick, placements.get(tick), retries.get(tick), hardUnreachable.get(tick)));
            while (!snapshots.isEmpty() && (tick - snapshots.peekFirst().tick()) > windowTicks) {
                snapshots.pollFirst();
            }
            ProgressSnapshot baseline = snapshots.peekFirst();
            if (baseline == null) {
                continue;
            }
            int placementDelta = placements.get(tick) - baseline.placements();
            int retryDelta = retries.get(tick) - baseline.pathRetries();
            int hardDelta = hardUnreachable.get(tick) - baseline.hardUnreachable();
            double density = retryDelta / (double) Math.max(1, placementDelta);
            boolean lowPlacementDelta = placementDelta <= RETRY_DENSITY_LOW_PLACEMENT_DELTA_THRESHOLD;
            if (lowPlacementDelta
                    && (density >= RETRY_DENSITY_TRIGGER_THRESHOLD
                    || hardDelta >= RETRY_DENSITY_HARD_UNREACHABLE_TRIGGER_DELTA)) {
                return new RetryDensityFallbackDecision(true, tick + 1, placementDelta, retryDelta, hardDelta, density);
            }
        }
        return new RetryDensityFallbackDecision(false, ticks, 0, 0, 0, 0.0D);
    }

    static WaitForStockDecision decideWaitForStockTransition(int availableWalls,
                                                            int availableCobblestone,
                                                            int threshold,
                                                            boolean allowCobblestoneFallback) {
        if (availableWalls >= threshold) {
            return WaitForStockDecision.MOVE_TO_SEGMENT;
        }
        if (allowCobblestoneFallback && availableWalls + availableCobblestone >= threshold) {
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

    private static long computeAdaptiveCooldown(int repeatCount, long baseCooldown, long maxCooldown) {
        long cooldownTicks = baseCooldown;
        int growthSteps = Math.max(0, repeatCount - 1);
        for (int i = 0; i < growthSteps && cooldownTicks < maxCooldown; i++) {
            cooldownTicks = Math.min(maxCooldown, cooldownTicks * 2L);
        }
        return cooldownTicks;
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

    private static final class LayerOneFaceCoverageState {
        private boolean firstCandidateVisited = false;
        private boolean firstSuccessfulPlacement = false;
        private boolean faceFullyBuilt = false;
        private int firstCandidateIndex = -1;
        private BlockPos firstPlacementPos = null;
    }

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

    private enum SegmentState {
        AVAILABLE,
        CLAIMED,
        DEFERRED,
        SKIPPED_TEMP,
        HARD_UNREACHABLE,
        CYCLE_EXCLUDED,
        IRRECOVERABLE,
        PLACED
    }

    private record NavigationCandidate(
            BlockPos pos,
            boolean pathAvailable,
            double distanceSqToSegment,
            int obstacleCount
    ) {}

    private record StandabilityAssessment(boolean standable, int obstacleCount) {}

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

    private record StructureEnvelopeScanResult(
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            Set<Long> protectedColumns,
            int sampledColumnCount,
            int tracedColumnCount
    ) {
        boolean foundAny() {
            return !protectedColumns.isEmpty();
        }

        static StructureEnvelopeScanResult empty() {
            return new StructureEnvelopeScanResult(
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MIN_VALUE,
                    Integer.MIN_VALUE,
                    Set.of(),
                    0,
                    0
            );
        }
    }

    private record ClusterScore(
            int clusterId,
            List<BlockPos> cluster,
            double score,
            double nearestDistanceSq,
            StructureEnvelopeScanResult structureEnvelope
    ) {}

    private record ClusterSelectionDecision(
            List<BlockPos> cluster,
            int clusterId,
            double score,
            Double runnerUpScore,
            StructureEnvelopeScanResult structureEnvelope
    ) {
        static ClusterSelectionDecision empty() {
            return new ClusterSelectionDecision(
                    List.of(),
                    -1,
                    Double.NEGATIVE_INFINITY,
                    null,
                    StructureEnvelopeScanResult.empty()
            );
        }
    }

    private record TransferTask(BlockPos sourceChestPos, BlockPos destChestPos, int amount) {}

    private record CycleIdentity(BlockPos anchorPos, int perimeterSignatureHash, int boundsHash, long cycleStartTick) {}

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

    record RetryDensityFallbackDecision(boolean fallbackTriggered,
                                        int decisionTick,
                                        int placementDelta,
                                        int retryDelta,
                                        int hardUnreachableDelta,
                                        double retryDensity) {}

    private record ProgressSnapshot(long tick, int placements, int pathRetries, int hardUnreachable) {}

    private record FailureBandWindow(int count, long firstFailureTick) {}

    private record SuppressionRegionKey(String face, int bandIndex, int layer) {
        private String asKey() {
            return face + "#" + bandIndex + "|L" + layer;
        }
    }

    private record StagnationRegionSuppressionState(
            long firstSuppressedAtTick,
            long lastSuppressedAtTick,
            long suppressedUntilTick,
            int hitCount
    ) {}

    private record LayerOneDominanceContext(
            boolean active,
            double overallCompletionRatio,
            Map<String, LayerOneRegionProgress> regionProgressByKey
    ) {
        private static LayerOneDominanceContext inactive() {
            return new LayerOneDominanceContext(false, 1.0D, Map.of());
        }
    }

    private record LayerOneRegionProgress(
            String key,
            String face,
            int segmentRangeStart,
            int segmentRangeEnd,
            int placed,
            int remaining,
            int total
    ) {
        private double completionRatio() {
            if (total <= 0) {
                return 1.0D;
            }
            return (double) placed / (double) total;
        }
    }

    private static final class MutableLayerOneProgress {
        private final String face;
        private final int segmentRangeStart;
        private final int segmentRangeEnd;
        private int placed = 0;
        private int total = 0;

        private MutableLayerOneProgress(String face, int segmentRangeStart, int segmentRangeEnd) {
            this.face = face;
            this.segmentRangeStart = segmentRangeStart;
            this.segmentRangeEnd = segmentRangeEnd;
        }
    }

    private record LayerOneRegion(String key, String face, int segmentRangeStart, int segmentRangeEnd) {}

    private record DominantAnchorSelection(BlockPos anchor, String forcedFromRegion) {}

    private enum PreflightProbeResult {
        PASS,
        FAIL,
        DEFERRED
    }

    private record PreflightCacheKey(BlockPos segment, BlockPos navTarget, long coarseTickBucket) {}

    private record PreflightCacheEntry(boolean reachesTarget, long expiresAtTick) {}

    record EarlyCycleAbortPolicyDecision(long cooldownTicks, boolean shouldQuarantineBand) {}

    private record DeferredSegmentRetry(BlockPos segment, long requeueTick) {}

    private record SegmentFailMetadata(
            long lastFailTick,
            String failReason,
            int failCount,
            int lastFailedNavTargetHash,
            int distinctFailedNavTargetCount,
            int placementsAtFirstFailure
    ) {
        private static SegmentFailMetadata create(long failTick, String reason, int navTargetHash, int placementsAtFirstFailure) {
            return new SegmentFailMetadata(
                    failTick,
                    reason,
                    1,
                    navTargetHash,
                    navTargetHash == Integer.MIN_VALUE ? 0 : 1,
                    placementsAtFirstFailure
            );
        }

        private SegmentFailMetadata withFailure(long failTick, String reason, int navTargetHash) {
            int nextDistinctCount = distinctFailedNavTargetCount;
            if (navTargetHash != Integer.MIN_VALUE
                    && navTargetHash != lastFailedNavTargetHash) {
                nextDistinctCount++;
            }
            return new SegmentFailMetadata(
                    failTick,
                    reason,
                    failCount + 1,
                    navTargetHash,
                    nextDistinctCount,
                    placementsAtFirstFailure
            );
        }
    }

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
