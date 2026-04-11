package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.HoeItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import dev.sterner.guardvillagers.common.util.VillagePenRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FarmerHarvestGoal extends Goal {
    private static final int HARVEST_RADIUS = 50;
    /**
     * Hoeing scans for raw dirt/grass to convert to farmland. This must be kept very tight —
     * the farmer's job block is a composter, which is placed adjacent to the farm plot.
     * A large radius causes the farmer to walk far from the farm and hoe unrelated dirt.
     * 10 blocks keeps hoeing anchored near the job block where the actual farmland is.
     */
    private static final int HOE_RADIUS = 10;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
    private static final int CHECK_INTERVAL_TICKS = 20;
    /** Long backoff when canStart evaluates but there is truly nothing actionable (no crops, seeds, or hoe-ready ground). */
    private static final int IDLE_BACKOFF_INTERVAL_TICKS = 600;
    /**
     * Short retry interval used when the farmer knows it has unseeded farmland but currently
     * lacks seeds. The farmer "remembers" the obligation and checks back frequently.
     */
    private static final int UNSEEDED_FARMLAND_SEED_WAIT_TICKS = 100;
    private static final int TARGET_TIMEOUT_TICKS = 200;
    private static final int GATHER_SEEDS_TIMEOUT_TICKS = 200;
    private static final int GATHER_SEEDS_NO_TARGET_LIMIT = 3;
    private static final int GATHER_SEEDS_LOW_YIELD_BREAK_LIMIT = 4;
    private static final int SEED_FORAGE_RETRY_BASE_COOLDOWN_TICKS = 100;
    private static final int SEED_FORAGE_RETRY_MAX_COOLDOWN_TICKS = 600;
    private static final int SEED_FORAGE_LOW_YIELD_COOLDOWN_TICKS = 100;
    private static final int DEFAULT_WHEAT_SEED_BOOTSTRAP_FLOOR = 0;
    /** Seeds pulled from chest per incremental pickup cycle (no-hoe, no-seeds idle mode). */
    private static final int SEED_INCREMENT_BATCH = 5;
    /** How often to attempt incremental seed pickup when no seeds and no hoe are available. */
    private static final int SEED_INCREMENT_INTERVAL_TICKS = 200;
    private static final int WATER_HYDRATION_RADIUS = 4;
    private static final int WATER_SEARCH_VERTICAL_RANGE = 8;
    private static final int BOOTSTRAP_SCAN_RADIUS = 16;
    private static final int BOOTSTRAP_SCAN_MIN_Y_OFFSET = -1;
    private static final int BOOTSTRAP_SCAN_MAX_Y_OFFSET = 1;
    private static final int BOOTSTRAP_SCAN_BLOCK_BUDGET = 900;
    private static final int BOOTSTRAP_SCAN_INTERVAL_TICKS = 200;
    private static final int BOOTSTRAP_INVALIDATION_CHECK_INTERVAL_TICKS = 200;
    private static final int BOOTSTRAP_INVALIDATION_SAMPLE_SIZE = 12;
    private static final int BOOTSTRAP_INVALIDATION_PERCENT = 35;
    private static final int BOOTSTRAP_RESCAN_COOLDOWN_TICKS = 100;
    private static final int MIN_VIABLE_TERRITORY_PLOTS = 6;
    private static final int SEED_TARGET_RESERVE_MARGIN_MIN = 2;
    private static final int SEED_TARGET_RESERVE_MARGIN_DIVISOR = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(FarmerHarvestGoal.class);

    private final VillagerEntity villager;
    private final Deque<BlockPos> harvestTargets = new ArrayDeque<>();

    private BlockPos jobPos;
    private BlockPos chestPos;
    private boolean enabled;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateRunRequested;
    private long lastHarvestDay = -1L;
    private boolean dailyHarvestRun;
    private FarmerCraftingGoal craftingGoal;
    private BlockPos currentTarget;
    private long currentTargetStartTick;
    private BlockPos bannerPos;
    private BlockPos gatePos;
    private BlockPos gateWalkTarget;
    private BlockPos exitWalkTarget;
    private double gateCloseDistanceSquared;
    private boolean gateClosedAfterEntry;
    private int feedTargetCount;
    private Direction penInsideDirection;
    private int exitDelayTicks;
    private final Deque<BlockPos> hoeTargets = new ArrayDeque<>();
    private final Deque<BlockPos> plantTargets = new ArrayDeque<>();
    private final Deque<BlockPos> gatherSeedTargets = new ArrayDeque<>();
    private BlockPos currentHoeTarget;
    private long currentHoeTargetStartTick;
    private BlockPos currentGatherTarget;
    private long currentGatherTargetStartTick;
    private long gatherSeedsStageStartTick;
    private boolean bootstrapSeedGatherRequested;
    private boolean wheatSeedForagingRequested;
    private boolean prioritizeWheatSeedsForPlanting;
    private int gatherNoTargetPasses;
    private int gatherStageSeedStartCount;
    private int gatherLowYieldBreakPasses;
    private long nextSeedForageRetryTick;
    private int seedForageRetryCount;
    private String pendingSeedGatherEndReason;

    // --- Farmland coverage scan cache ---
    // getFarmlandCoverageStats iterates ~30k blocks. Cache the result and reuse for COVERAGE_CACHE_TTL ticks
    // to avoid multiple expensive scans per tick (e.g. from ensureWheatSeedStartup + logSeedReserveStatus chains).
    private static final int COVERAGE_CACHE_TTL = 200;
    private FarmlandCoverageStats cachedCoverage = null;
    private long coverageCacheTime = -1L;
    private long adaptiveThrottleUntilTick = 0L;
    private long adaptiveScanVolumeWindow = 0L;
    private int adaptivePathRetryWindow = 0;
    private int adaptiveFailedSessionWindow = 0;
    private int adaptiveForcedRecoveryWindow = 0;
    private int adaptiveSessionCount = 0;
    /** World-time tick after which the next incremental chest seed pickup is allowed (no-seeds, no-hoe idle mode). */
    private long nextIncrementalSeedPickupTick = 0L;

    /**
     * True when the farmer has confirmed unseeded farmland exists within its range.
     * Persists across goal ticks so the farmer treats it as an ongoing obligation,
     * not just an opportunistic check. Cleared only when farmland coverage is full
     * or the farmer runs out of farmland entirely.
     */
    private boolean hasUnseededFarmlandObligation = false;

    /**
     * Used to suppress repeated logging for the obligation path.
     * Logged once per obligation cycle at DEBUG; subsequent polls remain DEBUG.
     */
    private boolean obligationLoggedThisCycle = false;
    private final Set<BlockPos> eligibleTerritory = new HashSet<>();
    private long nextBootstrapScanTick = 0L;
    private int bootstrapScanCursor = 0;
    private long lastTerritoryInvalidationTick = 0L;

    public FarmerHarvestGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.enabled = true;
        this.stage = Stage.IDLE;
        this.harvestTargets.clear();
        this.currentHoeTarget = null;
        this.gatherSeedTargets.clear();
        this.currentGatherTarget = null;
        this.wheatSeedForagingRequested = false;
        this.prioritizeWheatSeedsForPlanting = false;
        this.gatherNoTargetPasses = 0;
        this.gatherStageSeedStartCount = 0;
        this.gatherLowYieldBreakPasses = 0;
        this.nextSeedForageRetryTick = 0L;
        this.seedForageRetryCount = 0;
        this.hasUnseededFarmlandObligation = false;
        this.obligationLoggedThisCycle = false;
        this.nextIncrementalSeedPickupTick = 0L;
        this.eligibleTerritory.clear();
        this.bootstrapScanCursor = 0;
        this.nextBootstrapScanTick = 0L;
        this.lastTerritoryInvalidationTick = 0L;
        this.cachedCoverage = null;
        this.coverageCacheTime = -1L;
    }

    public void setCraftingGoal(FarmerCraftingGoal craftingGoal) {
        this.craftingGoal = craftingGoal;
    }

    public void requestImmediateWorkCheck() {
        immediateRunRequested = true;
        nextCheckTime = 0L;
    }

    public void requestCheckNoSoonerThan(long targetTick) {
        if (nextCheckTime == 0L || nextCheckTime > targetTick) {
            nextCheckTime = targetTick;
        }
    }

    @Override
    public boolean canStart() {
        if (!enabled || !villager.isAlive() || jobPos == null || chestPos == null) {
            return false;
        }
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!immediateRunRequested && world.getTime() < nextCheckTime) {
            return false;
        }
        if (immediateRunRequested) {
            immediateRunRequested = false;
        }
        if (world.getTime() < adaptiveThrottleUntilTick) {
            return false;
        }
        if (shouldThrottleFarmlandExpansionChecks(world)) {
            return false;
        }
        ensureEligibleTerritoryCache(world, false);
        int eligibleTerritoryCount = getEligibleTerritoryCount(world);
        if (eligibleTerritoryCount < MIN_VIABLE_TERRITORY_PLOTS) {
            nextCheckTime = world.getTime() + BOOTSTRAP_SCAN_INTERVAL_TICKS;
            return false;
        }

        BootstrapPreflight preflight = evaluateBootstrapPreflight(world, false);

        // Guard the expensive ~30k-block farmland scan behind resource availability.
        // The coverage scan is only actionable when the farmer can plant (seeds) or prep ground (hoe).
        // With neither, skip the scan entirely and instead pull a small seed batch from the chest
        // on a slow timer so the farmer accumulates resources without burning cycles.
        boolean hasSeedsAvailable = preflight.hasSeedsForPlanting || hasSeedsInChest(world);
        boolean hasHoeAvailable   = hasHoeInInventory() || hasHoeInChest(world);
        if (!hasSeedsAvailable && !hasHoeAvailable) {
            if (world.getTime() >= nextIncrementalSeedPickupTick) {
                nextIncrementalSeedPickupTick = world.getTime() + SEED_INCREMENT_INTERVAL_TICKS;
                tryIncrementalSeedPickupFromChest(world);
            }
            nextCheckTime = world.getTime() + SEED_INCREMENT_INTERVAL_TICKS;
            return false;
        }

        // Update unseeded farmland obligation state — this persists between canStart() calls
        // so the farmer "remembers" that it has unfinished work even between goal ticks.
        FarmlandCoverageStats coverage = getFarmlandCoverageStats(world);
        int unseededCount = Math.max(0, coverage.accessibleCells() - coverage.seededCells());
        if (unseededCount > 0) {
            if (!hasUnseededFarmlandObligation) {
                // Obligation newly detected — log once at DEBUG per cycle.
                hasUnseededFarmlandObligation = true;
                obligationLoggedThisCycle = false;
            }
        } else if (coverage.accessibleCells() > 0) {
            // Coverage is full — obligation satisfied
            hasUnseededFarmlandObligation = false;
            obligationLoggedThisCycle = false;
        }
        // If accessibleCells == 0 (no farmland at all), leave obligation as-is

        // --- OBLIGATION PATH ---
        // Unseeded farmland is an incomplete task. The farmer is obligated to finish it.
        if (hasUnseededFarmlandObligation) {
            boolean seedsAvailable = preflight.hasSeedsForPlanting || hasSeedsInChest(world);
            if (seedsAvailable) {
                // Seeds exist — act immediately, clear any stale forage cooldown so
                // the seed-gathering workflow isn't blocked by a previous retry timer.
                clearSeedForageRetryCooldown(world, "unseeded farmland obligation with seeds available");
                if (!obligationLoggedThisCycle) {
                    LOGGER.debug("Farmer {} obligation: {} unseeded farmland blocks, seeds available — starting immediately",
                            villager.getUuidAsString(), unseededCount);
                    obligationLoggedThisCycle = true;
                } else {
                    LOGGER.debug("Farmer {} obligation: {} unseeded farmland blocks, seeds available — resuming",
                            villager.getUuidAsString(), unseededCount);
                }
                nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
                long day = world.getTime() / 24000L;
                if (day != lastHarvestDay) {
                    lastHarvestDay = day;
                    dailyHarvestRun = true;
                }
                return true;
            } else {
                // No seeds in inventory or chest — but we still have unseeded farmland.
                // Allow the goal to START so it can route into GATHER_WHEAT_SEEDS via
                // routePostDepositFlow(). Blocking here creates a deadlock: the farmer
                // can never gather seeds because it can never start.
                if (!obligationLoggedThisCycle) {
                    LOGGER.debug("Farmer {} obligation: {} unseeded farmland blocks, no seeds — starting to forage",
                            villager.getUuidAsString(), unseededCount);
                    obligationLoggedThisCycle = true;
                } else {
                    LOGGER.debug("Farmer {} obligation: {} unseeded farmland blocks, no seeds — foraging",
                            villager.getUuidAsString(), unseededCount);
                }
                wheatSeedForagingRequested = true;
                nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
                long day = world.getTime() / 24000L;
                if (day != lastHarvestDay) {
                    lastHarvestDay = day;
                    dailyHarvestRun = true;
                }
                return true;
            }
        }

        // --- NORMAL PATH (no unseeded farmland obligation) ---
        long day = world.getTime() / 24000L;
        if (day != lastHarvestDay) {
            lastHarvestDay = day;
            // Evaluate before committing to a daily run — if there is truly nothing actionable
            // (no farmland, no crops, no seeds, no hoe-ready ground) skip the daily trip entirely.
            boolean nothingActionable = !preflight.canHoeGround
                    && !preflight.hasPlantTargets
                    && !preflight.hasSeedsForPlanting
                    && preflight.matureCropCount == 0
                    && preflight.plantedCropCount == 0;
            if (nothingActionable) {
                nextCheckTime = world.getTime() + IDLE_BACKOFF_INTERVAL_TICKS;
                return false;
            }
            dailyHarvestRun = true;
            nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
            return true;
        }

        int matureCount = preflight.matureCropCount;
        boolean canRunForHoeing = preflight.canHoeGround;
        if (matureCount >= 1 || canRunForHoeing) {
            nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
            return true;
        }
        if (preflight.shouldRun()) {
            // shouldRun() fires when there is a plausible reason (no crops found, may need seeding/hoeing).
            // But if there are truly no seeds, no hoe-ready ground, and no plantable targets,
            // there is nothing the farmer can do right now. Use a long backoff to avoid thrashing.
            boolean nothingActionable = !preflight.canHoeGround
                    && !preflight.hasPlantTargets
                    && !preflight.hasSeedsForPlanting
                    && preflight.matureCropCount == 0
                    && preflight.plantedCropCount == 0;
            nextCheckTime = world.getTime() + (nothingActionable ? IDLE_BACKOFF_INTERVAL_TICKS : CHECK_INTERVAL_TICKS);
            if (!nothingActionable) {
                logBootstrapReason(preflight.reason);
                return true;
            }
            return false;
        }
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        return false;
    }

    /** Returns true if any plantable seed type is present in the paired chest. */
    private boolean hasSeedsInChest(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return false;
        Inventory inv = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (inv == null) return false;
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (!stack.isEmpty() && isPlantableSeedOrCrop(stack.getItem())) return true;
        }
        return false;
    }

    @Override
    public boolean shouldContinue() {
        return enabled && villager.isAlive() && stage != Stage.DONE;
    }

    @Override
    public void start() {
        villager.setCanPickUpLoot(true);
        setStage(Stage.GO_TO_JOB);
        populateHarvestTargets();
        moveTo(jobPos);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        harvestTargets.clear();
        currentTarget = null;
        currentHoeTarget = null;
        currentGatherTarget = null;
        gatherSeedTargets.clear();
        setStage(Stage.DONE);
        adaptiveSessionCount++;
        maybeLogAdaptiveSummary();
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            setStage(Stage.DONE);
            return;
        }

        switch (stage) {
            case GO_TO_JOB -> {
                if (isNear(jobPos)) {
                    setStage(Stage.HARVEST);
                } else {
                    moveTo(jobPos);
                }
            }
            case HARVEST -> {
                if (harvestTargets.isEmpty()) {
                    if (prepareFeeding(serverWorld)) {
                        setStage(Stage.GO_TO_GATE);
                        moveTo(gatePos, MOVE_SPEED);
                    } else {
                        setStage(Stage.RETURN_TO_CHEST);
                        moveTo(chestPos);
                    }
                    return;
                }

                BlockPos target = harvestTargets.peekFirst();
                if (currentTarget == null || !currentTarget.equals(target)) {
                    currentTarget = target;
                    currentTargetStartTick = serverWorld.getTime();
                }

                if (serverWorld.getTime() - currentTargetStartTick >= TARGET_TIMEOUT_TICKS) {
                    adaptivePathRetryWindow++;
                    harvestTargets.removeFirst();
                    currentTarget = null;
                    return;
                }
                if (!isMatureCrop(serverWorld.getBlockState(target))) {
                    harvestTargets.removeFirst();
                    currentTarget = null;
                    return;
                }

                if (!isNear(target)) {
                    moveTo(target);
                    return;
                }

                BlockState harvestedState = serverWorld.getBlockState(target);
                serverWorld.breakBlock(target, true, villager);
                attemptReplant(serverWorld, target, harvestedState);
                collectNearbyDrops(serverWorld, target);
                harvestTargets.removeFirst();
                currentTarget = null;
            }
            case RETURN_TO_CHEST -> {
                if (isNear(chestPos)) {
                    setStage(Stage.DEPOSIT);
                } else {
                    moveTo(chestPos);
                }
            }
            case GO_TO_GATE -> {
                if (gatePos == null) {
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }
                if (!isNear(gatePos)) {
                    moveTo(gatePos, MOVE_SPEED);
                    return;
                }
                openGate(serverWorld, gatePos, true);
                gateWalkTarget = findGateWalkTarget(gatePos, penInsideDirection, 3);
                gateClosedAfterEntry = false;
                gateCloseDistanceSquared = calculateGateCloseDistanceSquared(gatePos, bannerPos);
                setStage(Stage.WALK_TO_BANNER);
            }
            case WALK_TO_BANNER -> {
                if (bannerPos == null) {
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }
                if (!gateClosedAfterEntry && gatePos != null) {
                    double distanceFromGateSquared = villager.squaredDistanceTo(gatePos.getX() + 0.5D, gatePos.getY() + 0.5D, gatePos.getZ() + 0.5D);
                    if (distanceFromGateSquared >= gateCloseDistanceSquared || isNear(bannerPos)) {
                        openGate(serverWorld, gatePos, false);
                        gateClosedAfterEntry = true;
                    }
                }
                if (!isNear(bannerPos)) {
                    moveTo(bannerPos, MOVE_SPEED);
                    return;
                }
                setStage(Stage.FEED_ANIMALS);
            }
            case FEED_ANIMALS -> {
                feedAnimals(serverWorld);
                villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                exitDelayTicks = 40;
                setStage(Stage.OPEN_GATE_EXIT);
            }
            case OPEN_GATE_EXIT -> {
                if (gatePos == null) {
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }
                if (exitDelayTicks > 0) {
                    exitDelayTicks--;
                    return;
                }
                openGate(serverWorld, gatePos, true);
                exitWalkTarget = findGateWalkTarget(gatePos, oppositeDirection(penInsideDirection), 3);
                setStage(Stage.EXIT_PEN);
            }
            case EXIT_PEN -> {
                if (gatePos == null) {
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }
                if (exitWalkTarget == null) {
                    exitWalkTarget = findGateWalkTarget(gatePos, oppositeDirection(penInsideDirection), 3);
                }
                moveTo(exitWalkTarget, MOVE_SPEED);
                if (isNear(exitWalkTarget)) {
                    setStage(Stage.CLOSE_GATE_EXIT);
                }
            }
            case CLOSE_GATE_EXIT -> {
                if (gatePos != null) {
                    openGate(serverWorld, gatePos, false);
                }
                setStage(Stage.RETURN_TO_CHEST);
                moveTo(chestPos);
            }
            case DEPOSIT -> {
                if (!isNear(chestPos)) {
                    setStage(Stage.RETURN_TO_CHEST);
                    return;
                }

                depositInventory(serverWorld);
                boolean hasHoe = hasHoeInInventory() || pickUpHoeFromChest(serverWorld);
                if (hasHoe) {
                    populateHoeTargets(serverWorld);
                } else {
                    hoeTargets.clear();
                }
                if (routePostDepositFlow(serverWorld, false)) {
                    break;
                }
                pendingSeedGatherEndReason = null;
                finishDailyRunIfNeeded(serverWorld);
                setStage(Stage.DONE);
            }
            case HOE_GROUND -> {
                if (hoeTargets.isEmpty()) {
                    currentHoeTarget = null;
                    if (!routePostDepositFlow(serverWorld, true)) {
                        finishDailyRunIfNeeded(serverWorld);
                        setStage(Stage.DONE);
                    }
                    return;
                }
                BlockPos target = hoeTargets.peekFirst();
                if (currentHoeTarget == null || !currentHoeTarget.equals(target)) {
                    currentHoeTarget = target;
                    currentHoeTargetStartTick = serverWorld.getTime();
                }

                if (serverWorld.getTime() - currentHoeTargetStartTick >= TARGET_TIMEOUT_TICKS) {
                    adaptivePathRetryWindow++;
                    hoeTargets.removeFirst();
                    currentHoeTarget = null;
                    return;
                }

                if (!isHoeTarget(serverWorld, target)) {
                    hoeTargets.removeFirst();
                    currentHoeTarget = null;
                    return;
                }

                if (!isNear(target)) {
                    moveTo(target, MOVE_SPEED);
                    return;
                }

                serverWorld.setBlockState(target, Blocks.FARMLAND.getDefaultState());
                hoeTargets.removeFirst();
                currentHoeTarget = null;
            }
            case PLANT_FARMLAND -> {
                if (plantTargets.isEmpty()) {
                    // Recheck coverage — if all farmland is now seeded, clear the obligation
                    FarmlandCoverageStats postPlantCoverage = getFarmlandCoverageStats(serverWorld);
                    if (postPlantCoverage.hasFullCoverage()) {
                        hasUnseededFarmlandObligation = false;
                        obligationLoggedThisCycle = false;
                        LOGGER.debug("Farmer {} farmland obligation satisfied — coverage full ({}/{})",
                                villager.getUuidAsString(), postPlantCoverage.seededCells(), postPlantCoverage.accessibleCells());
                    } else if (postPlantCoverage.accessibleCells() > postPlantCoverage.seededCells()) {
                        // Still unseeded farmland — keep obligation active
                        LOGGER.debug("Farmer {} farmland still partially unseeded ({}/{}) — obligation remains",
                                villager.getUuidAsString(), postPlantCoverage.seededCells(), postPlantCoverage.accessibleCells());
                    }
                    if (dailyHarvestRun) {
                        notifyDailyHarvestComplete(serverWorld);
                        dailyHarvestRun = false;
                    }
                    setStage(Stage.DONE);
                    return;
                }
                BlockPos target = plantTargets.peekFirst();
                BlockPos above = target.up();
                if (!isNear(above)) {
                    moveTo(above, MOVE_SPEED);
                    return;
                }
                if (serverWorld.getBlockState(above).isAir()) {
                    Item seedItem = findFirstPlantableInInventory(prioritizeWheatSeedsForPlanting);
                    if (seedItem == null) {
                        plantTargets.clear();
                        if (dailyHarvestRun) {
                            notifyDailyHarvestComplete(serverWorld);
                            dailyHarvestRun = false;
                        }
                        setStage(Stage.DONE);
                        return;
                    }
                    Block cropBlock = getCropBlockForItem(seedItem);
                    if (cropBlock != null) {
                        BlockState plantedState = cropBlock.getDefaultState();
                        if (plantedState.canPlaceAt(serverWorld, above)) {
                            if (consumeSeed(villager.getInventory(), seedItem)) {
                                serverWorld.setBlockState(above, plantedState);
                                if (prioritizeWheatSeedsForPlanting && seedItem == Items.WHEAT_SEEDS && !hasRequiredWheatSeedReserve(serverWorld)) {
                                    prioritizeWheatSeedsForPlanting = false;
                                }
                            }
                        }
                    }
                }
                plantTargets.removeFirst();
            }
            case GATHER_WHEAT_SEEDS -> {
                if (isSeedForageRetryCoolingDown(serverWorld)) {
                    logSeedForageCooldownBlocked(serverWorld, "tick-stage-check");
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }

                if (!bootstrapSeedGatherRequested && plantTargets.isEmpty()) {
                    populatePlantTargets(serverWorld);
                    if (plantTargets.isEmpty()) {
                        stopSeedForageWithOutcome(serverWorld, "no_remaining_plant_targets", "no plant targets remain");
                        return;
                    }
                }

                if (!wheatSeedForagingRequested && !ensureWheatSeedStartup(serverWorld)) {
                    return;
                }

                if (hasRequiredWheatSeedReserve(serverWorld)) {
                    logWheatSeedThresholdReached(serverWorld, getGatherStageNetSeedGain(serverWorld));
                    clearSeedForageRetryCooldown(serverWorld, "dynamic seed reserve policy reached");
                    prioritizeWheatSeedsForPlanting = true;
                    stopSeedForageWithOutcome(serverWorld, "policy_reached", "dynamic seed reserve policy reached");
                    return;
                }

                if (serverWorld.getTime() - gatherSeedsStageStartTick >= GATHER_SEEDS_TIMEOUT_TICKS) {
                    adaptiveFailedSessionWindow++;
                    stopSeedForageWithOutcome(serverWorld, "timeout", "timed out while gathering wheat seeds");
                    return;
                }

                if (gatherSeedTargets.isEmpty()) {
                    populateGatherSeedTargets(serverWorld);
                    if (gatherSeedTargets.isEmpty()) {
                        gatherNoTargetPasses++;
                        scheduleSeedForageRetryCooldown(serverWorld, "no valid gather targets", gatherNoTargetPasses);
                        if (gatherNoTargetPasses >= GATHER_SEEDS_NO_TARGET_LIMIT) {
                            stopSeedForageWithOutcome(serverWorld, "no_targets", "no valid wheat seed sources found");
                            return;
                        }
                        LOGGER.debug("Farmer {} wheat seed forage paused after chest retry {} of {} with no valid source", villager.getUuidAsString(), gatherNoTargetPasses, GATHER_SEEDS_NO_TARGET_LIMIT);
                        setStage(Stage.RETURN_TO_CHEST);
                        moveTo(chestPos);
                        return;
                    }
                    gatherNoTargetPasses = 0;
                    clearSeedForageRetryCooldown(serverWorld, "new gather targets found");
                }

                BlockPos gatherTarget = gatherSeedTargets.peekFirst();
                if (currentGatherTarget == null || !currentGatherTarget.equals(gatherTarget)) {
                    currentGatherTarget = gatherTarget;
                    currentGatherTargetStartTick = serverWorld.getTime();
                }

                if (serverWorld.getTime() - currentGatherTargetStartTick >= TARGET_TIMEOUT_TICKS) {
                    adaptivePathRetryWindow++;
                    gatherSeedTargets.removeFirst();
                    currentGatherTarget = null;
                    return;
                }

                if (!isValidSeedSource(serverWorld, gatherTarget)) {
                    gatherSeedTargets.removeFirst();
                    currentGatherTarget = null;
                    return;
                }

                if (!isNear(gatherTarget)) {
                    moveTo(gatherTarget, MOVE_SPEED);
                    return;
                }

                boolean brokeBlock = serverWorld.breakBlock(gatherTarget, true, villager);
                if (brokeBlock) {
                    collectNearbyDrops(serverWorld, gatherTarget);
                    if (getGatherStageNetSeedGain(serverWorld) <= 0) {
                        gatherLowYieldBreakPasses++;
                        if (gatherLowYieldBreakPasses >= GATHER_SEEDS_LOW_YIELD_BREAK_LIMIT) {
                            scheduleLowYieldSeedForageCooldown(serverWorld);
                            stopSeedForageWithOutcome(serverWorld, "low_yield_abort", "successful source breaks produced no net wheat seed gain");
                            return;
                        }
                    } else {
                        gatherLowYieldBreakPasses = 0;
                    }
                }
                gatherSeedTargets.removeFirst();
                currentGatherTarget = null;
            }
            case SEED_GATHER_END_RETURN_TO_CHEST -> {
                if (isNear(chestPos)) {
                    setStage(Stage.SEED_GATHER_END_DEPOSIT);
                } else {
                    moveTo(chestPos);
                }
            }
            case SEED_GATHER_END_DEPOSIT -> {
                if (!isNear(chestPos)) {
                    setStage(Stage.SEED_GATHER_END_RETURN_TO_CHEST);
                    return;
                }

                LOGGER.debug("Farmer {} seed_gather_end_deposit_start reason={}", villager.getUuidAsString(), pendingSeedGatherEndReason == null ? "unknown" : pendingSeedGatherEndReason);
                DepositSummary summary = depositPlantablesAndSeeds(serverWorld);
                LOGGER.debug("Farmer {} seed_gather_end_deposit_result moved={} remaining={}", villager.getUuidAsString(), summary.movedCount(), summary.remainingCount());

                populatePlantTargets(serverWorld);
                if (!plantTargets.isEmpty() && (hasPlantablesInInventory() || ensureWheatSeedStartup(serverWorld))) {
                    setStage(Stage.PLANT_FARMLAND);
                    return;
                }

                pendingSeedGatherEndReason = null;
                finishDailyRunIfNeeded(serverWorld);
                setStage(Stage.DONE);
            }
            case IDLE, DONE -> {
            }
        }
    }

    private void populateHarvestTargets() {
        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        harvestTargets.clear();
        int radius = HARVEST_RADIUS;
        int radiusSquared = radius * radius;
        BlockPos start = jobPos.add(-radius, -radius, -radius);
        BlockPos end = jobPos.add(radius, radius, radius);
        ArrayList<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (pos.getSquaredDistance(jobPos) > radiusSquared) {
                continue;
            }
            BlockState state = serverWorld.getBlockState(pos);
            if (isMatureCrop(state)) {
                targets.add(pos.toImmutable());
            }
        }

        targets.sort(Comparator.comparingDouble(pos -> villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)));
        harvestTargets.addAll(targets);
    }

    private int countMatureCrops(ServerWorld world) {
        int count = 0;
        int radius = HARVEST_RADIUS;
        int radiusSquared = radius * radius;
        BlockPos start = jobPos.add(-radius, -radius, -radius);
        BlockPos end = jobPos.add(radius, radius, radius);
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (pos.getSquaredDistance(jobPos) > radiusSquared) {
                continue;
            }
            if (isMatureCrop(world.getBlockState(pos))) {
                count++;
                if (count > 1) {
                    return count;
                }
            }
        }
        return count;
    }

    private int countPlantedCropBlocks(ServerWorld world) {
        int count = 0;
        int radius = HARVEST_RADIUS;
        int radiusSquared = radius * radius;
        BlockPos start = jobPos.add(-radius, -radius, -radius);
        BlockPos end = jobPos.add(radius, radius, radius);
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (pos.getSquaredDistance(jobPos) > radiusSquared) {
                continue;
            }
            if (world.getBlockState(pos).getBlock() instanceof CropBlock) {
                count++;
                if (count > 1) {
                    return count;
                }
            }
        }
        return count;
    }

    private boolean isMatureCrop(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        return false;
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void moveTo(BlockPos target) {
        moveTo(target, MOVE_SPEED);
    }

    private void moveTo(BlockPos target, double speed) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, speed);
    }

    private void notifyDailyHarvestComplete(ServerWorld world) {
        if (craftingGoal != null) {
            craftingGoal.notifyDailyHarvestComplete(world.getTime() / 24000L);
        }
    }

    private void attemptReplant(ServerWorld world, BlockPos pos, BlockState harvestedState) {
        if (!(harvestedState.getBlock() instanceof CropBlock crop)) {
            return;
        }

        if (!world.getBlockState(pos).isAir()) {
            return;
        }

        Item seedItem = getSeedItem(crop);
        if (seedItem == null) {
            return;
        }

        Inventory inventory = villager.getInventory();
        if (!consumeSeed(inventory, seedItem) && !consumeSeedFromChest(world, seedItem)) {
            return;
        }

        BlockState replantedState = crop.getDefaultState();
        if (!replantedState.canPlaceAt(world, pos)) {
            return;
        }

        world.setBlockState(pos, replantedState);
    }

    private void collectNearbyDrops(ServerWorld world, BlockPos pos) {
        Box box = new Box(pos).expand(2.0D);
        for (ItemEntity itemEntity : world.getEntitiesByClass(ItemEntity.class, box, entity -> entity.isAlive() && !entity.getStack().isEmpty())) {
            pickupItemEntity(itemEntity);
        }
    }

    private void pickupItemEntity(ItemEntity itemEntity) {
        ItemStack remaining = insertStack(villager.getInventory(), itemEntity.getStack());
        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setStack(remaining);
        }
    }

    private Item getSeedItem(CropBlock crop) {
        Block block = crop;
        if (block == Blocks.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (block == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (block == Blocks.POTATOES) {
            return Items.POTATO;
        }
        if (block == Blocks.BEETROOTS) {
            return Items.BEETROOT_SEEDS;
        }
        return null;
    }

    private boolean consumeSeed(Inventory inventory, Item seedItem) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || stack.getItem() != seedItem) {
                continue;
            }

            stack.decrement(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            inventory.markDirty();
            return true;
        }
        return false;
    }

    private boolean consumeSeedFromChest(ServerWorld world, Item seedItem) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return false;
        }
        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (chestInventory == null) {
            return false;
        }
        return consumeSeed(chestInventory, seedItem);
    }

    private void depositInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return;
        }

        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (chestInventory == null) {
            return;
        }

        Inventory villagerInventory = villager.getInventory();
        for (int i = 0; i < villagerInventory.size(); i++) {
            ItemStack stack = villagerInventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack remaining = insertStack(chestInventory, stack);
            villagerInventory.setStack(i, remaining);
        }

        villagerInventory.markDirty();
        chestInventory.markDirty();
    }

    private DepositSummary depositPlantablesAndSeeds(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return new DepositSummary(0, countPlantablesAndSeeds(villager.getInventory()));
        }

        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (chestInventory == null) {
            return new DepositSummary(0, countPlantablesAndSeeds(villager.getInventory()));
        }

        int movedCount = 0;
        Inventory villagerInventory = villager.getInventory();
        for (int slot = 0; slot < villagerInventory.size(); slot++) {
            ItemStack stack = villagerInventory.getStack(slot);
            if (stack.isEmpty() || !isPlantableSeedOrCrop(stack.getItem())) {
                continue;
            }

            int before = stack.getCount();
            ItemStack remaining = insertStack(chestInventory, stack);
            villagerInventory.setStack(slot, remaining);
            movedCount += Math.max(0, before - remaining.getCount());
        }

        villagerInventory.markDirty();
        chestInventory.markDirty();
        return new DepositSummary(movedCount, countPlantablesAndSeeds(villagerInventory));
    }

    private boolean isPlantableSeedOrCrop(Item item) {
        return item == Items.WHEAT_SEEDS || item == Items.CARROT || item == Items.POTATO || item == Items.BEETROOT_SEEDS;
    }

    private int countPlantablesAndSeeds(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && isPlantableSeedOrCrop(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private record DepositSummary(int movedCount, int remainingCount) {
    }

    private ItemStack insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }

                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack toInsert = remaining.copy();
                toInsert.setCount(moved);
                inventory.setStack(slot, toInsert);
                remaining.decrement(moved);
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }

            if (!inventory.isValid(slot, remaining)) {
                continue;
            }

            int space = existing.getMaxCount() - existing.getCount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, remaining.getCount());
            existing.increment(moved);
            remaining.decrement(moved);
        }

        return remaining;
    }

    private void setStage(Stage newStage) {
        if (stage == newStage) {
            return;
        }
        Stage previousStage = stage;
        stage = newStage;
        if (newStage != Stage.GATHER_WHEAT_SEEDS) {
            bootstrapSeedGatherRequested = false;
            wheatSeedForagingRequested = false;
            gatherNoTargetPasses = 0;
            gatherLowYieldBreakPasses = 0;
        }
        if (newStage == Stage.GATHER_WHEAT_SEEDS && villager.getWorld() instanceof ServerWorld world) {
            pendingSeedGatherEndReason = null;
            gatherSeedsStageStartTick = world.getTime();
            gatherStageSeedStartCount = getCombinedWheatSeedReserve(world);
            currentGatherTarget = null;
            gatherSeedTargets.clear();
            gatherNoTargetPasses = 0;
            gatherLowYieldBreakPasses = 0;
            logWheatSeedForageStart();
        }
        if (previousStage == Stage.GATHER_WHEAT_SEEDS && newStage == Stage.DONE && villager.getWorld() instanceof ServerWorld world) {
            clearSeedForageRetryCooldown(world, "gather stage completed with DONE");
        }
        LOGGER.debug("Farmer {} entering harvest stage {}", villager.getUuidAsString(), newStage);
    }

    private boolean routePostDepositFlow(ServerWorld world, boolean afterHoeing) {
        populatePlantTargets(world);
        BootstrapPreflight bootstrapPreflight = evaluateBootstrapPreflight(world, afterHoeing);

        if (!hoeTargets.isEmpty()) {
            setStage(Stage.HOE_GROUND);
            return true;
        }

        prioritizeWheatSeedsForPlanting = false;
        pickUpPlantablesFromChest(world);
        if (!plantTargets.isEmpty()) {
            if (hasPlantablesInInventory() && !requiresWheatSeedStartup()) {
                if (hasRequiredWheatSeedReserve(world)) {
                    logSeedReserveStatus(world, "wheat seed reserve policy already satisfied before planting");
                }
                setStage(Stage.PLANT_FARMLAND);
            } else {
                if (ensureWheatSeedStartup(world)) {
                    setStage(Stage.PLANT_FARMLAND);
                } else {
                    wheatSeedForagingRequested = true;
                    logWheatSeedForageIntent("post-deposit planting bootstrap", hasPlantablesForPlanting());
                    if (canEnterSeedForageStage(world, "post-deposit planting bootstrap")) {
                        setStage(Stage.GATHER_WHEAT_SEEDS);
                    } else {
                        return false;
                    }
                }
            }
            return true;
        }

        if (bootstrapPreflight.shouldGatherSeedsAfterChestCheck()) {
            depositPlantablesAndSeeds(world);
            bootstrapSeedGatherRequested = true;
            logBootstrapReason(bootstrapPreflight.reason);
            if (ensureWheatSeedStartup(world)) {
                setStage(Stage.PLANT_FARMLAND);
            } else {
                wheatSeedForagingRequested = true;
                boolean hasPlantables = hasPlantablesForPlanting();
                logWheatSeedForageIntent("post-deposit bootstrap preflight", hasPlantables);
                if (shouldForceBootstrapSeedGather(hasPlantables)) {
                    seedForageRetryCount = 0;
                    nextSeedForageRetryTick = 0L;
                }
                if (canEnterSeedForageStage(world, "post-deposit bootstrap preflight")) {
                    setStage(Stage.GATHER_WHEAT_SEEDS);
                } else {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    private BootstrapPreflight evaluateBootstrapPreflight(ServerWorld world, boolean afterHoeing) {
        int matureCropCount = countMatureCrops(world);
        int plantedCropCount = countPlantedCropBlocks(world);
        boolean canHoeGround = (hasHoeInInventory() || hasHoeInChest(world)) && hasHoeableGroundInRange(world);
        boolean hasPlantTargets = !plantTargets.isEmpty();
        boolean hasSeedsForPlanting = hasPlantablesForPlanting();

        String reason = null;
        if (matureCropCount == 0 && plantedCropCount == 0 && (canHoeGround || hasPlantTargets || !hasSeedsForPlanting)) {
            reason = "no mature or planted crops near job site";
        }
        if (reason == null && afterHoeing && !hasPlantTargets && !hasSeedsForPlanting) {
            reason = "no plant targets after hoeing and no seeds acquired";
        }

        return new BootstrapPreflight(matureCropCount, plantedCropCount, canHoeGround, hasPlantTargets, hasSeedsForPlanting, reason);
    }

    private void finishDailyRunIfNeeded(ServerWorld world) {
        if (dailyHarvestRun) {
            notifyDailyHarvestComplete(world);
            dailyHarvestRun = false;
        }
    }

    private void logBootstrapReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        LOGGER.debug("Farmer {} bootstrap preflight: {}", villager.getUuidAsString(), reason);
    }

    private static final class BootstrapPreflight {
        private final int matureCropCount;
        private final int plantedCropCount;
        private final boolean canHoeGround;
        private final boolean hasPlantTargets;
        private final boolean hasSeedsForPlanting;
        private final String reason;

        private BootstrapPreflight(int matureCropCount, int plantedCropCount, boolean canHoeGround, boolean hasPlantTargets, boolean hasSeedsForPlanting, String reason) {
            this.matureCropCount = matureCropCount;
            this.plantedCropCount = plantedCropCount;
            this.canHoeGround = canHoeGround;
            this.hasPlantTargets = hasPlantTargets;
            this.hasSeedsForPlanting = hasSeedsForPlanting;
            this.reason = reason;
        }

        private boolean shouldRun() {
            return reason != null;
        }

        private boolean shouldGatherSeedsAfterChestCheck() {
            return reason != null && !hasPlantTargets && !hasSeedsForPlanting;
        }
    }

    private enum Stage {
        IDLE,
        GO_TO_JOB,
        HARVEST,
        GO_TO_GATE,
        WALK_TO_BANNER,
        FEED_ANIMALS,
        OPEN_GATE_EXIT,
        EXIT_PEN,
        CLOSE_GATE_EXIT,
        RETURN_TO_CHEST,
        DEPOSIT,
        HOE_GROUND,
        GATHER_WHEAT_SEEDS,
        SEED_GATHER_END_RETURN_TO_CHEST,
        SEED_GATHER_END_DEPOSIT,
        PLANT_FARMLAND,
        DONE
    }

    private boolean prepareFeeding(ServerWorld world) {
        // Use VillagePenRegistry (geometry-based) instead of banner tracker.
        VillagePenRegistry.PenEntry pen = VillagePenRegistry.get(world.getServer())
                .getNearestPen(world, jobPos, 300)
                .orElse(null);
        if (pen == null) {
            return false;
        }
        if (villager.getInventory().isEmpty()) {
            return false;
        }
        bannerPos = pen.center();
        gatePos = pen.gate();
        if (gatePos == null) {
            return false;
        }
        penInsideDirection = findInsideDirection(world, gatePos, bannerPos);
        gateWalkTarget = findGateWalkTarget(gatePos, penInsideDirection, 3);
        exitWalkTarget = null;
        feedTargetCount = determineFeedTargetCount();
        exitDelayTicks = 0;
        gateClosedAfterEntry = false;
        gateCloseDistanceSquared = 0.0D;
        return !getAnimalsNearBanner(world).isEmpty();
    }

    private void feedAnimals(ServerWorld world) {
        if (bannerPos == null) {
            return;
        }

        List<AnimalEntity> animals = getAnimalsNearBanner(world);
        int fedCount = 0;
        for (AnimalEntity animal : animals) {
            if (fedCount >= feedTargetCount) {
                break;
            }
            if (!canFeedAnimal(animal)) {
                LOGGER.debug("Farmer {} attempted to feed {} at {}, but it was not ready to breed", villager.getUuidAsString(), animal.getType().getName().getString(), animal.getBlockPos().toShortString());
                continue;
            }

            ItemStack feedStack = findBreedingStack(villager.getInventory(), animal);
            if (feedStack == null) {
                LOGGER.debug("Farmer {} attempted to feed {} at {}, but had no valid food in inventory", villager.getUuidAsString(), animal.getType().getName().getString(), animal.getBlockPos().toShortString());
                continue;
            }

            ItemStack fedItem = feedStack.copy();
            if (!consumeFeedItems(villager.getInventory(), feedStack.getItem())) {
                LOGGER.debug("Farmer {} attempted to feed {} at {}, but inventory had insufficient food", villager.getUuidAsString(), animal.getType().getName().getString(), animal.getBlockPos().toShortString());
                continue;
            }

            applyBreedingState(animal);
            LOGGER.debug("Farmer {} fed {} x2 at {}", villager.getUuidAsString(), fedItem.getName().getString(), animal.getBlockPos().toShortString());
            if (animal.isInLove()) {
                LOGGER.debug("Animal {} entered breeding state at {}", animal.getType().getName().getString(), animal.getBlockPos().toShortString());
            }
            fedCount++;
        }

        if (fedCount == 0) {
            LOGGER.debug("Farmer {} attempted to feed, but no animals were available to breed at {}", villager.getUuidAsString(), bannerPos.toShortString());
        }
    }

    private List<AnimalEntity> getAnimalsNearBanner(ServerWorld world) {
        Box box = new Box(bannerPos).expand(6.0D);
        return world.getEntitiesByClass(AnimalEntity.class, box, animal -> animal.isAlive() && !animal.isBaby());
    }

    private ItemStack findBreedingStack(Inventory inventory, AnimalEntity animal) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getCount() < 2) {
                continue;
            }
            if (animal.isBreedingItem(stack)) {
                return stack;
            }
        }
        return null;
    }

    private boolean canFeedAnimal(AnimalEntity animal) {
        return !animal.isInLove() && animal.getBreedingAge() == 0;
    }

    private int determineFeedTargetCount() {
        int availablePairs = countAvailableBreedingPairs();
        if (availablePairs >= 6) {
            return 6;
        }
        if (availablePairs >= 4) {
            return 4;
        }
        if (availablePairs >= 2) {
            return 2;
        }
        return 0;
    }

    private int countAvailableBreedingPairs() {
        int total = 0;
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            ItemStack stack = villager.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            if (item == Items.WHEAT || item == Items.CARROT || item == Items.POTATO || item == Items.BEETROOT) {
                total += stack.getCount();
            }
        }
        return total / 2;
    }

    private boolean consumeFeedItems(Inventory inventory, Item item) {
        int remaining = 2;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            if (remaining <= 0) {
                inventory.markDirty();
                return true;
            }
        }
        return false;
    }

    private void applyBreedingState(AnimalEntity animal) {
        animal.setLoveTicks(600);
    }

    private void populateHoeTargets(ServerWorld world) {
        hoeTargets.clear();
        ensureEligibleTerritoryCache(world, false);

        List<BlockPos> hoeableTargets = new ArrayList<>();
        for (BlockPos pos : eligibleTerritory) {
            if (!isHoeTarget(world, pos)) {
                continue;
            }
            if (!hasNearbyWater(world, pos)) {
                continue;
            }
            hoeableTargets.add(pos.toImmutable());
        }

        hoeableTargets.sort(Comparator.comparingDouble(this::distanceToVillagerSquared));
        hoeTargets.addAll(hoeableTargets);
    }

    private void populatePlantTargets(ServerWorld world) {
        plantTargets.clear();
        ensureEligibleTerritoryCache(world, false);
        for (BlockPos pos : eligibleTerritory) {
            if (!world.getBlockState(pos).isOf(Blocks.FARMLAND)) {
                continue;
            }
            if (!world.getBlockState(pos.up()).isAir()) {
                continue;
            }
            plantTargets.add(pos.toImmutable());
        }
    }

    private Block getCropBlockForItem(Item item) {
        if (item == Items.WHEAT_SEEDS) {
            return Blocks.WHEAT;
        }
        if (item == Items.CARROT) {
            return Blocks.CARROTS;
        }
        if (item == Items.POTATO) {
            return Blocks.POTATOES;
        }
        if (item == Items.BEETROOT_SEEDS) {
            return Blocks.BEETROOTS;
        }
        return null;
    }


    private boolean pickUpHoeFromChest(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return false;
        }
        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (chestInventory == null) {
            return false;
        }

        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof HoeItem)) {
                continue;
            }
            ItemStack remaining = insertStack(villager.getInventory(), stack);
            chestInventory.setStack(slot, remaining);
            chestInventory.markDirty();
            villager.getInventory().markDirty();
            return true;
        }
        return false;
    }

    private boolean hasHoeInInventory() {
        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHoeInChest(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return false;
        }
        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (chestInventory == null) {
            return false;
        }

        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHoeableGroundInRange(ServerWorld world) {
        ensureEligibleTerritoryCache(world, false);
        for (BlockPos pos : eligibleTerritory) {
            if (isHoeTarget(world, pos) && hasNearbyWater(world, pos)) {
                return true;
            }
        }
        return false;
    }

    private void pickUpPlantablesFromChest(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return;
        }
        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (chestInventory == null) {
            return;
        }
        Item[] candidates = new Item[] {
                Items.WHEAT_SEEDS,
                Items.CARROT,
                Items.POTATO,
                Items.BEETROOT_SEEDS
        };
        for (Item candidate : candidates) {
            for (int slot = 0; slot < chestInventory.size(); slot++) {
                ItemStack stack = chestInventory.getStack(slot);
                if (stack.isEmpty() || stack.getItem() != candidate) {
                    continue;
                }
                ItemStack remaining = insertStack(villager.getInventory(), stack);
                chestInventory.setStack(slot, remaining);
                if (remaining.isEmpty()) {
                    break;
                }
            }
        }
        chestInventory.markDirty();
        villager.getInventory().markDirty();
    }

    /**
     * Pulls at most {@link #SEED_INCREMENT_BATCH} plantable seeds from the paired chest into
     * the villager's inventory. Used in no-seeds/no-hoe idle mode to accumulate resources
     * incrementally without triggering a full farmland scan.
     */
    private void tryIncrementalSeedPickupFromChest(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return;
        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (chestInventory == null) return;

        int pulled = 0;
        Item[] candidates = {Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS};
        outer:
        for (Item candidate : candidates) {
            for (int slot = 0; slot < chestInventory.size(); slot++) {
                if (pulled >= SEED_INCREMENT_BATCH) break outer;
                ItemStack stack = chestInventory.getStack(slot);
                if (stack.isEmpty() || stack.getItem() != candidate) continue;
                int take = Math.min(SEED_INCREMENT_BATCH - pulled, stack.getCount());
                ItemStack toInsert = new ItemStack(candidate, take);
                ItemStack remaining = insertStack(villager.getInventory(), toInsert);
                int moved = take - remaining.getCount();
                if (moved > 0) {
                    stack.decrement(moved);
                    if (stack.isEmpty()) chestInventory.setStack(slot, ItemStack.EMPTY);
                    pulled += moved;
                }
            }
        }
        if (pulled > 0) {
            chestInventory.markDirty();
            villager.getInventory().markDirty();
            LOGGER.debug("Farmer {} incremental seed pickup: {} item(s) from chest", villager.getUuidAsString(), pulled);
        }
    }

    private Item findFirstPlantableInInventory(boolean prioritizeWheatSeeds) {
        Item[] candidates = prioritizeWheatSeeds
                ? new Item[] {Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS}
                : new Item[] {Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS, Items.WHEAT_SEEDS};
        for (Item candidate : candidates) {
            for (int slot = 0; slot < villager.getInventory().size(); slot++) {
                ItemStack stack = villager.getInventory().getStack(slot);
                if (!stack.isEmpty() && stack.getItem() == candidate) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean hasPlantablesInInventory() {
        return findFirstPlantableInInventory(false) != null;
    }

    private int getConfiguredWheatSeedBootstrapFloor() {
        return Math.max(0, GuardVillagersConfig.farmerWheatSeedBootstrapFloor >= 0 ? GuardVillagersConfig.farmerWheatSeedBootstrapFloor : DEFAULT_WHEAT_SEED_BOOTSTRAP_FLOOR);
    }

    private int countWheatSeedsInVillagerInventory() {
        return countItemInInventory(Items.WHEAT_SEEDS);
    }

    private int countWheatSeedsInChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return 0;
        }
        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (chestInventory == null) {
            return 0;
        }

        int total = 0;
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (!stack.isEmpty() && stack.getItem() == Items.WHEAT_SEEDS) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int getCombinedWheatSeedReserve(ServerWorld world) {
        return countWheatSeedsInVillagerInventory() + countWheatSeedsInChestInventory(world);
    }

    private FarmlandCoverageStats getFarmlandCoverageStats(ServerWorld world) {
        long now = world.getTime();
        if (cachedCoverage != null && (now - coverageCacheTime) < COVERAGE_CACHE_TTL) {
            return cachedCoverage;
        }
        ensureEligibleTerritoryCache(world, false);
        int accessible = 0;
        int seeded = 0;
        for (BlockPos pos : eligibleTerritory) {
            boolean hoeable = isHoeTarget(world, pos) && hasNearbyWater(world, pos);
            boolean plantableFarmland = world.getBlockState(pos).isOf(Blocks.FARMLAND)
                    && (world.getBlockState(pos.up()).isAir() || world.getBlockState(pos.up()).getBlock() instanceof CropBlock);
            if (!hoeable && !plantableFarmland) {
                continue;
            }
            accessible++;
            if (world.getBlockState(pos.up()).getBlock() instanceof CropBlock) {
                seeded++;
            }
        }
        cachedCoverage = new FarmlandCoverageStats(accessible, seeded);
        coverageCacheTime = now;
        return cachedCoverage;
    }

    private boolean hasRequiredWheatSeedReserve(ServerWorld world) {
        int targetSeedReserve = computeWheatSeedTarget(world);
        if (targetSeedReserve <= 0) {
            return true;
        }
        int combinedReserve = getCombinedWheatSeedReserve(world);
        return combinedReserve >= targetSeedReserve;
    }

    private boolean requiresWheatSeedStartup() {
        return !hasPlantablesForPlanting();
    }

    private boolean ensureWheatSeedStartup(ServerWorld world) {
        if (hasRequiredWheatSeedReserve(world)) {
            logWheatSeedStartupDecision(world, true, true, hasPlantablesForPlanting(), hasNonWheatPlantablesForPlanting(), getCombinedWheatSeedReserve(world), getConfiguredWheatSeedBootstrapFloor(), "dynamic reserve satisfied");
            return true;
        }

        logSeedReserveStatus(world, "detected insufficient dynamic wheat seed reserve before startup");
        pickUpPlantablesFromChest(world);
        logSeedReserveStatus(world, "post-chest pickup reserve check");

        if (hasRequiredWheatSeedReserve(world)) {
            logWheatSeedStartupDecision(world, true, true, hasPlantablesForPlanting(), hasNonWheatPlantablesForPlanting(), getCombinedWheatSeedReserve(world), getConfiguredWheatSeedBootstrapFloor(), "dynamic reserve satisfied after chest pickup");
            return true;
        }

        boolean hasPlantables = hasPlantablesForPlanting();
        boolean hasNonWheatPlantables = hasNonWheatPlantablesForPlanting();
        int combinedReserve = getCombinedWheatSeedReserve(world);
        int bootstrapFloor = getConfiguredWheatSeedBootstrapFloor();
        boolean startupReady = hasStartupPlantingReadiness(hasPlantables, hasNonWheatPlantables, combinedReserve, bootstrapFloor);
        logWheatSeedStartupDecision(world, false, startupReady, hasPlantables, hasNonWheatPlantables, combinedReserve, bootstrapFloor, "startup fallback evaluated");
        return startupReady;
    }

    static boolean hasStartupPlantingReadiness(boolean hasPlantablesForPlanting, boolean hasNonWheatPlantablesForPlanting, int combinedSeedReserve, int configuredBootstrapFloor) {
        if (!hasPlantablesForPlanting) {
            return false;
        }
        if (hasNonWheatPlantablesForPlanting) {
            return true;
        }
        int effectiveBootstrapFloor = Math.max(1, configuredBootstrapFloor);
        return combinedSeedReserve >= effectiveBootstrapFloor;
    }

    static boolean shouldForceBootstrapSeedGather(boolean hasPlantablesForPlanting) {
        return !hasPlantablesForPlanting;
    }

    private boolean shouldThrottleFarmlandExpansionChecks(ServerWorld world) {
        long adaptiveLoadScore = adaptiveScanVolumeWindow
                + (long) adaptivePathRetryWindow * 18L
                + (long) adaptiveFailedSessionWindow * 40L
                + (long) adaptiveForcedRecoveryWindow * 55L;
        if (adaptiveLoadScore < GuardVillagersConfig.farmerAdaptiveThrottleLoadThreshold) {
            return false;
        }
        int jitter = GuardVillagersConfig.farmerAdaptiveThrottleJitterTicks <= 0
                ? 0
                : villager.getRandom().nextInt(GuardVillagersConfig.farmerAdaptiveThrottleJitterTicks + 1);
        int deferTicks = GuardVillagersConfig.farmerAdaptiveThrottleDeferTicks + jitter;
        adaptiveThrottleUntilTick = world.getTime() + deferTicks;
        nextCheckTime = Math.max(nextCheckTime, adaptiveThrottleUntilTick);
        adaptiveScanVolumeWindow = Math.max(0L, adaptiveScanVolumeWindow / 2L);
        adaptivePathRetryWindow = Math.max(0, adaptivePathRetryWindow / 2);
        adaptiveFailedSessionWindow = Math.max(0, adaptiveFailedSessionWindow / 2);
        adaptiveForcedRecoveryWindow = Math.max(0, adaptiveForcedRecoveryWindow / 2);
        return true;
    }

    private void maybeLogAdaptiveSummary() {
        int interval = Math.max(1, GuardVillagersConfig.farmerAdaptiveSummaryLogIntervalSessions);
        if (adaptiveSessionCount % interval != 0) {
            return;
        }
        LOGGER.info("Farmer adaptive summary villager={} sessions={} scanVolume={} pathRetries={} failedSessions={} forcedRecoveries={} throttleUntilTick={}",
                villager.getUuidAsString(),
                adaptiveSessionCount,
                adaptiveScanVolumeWindow,
                adaptivePathRetryWindow,
                adaptiveFailedSessionWindow,
                adaptiveForcedRecoveryWindow,
                adaptiveThrottleUntilTick);
    }

    private void logWheatSeedStartupDecision(ServerWorld world,
                                             boolean dynamicReserveSatisfied,
                                             boolean startupReady,
                                             boolean hasPlantables,
                                             boolean hasNonWheatPlantables,
                                             int combinedReserve,
                                             int configuredBootstrapFloor,
                                             String reason) {
        LOGGER.debug("Farmer {} wheat seed startup decision dynamicReserveSatisfied={} startupReady={} hasPlantables={} hasNonWheatPlantables={} combinedSeeds={} bootstrapFloor={} effectiveBootstrapFloor={} reason={}",
                villager.getUuidAsString(), dynamicReserveSatisfied, startupReady, hasPlantables, hasNonWheatPlantables, combinedReserve,
                configuredBootstrapFloor, Math.max(1, configuredBootstrapFloor), reason);
    }

    private void logWheatSeedForageIntent(String context, boolean optimizingSeedReserve) {
        if (villager.getWorld() instanceof ServerWorld world) {
            logSeedReserveStatus(world, "foraging wheat seeds (context: " + context + ", optimizing=" + optimizingSeedReserve + ")");
            return;
        }
        LOGGER.debug("Farmer {} foraging wheat seeds (context: {}, optimizing={})", villager.getUuidAsString(), context, optimizingSeedReserve);
    }

    private void logWheatSeedForageStart() {
        LOGGER.debug("Farmer {} starting wheat seed forage near {}", villager.getUuidAsString(), jobPos.toShortString());
    }

    private void logWheatSeedForageStop(String reason) {
        LOGGER.debug("Farmer {} stopping wheat seed forage: {}", villager.getUuidAsString(), reason);
    }

    private void logWheatSeedThresholdReached(ServerWorld world, int netSeedGain) {
        FarmlandCoverageStats stats = getFarmlandCoverageStats(world);
        LOGGER.debug("Farmer {} reached dynamic seed reserve policy villagerSeeds={} chestSeeds={} combined={} target={} bootstrapFloor={} coverage={}/{} ({}) netGain={}",
                villager.getUuidAsString(), countWheatSeedsInVillagerInventory(), countWheatSeedsInChestInventory(world), getCombinedWheatSeedReserve(world),
                computeWheatSeedTarget(world), getConfiguredWheatSeedBootstrapFloor(), stats.seededCells(), stats.accessibleCells(), stats.coverageLabel(), netSeedGain);
    }

    private int getGatherStageNetSeedGain(ServerWorld world) {
        return getCombinedWheatSeedReserve(world) - gatherStageSeedStartCount;
    }

    private void stopSeedForageWithOutcome(ServerWorld world, String reasonCode, String detail) {
        int currentSeedCount = getCombinedWheatSeedReserve(world);
        int netSeedGain = getGatherStageNetSeedGain(world);
        LOGGER.debug("Farmer {} wheat seed forage outcome={} detail={} startCombinedSeeds={} currentCombinedSeeds={} netGain={} noTargetPasses={} lowYieldBreakPasses={}",
                villager.getUuidAsString(), reasonCode, detail, gatherStageSeedStartCount, currentSeedCount, netSeedGain, gatherNoTargetPasses, gatherLowYieldBreakPasses);
        LOGGER.debug("Farmer {} seed_gather_end_reason={}", villager.getUuidAsString(), reasonCode);
        pendingSeedGatherEndReason = reasonCode;
        clearSeedForageRetryCooldown(world, "gather stage completed via deposit routing");
        logWheatSeedForageStop(detail);
        setStage(Stage.SEED_GATHER_END_RETURN_TO_CHEST);
        moveTo(chestPos);
    }

    private boolean canEnterSeedForageStage(ServerWorld world, String context) {
        if (!isSeedForageRetryCoolingDown(world)) {
            return true;
        }
        logSeedForageCooldownBlocked(world, context);
        return false;
    }

    private boolean isSeedForageRetryCoolingDown(ServerWorld world) {
        return world.getTime() < nextSeedForageRetryTick;
    }

    private void scheduleSeedForageRetryCooldown(ServerWorld world, String reason, int retryCount) {
        int exponent = Math.max(0, Math.min(retryCount - 1, 3));
        int cooldownTicks = Math.min(SEED_FORAGE_RETRY_MAX_COOLDOWN_TICKS, SEED_FORAGE_RETRY_BASE_COOLDOWN_TICKS << exponent);
        seedForageRetryCount = Math.max(seedForageRetryCount, retryCount);
        nextSeedForageRetryTick = world.getTime() + cooldownTicks;
        LOGGER.debug("Farmer {} wheat seed forage cooldown scheduled (reason: {}, retry: {}, cooldownTicks: {}, resumesIn: {})",
                villager.getUuidAsString(), reason, seedForageRetryCount, cooldownTicks, cooldownTicks);
    }

    private void scheduleLowYieldSeedForageCooldown(ServerWorld world) {
        nextSeedForageRetryTick = Math.max(nextSeedForageRetryTick, world.getTime() + SEED_FORAGE_LOW_YIELD_COOLDOWN_TICKS);
        seedForageRetryCount = Math.max(seedForageRetryCount, 1);
        LOGGER.debug("Farmer {} wheat seed forage cooldown scheduled (reason: low_yield_outcome, cooldownTicks: {})",
                villager.getUuidAsString(), SEED_FORAGE_LOW_YIELD_COOLDOWN_TICKS);
    }

    private void clearSeedForageRetryCooldown(ServerWorld world, String reason) {
        if (nextSeedForageRetryTick <= world.getTime() && seedForageRetryCount == 0) {
            return;
        }
        long remaining = Math.max(0L, nextSeedForageRetryTick - world.getTime());
        LOGGER.debug("Farmer {} wheat seed forage cooldown cleared (reason: {}, remainingTicks: {}, retries: {})",
                villager.getUuidAsString(), reason, remaining, seedForageRetryCount);
        nextSeedForageRetryTick = 0L;
        seedForageRetryCount = 0;
    }

    private void logSeedForageCooldownBlocked(ServerWorld world, String context) {
        long remaining = Math.max(0L, nextSeedForageRetryTick - world.getTime());
        LOGGER.debug("Farmer {} wheat seed forage re-entry blocked by cooldown (context: {}, remainingTicks: {}, retries: {})",
                villager.getUuidAsString(), context, remaining, seedForageRetryCount);
    }


    private void logSeedReserveStatus(ServerWorld world, String context) {
        FarmlandCoverageStats stats = getFarmlandCoverageStats(world);
        LOGGER.debug("Farmer {} {} villagerSeeds={} chestSeeds={} combined={} target={} bootstrapFloor={} coverage={}/{} ({})",
                villager.getUuidAsString(), context, countWheatSeedsInVillagerInventory(), countWheatSeedsInChestInventory(world), getCombinedWheatSeedReserve(world),
                computeWheatSeedTarget(world), getConfiguredWheatSeedBootstrapFloor(), stats.seededCells(), stats.accessibleCells(), stats.coverageLabel());
    }

    static int computeSeedTargetFromEligibleArea(int eligibleAreaPlots) {
        if (eligibleAreaPlots <= 0) {
            return 0;
        }
        int reserveMargin = Math.max(SEED_TARGET_RESERVE_MARGIN_MIN, (eligibleAreaPlots + (SEED_TARGET_RESERVE_MARGIN_DIVISOR - 1)) / SEED_TARGET_RESERVE_MARGIN_DIVISOR);
        return eligibleAreaPlots + reserveMargin;
    }

    private int computeWheatSeedTarget(ServerWorld world) {
        int eligibleArea = getEligibleTerritoryCount(world);
        int areaBasedTarget = computeSeedTargetFromEligibleArea(eligibleArea);
        return Math.max(areaBasedTarget, getConfiguredWheatSeedBootstrapFloor());
    }

    private record FarmlandCoverageStats(int accessibleCells, int seededCells) {
        private boolean hasFullCoverage() {
            return accessibleCells <= 0 || seededCells >= accessibleCells;
        }

        private String coverageLabel() {
            if (accessibleCells <= 0) {
                return "100.0%";
            }
            return String.format(java.util.Locale.ROOT, "%.1f%%", (seededCells * 100.0D) / accessibleCells);
        }
    }

    private boolean hasPlantablesForPlanting() {
        return countItemInInventory(Items.CARROT) > 0
                || countItemInInventory(Items.POTATO) > 0
                || countItemInInventory(Items.BEETROOT_SEEDS) > 0
                || countItemInInventory(Items.WHEAT_SEEDS) > 0;
    }

    private boolean hasNonWheatPlantablesForPlanting() {
        return countItemInInventory(Items.CARROT) > 0
                || countItemInInventory(Items.POTATO) > 0
                || countItemInInventory(Items.BEETROOT_SEEDS) > 0;
    }

    private int countItemInInventory(Item item) {
        int total = 0;
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            ItemStack stack = villager.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void populateGatherSeedTargets(ServerWorld world) {
        gatherSeedTargets.clear();
        List<BlockPos> candidates = new ArrayList<>();
        int radius = HARVEST_RADIUS;
        int radiusSquared = radius * radius;
        BlockPos start = jobPos.add(-radius, -radius, -radius);
        BlockPos end = jobPos.add(radius, radius, radius);
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (pos.getSquaredDistance(jobPos) > radiusSquared) {
                continue;
            }
            if (!isValidSeedSource(world, pos)) {
                continue;
            }
            candidates.add(pos.toImmutable());
        }
        candidates.sort(Comparator.comparingDouble(this::distanceToVillagerSquared));
        gatherSeedTargets.addAll(candidates);
    }

    private boolean isValidSeedSource(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(Blocks.SHORT_GRASS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN);
    }

    private boolean hasNearbyWater(ServerWorld world, BlockPos farmlandCandidate) {
        BlockPos start = farmlandCandidate.add(-WATER_HYDRATION_RADIUS, -WATER_SEARCH_VERTICAL_RANGE, -WATER_HYDRATION_RADIUS);
        BlockPos end = farmlandCandidate.add(WATER_HYDRATION_RADIUS, WATER_SEARCH_VERTICAL_RANGE, WATER_HYDRATION_RADIUS);
        int hydrationRadiusSquared = WATER_HYDRATION_RADIUS * WATER_HYDRATION_RADIUS;

        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (horizontalDistanceSquared(pos, farmlandCandidate) > hydrationRadiusSquared) {
                continue;
            }
            if (world.getBlockState(pos).isOf(Blocks.WATER)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithinHarvestRange(BlockPos pos) {
        return pos.getSquaredDistance(jobPos) <= HARVEST_RADIUS * HARVEST_RADIUS;
    }

    private double distanceToVillagerSquared(BlockPos pos) {
        return villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private double horizontalDistanceSquared(BlockPos first, BlockPos second) {
        int dx = first.getX() - second.getX();
        int dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private boolean isHoeTarget(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.DIRT_PATH))) {
            return false;
        }
        return world.getBlockState(pos.up()).isAir();
    }

    private void ensureEligibleTerritoryCache(ServerWorld world, boolean forceScan) {
        maybeInvalidateEligibleTerritory(world);
        if (!forceScan && world.getTime() < nextBootstrapScanTick) {
            return;
        }
        if (!forceScan && !eligibleTerritory.isEmpty() && bootstrapScanCursor == 0) {
            return;
        }
        runBootstrapTerritoryScan(world);
    }

    private void maybeInvalidateEligibleTerritory(ServerWorld world) {
        if (eligibleTerritory.isEmpty()) {
            return;
        }
        long now = world.getTime();
        if (now - lastTerritoryInvalidationTick < BOOTSTRAP_INVALIDATION_CHECK_INTERVAL_TICKS) {
            return;
        }
        lastTerritoryInvalidationTick = now;
        int sampled = 0;
        int invalid = 0;
        for (BlockPos pos : eligibleTerritory) {
            if (sampled >= BOOTSTRAP_INVALIDATION_SAMPLE_SIZE) {
                break;
            }
            sampled++;
            if (!isEligibleTerritoryCell(world, pos)) {
                invalid++;
            }
        }
        if (sampled == 0) {
            return;
        }
        int invalidPercent = (invalid * 100) / sampled;
        if (invalidPercent >= BOOTSTRAP_INVALIDATION_PERCENT) {
            eligibleTerritory.clear();
            bootstrapScanCursor = 0;
            nextBootstrapScanTick = now + BOOTSTRAP_RESCAN_COOLDOWN_TICKS;
            cachedCoverage = null;
            coverageCacheTime = -1L;
            LOGGER.debug("Farmer {} territory cache invalidated (invalidPercent={} sampled={})",
                    villager.getUuidAsString(), invalidPercent, sampled);
        }
    }

    private void runBootstrapTerritoryScan(ServerWorld world) {
        int diameter = BOOTSTRAP_SCAN_RADIUS * 2 + 1;
        int layers = BOOTSTRAP_SCAN_MAX_Y_OFFSET - BOOTSTRAP_SCAN_MIN_Y_OFFSET + 1;
        int total = diameter * diameter * layers;
        int scanned = 0;
        while (scanned < BOOTSTRAP_SCAN_BLOCK_BUDGET && bootstrapScanCursor < total) {
            int index = bootstrapScanCursor++;
            int layerSize = diameter * diameter;
            int yIndex = index / layerSize;
            int layerOffset = index % layerSize;
            int zIndex = layerOffset / diameter;
            int xIndex = layerOffset % diameter;

            int x = jobPos.getX() - BOOTSTRAP_SCAN_RADIUS + xIndex;
            int y = jobPos.getY() + BOOTSTRAP_SCAN_MIN_Y_OFFSET + yIndex;
            int z = jobPos.getZ() - BOOTSTRAP_SCAN_RADIUS + zIndex;
            BlockPos pos = new BlockPos(x, y, z);

            if (horizontalDistanceSquared(pos, jobPos) <= BOOTSTRAP_SCAN_RADIUS * BOOTSTRAP_SCAN_RADIUS) {
                if (isEligibleTerritoryCell(world, pos)) {
                    eligibleTerritory.add(pos.toImmutable());
                } else {
                    eligibleTerritory.remove(pos);
                }
            }
            scanned++;
        }
        adaptiveScanVolumeWindow += scanned;
        cachedCoverage = null;
        coverageCacheTime = -1L;
        if (bootstrapScanCursor >= total) {
            bootstrapScanCursor = 0;
        }
        nextBootstrapScanTick = world.getTime() + BOOTSTRAP_SCAN_INTERVAL_TICKS;
    }

    private int getEligibleTerritoryCount(ServerWorld world) {
        ensureEligibleTerritoryCache(world, false);
        return eligibleTerritory.size();
    }

    private boolean isEligibleTerritoryCell(ServerWorld world, BlockPos pos) {
        if (world.getBlockState(pos).isOf(Blocks.FARMLAND)) {
            BlockState above = world.getBlockState(pos.up());
            return above.isAir() || above.getBlock() instanceof CropBlock;
        }
        return isHoeTarget(world, pos) && hasNearbyWater(world, pos);
    }


    private BlockPos findGateWalkTarget(BlockPos gatePos, Direction direction, int distance) {
        if (direction == null) {
            return gatePos;
        }
        return gatePos.offset(direction, distance);
    }

    private void openGate(ServerWorld world, BlockPos pos, boolean open) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return;
        }
        if (state.get(FenceGateBlock.OPEN) == open) {
            return;
        }
        world.setBlockState(pos, state.with(FenceGateBlock.OPEN, open), 2);
    }

    private Direction findInsideDirection(ServerWorld world, BlockPos gatePos, BlockPos bannerPos) {
        BlockState state = world.getBlockState(gatePos);
        if (state.contains(FenceGateBlock.FACING)) {
            Direction facing = state.get(FenceGateBlock.FACING);
            BlockPos frontPos = gatePos.offset(facing);
            BlockPos backPos = gatePos.offset(facing.getOpposite());
            double frontDistance = squaredDistance(frontPos, bannerPos);
            double backDistance = squaredDistance(backPos, bannerPos);
            return frontDistance <= backDistance ? facing : facing.getOpposite();
        }
        return bannerPos.getX() >= gatePos.getX() ? Direction.EAST : Direction.WEST;
    }

    private Direction oppositeDirection(Direction direction) {
        return direction == null ? null : direction.getOpposite();
    }

    private double squaredDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() + 0.5D - (b.getX() + 0.5D);
        double dy = a.getY() + 0.5D - (b.getY() + 0.5D);
        double dz = a.getZ() + 0.5D - (b.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private double calculateGateCloseDistanceSquared(BlockPos gatePos, BlockPos bannerPos) {
        if (gatePos == null || bannerPos == null) {
            return 0.0D;
        }
        double gateToBannerDistance = Math.sqrt(squaredDistance(gatePos, bannerPos));
        double closeDistance = Math.min(2.0D, gateToBannerDistance);
        return closeDistance * closeDistance;
    }

    private BlockPos findNearestGate(ServerWorld world, BlockPos center) {
        int gateRange = 6;
        BlockPos start = center.add(-gateRange, -gateRange, -gateRange);
        BlockPos end = center.add(gateRange, gateRange, gateRange);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof FenceGateBlock)) {
                continue;
            }
            double distance = villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = pos.toImmutable();
            }
        }
        return nearest;
    }

}
