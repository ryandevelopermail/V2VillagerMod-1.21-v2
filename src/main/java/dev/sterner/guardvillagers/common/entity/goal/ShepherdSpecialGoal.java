package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillagePenRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShepherdSpecialGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShepherdSpecialGoal.class);
    private static final int SHEAR_CHECK_INTERVAL_MIN_TICKS = 6000;
    private static final int SHEAR_CHECK_INTERVAL_MAX_TICKS = 12000;
    private static final int SHEEP_SENSOR_INTERVAL_TICKS = 80;
    private static final double MOVE_SPEED = 0.6D;
    private static final int PATH_RETRY_INTERVAL_TICKS = 20;
    private static final double SLOW_GUIDE_SPEED = 0.45D;
    private static final double FAST_GATE_CLOSE_SPEED = 0.9D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int SHEEP_SCAN_RANGE = 50;
    private static final int PEN_SCAN_RANGE = 100;
    private static final int PEN_FENCE_RANGE = 64;
    private static final int PEN_SEARCH_Y_RANGE = 24;
    private static final int PEN_BANNER_TO_GATE_SCAN_RADIUS = 24;
    private static final int PEN_BANNER_CANDIDATE_LIMIT = 32;
    private static final int PEN_GATE_CHECK_LIMIT = 40;
    private static final double GATE_INTERACT_RANGE_SQUARED = 9.0D;
    private static final int SHEAR_STAGE_STUCK_TIMEOUT_TICKS = 240;
    private static final int GATHER_RADIUS = 50;
    private static final int GATHER_MIN_SESSION_TICKS = 1200;
    private static final int GATHER_MAX_SESSION_TICKS = 2200;
    private static final int GATHER_WANDER_REPATH_TICKS = 120;
    private static final int ACTIVE_HERD_LIMIT = 6;
    private static final double HERD_SELECTION_RANGE = 24.0D;
    private static final double HERD_FOLLOW_DISTANCE_SQUARED = 81.0D;
    private static final double HERD_PEN_CLOSE_DISTANCE_SQUARED = 25.0D;
    private static final int GATHER_FOLLOW_CHECK_INTERVAL_TICKS = 40;
    private static final int GATHER_CIRCLE_RADIUS = 20;
    private static final int GATHER_CIRCLE_WAYPOINT_COUNT = 16;
    private static final int GATHER_BANNER_HOLD_TICKS = 1200;
    private static final double GATHER_GATE_ALIGNMENT_DOT_THRESHOLD = 0.96D;
    private static final long SPATIAL_SEARCH_CACHE_TTL_TICKS = 40L;
    private static final long SHEARS_CHEST_RETURN_TIMEOUT_TICKS = 600L;
    private static final long SHEARS_EXIT_PEN_RETRY_TICKS = 200L;
    private static final long GATHER_EXIT_PEN_RETRY_TICKS = 200L;
    private static final int BACKUP_SHEAR_MIN_TARGETS = 3;
    private static final int BACKUP_SHEAR_MAX_TARGETS = 6;

    private final VillagerEntity villager;
    private BlockPos currentNavigationTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long nextSheepSensorCheckTime;
    private long nextChestShearTriggerTime;
    private long shearCountdownTotalTicks;
    private long shearCountdownStartTime;
    private int lastShearsInChestCount;
    private int lastShearCountdownLogStep;
    private boolean shearCountdownActive;
    private long lastShearDay = -1L;
    private boolean hadShearsInChest;
    private int lastBannersInChestCount;
    private TaskType taskType;
    private List<SheepEntity> sheepTargets = new ArrayList<>();
    private int sheepTargetIndex;
    private final List<PenTarget> shearPenTargets = new ArrayList<>();
    private int shearPenIndex;
    private BlockPos currentShearPenCenter;
    private long shearStageStartTick;
    private Stage lastShearObservedStage = Stage.IDLE;
    private BlockPos penTarget;
    private BlockPos penGatePos;
    private ItemStack carriedItem = ItemStack.EMPTY;
    private boolean openedPenGate;
    private boolean wasInsidePen;
    private BlockPos shearsGateInsideTarget;
    private BlockPos shearsGateOutsideTarget;
    private double shearsGateCloseDistanceSquared;
    private boolean shearsGateClosedAfterEntry;
    private long shearsExitPenStuckStartTick;
    private BlockPos currentShearBannerPos;
    private long shearsChestReturnStartTick;
    private BlockPos gatherBannerPos;
    private long gatherSessionStartTick;
    private long gatherSessionDurationTicks;
    private long nextGatherRepathTick;
    private long nextGatherFollowCheckTick;
    private long gatherBannerHoldStartTick;
    private long gatherExitPenStuckStartTick;
    private final List<BlockPos> gatherCircleWaypoints = new ArrayList<>();
    private int gatherCircleWaypointIndex;
    private int gatherCircleStartIndex;
    private boolean gatherCircleCompleted;
    private BlockPos gatherExitTarget;
    private boolean gatherHalfLogged;
    private final List<AnimalEntity> activeHerd = new ArrayList<>();
    private long nearestPenCacheTick = Long.MIN_VALUE;
    private BlockPos cachedNearestPenTarget;
    private BlockPos cachedNearestPenGatePos;
    private long nearestGroundBannerCacheTick = Long.MIN_VALUE;
    private BlockPos cachedNearestGroundBanner;
    private int observedChestBannerCount = -1;
    private int observedChestWheatCount = -1;

    public ShepherdSpecialGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
        this.currentShearBannerPos = null;
        this.shearsChestReturnStartTick = 0L;
        invalidateSpatialSearchCache();
        observedChestBannerCount = -1;
        observedChestWheatCount = -1;
    }

    public void requestImmediateCheck() {
        nextCheckTime = 0L;
    }

    public void requestCheckNoSoonerThan(long targetTick) {
        if (nextCheckTime == 0L || nextCheckTime > targetTick) {
            nextCheckTime = targetTick;
        }
    }

    public void onChestInventoryChanged(ServerWorld world) {
        int currentBannerCount = countBannersInChest(world);
        int currentWheatCount = countWheatInChest(world);
        boolean bannerChanged = observedChestBannerCount != currentBannerCount;
        boolean wheatChanged = observedChestWheatCount != currentWheatCount;
        observedChestBannerCount = currentBannerCount;
        observedChestWheatCount = currentWheatCount;

        if (bannerChanged || wheatChanged) {
            invalidateSpatialSearchCache();
        }
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        updateShearsCountdown(world);
        if (jobPos == null || chestPos == null) {
            return false;
        }
        long day = world.getTimeOfDay() / 24000L;
        boolean dayChanged = day != lastShearDay;
        if (dayChanged) {
            lastShearDay = day;
            hadShearsInChest = false;
            nextChestShearTriggerTime = 0L;
            shearCountdownTotalTicks = 0L;
            shearCountdownStartTime = 0L;
            lastShearsInChestCount = 0;
            lastBannersInChestCount = 0;
            lastShearCountdownLogStep = 0;
            shearCountdownActive = false;
        }

        TaskType nextTask = findTaskType(world);
        if (nextTask == null) {
            hadShearsInChest = false;
            nextCheckTime = world.getTime() + nextRandomCheckInterval();
            return false;
        }

        if (nextTask == TaskType.WHEAT_GATHER && !world.isDay()) {
            return false;
        }

        if (dayChanged && nextTask == TaskType.SHEARS && nextCheckTime == 0L) {
            nextCheckTime = world.getTime();
        }

        int shearsInChestCount = countShearsInChest(world);
        boolean hasShearsInChest = shearsInChestCount > 0;

        if (nextTask == TaskType.SHEARS && hasShearsInChest) {
            lastShearsInChestCount = countShearsInChest(world);
        } else if (!hasShearsInChest) {
            lastShearsInChestCount = 0;
        }

        if (nextTask == TaskType.SHEARS && hasShearsInChest && !hadShearsInChest) {
            hadShearsInChest = true;
            if (nextChestShearTriggerTime == 0L) {
                nextChestShearTriggerTime = world.getTime() + nextRandomCheckInterval();
            }
            nextCheckTime = 0L;
        } else if (nextTask == TaskType.BANNER) {
            int bannersInChestCount = countBannersInChest(world);
            boolean bannersAddedToChest = bannersInChestCount > lastBannersInChestCount;
            if (bannersAddedToChest) {
                nextCheckTime = 0L;
            }
            lastBannersInChestCount = bannersInChestCount;
        }

        if (nextTask == TaskType.SHEARS && nextCheckTime == 0L && hasShearsInInventoryOrHand()) {
            nextCheckTime = world.getTime();
        }

        if (world.getTime() >= nextSheepSensorCheckTime && (stage == Stage.IDLE || stage == Stage.DONE)) {
            nextSheepSensorCheckTime = world.getTime() + SHEEP_SENSOR_INTERVAL_TICKS;
        if (nextCheckTime > world.getTime()
                && hasShearableSheepNearby(world)
                && (hasShearsInInventoryOrHand() || hasShearsInChest)) {
            nextCheckTime = 0L;
        }
        }

        if (world.getTime() < nextCheckTime) {
            return false;
        }

        if (nextTask == TaskType.SHEARS) {
            int sheepCount = countSheepNearby(world);
            boolean needsShearsFromChest = !hasShearsInInventoryOrHand() && hasShearsInChest;
            if (sheepCount < 1 && !needsShearsFromChest) {
                nextCheckTime = world.getTime() + nextRandomCheckInterval();
                return false;
            }
        }

        taskType = nextTask;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        if (taskType == TaskType.SHEARS) {
            if (hasShearsInInventoryOrHand() || hasShearsInChest(world)) {
                carriedItem = ItemStack.EMPTY;
            } else {
                nextCheckTime = world.getTime() + nextRandomCheckInterval();
                stage = Stage.DONE;
                return;
            }
        } else if (taskType == TaskType.BANNER) {
            carriedItem = ItemStack.EMPTY;
        } else {
            carriedItem = ItemStack.EMPTY;
        }

        if (taskType == TaskType.SHEARS) {
            if (!hasShearsInInventoryOrHand()) {
                takeItemFromChest(world, taskType);
            }
            equipShearsFromInventory();

            shearPenTargets.clear();
            shearPenTargets.addAll(findShearPensWithBanners(world));
            shearPenIndex = 0;
            currentShearPenCenter = null;
            currentShearBannerPos = null;
            shearsChestReturnStartTick = 0L;
            shearStageStartTick = world.getTime();
            lastShearObservedStage = Stage.IDLE;

            if (!prepareCurrentShearPen(world)) {
                sheepTargets = findBackupSheepTargets(world);
                sheepTargetIndex = 0;
                if (sheepTargets.isEmpty()) {
                    scheduleNextShearCheck(world, "no paired pens or backup sheep targets available");
                    stage = Stage.DONE;
                    return;
                }

                LOGGER.info("Shepherd {} entering backup shearing mode with {} target(s) (no paired pens found)",
                        villager.getUuidAsString(),
                        sheepTargets.size());
                currentShearPenCenter = null;
                currentShearBannerPos = null;
                penGatePos = null;
                stage = Stage.GO_TO_SHEEP;
                moveTo(sheepTargets.get(sheepTargetIndex).getBlockPos());
                return;
            }

            stage = Stage.GO_TO_PEN;
            moveTo(penTarget);
            return;
        }

        if (taskType == TaskType.BANNER) {
            LOGGER.info("Shepherd {} banner workflow kickoff (gate-first-v2)", villager.getUuidAsString());
            penTarget = findNearestPenTarget(world);
            if (penTarget == null) {
                LOGGER.info("Shepherd {} has banner available but no eligible pen was found; delaying pickup", villager.getUuidAsString());
                nextCheckTime = world.getTime() + 100L;
                stage = Stage.DONE;
                return;
            }

            if (hasBannerInInventoryOrHand()) {
                carriedItem = getBannerInInventoryOrHand();
            } else {
                carriedItem = takeItemFromChest(world, taskType);
            }
            if (carriedItem.isEmpty() && !hasBannerInInventoryOrHand()) {
                LOGGER.info("Shepherd {} found pen {} but no banner remained to carry", villager.getUuidAsString(), penTarget.toShortString());
                nextCheckTime = world.getTime() + 100L;
                stage = Stage.DONE;
                return;
            }

            LOGGER.info("Shepherd {} starting banner placement run toward gate {}", villager.getUuidAsString(), penTarget.toShortString());
            stage = Stage.GO_TO_PEN;
            moveTo(penTarget);
            return;
        }

        PenTarget gatherPen = resolveNearestGatherPen(world);
        if (gatherPen == null) {
            LOGGER.info("Shepherd {} has wheat available but no eligible paired pen was found; leaving wheat in chest",
                    villager.getUuidAsString());
            stage = Stage.DONE;
            nextCheckTime = world.getTime() + 100L;
            return;
        }

        if (!equipWheatForGathering(world)) {
            nextCheckTime = world.getTime() + nextRandomCheckInterval();
            stage = Stage.DONE;
            return;
        }

        gatherBannerPos = gatherPen.banner();
        penTarget = gatherPen.center();
        penGatePos = gatherPen.gate();
        gatherExitTarget = resolveOutsideGateTarget(world, penGatePos, penTarget);

        gatherSessionStartTick = world.getTime();
        gatherSessionDurationTicks = randomGatherSessionDuration();
        gatherHalfLogged = false;
        nextGatherRepathTick = 0L;
        nextGatherFollowCheckTick = 0L;
        gatherCircleWaypoints.clear();
        gatherCircleWaypoints.addAll(buildGatherCircleWaypoints(gatherBannerPos));
        gatherCircleStartIndex = findClosestGatherWaypointIndex(villager.getBlockPos());
        gatherCircleWaypointIndex = gatherCircleStartIndex;
        gatherCircleCompleted = false;
        gatherBannerHoldStartTick = 0L;
        gatherExitPenStuckStartTick = 0L;
        activeHerd.clear();
        refreshActiveHerd(world);
        LOGGER.info("Shepherd {} started wheat gather session near banner {} for {} ticks", villager.getUuidAsString(), gatherBannerPos.toShortString(), gatherSessionDurationTicks);
        stage = Stage.GATHER_CIRCLE;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        stage = Stage.DONE;
        taskType = null;
        sheepTargets = new ArrayList<>();
        sheepTargetIndex = 0;
        shearPenTargets.clear();
        shearPenIndex = 0;
        currentShearPenCenter = null;
        shearStageStartTick = 0L;
        lastShearObservedStage = Stage.IDLE;
        penTarget = null;
        penGatePos = null;
        carriedItem = ItemStack.EMPTY;
        openedPenGate = false;
        wasInsidePen = false;
        shearsGateInsideTarget = null;
        shearsGateOutsideTarget = null;
        shearsGateCloseDistanceSquared = 0.0D;
        shearsGateClosedAfterEntry = false;
        shearsExitPenStuckStartTick = 0L;
        currentShearBannerPos = null;
        shearsChestReturnStartTick = 0L;
        activeHerd.clear();
        gatherCircleWaypoints.clear();
        gatherCircleWaypointIndex = 0;
        gatherCircleStartIndex = 0;
        gatherCircleCompleted = false;
        gatherBannerHoldStartTick = 0L;
        gatherExitPenStuckStartTick = 0L;
        gatherExitTarget = null;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        updateShearsCountdown(world);
        monitorShearStageProgress(world);

        switch (stage) {
            case GO_TO_SHEEP -> {
                SheepEntity targetEntity = getSheepTarget();
                if (targetEntity == null) {
                    LOGGER.info("Shepherd {} completed shearing session for banner {} (pen center {})",
                            villager.getUuidAsString(),
                            currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString(),
                            currentShearPenCenter == null ? "unknown" : currentShearPenCenter.toShortString());
                    stage = Stage.SHEARS_EXIT_PEN;
                    moveTo(penGatePos == null ? chestPos : penGatePos);
                    return;
                }

                if (isNear(targetEntity.getBlockPos())) {
                    shearSheep(world, targetEntity);
                    sheepTargetIndex++;
                    SheepEntity nextTarget = getSheepTarget();
                    if (nextTarget != null) {
                        moveTo(nextTarget.getBlockPos());
                    } else {
                        LOGGER.info("Shepherd {} completed shearing session for banner {} (pen center {})",
                                villager.getUuidAsString(),
                                currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString(),
                                currentShearPenCenter == null ? "unknown" : currentShearPenCenter.toShortString());
                        stage = Stage.SHEARS_EXIT_PEN;
                        moveTo(penGatePos == null ? chestPos : penGatePos);
                    }
                } else {
                    moveTo(targetEntity.getBlockPos());
                }
            }
            case GO_TO_PEN -> {
                if (penTarget == null) {
                    stage = Stage.RETURN_TO_CHEST;
                    moveTo(chestPos);
                    return;
                }

                updatePenGateAccess(world, penGatePos);

                if (taskType == TaskType.SHEARS) {
                    if (penGatePos != null && penTarget.equals(penGatePos)) {
                        if (!isNear(penGatePos)) {
                            moveTo(penGatePos);
                            return;
                        }
                        openGate(world, penGatePos, true);
                        openedPenGate = true;
                        LOGGER.info("Shepherd {} opened shearing gate {} for banner {}",
                                villager.getUuidAsString(),
                                penGatePos.toShortString(),
                                currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString());
                        penTarget = currentShearPenCenter;
                        shearsGateInsideTarget = resolveInsideGateTarget(world, penGatePos, currentShearPenCenter);
                        shearsGateOutsideTarget = resolveOutsideGateTarget(world, penGatePos, currentShearPenCenter);
                        shearsGateCloseDistanceSquared = calculateGateCloseDistanceSquared(penGatePos, currentShearPenCenter);
                        shearsGateClosedAfterEntry = false;
                        if (shearsGateInsideTarget != null) {
                            moveTo(shearsGateInsideTarget, FAST_GATE_CLOSE_SPEED);
                            return;
                        }
                        if (penTarget == null) {
                            stage = Stage.RETURN_TO_CHEST;
                            moveTo(chestPos);
                            return;
                        }
                    }

                    if (!shearsGateClosedAfterEntry && penGatePos != null) {
                        boolean isInsidePen = isInsideSpecificPen(world, villager.getBlockPos(), currentShearPenCenter);
                        double distanceFromGateSquared = villager.squaredDistanceTo(
                                penGatePos.getX() + 0.5D,
                                penGatePos.getY() + 0.5D,
                                penGatePos.getZ() + 0.5D);
                        boolean reachedInsideTarget = shearsGateInsideTarget != null
                                && villager.squaredDistanceTo(
                                shearsGateInsideTarget.getX() + 0.5D,
                                shearsGateInsideTarget.getY() + 0.5D,
                                shearsGateInsideTarget.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
                        boolean safelyInsidePen = isInsidePen && distanceFromGateSquared >= 2.25D;
                        if (reachedInsideTarget || safelyInsidePen) {
                            openGate(world, penGatePos, false);
                            shearsGateClosedAfterEntry = true;
                        }
                    }

                    boolean insideCurrentPen = currentShearPenCenter != null
                            && isInsideSpecificPen(world, villager.getBlockPos(), currentShearPenCenter);
                    if ((penTarget != null && isNear(penTarget)) || insideCurrentPen) {
                        sheepTargets = findSheepTargets(world, currentShearPenCenter);
                        sheepTargetIndex = 0;
                        if (sheepTargets.isEmpty()) {
                            LOGGER.info("Shepherd {} selected pen banner {} (center {}) with 0 shearable sheep",
                                    villager.getUuidAsString(),
                                    currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString(),
                                    currentShearPenCenter == null ? "unknown" : currentShearPenCenter.toShortString());
                            LOGGER.info("Shepherd {} completed shearing session for banner {} (pen center {})",
                                    villager.getUuidAsString(),
                                    currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString(),
                                    currentShearPenCenter == null ? "unknown" : currentShearPenCenter.toShortString());
                            stage = Stage.SHEARS_EXIT_PEN;
                            moveTo(penGatePos == null ? chestPos : penGatePos);
                        } else {
                            LOGGER.info("Shepherd {} selected pen banner {} (center {}) with {} shearable sheep",
                                    villager.getUuidAsString(),
                                    currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString(),
                                    currentShearPenCenter == null ? "unknown" : currentShearPenCenter.toShortString(),
                                    sheepTargets.size());
                            stage = Stage.GO_TO_SHEEP;
                            moveTo(sheepTargets.get(sheepTargetIndex).getBlockPos());
                        }
                        return;
                    }

                    moveTo(penTarget);
                    return;
                }

                if (penGatePos != null && penTarget.equals(penGatePos) && isNear(penGatePos)) {
                    BlockState gateState = world.getBlockState(penGatePos);
                    BlockPos insidePos = getPenInterior(world, penGatePos, gateState);
                    BlockPos center = insidePos == null ? null : getPenCenter(world, insidePos);
                    if (center == null || hasBannerInPen(world, center)) {
                        stage = Stage.RETURN_TO_CHEST;
                        moveTo(chestPos);
                        return;
                    }
                    penTarget = center.toImmutable();
                    moveTo(penTarget);
                    return;
                }

                if (isNear(penTarget)) {
                    BlockPos placedBannerPos = placeBannerInPen(world, penTarget);
                    if (placedBannerPos != null) {
                        invalidateSpatialSearchCache();
                        triggerBannerPairing(world, placedBannerPos);
                    }
                    stage = Stage.RETURN_TO_CHEST;
                    moveTo(chestPos);
                } else {
                    moveTo(penTarget);
                }
            }
            case SHEARS_EXIT_PEN -> {
                if (taskType != TaskType.SHEARS || penGatePos == null) {
                    stage = Stage.RETURN_TO_CHEST;
                    moveTo(chestPos);
                    return;
                }
                boolean isInsidePen = isInsideSpecificPen(world, villager.getBlockPos(), currentShearPenCenter);
                BlockPos outsideTarget = shearsGateOutsideTarget == null
                        ? resolveOutsideGateTarget(world, penGatePos, currentShearPenCenter)
                        : shearsGateOutsideTarget;
                double distanceFromGateSquared = villager.squaredDistanceTo(
                        penGatePos.getX() + 0.5D,
                        penGatePos.getY() + 0.5D,
                        penGatePos.getZ() + 0.5D);

                if (isInsidePen) {
                    if (shearsExitPenStuckStartTick == 0L) {
                        shearsExitPenStuckStartTick = world.getTime();
                    } else if (world.getTime() - shearsExitPenStuckStartTick >= SHEARS_EXIT_PEN_RETRY_TICKS) {
                        retryShearsExitPen(world);
                        shearsExitPenStuckStartTick = world.getTime();
                        return;
                    }
                    BlockState gateState = world.getBlockState(penGatePos);
                    openGate(world, penGatePos, true);
                    if (gateState.getBlock() instanceof FenceGateBlock && !gateState.get(FenceGateBlock.OPEN)) {
                        LOGGER.info("Shepherd {} opened shearing gate {} to exit pen for banner {}",
                                villager.getUuidAsString(),
                                penGatePos.toShortString(),
                                currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString());
                    }
                    moveTo(outsideTarget == null ? penGatePos : outsideTarget, FAST_GATE_CLOSE_SPEED);
                    return;
                }
                shearsExitPenStuckStartTick = 0L;

                if (outsideTarget != null) {
                    double outsideDistanceSquared = villager.squaredDistanceTo(outsideTarget.getX() + 0.5D, outsideTarget.getY() + 0.5D, outsideTarget.getZ() + 0.5D);
                    if (outsideDistanceSquared > 4.0D && distanceFromGateSquared < 16.0D) {
                        moveTo(outsideTarget, FAST_GATE_CLOSE_SPEED);
                        return;
                    }
                }

                BlockState gateState = world.getBlockState(penGatePos);
                boolean gateOpen = gateState.getBlock() instanceof FenceGateBlock && gateState.get(FenceGateBlock.OPEN);
                if (gateOpen && distanceFromGateSquared > GATE_INTERACT_RANGE_SQUARED) {
                    moveTo(penGatePos, FAST_GATE_CLOSE_SPEED);
                    return;
                }

                if (gateOpen) {
                    openGate(world, penGatePos, false);
                    LOGGER.info("Shepherd {} closed shearing gate {} after exiting pen for banner {}",
                            villager.getUuidAsString(),
                            penGatePos.toShortString(),
                            currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString());
                }
                openedPenGate = false;
                wasInsidePen = false;
                shearsGateInsideTarget = null;
                shearsGateOutsideTarget = null;
                shearsGateCloseDistanceSquared = 0.0D;
                shearsGateClosedAfterEntry = false;
                shearsExitPenStuckStartTick = 0L;
                stage = Stage.RETURN_TO_CHEST;
                shearsChestReturnStartTick = world.getTime();
                moveTo(chestPos);
            }
            case GATHER_CIRCLE -> {
                if (gatherBannerPos == null || !ensureGatherWheatDisplayed(world)) {
                    stage = Stage.DONE;
                    return;
                }

                forceNearbySheepFocusOnShepherd(world);

                if (world.getTime() >= nextGatherFollowCheckTick) {
                    refreshActiveHerd(world);
                    nextGatherFollowCheckTick = world.getTime() + GATHER_FOLLOW_CHECK_INTERVAL_TICKS;
                }

                long elapsed = world.getTime() - gatherSessionStartTick;
                if (!gatherHalfLogged && elapsed >= gatherSessionDurationTicks / 2L) {
                    gatherHalfLogged = true;
                    LOGGER.info("Shepherd {} wheat gather session 50% complete", villager.getUuidAsString());
                }

                if (gatherCircleWaypoints.isEmpty() || penGatePos == null) {
                    stage = Stage.DONE;
                    nextCheckTime = world.getTime() + nextRandomCheckInterval();
                    return;
                }

                BlockPos waypoint = gatherCircleWaypoints.get(gatherCircleWaypointIndex);
                if (isNear(waypoint)) {
                    gatherCircleWaypointIndex = (gatherCircleWaypointIndex + 1) % gatherCircleWaypoints.size();
                    if (gatherCircleWaypointIndex == gatherCircleStartIndex) {
                        gatherCircleCompleted = true;
                    }
                    waypoint = gatherCircleWaypoints.get(gatherCircleWaypointIndex);
                }

                if (gatherCircleCompleted && isAlignedWithGate(gatherBannerPos, penGatePos, villager.getBlockPos())) {
                    stage = Stage.GATHER_MOVE_TO_GATE;
                    moveTo(penGatePos, SLOW_GUIDE_SPEED);
                    return;
                }

                moveTo(waypoint, SLOW_GUIDE_SPEED);
            }
            case GATHER_MOVE_TO_GATE -> {
                if (penTarget == null || penGatePos == null) {
                    stage = Stage.DONE;
                    return;
                }

                if (!ensureGatherWheatDisplayed(world)) {
                    stage = Stage.DONE;
                    return;
                }
                ensureGateOpen(world, penGatePos);
                refreshActiveHerd(world);
                forceNearbySheepFocusOnShepherd(world);

                if (isNear(penGatePos)) {
                    stage = Stage.GATHER_MOVE_TO_BANNER;
                    moveTo(gatherBannerPos, SLOW_GUIDE_SPEED);
                } else {
                    moveTo(penGatePos, SLOW_GUIDE_SPEED);
                }
            }
            case GATHER_MOVE_TO_BANNER -> {
                if (gatherBannerPos == null || penGatePos == null) {
                    stage = Stage.DONE;
                    return;
                }

                if (!ensureGatherWheatDisplayed(world)) {
                    stage = Stage.DONE;
                    return;
                }
                ensureGateOpen(world, penGatePos);
                forceNearbySheepFocusOnShepherd(world);
                if (isNear(gatherBannerPos)) {
                    gatherBannerHoldStartTick = world.getTime();
                    villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
                    LOGGER.info("Shepherd {} reached gather banner {}; holding center for {} ticks before exit",
                            villager.getUuidAsString(),
                            gatherBannerPos.toShortString(),
                            GATHER_BANNER_HOLD_TICKS);
                    stage = Stage.GATHER_HOLD_BANNER;
                } else {
                    moveTo(gatherBannerPos, SLOW_GUIDE_SPEED);
                }
            }
            case GATHER_HOLD_BANNER -> {
                if (gatherBannerPos == null || penGatePos == null) {
                    stage = Stage.DONE;
                    return;
                }

                if (!ensureGatherWheatDisplayed(world)) {
                    stage = Stage.DONE;
                    return;
                }

                ensureGateOpen(world, penGatePos);
                forceNearbySheepFocusOnShepherd(world);
                if (!isNear(gatherBannerPos)) {
                    moveTo(gatherBannerPos, SLOW_GUIDE_SPEED);
                    return;
                }

                villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
                long holdElapsed = world.getTime() - gatherBannerHoldStartTick;
                if (holdElapsed < GATHER_BANNER_HOLD_TICKS) {
                    return;
                }

                clearGatherWheatHands();
                activeHerd.clear();
                LOGGER.info("Shepherd {} completed center hold at banner {}; wheat attraction disabled, exiting pen",
                        villager.getUuidAsString(),
                        gatherBannerPos.toShortString());
                gatherExitTarget = resolveOutsideGateTarget(world, penGatePos, penTarget);
                gatherExitPenStuckStartTick = 0L;
                stage = Stage.GATHER_EXIT_AND_CLOSE;
            }
            case GATHER_EXIT_AND_CLOSE -> {
                if (penGatePos == null) {
                    stage = Stage.DONE;
                    return;
                }

                boolean isInsidePen = penTarget != null
                        ? isInsideSpecificPen(world, villager.getBlockPos(), penTarget)
                        : isInsideFencePen(world, villager.getBlockPos());

                if (isInsidePen) {
                    if (gatherExitPenStuckStartTick == 0L) {
                        gatherExitPenStuckStartTick = world.getTime();
                    } else if (world.getTime() - gatherExitPenStuckStartTick >= GATHER_EXIT_PEN_RETRY_TICKS) {
                        retryGatherExitPen(world);
                        gatherExitPenStuckStartTick = world.getTime();
                        return;
                    }

                    ensureGateOpen(world, penGatePos);
                    if (gatherExitTarget == null) {
                        gatherExitTarget = resolveOutsideGateTarget(world, penGatePos, penTarget);
                    }
                    moveTo(gatherExitTarget == null ? penGatePos : gatherExitTarget, FAST_GATE_CLOSE_SPEED);
                    return;
                }

                gatherExitPenStuckStartTick = 0L;

                BlockState gateState = world.getBlockState(penGatePos);
                boolean gateOpen = gateState.getBlock() instanceof FenceGateBlock && gateState.get(FenceGateBlock.OPEN);
                double distanceFromGateSquared = villager.squaredDistanceTo(
                        penGatePos.getX() + 0.5D,
                        penGatePos.getY() + 0.5D,
                        penGatePos.getZ() + 0.5D);

                if (gateOpen && distanceFromGateSquared > GATE_INTERACT_RANGE_SQUARED) {
                    moveTo(penGatePos, FAST_GATE_CLOSE_SPEED);
                    return;
                }

                if (gateOpen) {
                    openGate(world, penGatePos, false);
                    LOGGER.info("Shepherd {} closed pen gate at {} after wheat gather", villager.getUuidAsString(), penGatePos.toShortString());
                }

                nextCheckTime = world.getTime() + nextRandomCheckInterval();
                stage = Stage.DONE;
            }
            case RETURN_TO_CHEST -> {
                updatePenGateAccess(world, penGatePos);
                if (isNear(chestPos)) {
                    depositSpecialItems(world);
                    if (taskType == TaskType.SHEARS) {
                        LOGGER.info("Shepherd {} delivered shearing output to chest after banner {}",
                                villager.getUuidAsString(),
                                currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString());
                        shearPenIndex++;
                        if (prepareCurrentShearPen(world)) {
                            stage = Stage.GO_TO_PEN;
                            shearsChestReturnStartTick = 0L;
                            moveTo(penTarget);
                            return;
                        }
                        scheduleNextShearCheck(world, "shearing session complete");
                    } else {
                        nextCheckTime = resolveNextCheckTimeAfterChestReturn(world);
                    }
                    stage = Stage.DONE;
                } else {
                    if (taskType == TaskType.SHEARS && shearsChestReturnStartTick > 0L
                            && world.getTime() - shearsChestReturnStartTick >= SHEARS_CHEST_RETURN_TIMEOUT_TICKS) {
                        BlockPos escapePen = findNearestPenTarget(world);
                        if (escapePen != null) {
                            LOGGER.info("Shepherd {} chest return exceeded {} ticks; rerouting briefly via nearest pen target {} before retrying chest",
                                    villager.getUuidAsString(), SHEARS_CHEST_RETURN_TIMEOUT_TICKS, escapePen.toShortString());
                            moveTo(escapePen, FAST_GATE_CLOSE_SPEED);
                            shearsChestReturnStartTick = world.getTime();
                            return;
                        }
                    }
                    moveTo(chestPos);
                }
            }
            case IDLE, DONE -> {
            }
        }
    }

    private TaskType findTaskType(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        boolean hasBannerAvailable = hasBannerInInventoryOrHand()
                || (inventory != null && hasMatchingItem(inventory, stack -> stack.isIn(ItemTags.BANNERS)));

        if (hasBannerAvailable) {
            return TaskType.BANNER;
        }

        if (inventory == null) {
            if (hasShearsInInventoryOrHand()) {
                return TaskType.SHEARS;
            }
            if (hasWheatInInventoryOrOffhand() && resolveNearestGatherPen(world) != null) {
                return TaskType.WHEAT_GATHER;
            }
            return null;
        }

        if (hasShearsInChestOrInventory(inventory)) {
            return TaskType.SHEARS;
        }

        if ((hasMatchingItem(inventory, stack -> stack.isOf(Items.WHEAT)) || hasWheatInInventoryOrOffhand())
                && resolveNearestGatherPen(world) != null) {
            return TaskType.WHEAT_GATHER;
        }

        return null;
    }

    private long resolveNextCheckTimeAfterChestReturn(ServerWorld world) {
        if (taskType == TaskType.BANNER) {
            boolean hasMoreBanners = hasBannerInInventoryOrHand() || countBannersInChest(world) > 0;
            if (hasMoreBanners && findNearestPenTarget(world) != null) {
                return world.getTime() + 20L;
            }
        }
        return world.getTime() + nextRandomCheckInterval();
    }

    private boolean hasMatchingItem(Inventory inventory, Predicate<ItemStack> matcher) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasShearsInChestOrInventory(Inventory chestInventory) {
        return hasMatchingItem(chestInventory, stack -> stack.isOf(Items.SHEARS))
                || hasShearsInInventoryOrHand();
    }

    private boolean hasShearsInInventoryOrHand() {
        Inventory villagerInventory = villager.getInventory();
        return hasMatchingItem(villagerInventory, stack -> stack.isOf(Items.SHEARS))
                || villager.getMainHandStack().isOf(Items.SHEARS);
    }

    private boolean hasBannerInInventoryOrHand() {
        Inventory villagerInventory = villager.getInventory();
        return hasMatchingItem(villagerInventory, stack -> stack.isIn(ItemTags.BANNERS))
                || villager.getMainHandStack().isIn(ItemTags.BANNERS);
    }

    private boolean hasWheatInInventoryOrOffhand() {
        Inventory villagerInventory = villager.getInventory();
        return hasMatchingItem(villagerInventory, stack -> stack.isOf(Items.WHEAT))
                || villager.getMainHandStack().isOf(Items.WHEAT)
                || villager.getOffHandStack().isOf(Items.WHEAT);
    }

    private ItemStack getBannerInInventoryOrHand() {
        ItemStack mainHand = villager.getMainHandStack();
        if (mainHand.isIn(ItemTags.BANNERS)) {
            return mainHand.copy();
        }
        Inventory villagerInventory = villager.getInventory();
        for (int slot = 0; slot < villagerInventory.size(); slot++) {
            ItemStack stack = villagerInventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.BANNERS)) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean hasShearsInChest(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return false;
        }
        return hasMatchingItem(chestInventory, stack -> stack.isOf(Items.SHEARS));
    }

    private int countBannersInChest(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.BANNERS)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countShearsInChest(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(Items.SHEARS)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countWheatInChest(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(Items.WHEAT)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private ItemStack takeItemFromChest(ServerWorld world, TaskType taskType) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return ItemStack.EMPTY;
        }

        boolean wantsShears = taskType == TaskType.SHEARS;
        Predicate<ItemStack> matcher = wantsShears
                ? stack -> stack.isOf(Items.SHEARS)
                : stack -> stack.isIn(ItemTags.BANNERS);

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }

            ItemStack extracted = stack.split(1);
            if (wantsShears && villager.getMainHandStack().isEmpty()) {
                villager.setStackInHand(Hand.MAIN_HAND, extracted);
                inventory.setStack(slot, stack);
                inventory.markDirty();
                return extracted;
            }

            ItemStack remaining = insertStack(villager.getInventory(), extracted);
            if (!remaining.isEmpty()) {
                stack.increment(remaining.getCount());
                inventory.setStack(slot, stack);
                return ItemStack.EMPTY;
            }

            inventory.setStack(slot, stack);
            inventory.markDirty();
            villager.getInventory().markDirty();
            return extracted;
        }

        return ItemStack.EMPTY;
    }

    private boolean equipWheatForGathering(ServerWorld world) {
        if (isHoldingGatherWheat()) {
            return true;
        }

        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.WHEAT)) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            villager.setStackInHand(Hand.MAIN_HAND, extracted);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            inventory.markDirty();
            return true;
        }

        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return false;
        }
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.WHEAT)) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            villager.setStackInHand(Hand.MAIN_HAND, extracted);
            chestInventory.setStack(slot, stack);
            chestInventory.markDirty();
            return true;
        }
        return false;
    }


    private boolean isHoldingGatherWheat() {
        return villager.getMainHandStack().isOf(Items.WHEAT) || villager.getOffHandStack().isOf(Items.WHEAT);
    }

    private boolean ensureGatherWheatDisplayed(ServerWorld world) {
        if (isHoldingGatherWheat()) {
            return true;
        }
        if (equipWheatForGathering(world)) {
            return true;
        }

        villager.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.WHEAT));
        LOGGER.info("Shepherd {} forced synthetic wheat display for gather session", villager.getUuidAsString());
        return true;
    }

    private void clearGatherWheatHands() {
        if (villager.getOffHandStack().isOf(Items.WHEAT)) {
            villager.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
        }
        if (villager.getMainHandStack().isOf(Items.WHEAT)) {
            villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    private void forceNearbySheepFocusOnShepherd(ServerWorld world) {
        Box sheepBox = new Box(villager.getBlockPos()).expand(HERD_SELECTION_RANGE, 6.0D, HERD_SELECTION_RANGE);
        List<SheepEntity> nearbySheep = world.getEntitiesByClass(SheepEntity.class, sheepBox, SheepEntity::isAlive);
        for (SheepEntity sheep : nearbySheep) {
            sheep.getLookControl().lookAt(villager, 30.0F, 30.0F);
            sheep.getNavigation().startMovingTo(villager, 1.05D);
        }
    }

    private int countSheepNearby(ServerWorld world) {
        Box box = new Box(villager.getBlockPos()).expand(SHEEP_SCAN_RANGE);
        List<SheepEntity> sheep = world.getEntitiesByClass(SheepEntity.class, box, SheepEntity::isAlive);
        return sheep.size();
    }

    private boolean hasShearableSheepNearby(ServerWorld world) {
        Box box = new Box(villager.getBlockPos()).expand(SHEEP_SCAN_RANGE);
        return !world.getEntitiesByClass(SheepEntity.class, box, entity -> entity.isAlive() && entity.isShearable()).isEmpty();
    }

    private void depositSpecialItems(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return;
        }

        Inventory villagerInventory = villager.getInventory();
        for (int slot = 0; slot < villagerInventory.size(); slot++) {
            ItemStack stack = villagerInventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!(stack.isIn(ItemTags.BANNERS) || stack.isIn(ItemTags.WOOL) || stack.isOf(Items.WHEAT))) {
                continue;
            }

            ItemStack remaining = insertStack(chestInventory, stack);
            villagerInventory.setStack(slot, remaining);
        }

        ItemStack mainHand = villager.getMainHandStack();
        if (mainHand.isIn(ItemTags.BANNERS) || mainHand.isIn(ItemTags.WOOL) || mainHand.isOf(Items.WHEAT)) {
            ItemStack remaining = insertStack(chestInventory, mainHand);
            villager.setStackInHand(Hand.MAIN_HAND, remaining);
        }

        ItemStack offHand = villager.getOffHandStack();
        if (offHand.isOf(Items.WHEAT)) {
            ItemStack remaining = insertStack(chestInventory, offHand);
            villager.setStackInHand(Hand.OFF_HAND, remaining);
        }

        villagerInventory.markDirty();
        chestInventory.markDirty();
    }

    private boolean prepareCurrentShearPen(ServerWorld world) {
        while (shearPenIndex < shearPenTargets.size()) {
            PenTarget target = shearPenTargets.get(shearPenIndex);
            if (target == null) {
                shearPenIndex++;
                continue;
            }
            currentShearPenCenter = target.center();
            currentShearBannerPos = target.banner();
            penGatePos = resolveGateForPen(world, currentShearPenCenter, target.gate());
            penTarget = penGatePos;
            openedPenGate = false;
            wasInsidePen = false;
            shearsGateInsideTarget = null;
            shearsGateOutsideTarget = null;
            shearsGateCloseDistanceSquared = 0.0D;
            shearsGateClosedAfterEntry = false;
            int sheepCount = currentShearPenCenter == null ? 0 : findSheepTargets(world, currentShearPenCenter).size();
            LOGGER.info("Shepherd {} selected shearing pen banner {} with {} sheep (center {}, gate {})",
                    villager.getUuidAsString(),
                    currentShearBannerPos == null ? "unknown" : currentShearBannerPos.toShortString(),
                    sheepCount,
                    currentShearPenCenter == null ? "unknown" : currentShearPenCenter.toShortString(),
                    penGatePos == null ? "unknown" : penGatePos.toShortString());
            return penTarget != null;
        }
        currentShearPenCenter = null;
        currentShearBannerPos = null;
        penGatePos = null;
        penTarget = null;
        shearsGateInsideTarget = null;
        shearsGateOutsideTarget = null;
        shearsGateCloseDistanceSquared = 0.0D;
        shearsGateClosedAfterEntry = false;
        return false;
    }


    private BlockPos resolveGateForPen(ServerWorld world, BlockPos penCenter, BlockPos preferredGate) {
        if (penCenter == null) {
            return preferredGate;
        }

        if (preferredGate != null) {
            BlockState preferredState = world.getBlockState(preferredGate);
            if (preferredState.getBlock() instanceof FenceGateBlock) {
                BlockPos preferredInterior = getPenInterior(world, preferredGate, preferredState);
                BlockPos preferredCenter = preferredInterior == null ? null : getPenCenter(world, preferredInterior);
                if (preferredCenter != null && preferredCenter.getSquaredDistance(penCenter) <= 36.0D) {
                    return preferredGate.toImmutable();
                }
            }
        }

        int minY = getLocalMinY(world, penCenter);
        int maxY = getLocalMaxY(world, penCenter);
        List<BlockPos> gates = collectNearbyGateCandidates(world, penCenter, List.of(penCenter), PEN_BANNER_TO_GATE_SCAN_RADIUS, minY, maxY, PEN_GATE_CHECK_LIMIT);
        BlockPos bestGate = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos gatePos : gates) {
            BlockState state = world.getBlockState(gatePos);
            if (!(state.getBlock() instanceof FenceGateBlock)) {
                continue;
            }
            BlockPos insidePos = getPenInterior(world, gatePos, state);
            if (insidePos == null || !isInsideFencePen(world, insidePos)) {
                continue;
            }
            BlockPos center = getPenCenter(world, insidePos);
            if (center == null || center.getSquaredDistance(penCenter) > 36.0D) {
                continue;
            }
            double distance = center.getSquaredDistance(penCenter);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestGate = gatePos.toImmutable();
            }
        }
        return bestGate;
    }

    private List<PenTarget> findShearPensWithBanners(ServerWorld world) {
        // Use VillagePenRegistry (geometry-only, no banner required) instead of banner scan.
        BlockPos villagerPos = villager.getBlockPos();
        List<VillagePenRegistry.PenEntry> registryPens =
                VillagePenRegistry.get(world.getServer()).getNearestBellPens(world, villagerPos, PEN_SCAN_RANGE * 2);

        if (registryPens.isEmpty()) {
            // Fall back to legacy banner scan if registry hasn't populated yet.
            return findShearPensLegacyBannerScan(world);
        }

        List<PenTarget> pens = new ArrayList<>();
        for (VillagePenRegistry.PenEntry entry : registryPens) {
            // Use center as the "banner" position for compatibility with shearing stage logic.
            PenTarget pen = new PenTarget(entry.center(), entry.center(), entry.gate());
            pens.add(pen);
        }
        pens.sort(Comparator.comparingDouble(pen -> villagerPos.getSquaredDistance(pen.gate())));
        LOGGER.info("Shepherd {} found {} shear pen(s) via VillagePenRegistry", villager.getUuidAsString(), pens.size());
        return pens;
    }

    /** Legacy banner-based pen scan used as fallback when the registry is empty. */
    private List<PenTarget> findShearPensLegacyBannerScan(ServerWorld world) {
        BlockPos villagerPos = villager.getBlockPos();
        int minY = getLocalMinY(world, villagerPos);
        int maxY = getLocalMaxY(world, villagerPos);
        List<BlockPos> bannerCandidates = findBannerCandidatesWithinRange(world, villagerPos, PEN_SCAN_RANGE, minY, maxY);
        List<PenTarget> pens = new ArrayList<>();
        for (BlockPos bannerPos : bannerCandidates) {
            PenTarget pen = findNearestPenWithBanner(world, bannerPos);
            if (pen == null) {
                continue;
            }
            boolean duplicate = pens.stream().anyMatch(existing -> existing.gate().equals(pen.gate())
                    || existing.center().equals(pen.center())
                    || existing.center().getSquaredDistance(pen.center()) <= 9.0D);
            if (!duplicate) {
                pens.add(pen);
            }
        }
        pens.sort(Comparator.comparingDouble(pen -> villagerPos.getSquaredDistance(pen.gate())));
        return pens;
    }

    private List<SheepEntity> findSheepTargets(ServerWorld world, BlockPos penCenter) {
        if (penCenter == null) {
            return List.of();
        }
        Box box = new Box(penCenter).expand(16.0D, 6.0D, 16.0D);
        List<SheepEntity> sheep = world.getEntitiesByClass(
                SheepEntity.class,
                box,
                entity -> entity.isAlive() && entity.isShearable() && isInsideSpecificPen(world, entity.getBlockPos(), penCenter));
        sheep.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(villager)));
        return new ArrayList<>(sheep);
    }

    private SheepEntity getSheepTarget() {
        while (sheepTargetIndex < sheepTargets.size()) {
            SheepEntity target = sheepTargets.get(sheepTargetIndex);
            if (target != null && target.isAlive() && target.isShearable()) {
                if (currentShearPenCenter == null) {
                    return target;
                }
                if (villager.getWorld() instanceof ServerWorld world
                        && isInsideSpecificPen(world, target.getBlockPos(), currentShearPenCenter)) {
                    return target;
                }
            }
            sheepTargetIndex++;
        }
        return null;
    }

    private void equipShearsFromInventory() {
        if (villager.getMainHandStack().isOf(Items.SHEARS)) {
            return;
        }
        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.SHEARS)) {
                continue;
            }
            villager.setStackInHand(Hand.MAIN_HAND, stack);
            inventory.setStack(slot, ItemStack.EMPTY);
            inventory.markDirty();
            return;
        }
    }

    private void shearSheep(ServerWorld world, SheepEntity sheep) {
        LOGGER.info("Shepherd {} attempting to shear sheep {} at {} (alive={}, shearable={}, sheared={})",
                villager.getUuidAsString(),
                sheep.getUuidAsString(),
                sheep.getBlockPos().toShortString(),
                sheep.isAlive(),
                sheep.isShearable(),
                sheep.isSheared());
        if (!sheep.isAlive() || !sheep.isShearable()) {
            return;
        }

        sheep.setSheared(true);
        sheep.playSound(SoundEvents.ENTITY_SHEEP_SHEAR, 1.0F, 1.0F);
        int dropCount = 1 + sheep.getRandom().nextInt(3);
        ItemStack woolStack = new ItemStack(woolFromColor(sheep.getColor()), dropCount);
        ItemStack remaining = insertStack(villager.getInventory(), woolStack);
        if (!remaining.isEmpty()) {
            sheep.dropStack(remaining);
        }
        villager.getInventory().markDirty();
        LOGGER.info("Shepherd {} sheared sheep {} at {} (now sheared={})",
                villager.getUuidAsString(),
                sheep.getUuidAsString(),
                sheep.getBlockPos().toShortString(),
                sheep.isSheared());
        collectNearbyWool(world, sheep.getBlockPos());
    }

    private void collectNearbyWool(ServerWorld world, BlockPos center) {
        Box box = new Box(center).expand(3.0D);
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class, box, entity -> entity.isAlive() && entity.getStack().isIn(ItemTags.WOOL));
        for (ItemEntity itemEntity : items) {
            ItemStack remaining = insertStack(villager.getInventory(), itemEntity.getStack());
            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setStack(remaining);
            }
        }
        villager.getInventory().markDirty();
    }

    private void triggerShearsPlacedInChest(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return;
        }

        ItemStack shears = new ItemStack(Items.SHEARS);
        ItemStack remaining = insertStack(chestInventory, shears);
        if (remaining.isEmpty()) {
            chestInventory.markDirty();
            LOGGER.info("Shepherd {} inserted shears into chest at {}",
                    villager.getUuidAsString(),
                    chestPos.toShortString());
        } else {
            LOGGER.info("Shepherd {} failed to insert shears into chest at {} (no space)",
                    villager.getUuidAsString(),
                chestPos.toShortString());
        }
    }

    private void updateShearsCountdown(ServerWorld world) {
        if (chestPos == null) {
            return;
        }
        int shearsInChestCount = countShearsInChest(world);
        boolean hasShearsInChest = shearsInChestCount > 0;
        boolean shearsAddedToChest = shearsInChestCount > lastShearsInChestCount;
        boolean shouldContinueCountdown = hasShearsInChest || hasShearsInInventoryOrHand();

        if (shearsAddedToChest) {
            startShearCountdown(world, "shears added to chest");
            hadShearsInChest = false;
            shearCountdownActive = true;
        } else if (!shearCountdownActive && hasShearsInChest) {
            startShearCountdown(world, "shears detected in chest");
            shearCountdownActive = true;
        }

        if (shearCountdownActive && nextChestShearTriggerTime > 0L) {
            logShearCountdownProgress(world, nextChestShearTriggerTime);
        }

        if (shearCountdownActive && nextChestShearTriggerTime > 0L && world.getTime() >= nextChestShearTriggerTime) {
            triggerShearsPlacedInChest(world);
            hadShearsInChest = false;
            if (shouldContinueCountdown) {
                startShearCountdown(world, "shears insertion complete");
            } else {
                shearCountdownActive = false;
            }
        }

        if (!shouldContinueCountdown) {
            nextChestShearTriggerTime = 0L;
            shearCountdownTotalTicks = 0L;
            shearCountdownStartTime = 0L;
            lastShearCountdownLogStep = 0;
            shearCountdownActive = false;
        }

        lastShearsInChestCount = shearsInChestCount;
    }

    private void startShearCountdown(ServerWorld world, String reason) {
        shearCountdownTotalTicks = nextRandomCheckInterval();
        shearCountdownStartTime = world.getTime();
        nextChestShearTriggerTime = shearCountdownStartTime + shearCountdownTotalTicks;
        lastShearCountdownLogStep = 0;
        shearCountdownActive = true;
        LOGGER.info("Shepherd {} shears countdown started ({} ticks) {}",
                villager.getUuidAsString(),
                shearCountdownTotalTicks,
                reason);
    }

    private void logShearCountdownProgress(ServerWorld world, long triggerTime) {
        if (shearCountdownTotalTicks <= 0L) {
            return;
        }
        long remainingTicks = triggerTime - world.getTime();
        long elapsedTicks = world.getTime() - shearCountdownStartTime;
        int step = Math.min(4, (int) ((elapsedTicks * 4L) / shearCountdownTotalTicks));
        if (step <= lastShearCountdownLogStep || step == 0) {
            return;
        }
        lastShearCountdownLogStep = step;
        int percent = step * 25;
        LOGGER.info("Shepherd {} shears countdown {}% ({} ticks remaining)",
                villager.getUuidAsString(),
                percent,
                Math.max(remainingTicks, 0L));
    }

    private long nextRandomCheckInterval() {
        return SHEAR_CHECK_INTERVAL_MIN_TICKS
                + villager.getRandom().nextInt(SHEAR_CHECK_INTERVAL_MAX_TICKS - SHEAR_CHECK_INTERVAL_MIN_TICKS + 1);
    }

    private void scheduleNextShearCheck(ServerWorld world, String reason) {
        long interval = nextRandomCheckInterval();
        nextCheckTime = world.getTime() + interval;
        LOGGER.info("Shepherd {} scheduled next shearing session in {} ticks ({} min) {}",
                villager.getUuidAsString(),
                interval,
                String.format("%.2f", interval / 1200.0D),
                reason);
    }

    private Item woolFromColor(DyeColor color) {
        return switch (color) {
            case ORANGE -> Items.ORANGE_WOOL;
            case MAGENTA -> Items.MAGENTA_WOOL;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_WOOL;
            case YELLOW -> Items.YELLOW_WOOL;
            case LIME -> Items.LIME_WOOL;
            case PINK -> Items.PINK_WOOL;
            case GRAY -> Items.GRAY_WOOL;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_WOOL;
            case CYAN -> Items.CYAN_WOOL;
            case PURPLE -> Items.PURPLE_WOOL;
            case BLUE -> Items.BLUE_WOOL;
            case BROWN -> Items.BROWN_WOOL;
            case GREEN -> Items.GREEN_WOOL;
            case RED -> Items.RED_WOOL;
            case BLACK -> Items.BLACK_WOOL;
            default -> Items.WHITE_WOOL;
        };
    }

    private List<SheepEntity> findBackupSheepTargets(ServerWorld world) {
        Box box = new Box(villager.getBlockPos()).expand(SHEEP_SCAN_RANGE);
        List<SheepEntity> candidates = new ArrayList<>(world.getEntitiesByClass(
                SheepEntity.class,
                box,
                entity -> entity.isAlive() && entity.isShearable()));

        if (candidates.isEmpty()) {
            return List.of();
        }

        candidates.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(villager)));
        int desiredTargetCount = BACKUP_SHEAR_MIN_TARGETS
                + villager.getRandom().nextInt(BACKUP_SHEAR_MAX_TARGETS - BACKUP_SHEAR_MIN_TARGETS + 1);
        java.util.Collections.shuffle(candidates, new java.util.Random(villager.getRandom().nextLong()));
        int selectedCount = Math.min(desiredTargetCount, candidates.size());
        List<SheepEntity> selected = new ArrayList<>(candidates.subList(0, selectedCount));
        selected.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(villager)));
        LOGGER.info("Shepherd {} backup shearing selected {} target(s) (requested {}, available {})",
                villager.getUuidAsString(),
                selected.size(),
                desiredTargetCount,
                candidates.size());
        return selected;
    }


    private void logGateCandidate(BlockPos gatePos, String reason) {
        LOGGER.info("Shepherd {} gate candidate {} rejected: {}", villager.getUuidAsString(), gatePos.toShortString(), reason);
    }

    private BlockPos findNearestPenTarget(ServerWorld world) {
        long now = world.getTime();

        BlockPos villagerPos = villager.getBlockPos();
        int minY = getLocalMinY(world, villagerPos);
        int maxY = getLocalMaxY(world, villagerPos);
        List<BlockPos> gateCandidates = collectFallbackGateCandidates(world, villagerPos, minY, maxY);
        LOGGER.info("Shepherd {} evaluating {} gate candidate(s) within {} blocks", villager.getUuidAsString(), gateCandidates.size(), PEN_SCAN_RANGE);

        BlockPos nearestGate = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos gatePos : gateCandidates) {
            BlockState state = world.getBlockState(gatePos);
            if (!(state.getBlock() instanceof FenceGateBlock)) {
                logGateCandidate(gatePos, "not a fence gate block state at evaluation time");
                continue;
            }

            BlockPos insidePos = getPenInterior(world, gatePos, state);
            if (insidePos == null) {
                logGateCandidate(gatePos, "could not resolve enclosed interior from either gate side");
                continue;
            }

            if (!isInsideFencePen(world, insidePos)) {
                logGateCandidate(gatePos, "interior candidate " + insidePos.toShortString() + " is not fully enclosed by fence");
                continue;
            }

            BlockPos penCenter = getPenCenter(world, insidePos);
            if (penCenter == null) {
                logGateCandidate(gatePos, "failed to compute pen center from interior " + insidePos.toShortString());
                continue;
            }

            if (hasBannerInPen(world, penCenter)) {
                logGateCandidate(gatePos, "pen already contains banner near center " + penCenter.toShortString());
                continue;
            }

            double distance = villager.squaredDistanceTo(gatePos.getX() + 0.5D, gatePos.getY() + 0.5D, gatePos.getZ() + 0.5D);
            LOGGER.info("Shepherd {} gate candidate {} accepted (center {}, distanceSq={})",
                    villager.getUuidAsString(),
                    gatePos.toShortString(),
                    penCenter.toShortString(),
                    String.format("%.2f", distance));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestGate = gatePos.toImmutable();
            }
        }

        if (nearestGate == null) {
            LOGGER.info("Shepherd {} found no valid gate candidates after evaluating {} gate(s)", villager.getUuidAsString(), gateCandidates.size());
        } else {
            LOGGER.info("Shepherd {} selected gate {} as nearest valid pen entry", villager.getUuidAsString(), nearestGate.toShortString());
        }

        nearestPenCacheTick = now;
        cachedNearestPenTarget = nearestGate;
        cachedNearestPenGatePos = nearestGate;
        penGatePos = nearestGate;
        return nearestGate;
    }

    private List<BlockPos> collectFallbackGateCandidates(ServerWorld world, BlockPos villagerPos, int minY, int maxY) {
        List<BlockPos> searchAnchors = new ArrayList<>();
        searchAnchors.add(villagerPos);
        if (jobPos != null && !jobPos.equals(villagerPos)) {
            searchAnchors.add(jobPos);
        }
        if (chestPos != null && !chestPos.equals(villagerPos) && !chestPos.equals(jobPos)) {
            searchAnchors.add(chestPos);
        }
        return collectNearbyGateCandidates(world, villagerPos, searchAnchors, PEN_SCAN_RANGE, minY, maxY, PEN_GATE_CHECK_LIMIT);
    }

    private BlockPos getPenInterior(ServerWorld world, BlockPos gatePos, BlockState state) {
        if (!state.contains(FenceGateBlock.FACING)) {
            return null;
        }

        Direction facing = state.get(FenceGateBlock.FACING);
        BlockPos front = gatePos.offset(facing);
        BlockPos back = gatePos.offset(facing.getOpposite());

        BlockPos frontInterior = findInteriorFromGateSide(world, front, gatePos);
        if (frontInterior != null) {
            return frontInterior;
        }

        return findInteriorFromGateSide(world, back, gatePos);
    }

    private BlockPos findInteriorFromGateSide(ServerWorld world, BlockPos startPos, BlockPos gatePos) {
        if (isInsideFencePen(world, startPos)) {
            return startPos.toImmutable();
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        LinkedHashSet<BlockPos> visited = new LinkedHashSet<>();
        queue.add(startPos.toImmutable());
        visited.add(startPos.toImmutable());

        int maxNodes = 256;
        int maxDistance = 8;

        while (!queue.isEmpty() && visited.size() <= maxNodes) {
            BlockPos current = queue.poll();
            if (current == null) {
                continue;
            }

            if (isInsideFencePen(world, current)) {
                return current.toImmutable();
            }

            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos next = current.offset(direction);
                if (visited.contains(next)) {
                    continue;
                }
                if (!gatePos.isWithinDistance(next, maxDistance)) {
                    continue;
                }
                if (!isPenWalkable(world, next)) {
                    continue;
                }
                visited.add(next.toImmutable());
                queue.add(next.toImmutable());
            }
        }

        return null;
    }

    private boolean isPenWalkable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
            return false;
        }
        return state.isAir();
    }

    private boolean hasBannerInPen(ServerWorld world, BlockPos penPos) {
        BlockPos center = getPenCenter(world, penPos);
        if (center == null) {
            return false;
        }
        int scanRange = 2;
        BlockPos start = center.add(-scanRange, -1, -scanRange);
        BlockPos end = center.add(scanRange, 1, scanRange);
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.BANNERS)) {
                continue;
            }
            if (isInsideFencePen(world, pos)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos getPenCenter(ServerWorld world, BlockPos insidePos) {
        BlockPos westFence = findFenceInDirection(world, insidePos, Direction.WEST, PEN_FENCE_RANGE);
        BlockPos eastFence = findFenceInDirection(world, insidePos, Direction.EAST, PEN_FENCE_RANGE);
        BlockPos northFence = findFenceInDirection(world, insidePos, Direction.NORTH, PEN_FENCE_RANGE);
        BlockPos southFence = findFenceInDirection(world, insidePos, Direction.SOUTH, PEN_FENCE_RANGE);
        if (westFence == null || eastFence == null || northFence == null || southFence == null) {
            return null;
        }

        int minX = westFence.getX() + 1;
        int maxX = eastFence.getX() - 1;
        int minZ = northFence.getZ() + 1;
        int maxZ = southFence.getZ() - 1;
        if (minX > maxX || minZ > maxZ) {
            return null;
        }

        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        return new BlockPos(centerX, insidePos.getY(), centerZ);
    }

    private BlockPos findFenceInDirection(ServerWorld world, BlockPos start, Direction direction, int maxDistance) {
        for (int i = 1; i <= maxDistance; i++) {
            BlockPos pos = start.offset(direction, i);
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
                return pos;
            }
        }
        return null;
    }

    private boolean isInsideFencePen(ServerWorld world, BlockPos pos) {
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (!hasFenceInDirection(world, pos, direction, PEN_FENCE_RANGE)) {
                return false;
            }
        }
        return true;
    }

    private boolean isInsideSpecificPen(ServerWorld world, BlockPos pos, BlockPos expectedPenCenter) {
        if (expectedPenCenter == null || !isInsideFencePen(world, pos)) {
            return false;
        }
        BlockPos detectedCenter = getPenCenter(world, pos);
        return detectedCenter != null && detectedCenter.getSquaredDistance(expectedPenCenter) <= 9.0D;
    }

    private boolean hasFenceInDirection(ServerWorld world, BlockPos start, Direction direction, int maxDistance) {
        for (int i = 1; i <= maxDistance; i++) {
            BlockPos pos = start.offset(direction, i);
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
                return true;
            }
        }
        return false;
    }

    private BlockPos placeBannerInPen(ServerWorld world, BlockPos centerPos) {
        if (!carriedItem.isIn(ItemTags.BANNERS)) {
            return null;
        }
        if (!(carriedItem.getItem() instanceof BlockItem blockItem)) {
            return null;
        }

        BlockPos placed = tryPlaceBanner(world, centerPos, blockItem);
        if (placed == null) {
            placed = tryPlaceBanner(world, centerPos.up(), blockItem);
        }
        if (placed != null && consumeBannerFromInventory(carriedItem)) {
            carriedItem.decrement(1);
            if (carriedItem.isEmpty()) {
                carriedItem = ItemStack.EMPTY;
            }
        }
        return placed;
    }

    private BlockPos tryPlaceBanner(ServerWorld world, BlockPos pos, BlockItem blockItem) {
        if (!world.getBlockState(pos).isAir()) {
            return null;
        }
        BlockState bannerState = blockItem.getBlock().getDefaultState();
        if (!bannerState.canPlaceAt(world, pos)) {
            return null;
        }
        if (world.setBlockState(pos, bannerState)) {
            world.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
            return pos;
        }
        return null;
    }

    private boolean consumeBannerFromInventory(ItemStack bannerStack) {
        if (bannerStack.isEmpty()) {
            return false;
        }
        ItemStack mainHand = villager.getMainHandStack();
        if (!mainHand.isEmpty()
                && mainHand.isIn(ItemTags.BANNERS)
                && ItemStack.areItemsAndComponentsEqual(mainHand, bannerStack)) {
            mainHand.decrement(1);
            if (mainHand.isEmpty()) {
                villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
            }
            return true;
        }
        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.BANNERS)) {
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(stack, bannerStack)) {
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

    private void triggerBannerPairing(ServerWorld world, BlockPos bannerPos) {
        BlockState bannerState = world.getBlockState(bannerPos);
        JobBlockPairingHelper.handleBannerPlacement(world, bannerPos, bannerState);
    }

    private void retryShearsExitPen(ServerWorld world) {
        if (penGatePos == null) {
            return;
        }
        villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        shearsGateInsideTarget = resolveInsideGateTarget(world, penGatePos, currentShearPenCenter);
        shearsGateOutsideTarget = resolveOutsideGateTarget(world, penGatePos, currentShearPenCenter);
        shearsGateCloseDistanceSquared = calculateGateCloseDistanceSquared(penGatePos, currentShearPenCenter);
        openGate(world, penGatePos, true);
        BlockPos retryTarget = shearsGateOutsideTarget == null ? penGatePos : shearsGateOutsideTarget;
        LOGGER.info("Shepherd {} was inside pen for {} ticks while exiting; retrying leave-pen logic via {}",
                villager.getUuidAsString(),
                SHEARS_EXIT_PEN_RETRY_TICKS,
                retryTarget.toShortString());
        moveTo(retryTarget, FAST_GATE_CLOSE_SPEED);
    }

    private void retryGatherExitPen(ServerWorld world) {
        if (penGatePos == null) {
            return;
        }
        villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        gatherExitTarget = resolveOutsideGateTarget(world, penGatePos, penTarget);
        openGate(world, penGatePos, true);
        BlockPos retryTarget = gatherExitTarget == null ? penGatePos : gatherExitTarget;
        LOGGER.info("Shepherd {} remained inside gather pen for {} ticks; retrying exit via {}",
                villager.getUuidAsString(),
                GATHER_EXIT_PEN_RETRY_TICKS,
                retryTarget.toShortString());
        moveTo(retryTarget, FAST_GATE_CLOSE_SPEED);
    }

    private void monitorShearStageProgress(ServerWorld world) {
        // Intentionally disabled for shears while using the simplified per-pen chest handoff loop.
    }

    private void updatePenGateAccess(ServerWorld world, BlockPos gatePos) {
        if (taskType == TaskType.WHEAT_GATHER) {
            return;
        }
        if (taskType == TaskType.SHEARS && stage == Stage.RETURN_TO_CHEST) {
            return;
        }
        if (gatePos == null) {
            return;
        }
        BlockState state = world.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return;
        }

        boolean isOpen = state.get(FenceGateBlock.OPEN);
        boolean isNearGate = villager.squaredDistanceTo(gatePos.getX() + 0.5D, gatePos.getY() + 0.5D, gatePos.getZ() + 0.5D) <= GATE_INTERACT_RANGE_SQUARED;
        boolean isInsidePen;
        if (taskType == TaskType.SHEARS) {
            isInsidePen = currentShearPenCenter != null
                    && isInsideSpecificPen(world, villager.getBlockPos(), currentShearPenCenter);
        } else {
            isInsidePen = penTarget != null && isInsideFencePen(world, villager.getBlockPos());
        }

        if (isNearGate && (!isOpen || !openedPenGate)) {
            openGate(world, gatePos, true);
            openedPenGate = true;
            wasInsidePen = isInsidePen;
            return;
        }

        if (openedPenGate && isOpen && !isInsidePen && stage == Stage.RETURN_TO_CHEST) {
            openGate(world, gatePos, false);
            openedPenGate = false;
        } else if (openedPenGate && isOpen && wasInsidePen && !isInsidePen && isNearGate) {
            openGate(world, gatePos, false);
            openedPenGate = false;
        }
        wasInsidePen = isInsidePen;
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

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        return Optional.ofNullable(inventory);
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

    private void moveTo(BlockPos target) {
        moveTo(target, MOVE_SPEED);
    }

    private void moveTo(BlockPos target, double speed) {
        if (target == null) {
            return;
        }

        long currentTick = villager.getWorld().getTime();
        boolean shouldRequestPath = !target.equals(currentNavigationTarget)
                || villager.getNavigation().isIdle()
                || currentTick - lastPathRequestTick >= PATH_RETRY_INTERVAL_TICKS;
        if (!shouldRequestPath) {
            return;
        }

        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, speed);
        currentNavigationTarget = target.toImmutable();
        lastPathRequestTick = currentTick;
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private double calculateGateCloseDistanceSquared(BlockPos gatePos, BlockPos penCenter) {
        if (gatePos == null || penCenter == null) {
            return 0.0D;
        }
        double gateToPenDistance = Math.sqrt(gatePos.getSquaredDistance(penCenter));
        double closeDistance = Math.min(2.0D, gateToPenDistance);
        return closeDistance * closeDistance;
    }

    private BlockPos resolveInsideGateTarget(ServerWorld world, BlockPos gatePos, BlockPos expectedPenCenter) {
        BlockState state = world.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock) || !state.contains(FenceGateBlock.FACING)) {
            return gatePos;
        }
        Direction facing = state.get(FenceGateBlock.FACING);
        BlockPos front = gatePos.offset(facing);
        BlockPos back = gatePos.offset(facing.getOpposite());
        boolean frontInside = isInsideSpecificPen(world, front, expectedPenCenter);
        boolean backInside = isInsideSpecificPen(world, back, expectedPenCenter);
        if (frontInside && !backInside) {
            return front.toImmutable();
        }
        if (backInside && !frontInside) {
            return back.toImmutable();
        }
        return gatePos;
    }

    private BlockPos resolveOutsideGateTarget(ServerWorld world, BlockPos gatePos, BlockPos expectedPenCenter) {
        BlockState state = world.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock) || !state.contains(FenceGateBlock.FACING)) {
            return gatePos;
        }
        Direction facing = state.get(FenceGateBlock.FACING);
        BlockPos front = gatePos.offset(facing);
        BlockPos back = gatePos.offset(facing.getOpposite());
        boolean frontInside = isInsideSpecificPen(world, front, expectedPenCenter);
        boolean backInside = isInsideSpecificPen(world, back, expectedPenCenter);
        if (frontInside && !backInside) {
            return back.toImmutable();
        }
        if (backInside && !frontInside) {
            return front.toImmutable();
        }
        return gatePos;
    }

    private void ensureGateOpen(ServerWorld world, BlockPos gatePos) {
        if (gatePos == null) {
            return;
        }
        BlockState state = world.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return;
        }
        if (!state.get(FenceGateBlock.OPEN)) {
            openGate(world, gatePos, true);
        }
    }

    private boolean hasGroundBannerNearby(ServerWorld world) {
        return resolveGatherBanner(world) != null;
    }

    private BlockPos resolveGatherBanner(ServerWorld world) {
        // Registry-based: use nearest pen center as gather anchor.
        BlockPos villagerPos = villager.getBlockPos();
        return VillagePenRegistry.get(world.getServer())
                .getNearestPen(world, villagerPos, PEN_SCAN_RANGE * 2)
                .map(VillagePenRegistry.PenEntry::center)
                .orElseGet(() -> findNearestGroundBanner(world));
    }

    private BlockPos findNearestGroundBanner(ServerWorld world) {
        long now = world.getTime();
        if (now - nearestGroundBannerCacheTick <= SPATIAL_SEARCH_CACHE_TTL_TICKS) {
            return cachedNearestGroundBanner;
        }

        BlockPos villagerPos = villager.getBlockPos();
        int minY = getLocalMinY(world, villagerPos);
        int maxY = getLocalMaxY(world, villagerPos);
        List<BlockPos> bannerCandidates = findBannerCandidatesWithinRange(world, villagerPos, PEN_SCAN_RANGE, minY, maxY);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : bannerCandidates) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.BANNERS)) {
                continue;
            }
            double distance = villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = pos.toImmutable();
            }
        }
        nearestGroundBannerCacheTick = now;
        cachedNearestGroundBanner = nearest;
        return nearest;
    }

    private PenTarget findNearestPenWithBanner(ServerWorld world, BlockPos bannerPos) {
        int minY = getLocalMinY(world, bannerPos);
        int maxY = getLocalMaxY(world, bannerPos);
        List<BlockPos> gateCandidates = collectNearbyGateCandidates(world, bannerPos, List.of(bannerPos), PEN_BANNER_TO_GATE_SCAN_RADIUS, minY, maxY, PEN_GATE_CHECK_LIMIT);
        PenTarget nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos gatePos : gateCandidates) {
            BlockState state = world.getBlockState(gatePos);
            if (!(state.getBlock() instanceof FenceGateBlock)) {
                continue;
            }

            BlockPos insidePos = getPenInterior(world, gatePos, state);
            if (insidePos == null || !isInsideFencePen(world, insidePos)) {
                continue;
            }

            BlockPos center = getPenCenter(world, insidePos);
            if (center == null || !hasBannerInPen(world, center)) {
                continue;
            }

            double distanceToBanner = center.getSquaredDistance(bannerPos);
            if (distanceToBanner < nearestDistance) {
                nearestDistance = distanceToBanner;
                nearest = new PenTarget(bannerPos.toImmutable(), center.toImmutable(), gatePos.toImmutable());
            }
        }
        return nearest;
    }

    private List<BlockPos> findBannerCandidatesWithinRange(ServerWorld world, BlockPos center, int range, int minY, int maxY) {
        int chunkRadius = (int) Math.ceil(range / 16.0D);
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        List<BlockPos> banners = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = world.getChunkManager().getChunk(centerChunkX + dx, centerChunkZ + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos pos : chunk.getBlockEntityPositions()) {
                    if (pos.getY() < minY || pos.getY() > maxY || !center.isWithinDistance(pos, range)) {
                        continue;
                    }
                    if (!world.getBlockState(pos).isIn(BlockTags.BANNERS)) {
                        continue;
                    }
                    banners.add(pos.toImmutable());
                }
            }
        }

        banners.sort(Comparator.comparingDouble(center::getSquaredDistance));
        if (banners.size() > PEN_BANNER_CANDIDATE_LIMIT) {
            return new ArrayList<>(banners.subList(0, PEN_BANNER_CANDIDATE_LIMIT));
        }
        return banners;
    }

    private List<BlockPos> collectNearbyGateCandidates(ServerWorld world, BlockPos sortOrigin, List<BlockPos> bannerCandidates, int radius, int minY, int maxY, int limit) {
        LinkedHashSet<BlockPos> gateSet = new LinkedHashSet<>();
        for (BlockPos bannerPos : bannerCandidates) {
            int yStart = Math.max(minY, bannerPos.getY() - PEN_SEARCH_Y_RANGE);
            int yEnd = Math.min(maxY, bannerPos.getY() + PEN_SEARCH_Y_RANGE);
            for (int x = bannerPos.getX() - radius; x <= bannerPos.getX() + radius; x++) {
                for (int z = bannerPos.getZ() - radius; z <= bannerPos.getZ() + radius; z++) {
                    for (int y = yStart; y <= yEnd; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (world.getBlockState(pos).getBlock() instanceof FenceGateBlock) {
                            gateSet.add(pos.toImmutable());
                        }
                    }
                }
            }
        }

        List<BlockPos> gates = new ArrayList<>(gateSet);
        gates.sort(Comparator.comparingDouble(sortOrigin::getSquaredDistance));
        if (gates.size() > limit) {
            return new ArrayList<>(gates.subList(0, limit));
        }
        return gates;
    }

    private int getLocalMinY(ServerWorld world, BlockPos center) {
        return Math.max(world.getBottomY(), center.getY() - PEN_SEARCH_Y_RANGE);
    }

    private int getLocalMaxY(ServerWorld world, BlockPos center) {
        return Math.min(world.getTopY() - 1, center.getY() + PEN_SEARCH_Y_RANGE);
    }

    private BlockPos randomGatherWanderTarget(ServerWorld world, BlockPos origin) {
        int dx = villager.getRandom().nextBetween(-GATHER_RADIUS, GATHER_RADIUS);
        int dz = villager.getRandom().nextBetween(-GATHER_RADIUS, GATHER_RADIUS);
        BlockPos target = origin.add(dx, 0, dz);
        int topY = world.getTopY() - 1;
        int clampedY = Math.max(world.getBottomY(), Math.min(topY, target.getY()));
        return new BlockPos(target.getX(), clampedY, target.getZ());
    }

    private void refreshActiveHerd(ServerWorld world) {
        if (gatherBannerPos == null || !isHoldingGatherWheat()) {
            activeHerd.clear();
            return;
        }

        Box searchBox = new Box(villager.getBlockPos()).expand(HERD_SELECTION_RANGE, 6.0D, HERD_SELECTION_RANGE);
        List<AnimalEntity> nearbyAnimals = world.getEntitiesByClass(AnimalEntity.class, searchBox, this::isGatherTargetAnimal);
        nearbyAnimals.sort(Comparator.comparingDouble(villager::squaredDistanceTo));

        activeHerd.clear();
        int limit = Math.min(ACTIVE_HERD_LIMIT, nearbyAnimals.size());
        for (int i = 0; i < limit; i++) {
            activeHerd.add(nearbyAnimals.get(i));
        }
    }

    private boolean isGatherTargetAnimal(AnimalEntity animal) {
        if (!animal.isAlive() || !animal.canEat()) {
            return false;
        }
        return animal.isInLove() || animal.isBreedingItem(Items.WHEAT.getDefaultStack());
    }

    private boolean isActiveHerdFollowing() {
        if (activeHerd.isEmpty()) {
            return false;
        }
        for (AnimalEntity animal : activeHerd) {
            if (!animal.isAlive()) {
                continue;
            }
            if (animal.squaredDistanceTo(villager) <= HERD_FOLLOW_DISTANCE_SQUARED) {
                return true;
            }
        }
        return false;
    }

    private BlockPos herdAnchoredGuideTarget(BlockPos fallbackTarget) {
        BlockPos herdCentroid = getHerdCentroid();
        if (herdCentroid == null) {
            return fallbackTarget;
        }
        double midX = (herdCentroid.getX() + fallbackTarget.getX()) / 2.0D;
        double midY = (herdCentroid.getY() + fallbackTarget.getY()) / 2.0D;
        double midZ = (herdCentroid.getZ() + fallbackTarget.getZ()) / 2.0D;
        return new BlockPos((int) Math.round(midX), (int) Math.round(midY), (int) Math.round(midZ));
    }

    private BlockPos getHerdAnchoredWanderTarget(ServerWorld world) {
        BlockPos herdCentroid = getHerdCentroid();
        BlockPos wanderOrigin = herdCentroid != null ? herdCentroid : gatherBannerPos;
        return randomGatherWanderTarget(world, wanderOrigin);
    }

    private PenTarget resolveNearestGatherPen(ServerWorld world) {
        // Use VillagePenRegistry first; fall back to banner scan if empty.
        BlockPos villagerPos = villager.getBlockPos();
        List<VillagePenRegistry.PenEntry> registryPens =
                VillagePenRegistry.get(world.getServer()).getNearestBellPens(world, villagerPos, PEN_SCAN_RANGE * 2);

        if (!registryPens.isEmpty()) {
            VillagePenRegistry.PenEntry nearest = registryPens.get(0);
            double nearestDist = villagerPos.getSquaredDistance(nearest.gate());
            for (VillagePenRegistry.PenEntry entry : registryPens) {
                double dist = villagerPos.getSquaredDistance(entry.gate());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = entry;
                }
            }
            return new PenTarget(nearest.center(), nearest.center(), nearest.gate());
        }

        // Legacy fallback: banner-based scan.
        List<PenTarget> pens = findShearPensLegacyBannerScan(world);
        if (pens.isEmpty()) {
            return null;
        }
        pens.sort(Comparator.comparingDouble(pen -> villagerPos.getSquaredDistance(pen.banner())));
        return pens.get(0);
    }

    private List<BlockPos> buildGatherCircleWaypoints(BlockPos center) {
        List<BlockPos> waypoints = new ArrayList<>();
        if (center == null) {
            return waypoints;
        }

        for (int i = 0; i < GATHER_CIRCLE_WAYPOINT_COUNT; i++) {
            double angle = (Math.PI * 2.0D * i) / GATHER_CIRCLE_WAYPOINT_COUNT;
            int x = (int) Math.round(center.getX() + Math.cos(angle) * GATHER_CIRCLE_RADIUS);
            int z = (int) Math.round(center.getZ() + Math.sin(angle) * GATHER_CIRCLE_RADIUS);
            waypoints.add(new BlockPos(x, center.getY(), z));
        }
        return waypoints;
    }

    private int findClosestGatherWaypointIndex(BlockPos from) {
        if (gatherCircleWaypoints.isEmpty()) {
            return 0;
        }
        int nearestIndex = 0;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < gatherCircleWaypoints.size(); i++) {
            double distance = from.getSquaredDistance(gatherCircleWaypoints.get(i));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private boolean isAlignedWithGate(BlockPos center, BlockPos gate, BlockPos actorPos) {
        double gateX = gate.getX() - center.getX();
        double gateZ = gate.getZ() - center.getZ();
        double actorX = actorPos.getX() - center.getX();
        double actorZ = actorPos.getZ() - center.getZ();

        double gateLength = Math.sqrt(gateX * gateX + gateZ * gateZ);
        double actorLength = Math.sqrt(actorX * actorX + actorZ * actorZ);
        if (gateLength <= 0.001D || actorLength <= 0.001D) {
            return false;
        }

        double dot = ((gateX / gateLength) * (actorX / actorLength))
                + ((gateZ / gateLength) * (actorZ / actorLength));
        return dot >= GATHER_GATE_ALIGNMENT_DOT_THRESHOLD;
    }

    private BlockPos getHerdCentroid() {
        if (activeHerd.isEmpty()) {
            return null;
        }

        double sumX = 0.0D;
        double sumY = 0.0D;
        double sumZ = 0.0D;
        int count = 0;
        for (AnimalEntity animal : activeHerd) {
            if (!animal.isAlive()) {
                continue;
            }
            sumX += animal.getX();
            sumY += animal.getY();
            sumZ += animal.getZ();
            count++;
        }

        if (count == 0) {
            return null;
        }

        return BlockPos.ofFloored(sumX / count, sumY / count, sumZ / count);
    }

    private boolean isHerdInsidePen(ServerWorld world, BlockPos penCenter) {
        if (activeHerd.isEmpty()) {
            return false;
        }

        int insideCount = 0;
        int totalCount = 0;
        for (AnimalEntity animal : activeHerd) {
            if (!animal.isAlive()) {
                continue;
            }
            totalCount++;
            BlockPos animalPos = animal.getBlockPos();
            boolean closeToCenter = animalPos.getSquaredDistance(penCenter) <= HERD_PEN_CLOSE_DISTANCE_SQUARED;
            if (closeToCenter && isInsideFencePen(world, animalPos)) {
                insideCount++;
            }
        }

        return totalCount > 0 && insideCount >= Math.max(1, totalCount / 2);
    }

    private long randomGatherSessionDuration() {
        return GATHER_MIN_SESSION_TICKS + villager.getRandom().nextInt(GATHER_MAX_SESSION_TICKS - GATHER_MIN_SESSION_TICKS + 1);
    }

    private void invalidateSpatialSearchCache() {
        nearestPenCacheTick = Long.MIN_VALUE;
        cachedNearestPenTarget = null;
        cachedNearestPenGatePos = null;
        nearestGroundBannerCacheTick = Long.MIN_VALUE;
        cachedNearestGroundBanner = null;
    }

    private enum Stage {
        IDLE,
        GO_TO_SHEEP,
        GO_TO_PEN,
        SHEARS_EXIT_PEN,
        GATHER_CIRCLE,
        GATHER_MOVE_TO_GATE,
        GATHER_MOVE_TO_BANNER,
        GATHER_HOLD_BANNER,
        GATHER_EXIT_AND_CLOSE,
        RETURN_TO_CHEST,
        DONE
    }

    private enum TaskType {
        SHEARS,
        BANNER,
        WHEAT_GATHER
    }

    private record PenTarget(BlockPos banner, BlockPos center, BlockPos gate) {
    }
}
