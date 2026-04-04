package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.CartographerMapChestUtil;
import dev.sterner.guardvillagers.common.util.VillageMappedBoundsState;
import dev.sterner.guardvillagers.common.villager.behavior.CartographerBehavior;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.PointOfInterestTypeTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public class LumberjackGuardChopTreesGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackGuardChopTreesGoal.class);
    private static final int SESSION_TARGET_MIN = 3;
    private static final int SESSION_TARGET_MAX = 5;
    private static final int SESSION_MIN_PLANKS = 11;
    private static final int SESSION_MIN_STICKS = 2;
    private static final int PLANKS_PER_LOG = 4;
    private static final int STICKS_PER_PLANK = 2;
    private static final int CHOP_INTERVAL_MIN_TICKS = 20 * 60 * 3;
    private static final int CHOP_INTERVAL_MAX_TICKS = 20 * 60 * 8;
    private static final int TREE_SEARCH_RADIUS = 20;
    private static final int TREE_SEARCH_EXPANDED_RADIUS = 32;
    private static final int TREE_SEARCH_MAX_EXPANDED_RADIUS = 40;
    private static final int TREE_SEARCH_HEIGHT = 10;
    private static final int MAX_LOGS_PER_TREE = 256;
    private static final int TREE_CROWN_RADIUS = 4;
    private static final int MIN_ROOT_STRUCTURE_LOGS = 4;
    private static final int ROOT_STRUCTURE_MAX_HEIGHT = 12;
    private static final int ROOT_STRUCTURE_HORIZONTAL_RADIUS = 2;
    private static final int ROOT_CANOPY_SEARCH_RADIUS = 3;
    private static final int ROOT_CANOPY_SEARCH_HEIGHT = 8;
    private static final int CARTOGRAPHER_INFLUENCE_RADIUS = 300;
    private static final int MAX_REJECT_REASON_DEBUG_PER_SCAN = 200;
    private static final String REJECTED_NOT_LOG = "rejected_not_log";
    private static final String REJECTED_STRUCTURE_PROXIMITY = "rejected_structure_proximity";
    private static final String REJECTED_BAD_SOIL = "rejected_bad_soil";
    private static final String REJECTED_STILTED_LOG = "rejected_stilted_log";
    private static final String REJECTED_BELL_RADIUS = "rejected_bell_radius";
    private static final String REJECTED_HOUSE_POI_RADIUS = "rejected_house_poi_radius";
    private static final String REJECTED_NO_LEAVES = "rejected_no_leaves";
    private static final String REJECTED_DUPLICATE_ROOT = "rejected_duplicate_root";

    /**
     * Logs within this horizontal radius of a bell block are considered part of a village
     * structure and are never chopped. Village bells sit in the center of build areas;
     * any tree this close to a bell is almost certainly a decoration, not a harvestable tree.
     */
    private static final int BELL_EXCLUSION_RADIUS = 6;
    private static final double ITEM_PICKUP_RADIUS = 2.5D;
    private static final int PATH_STALL_TICKS = 30;
    private static final int MAX_STALL_RECOVERY_ATTEMPTS = 3;
    private static final int MAX_TEARDOWN_RETRY_ATTEMPTS_PER_ROOT = 3;
    private static final int TARGET_STUCK_FALLBACK_TICKS = 20 * 30;
    private static final double STALL_PROGRESS_DELTA_SQ = 0.25D;
    private static final double STALL_JITTER_DELTA_SQ = 0.03D;
    private static final int TREE_SCAN_HIGH_ELAPSED_MS = 12;
    private static final int TREE_SCAN_LOW_ELAPSED_MS = 4;
    private static final float TREE_SCAN_HIGH_ELAPSED_REDUCTION_FACTOR = 0.75F;
    private static final float TREE_SCAN_LOW_ELAPSED_INCREASE_FACTOR = 1.15F;
    private static final int INITIAL_SCAN_ACCEPTED_ROOT_TARGET = SESSION_TARGET_MAX;
    private static final int INITIAL_SCAN_LOCAL_ROOT_TARGET = 1;
    private static final int MAX_REGION_BLOCK_VISITS_PER_PASS = 12000;
    private static final int NO_TREE_ESCALATION_HIGH_WATER_MARK = 5;
    private static final int NO_TREE_ESCALATION_REPEAT_INTERVAL = 3;
    private static final int NO_TREE_ESCALATION_RETRY_DELAY_TICKS = 20 * 60 * 12;
    private static final int TREE_SCAN_METRICS_INFO_ELAPSED_MS = 20;
    private static final int TREE_SCAN_METRICS_INFO_INTERVAL = 5;

    private final LumberjackGuardEntity guard;
    private final Set<BlockPos> completedSessionRoots = new HashSet<>();
    private final Set<BlockPos> failedSessionRoots = new HashSet<>();
    private final Map<BlockPos, Integer> rootTeardownRetryAttempts = new HashMap<>();
    private final Map<BlockPos, Integer> rootFocusTicks = new HashMap<>();
    private double lastTreeDistanceSq = Double.MAX_VALUE;
    private int stalledTicks;
    private int stallRecoveryAttempts;
    private boolean bootstrapRetryTreeScheduled;
    private @Nullable TreeTargetScanSession pendingTreeTargetScan;
    private int adaptiveScanPerGuardBudget = getConfiguredTreeScanPerGuardBudgetCap();
    private long lastObservedScanElapsedMs;
    private int completedTreeScanCount;

    public LumberjackGuardChopTreesGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
    }

    @Override
    public boolean canStart() {
        return this.guard.getWorld() instanceof ServerWorld
                && this.guard.isAlive()
                && this.guard.getPairedCraftingTablePos() != null;
    }

    @Override
    public boolean shouldContinue() {
        return canStart();
    }

    @Override
    public void tick() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return;
        }

        if (!this.guard.isActiveSession()) {
            updateChopCountdown(world);
            return;
        }

        if (this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.RETURNING_TO_BASE) {
            moveBackToBaseAndHandoff();
            return;
        }

        if (this.guard.getSessionTargetsRemaining() <= 0 || this.guard.getSelectedTreeTargets().isEmpty()) {
            LOGGER.info("Lumberjack Guard {} targets exhausted; returning to base for handoff",
                    this.guard.getUuidAsString());
            beginReturnToBase();
            return;
        }

        BlockPos targetRoot = this.guard.getSelectedTreeTargets().get(0);
        if (!isEligibleLog(world, targetRoot)) {
            BlockPos replacementRoot = resolveReplacementRoot(world, targetRoot);
            if (replacementRoot != null) {
                LOGGER.debug("Lumberjack Guard {} replacing invalid root {} with {}",
                        this.guard.getUuidAsString(),
                        targetRoot,
                        replacementRoot);
                this.guard.getSelectedTreeTargets().set(0, replacementRoot);
                targetRoot = replacementRoot;
            } else {
                recordFailedTargetAttempt(targetRoot, "invalid root and no replacement");
                this.guard.getSelectedTreeTargets().remove(0);
                return;
            }
        }

        if (!isEligibleLog(world, targetRoot)) {
            recordFailedTargetAttempt(targetRoot, "replacement root became invalid");
            this.guard.getSelectedTreeTargets().remove(0);
            return;
        }

        int focusedTicks = updateAndGetFocusedRootTicks(targetRoot);
        if (focusedTicks >= TARGET_STUCK_FALLBACK_TICKS) {
            LOGGER.warn("Lumberjack Guard {} target {} exceeded focus timeout ({} ticks); forcing full tree removal",
                    this.guard.getUuidAsString(),
                    targetRoot,
                    focusedTicks);
            executeForcedTreeRemoval(world, targetRoot, "focus timeout");
            return;
        }

        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_TREE);
        double treeDistanceSq = this.guard.squaredDistanceTo(Vec3d.ofCenter(targetRoot));
        if (treeDistanceSq > 6.25D) {
            this.guard.getNavigation().startMovingTo(targetRoot.getX() + 0.5D, targetRoot.getY(), targetRoot.getZ() + 0.5D, 0.8D);
            if (!updateStallState(world, targetRoot, treeDistanceSq)) {
                recordFailedTargetAttempt(targetRoot, "stalled while pathing");
                this.guard.getSelectedTreeTargets().remove(0);
            }
            return;
        }

        this.stalledTicks = 0;
        this.stallRecoveryAttempts = 0;
        this.lastTreeDistanceSq = treeDistanceSq;

        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.CHOPPING);
        TreeTeardownResult teardownResult = teardownTree(world, targetRoot);
        RemainingLogCountResult remainingLogCountResult = countRemainingConnectedLogs(world, targetRoot);
        int remainingLogs = remainingLogCountResult.count();
        collectNearbyWoodDrops(world);

        if (remainingLogs > 0) {
            int retryAttempt = this.rootTeardownRetryAttempts.getOrDefault(targetRoot, 0) + 1;
            this.rootTeardownRetryAttempts.put(targetRoot.toImmutable(), retryAttempt);
            if (retryAttempt >= MAX_TEARDOWN_RETRY_ATTEMPTS_PER_ROOT) {
                LOGGER.warn("Lumberjack Guard {} tree_teardown_retry_exhausted root={} attempts={} initialCandidates={} brokenLogs={} remainingLogs={}; forcing full tree removal",
                        this.guard.getUuidAsString(),
                        targetRoot,
                        retryAttempt,
                        teardownResult.initialCandidateLogs(),
                        teardownResult.brokenLogs(),
                        remainingLogs);
                executeForcedTreeRemoval(world, targetRoot, "teardown retries exhausted");
            } else {
                LOGGER.warn("Lumberjack Guard {} tree_teardown_retry_scheduled root={} attempt={} maxAttempts={} initialCandidates={} brokenLogs={} remainingLogs={}",
                        this.guard.getUuidAsString(),
                        targetRoot,
                        retryAttempt,
                        MAX_TEARDOWN_RETRY_ATTEMPTS_PER_ROOT,
                        teardownResult.initialCandidateLogs(),
                        teardownResult.brokenLogs(),
                        remainingLogs);
            }
            return;
        }
        if (isLumberjackVerboseLoggingEnabled()) {
            LOGGER.debug("Lumberjack Guard {} tree_teardown_verification root={} initialCandidates={} brokenLogs={} remainingLogs={} fallbackSeedUsed={}",
                    this.guard.getUuidAsString(),
                    targetRoot,
                    teardownResult.initialCandidateLogs(),
                    teardownResult.brokenLogs(),
                    remainingLogs,
                    remainingLogCountResult.fallbackSeedUsed());
        }

        this.rootTeardownRetryAttempts.remove(targetRoot);
        this.completedSessionRoots.add(targetRoot.toImmutable());
        this.guard.getSelectedTreeTargets().remove(0);
        this.guard.setSessionTargetsRemaining(this.guard.getSessionTargetsRemaining() - 1);

        if (this.guard.getSessionTargetsRemaining() <= 0 || this.guard.getSelectedTreeTargets().isEmpty()) {
            if (this.bootstrapRetryTreeScheduled) {
                LOGGER.info("Lumberjack Guard {} completed bootstrap retry tree; returning to base for crafting retry",
                        this.guard.getUuidAsString());
                beginReturnToBase();
            } else if (!extendSessionIfMaterialThresholdUnmet(world)) {
                beginReturnToBase();
            }
        }
    }

    private boolean extendSessionIfMaterialThresholdUnmet(ServerWorld world) {
        Inventory chestInventory = resolveChestInventory(world);
        int plankCount = countByPredicate(chestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countByPredicate(this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS));
        int stickCount = countByItem(chestInventory, Items.STICK)
                + countByItem(this.guard.getGatheredStackBuffer(), Items.STICK);
        int logCount = countByPredicate(chestInventory, stack -> stack.isIn(ItemTags.LOGS))
                + countByPredicate(this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.LOGS));

        int effectivePlankCount = plankCount + (logCount * PLANKS_PER_LOG);
        int convertiblePlanksToSticks = Math.max(0, effectivePlankCount - SESSION_MIN_PLANKS);
        int effectiveStickCount = stickCount + (convertiblePlanksToSticks * STICKS_PER_PLANK);

        if (effectivePlankCount >= SESSION_MIN_PLANKS && effectiveStickCount >= SESSION_MIN_STICKS) {
            return false;
        }

        Set<BlockPos> excludedRoots = new HashSet<>(this.completedSessionRoots);
        excludedRoots.addAll(this.failedSessionRoots);
        excludedRoots.addAll(this.guard.getSelectedTreeTargets());

        List<BlockPos> candidates = findTreeTargets(world);
        for (BlockPos candidate : candidates) {
            if (excludedRoots.contains(candidate)) {
                continue;
            }
            this.guard.getSelectedTreeTargets().add(candidate);
            this.guard.setSessionTargetsRemaining(this.guard.getSessionTargetsRemaining() + 1);
            this.bootstrapRetryTreeScheduled = true;
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_TREE);
            this.stalledTicks = 0;
            this.stallRecoveryAttempts = 0;
            this.lastTreeDistanceSq = Double.MAX_VALUE;

            LOGGER.info("Lumberjack Guard {} below material minimum (raw: logs {}, planks {}, sticks {}; effective: planks {}, sticks {}); scheduled one retry tree {} before returning to base",
                    this.guard.getUuidAsString(),
                    logCount,
                    plankCount,
                    stickCount,
                    effectivePlankCount,
                    effectiveStickCount,
                    candidate);
            return true;
        }

        LOGGER.info("Lumberjack Guard {} did not meet material minimum (raw: logs {}, planks {}, sticks {}; effective: planks {}, sticks {}), but no retry tree target was found",
                this.guard.getUuidAsString(),
                logCount,
                plankCount,
                stickCount,
                effectivePlankCount,
                effectiveStickCount);
        return false;
    }

    private void updateChopCountdown(ServerWorld world) {
        if (this.pendingTreeTargetScan != null) {
            continueOrStartTreeTargetScan(world);
            return;
        }

        if (!this.guard.isChopCountdownActive()) {
            startChopCountdown(world, "initial schedule");
        }

        if (this.guard.isChopCountdownActive()) {
            logCountdownProgress(world);
        }

        if (this.guard.isChopCountdownActive() && world.getTime() >= this.guard.getNextChopTick()) {
            this.guard.clearChopCountdown();
            continueOrStartTreeTargetScan(world);
        }
    }

    private void continueOrStartTreeTargetScan(ServerWorld world) {
        if (this.pendingTreeTargetScan == null) {
            this.pendingTreeTargetScan = createTreeTargetScanSession(world);
            this.adaptiveScanPerGuardBudget = getConfiguredTreeScanPerGuardBudgetCap();
            this.lastObservedScanElapsedMs = 0L;
        }

        int perGuardCap = Math.max(1, Math.min(this.adaptiveScanPerGuardBudget, getConfiguredTreeScanPerGuardBudgetCap()));
        int worldSharedRemaining = WorldScanBudgetManager.remaining(world);
        int grantedBudget = Math.min(perGuardCap, worldSharedRemaining);
        if (grantedBudget <= 0) {
            return;
        }

        this.pendingTreeTargetScan.scanNextBudget(world, grantedBudget,
                (scanWorld, pos, bells, roots, qualificationContext, metrics) -> tryAddQualifiedRoot(scanWorld, pos, bells, roots, qualificationContext, metrics));
        WorldScanBudgetManager.consume(world, grantedBudget);
        adaptPerGuardScanBudgetFromElapsed(this.pendingTreeTargetScan.metricsElapsedMs());

        if (!this.pendingTreeTargetScan.complete()) {
            return;
        }

        List<BlockPos> targets = this.pendingTreeTargetScan.sortedRoots();
        this.completedTreeScanCount++;
        boolean logScanMetricsAtInfo = shouldLogScanMetricsAtInfo(this.pendingTreeTargetScan)
                || this.completedTreeScanCount % TREE_SCAN_METRICS_INFO_INTERVAL == 0;
        this.pendingTreeTargetScan.logMetrics(this.guard.getUuidAsString(), logScanMetricsAtInfo, isLumberjackVerboseLoggingEnabled());
        this.pendingTreeTargetScan = null;
        startSession(world, targets);
    }

    private void adaptPerGuardScanBudgetFromElapsed(long cumulativeElapsedMs) {
        long elapsedThisPass = Math.max(0L, cumulativeElapsedMs - this.lastObservedScanElapsedMs);
        this.lastObservedScanElapsedMs = cumulativeElapsedMs;
        int cap = getConfiguredTreeScanPerGuardBudgetCap();
        int min = getConfiguredTreeScanPerGuardBudgetFloor(cap);

        if (elapsedThisPass >= TREE_SCAN_HIGH_ELAPSED_MS) {
            this.adaptiveScanPerGuardBudget = Math.max(min,
                    Math.round(this.adaptiveScanPerGuardBudget * TREE_SCAN_HIGH_ELAPSED_REDUCTION_FACTOR));
            return;
        }
        if (elapsedThisPass <= TREE_SCAN_LOW_ELAPSED_MS) {
            this.adaptiveScanPerGuardBudget = Math.min(cap,
                    Math.max(min + 1, Math.round(this.adaptiveScanPerGuardBudget * TREE_SCAN_LOW_ELAPSED_INCREASE_FACTOR)));
        }
    }

    private void startSession(ServerWorld world, List<BlockPos> targets) {
        if (targets.isEmpty()) {
            int noTreeFailures = this.guard.getConsecutiveNoTreeSessions() + 1;
            this.guard.setConsecutiveNoTreeSessions(noTreeFailures);
            boolean escalated = maybeRunNoTreeEscalation(world, noTreeFailures);

            LOGGER.info("Lumberjack Guard {} could not find eligible trees near paired base (failures={}); rescheduling countdown",
                    this.guard.getUuidAsString(),
                    noTreeFailures);
            if (escalated) {
                startChopCountdown(world, NO_TREE_ESCALATION_RETRY_DELAY_TICKS,
                        "no eligible tree targets (no-tree-escalation delayed retry)");
                return;
            }
            startChopCountdown(world, "no eligible tree targets");
            return;
        }
        this.guard.setConsecutiveNoTreeSessions(0);

        int sessionCap = MathHelper.nextInt(this.guard.getRandom(), SESSION_TARGET_MIN, SESSION_TARGET_MAX);
        int selectedCount = Math.min(sessionCap, targets.size());

        this.guard.getSelectedTreeTargets().clear();
        this.guard.getSelectedTreeTargets().addAll(targets.subList(0, selectedCount));
        this.guard.setSessionTargetsRemaining(selectedCount);
        this.completedSessionRoots.clear();
        this.failedSessionRoots.clear();
        this.rootTeardownRetryAttempts.clear();
        this.rootFocusTicks.clear();
        this.bootstrapRetryTreeScheduled = false;
        this.pendingTreeTargetScan = null;
        this.guard.setActiveSession(true);
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_TREE);

        LOGGER.info("Lumberjack Guard {} starting chop session with {} target(s)",
                this.guard.getUuidAsString(),
                selectedCount);
    }

    private void beginReturnToBase() {
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.RETURNING_TO_BASE);
        this.stalledTicks = 0;
        this.stallRecoveryAttempts = 0;
        this.lastTreeDistanceSq = Double.MAX_VALUE;
    }

    private void moveBackToBaseAndHandoff() {
        BlockPos table = this.guard.getPairedCraftingTablePos();
        BlockPos chest = this.guard.getPairedChestPos();
        if (table == null) {
            completeSession("pairing lost");
            return;
        }

        BlockPos baseTarget = chest != null ? chest : table;
        if (this.guard.squaredDistanceTo(Vec3d.ofCenter(baseTarget)) > 9.0D) {
            this.guard.getNavigation().startMovingTo(baseTarget.getX() + 0.5D, baseTarget.getY(), baseTarget.getZ() + 0.5D, 0.8D);
            return;
        }

        completeSession("session complete");
    }

    private void completeSession(String reason) {
        this.guard.getNavigation().stop();
        this.guard.setActiveSession(false);
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.CRAFTING);
        this.guard.getSelectedTreeTargets().clear();
        this.guard.setSessionTargetsRemaining(0);
        this.completedSessionRoots.clear();
        this.failedSessionRoots.clear();
        this.rootTeardownRetryAttempts.clear();
        this.rootFocusTicks.clear();
        this.bootstrapRetryTreeScheduled = false;
        this.pendingTreeTargetScan = null;
        startChopCountdown((ServerWorld) this.guard.getWorld(), reason);
        this.stalledTicks = 0;
        this.stallRecoveryAttempts = 0;
        this.lastTreeDistanceSq = Double.MAX_VALUE;
        if ("session complete".equals(reason)) {
            if (isLumberjackVerboseLoggingEnabled()) {
                LOGGER.debug("Lumberjack Guard {} {} (buffer stacks: {})",
                        this.guard.getUuidAsString(),
                        reason,
                        this.guard.getGatheredStackBuffer().size());
            }
        } else {
            LOGGER.info("Lumberjack Guard {} {} (buffer stacks: {})",
                    this.guard.getUuidAsString(),
                    reason,
                    this.guard.getGatheredStackBuffer().size());
        }
    }

    private boolean updateStallState(ServerWorld world, BlockPos targetRoot, double treeDistanceSq) {
        if (treeDistanceSq + STALL_PROGRESS_DELTA_SQ < this.lastTreeDistanceSq) {
            LOGGER.debug("Lumberjack Guard {} target {} progress recovered (distanceSq {} -> {})",
                    this.guard.getUuidAsString(),
                    targetRoot,
                    this.lastTreeDistanceSq,
                    treeDistanceSq);
            this.stalledTicks = 0;
            this.stallRecoveryAttempts = 0;
            this.lastTreeDistanceSq = treeDistanceSq;
            return true;
        }

        double delta = Math.abs(this.lastTreeDistanceSq - treeDistanceSq);
        if (delta <= STALL_JITTER_DELTA_SQ) {
            LOGGER.debug("Lumberjack Guard {} target {} jitter detected (deltaSq {}, stalledTicks {})",
                    this.guard.getUuidAsString(),
                    targetRoot,
                    delta,
                    this.stalledTicks);
        } else {
            this.stalledTicks++;
        }

        this.lastTreeDistanceSq = treeDistanceSq;

        if (this.stalledTicks >= PATH_STALL_TICKS || this.guard.getNavigation().isIdle()) {
            LOGGER.debug("Lumberjack Guard {} target {} entering stall recovery (stalledTicks {}, recoveries {})",
                    this.guard.getUuidAsString(),
                    targetRoot,
                    this.stalledTicks,
                    this.stallRecoveryAttempts);
            boolean cleared = clearAxeBreakableObstruction(world, targetRoot);
            if (cleared) {
                LOGGER.debug("Lumberjack Guard {} target {} cleared obstruction and repathing",
                        this.guard.getUuidAsString(),
                        targetRoot);
                this.guard.getNavigation().startMovingTo(targetRoot.getX() + 0.5D, targetRoot.getY(), targetRoot.getZ() + 0.5D, 0.8D);
                this.stalledTicks = 0;
                return true;
            }

            if (attemptFallbackApproach(targetRoot)) {
                LOGGER.debug("Lumberjack Guard {} target {} using alternate approach point",
                        this.guard.getUuidAsString(),
                        targetRoot);
                this.stalledTicks = 0;
                return true;
            }

            this.stallRecoveryAttempts++;
            this.stalledTicks = 0;
            if (this.stallRecoveryAttempts >= MAX_STALL_RECOVERY_ATTEMPTS) {
                LOGGER.debug("Lumberjack Guard {} target {} exhausted stall recovery after {} attempts",
                        this.guard.getUuidAsString(),
                        targetRoot,
                        this.stallRecoveryAttempts);
                this.stallRecoveryAttempts = 0;
                return false;
            }
        }

        return true;
    }

    private boolean clearAxeBreakableObstruction(ServerWorld world, BlockPos targetRoot) {
        Vec3d from = this.guard.getPos().add(0.0D, this.guard.getHeight() * 0.5D, 0.0D);
        Vec3d to = Vec3d.ofCenter(targetRoot);
        Vec3d delta = to.subtract(from);
        if (delta.lengthSquared() < 0.0001D) {
            return false;
        }

        Vec3d step = delta.normalize().multiply(0.9D);
        for (int i = 1; i <= 4; i++) {
            Vec3d sample = from.add(step.multiply(i));
            BlockPos samplePos = BlockPos.ofFloored(sample);
            if (tryBreakObstacleAt(world, samplePos)) {
                LOGGER.debug("Lumberjack Guard {} target {} broke obstruction at {}",
                        this.guard.getUuidAsString(),
                        targetRoot,
                        samplePos);
                return true;
            }
            if (tryBreakObstacleAt(world, samplePos.up())) {
                LOGGER.debug("Lumberjack Guard {} target {} broke obstruction at {}",
                        this.guard.getUuidAsString(),
                        targetRoot,
                        samplePos.up());
                return true;
            }
        }

        for (Vec3i offset : List.of(new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1), new Vec3i(0, 1, 0))) {
            if (tryBreakObstacleAt(world, this.guard.getBlockPos().add(offset))) {
                LOGGER.debug("Lumberjack Guard {} target {} broke nearby obstruction at {}",
                        this.guard.getUuidAsString(),
                        targetRoot,
                        this.guard.getBlockPos().add(offset));
                return true;
            }
        }

        LOGGER.debug("Lumberjack Guard {} target {} found no breakable obstruction",
                this.guard.getUuidAsString(),
                targetRoot);
        return false;
    }

    private boolean attemptFallbackApproach(BlockPos targetRoot) {
        if (this.guard.getNavigation().startMovingTo(targetRoot.getX() + 0.5D, targetRoot.getY(), targetRoot.getZ() + 0.5D, 0.8D)) {
            return true;
        }

        for (Vec3i offset : List.of(new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1), new Vec3i(1, 0, 1), new Vec3i(1, 0, -1), new Vec3i(-1, 0, 1), new Vec3i(-1, 0, -1))) {
            BlockPos approach = targetRoot.add(offset);
            if (this.guard.getNavigation().startMovingTo(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D, 0.8D)) {
                LOGGER.debug("Lumberjack Guard {} target {} fallback approach {}",
                        this.guard.getUuidAsString(),
                        targetRoot,
                        approach);
                return true;
            }
        }

        return false;
    }

    private BlockPos resolveReplacementRoot(ServerWorld world, BlockPos originalRoot) {
        return resolveReplacementRoot(
                originalRoot,
                pos -> isEligibleLog(world, pos),
                candidate -> normalizeRoot(world, candidate),
                normalized -> isEligibleRoot(world, normalized),
                this::getTrunkAdjacent
        );
    }

    static BlockPos resolveReplacementRoot(BlockPos originalRoot,
                                           Predicate<BlockPos> isEligibleLog,
                                           Function<BlockPos, BlockPos> normalizeRoot,
                                           Predicate<BlockPos> isEligibleRoot,
                                           Function<BlockPos, List<BlockPos>> adjacentProvider) {
        BlockPos min = originalRoot.add(-2, -3, -2);
        BlockPos max = originalRoot.add(2, 3, 2);
        BlockPos replacement = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockPos candidate = cursor.toImmutable();
            if (!isEligibleLog.test(candidate)) {
                continue;
            }

            BlockPos normalized = normalizeRoot.apply(candidate);
            if (!isEligibleRoot.test(normalized)) {
                continue;
            }

            double distanceSq = normalized.getSquaredDistance(originalRoot);
            if (distanceSq < bestDistance) {
                bestDistance = distanceSq;
                replacement = normalized;
            }
        }

        if (replacement != null) {
            return replacement;
        }

        ConnectedLogScanResult fallbackScanResult = collectConnectedLogsWithinTreeBounds(
                originalRoot,
                isEligibleLog,
                adjacentProvider
        );
        for (BlockPos candidate : fallbackScanResult.logs()) {
            double distanceSq = candidate.getSquaredDistance(originalRoot);
            if (distanceSq < bestDistance) {
                bestDistance = distanceSq;
                replacement = candidate;
            }
        }

        return replacement;
    }

    private void recordFailedTargetAttempt(BlockPos targetRoot, String reason) {
        this.guard.setSessionTargetsRemaining(this.guard.getSessionTargetsRemaining() - 1);
        this.failedSessionRoots.add(targetRoot.toImmutable());
        this.rootTeardownRetryAttempts.remove(targetRoot);
        this.rootFocusTicks.remove(targetRoot);
        this.stalledTicks = 0;
        this.stallRecoveryAttempts = 0;
        this.lastTreeDistanceSq = Double.MAX_VALUE;
        LOGGER.debug("Lumberjack Guard {} failed target {} (reason: {}, remaining targets {}, stallTicks {})",
                this.guard.getUuidAsString(),
                targetRoot,
                reason,
                this.guard.getSessionTargetsRemaining(),
                this.stalledTicks);

        if (this.guard.getSessionTargetsRemaining() <= 0 || this.guard.getSelectedTreeTargets().size() <= 1) {
            beginReturnToBase();
        }
    }

    private boolean tryBreakObstacleAt(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!isAxeBreakablePathObstacle(state)) {
            return false;
        }
        if (isProtectedPairedBlock(pos)) {
            return false;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        return breakObstacleAndBufferDrops(
                () -> Block.getDroppedStacks(state, world, pos, blockEntity, this.guard, ItemStack.EMPTY),
                () -> world.removeBlock(pos, false),
                this::bufferStack
        );
    }

    static boolean breakObstacleAndBufferDrops(
            Supplier<List<ItemStack>> dropsSupplier,
            BooleanSupplier removeBlock,
            Consumer<ItemStack> dropBuffer
    ) {
        List<ItemStack> drops = dropsSupplier.get();
        if (!removeBlock.getAsBoolean()) {
            return false;
        }

        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                dropBuffer.accept(drop.copy());
            }
        }
        return true;
    }

    private boolean isAxeBreakablePathObstacle(BlockState state) {
        // NOTE: Logs are intentionally excluded here.
        // A log in the path is either part of a building (must not be broken) or a tree
        // that should be properly targeted via the session mechanism — not silently
        // chewed through as a "path obstacle". Leaves and other axe-mineable blocks
        // (e.g. dead bushes, bamboo, etc.) are safe to clear.
        return !state.isAir()
                && !state.isIn(BlockTags.LOGS)
                && (state.isIn(BlockTags.LEAVES)
                || state.isIn(BlockTags.AXE_MINEABLE));
    }

    private boolean isProtectedPairedBlock(BlockPos pos) {
        BlockPos craftingTable = this.guard.getPairedCraftingTablePos();
        BlockPos chest = this.guard.getPairedChestPos();
        BlockPos furnaceModifier = this.guard.getPairedFurnaceModifierPos();
        return pos.equals(craftingTable) || pos.equals(chest) || pos.equals(furnaceModifier);
    }

    private void startChopCountdown(ServerWorld world, String reason) {
        long totalTicks = MathHelper.nextInt(this.guard.getRandom(), CHOP_INTERVAL_MIN_TICKS, CHOP_INTERVAL_MAX_TICKS);
        startChopCountdown(world, totalTicks, reason);
    }

    private void startChopCountdown(ServerWorld world, long totalTicks, String reason) {
        this.guard.startChopCountdown(world.getTime(), totalTicks);
        LOGGER.info("Lumberjack Guard {} chop countdown started ({} ticks) {}",
                this.guard.getUuidAsString(),
                totalTicks,
                reason);
    }

    private boolean maybeRunNoTreeEscalation(ServerWorld world, int noTreeFailures) {
        if (!shouldRunNoTreeEscalation(noTreeFailures)) {
            return false;
        }

        boolean midpointUpgradeApplied = runMidpointUpgradeNeedPass(world, this.guard);
        this.guard.requestTriggerEvaluation();
        LOGGER.warn("Lumberjack Guard {} no-tree-escalation failures={} highWaterMark={} midpointUpgradeApplied={} triggerEvaluationRequested={}",
                this.guard.getUuidAsString(),
                noTreeFailures,
                NO_TREE_ESCALATION_HIGH_WATER_MARK,
                midpointUpgradeApplied,
                this.guard.isTriggerEvaluationRequested());
        return true;
    }

    static boolean shouldRunNoTreeEscalation(int noTreeFailures) {
        return noTreeFailures >= NO_TREE_ESCALATION_HIGH_WATER_MARK
                && (noTreeFailures - NO_TREE_ESCALATION_HIGH_WATER_MARK) % NO_TREE_ESCALATION_REPEAT_INTERVAL == 0;
    }

    private void logCountdownProgress(ServerWorld world) {
        if (this.guard.getChopCountdownTotalTicks() <= 0L) {
            return;
        }
        long elapsed = world.getTime() - this.guard.getChopCountdownStartTick();
        int step = Math.min(4, (int) ((elapsed * 4L) / this.guard.getChopCountdownTotalTicks()));
        if (step <= this.guard.getChopCountdownLastLogStep() || step == 0) {
            return;
        }

        this.guard.setChopCountdownLastLogStep(step);

        if (step == 2) {
            LumberjackVillageDemandAudit.AuditSnapshot auditSnapshot = LumberjackVillageDemandAudit.run(world, this.guard);
            List<String> recipientsChosen = LumberjackGuardDepositLogsGoal.runMidpointAuditedDemandDistribution(
                    world,
                    this.guard,
                    auditSnapshot);
            boolean midpointUpgradeApplied = runMidpointUpgradeNeedPass(world, this.guard);
            LOGGER.info("Lumberjack Guard {} countdown audit [midpoint]: eligible-v1 missing chest={}, eligible-v2 missing crafting table={}, v2 recipients under stick/plank targets={}, recipients chosen={}, midpoint upgrade applied={}",
                    this.guard.getUuidAsString(),
                    auditSnapshot.eligibleV1VillagersMissingChest(),
                    auditSnapshot.eligibleV2VillagersMissingCraftingTable(),
                    auditSnapshot.eligibleV2ProfessionalsUnderToolMaterialThreshold(),
                    recipientsChosen,
                    midpointUpgradeApplied);
        }

        long remaining = this.guard.getNextChopTick() - world.getTime();
        if (isLumberjackVerboseLoggingEnabled()) {
            LOGGER.debug("Lumberjack Guard {} chop countdown {}% ({} ticks remaining)",
                    this.guard.getUuidAsString(),
                    step * 25,
                    Math.max(remaining, 0L));
        }
    }

    private static boolean isLumberjackVerboseLoggingEnabled() {
        return GuardVillagersConfig.lumberjackVerboseLogging;
    }

    static boolean runMidpointUpgradeNeedPass(ServerWorld world, LumberjackGuardEntity guard) {
        Inventory chestInventory = LumberjackGuardCraftingGoal.resolveChestInventoryForGuard(world, guard);
        return runMidpointUpgradeNeedPass(
                () -> LumberjackChestTriggerController.resolveNextUpgradeDemand(world, guard),
                demand -> countByItemForMidpoint(chestInventory, demand.outputItem())
                        + countByItemForMidpoint(guard.getGatheredStackBuffer(), demand.outputItem()),
                demand -> LumberjackGuardCraftingGoal.craftSingleUpgradeDemandOutputIfPossible(guard, chestInventory, demand),
                () -> LumberjackChestTriggerController.runImmediateVillageUpgradePass(world, guard)
        );
    }

    static boolean runMidpointUpgradeNeedPass(java.util.function.Supplier<LumberjackChestTriggerController.UpgradeDemand> resolveDemand,
                                              java.util.function.ToIntFunction<LumberjackChestTriggerController.UpgradeDemand> outputCount,
                                              java.util.function.Predicate<LumberjackChestTriggerController.UpgradeDemand> craftOutput,
                                              java.util.function.BooleanSupplier runImmediateUpgradePass) {
        LumberjackChestTriggerController.UpgradeDemand demand = resolveDemand.get();
        if (demand == null) {
            return false;
        }

        int onHandOutput = outputCount.applyAsInt(demand);
        if (onHandOutput <= 0 && !craftOutput.test(demand)) {
            return false;
        }

        return runImmediateUpgradePass.getAsBoolean();
    }

    private static int countByItemForMidpoint(Inventory inventory, Item item) {
        if (inventory == null) {
            return 0;
        }

        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countByItemForMidpoint(List<ItemStack> stacks, Item item) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean scheduleSingleTreeRecoverySession(ServerWorld world, LumberjackGuardEntity guard) {
        BlockPos recoveryTarget = findSingleRecoveryTarget(world, guard);
        if (recoveryTarget == null) {
            return false;
        }

        guard.getSelectedTreeTargets().clear();
        guard.getSelectedTreeTargets().add(recoveryTarget);
        guard.setSessionTargetsRemaining(1);
        guard.setActiveSession(true);
        guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_TREE);
        guard.clearChopCountdown();

        LOGGER.info("Lumberjack Guard {} entering constrained recovery chopping mode with single target {}",
                guard.getUuidAsString(),
                recoveryTarget);
        return true;
    }

    private static BlockPos findSingleRecoveryTarget(ServerWorld world, LumberjackGuardEntity guard) {
        BlockPos table = guard.getPairedCraftingTablePos();
        if (table == null) {
            return null;
        }

        BlockPos chest = guard.getPairedChestPos();
        BlockPos center = chest == null
                ? table
                : new BlockPos((table.getX() + chest.getX()) / 2, Math.min(table.getY(), chest.getY()), (table.getZ() + chest.getZ()) / 2);
        int effectiveTreeSearchRadius = getEffectiveTreeSearchRadius(guard);

        Set<BlockPos> excluded = new HashSet<>(guard.getSelectedTreeTargets());
        BlockPos min = center.add(-effectiveTreeSearchRadius, -TREE_SEARCH_HEIGHT, -effectiveTreeSearchRadius);
        BlockPos max = center.add(effectiveTreeSearchRadius, TREE_SEARCH_HEIGHT, effectiveTreeSearchRadius);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockPos pos = cursor.toImmutable();
            if (!center.isWithinDistance(pos, effectiveTreeSearchRadius)) {
                continue;
            }
            if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                continue;
            }

            BlockPos root = normalizeRootStatic(world, pos);
            if (excluded.contains(root)) {
                continue;
            }
            if (!isEligibleRootStatic(world, root)) {
                continue;
            }

            double distance = center.getSquaredDistance(root);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = root;
            }
        }

        return nearest;
    }

    private int getEffectiveTreeSearchRadius() {
        return getEffectiveTreeSearchRadius(this.guard);
    }

    static int getEffectiveTreeSearchRadius(LumberjackGuardEntity guard) {
        int attempts = guard.getConsecutiveNoTreeSessions();
        return getEffectiveTreeSearchRadiusForAttempts(attempts);
    }

    static int getEffectiveTreeSearchRadiusForAttempts(int attempts) {
        if (attempts >= 4) {
            return TREE_SEARCH_MAX_EXPANDED_RADIUS;
        }
        if (attempts >= 2) {
            return TREE_SEARCH_EXPANDED_RADIUS;
        }
        return TREE_SEARCH_RADIUS;
    }

    private static BlockPos normalizeRootStatic(ServerWorld world, BlockPos pos) {
        BlockPos.Mutable mutable = pos.mutableCopy();
        while (mutable.getY() > world.getBottomY() && world.getBlockState(mutable.down()).isIn(BlockTags.LOGS)) {
            mutable.move(0, -1, 0);
        }
        return mutable.toImmutable();
    }

    private static boolean isEligibleRootStatic(ServerWorld world, BlockPos pos) {
        return isEligibleRootImpl(world, pos, null);
    }

    private static boolean hasMinimumTreeStructureStatic(ServerWorld world, BlockPos root) {
        return hasMinimumTreeStructureImpl(world, root);
    }

    /**
     * Returns {@code true} if {@code root} has at least {@value MIN_ROOT_STRUCTURE_LOGS}
     * connected log blocks forming a trunk column, AND natural (non-persistent) leaves are
     * connected to the crown of this specific column (not just present somewhere nearby).
     *
     * <p>Requiring crown-attached leaves (rather than any leaves within a flat radius of the
     * root) prevents village house log pillars from being classified as harvestable trees.
     * A house log sitting next to a real tree would previously pass the old "leaves nearby"
     * check; now the leaves must actually be adjacent to the top of <em>this</em> column.
     */
    private static boolean hasMinimumTreeStructureImpl(ServerWorld world, BlockPos root) {
        if (!hasCrownAttachedNaturalLeaves(world, root)) {
            return false;
        }

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(root);

        int minY = root.getY();
        int maxY = root.getY() + ROOT_STRUCTURE_MAX_HEIGHT;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (visited.size() >= MIN_ROOT_STRUCTURE_LOGS) {
                return true;
            }

            for (BlockPos adjacent : List.of(current.up(), current.down(), current.north(), current.south(), current.east(), current.west())) {
                if (adjacent.getY() < minY || adjacent.getY() > maxY) {
                    continue;
                }
                if (Math.abs(adjacent.getX() - root.getX()) > ROOT_STRUCTURE_HORIZONTAL_RADIUS
                        || Math.abs(adjacent.getZ() - root.getZ()) > ROOT_STRUCTURE_HORIZONTAL_RADIUS) {
                    continue;
                }
                if (visited.contains(adjacent) || !world.getBlockState(adjacent).isIn(BlockTags.LOGS)) {
                    continue;
                }
                queue.add(adjacent.toImmutable());
            }
        }

        return false;
    }

    private static boolean hasNearbyNaturalLeavesStatic(ServerWorld world, BlockPos root) {
        BlockPos min = root.add(-ROOT_CANOPY_SEARCH_RADIUS, 1, -ROOT_CANOPY_SEARCH_RADIUS);
        BlockPos max = root.add(ROOT_CANOPY_SEARCH_RADIUS, ROOT_CANOPY_SEARCH_HEIGHT, ROOT_CANOPY_SEARCH_RADIUS);
        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockState state = world.getBlockState(cursor);
            if (!(state.getBlock() instanceof LeavesBlock) || state.get(LeavesBlock.PERSISTENT)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if natural (non-persistent) leaves are directly attached to the
     * crown of the log column rooted at {@code root}.
     *
     * <p>Unlike the old "leaves anywhere within radius 3" check, this walks up to the top of
     * the log column and then checks the immediate neighbourhood of the topmost log block.
     * This prevents village house pillars from qualifying: a house pillar has no leaves
     * coming off its own top — nearby tree canopy no longer counts.
     *
     * <p>The crown search checks a 3×3×3 neighbourhood around the topmost log, then expands
     * one more level up for tall trees where the canopy starts slightly above the last trunk
     * block.
     */
    private static boolean hasCrownAttachedNaturalLeaves(ServerWorld world, BlockPos root) {
        // Walk to the top of this log column.
        BlockPos crown = root;
        for (int i = 0; i < ROOT_STRUCTURE_MAX_HEIGHT; i++) {
            BlockPos above = crown.up();
            if (!world.getBlockState(above).isIn(BlockTags.LOGS)) {
                break;
            }
            crown = above;
        }

        // Search a tight box around the crown for non-persistent leaves.
        int leafRadius = getConfiguredLeafSearchRadius();
        int requiredLeafCount = getConfiguredRequiredLeafCount();
        BlockPos min = crown.add(-leafRadius, -1, -leafRadius);
        BlockPos max = crown.add(leafRadius, leafRadius + 1, leafRadius);
        int naturalLeaves = 0;
        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockState state = world.getBlockState(cursor);
            if (state.getBlock() instanceof LeavesBlock && !state.get(LeavesBlock.PERSISTENT)) {
                naturalLeaves++;
                if (naturalLeaves >= requiredLeafCount) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<BlockPos> findTreeTargets(ServerWorld world) {
        TreeTargetScanSession session = createTreeTargetScanSession(world);
        while (!session.complete()) {
            session.scanNextBudget(world, Integer.MAX_VALUE / 4,
                    (scanWorld, pos, bells, roots, qualificationContext, metrics) -> tryAddQualifiedRoot(scanWorld, pos, bells, roots, qualificationContext, metrics));
        }
        boolean logScanMetricsAtInfo = shouldLogScanMetricsAtInfo(session);
        session.logMetrics(this.guard.getUuidAsString(), logScanMetricsAtInfo, isLumberjackVerboseLoggingEnabled());
        return session.sortedRoots();
    }

    private boolean shouldLogScanMetricsAtInfo(TreeTargetScanSession session) {
        return session.metricsElapsedMs() >= TREE_SCAN_METRICS_INFO_ELAPSED_MS
                || session.usedMappedBounds()
                || session.earlyCompleted();
    }

    private TreeTargetScanSession createTreeTargetScanSession(ServerWorld world) {
        BlockPos table = this.guard.getPairedCraftingTablePos();
        BlockPos chest = this.guard.getPairedChestPos();
        BlockPos center = getPairedBaseCenter(table, chest);
        int effectiveTreeSearchRadius = getEffectiveTreeSearchRadius();

        MappedBoundsSearchContext mappedContext = resolveMappedBoundsSearchContext(world, center);
        boolean hasMappedBounds = mappedContext != null;
        Set<BlockPos> nearbyBells = collectBellsNear(world, center, effectiveTreeSearchRadius + BELL_EXCLUSION_RADIUS);

        List<ScanBounds> regions = new ArrayList<>();
        regions.add(ScanBounds.fromLocalRadius(center, effectiveTreeSearchRadius));
        if (hasMappedBounds) {
            for (VillageMappedBoundsState.MappedBounds bounds : mappedContext.bounds()) {
                regions.add(ScanBounds.fromMapped(center, bounds));
            }
        }

        int uniqueBoundsCount = mappedContext == null ? 0 : mappedContext.bounds().size();
        String scanMode = hasMappedBounds ? "local+mapped" : "local-only";
        LOGGER.debug("Lumberjack Guard {} scan-mode={} uniqueBounds={} center={}",
                this.guard.getUuidAsString(),
                scanMode,
                uniqueBoundsCount,
                center.toShortString());
        return new TreeTargetScanSession(this.guard.getUuidAsString(),
                center,
                effectiveTreeSearchRadius,
                hasMappedBounds,
                mappedContext == null ? 0 : mappedContext.cartographerCount(),
                regions,
                nearbyBells,
                ScanQualificationContext.create(world, regions, getConfiguredHousePoiProtectionRadius()));
    }

    private Set<BlockPos> collectQualifiedRootsInMappedBounds(ServerWorld world,
                                                              BlockPos center,
                                                              MappedBoundsSearchContext mappedContext) {
        Set<BlockPos> uniqueRoots = new HashSet<>();
        int candidateLogs = 0;
        int acceptedRoots = 0;
        long candidateVolume = 0L;

        for (VillageMappedBoundsState.MappedBounds bounds : mappedContext.bounds()) {
            ScanBounds scanBounds = ScanBounds.fromMapped(center, bounds);
            candidateVolume += scanBounds.candidateCount();
            for (BlockPos cursor : BlockPos.iterate(scanBounds.min(), scanBounds.max())) {
                BlockPos pos = cursor.toImmutable();
                if (!bounds.contains(pos)) {
                    continue;
                }
                if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                    continue;
                }
                candidateLogs++;
                if (tryAddQualifiedRoot(world, pos, null, uniqueRoots).accepted()) {
                    acceptedRoots++;
                }
            }
        }

        LOGGER.debug("Lumberjack Guard {} mapped-bounds qualification cartographers={} mappedBoxes={} candidateVolume={} candidateLogs={} acceptedRoots={}",
                this.guard.getUuidAsString(),
                mappedContext.cartographerCount(),
                mappedContext.bounds().size(),
                candidateVolume,
                candidateLogs,
                acceptedRoots);
        return uniqueRoots;
    }

    private Set<BlockPos> collectQualifiedRootsInBounds(ServerWorld world,
                                                        ScanBounds scanBounds,
                                                        BlockPos center,
                                                        Set<BlockPos> nearbyBells) {
        int candidateLogs = 0;
        int acceptedRoots = 0;
        Set<BlockPos> uniqueRoots = new HashSet<>();

        for (BlockPos cursor : BlockPos.iterate(scanBounds.min(), scanBounds.max())) {
            BlockPos pos = cursor.toImmutable();
            if (!isCandidateInScanMode(center, pos, null)) {
                continue;
            }
            if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                continue;
            }
            candidateLogs++;
            if (tryAddQualifiedRoot(world, pos, nearbyBells, uniqueRoots).accepted()) {
                acceptedRoots++;
            }
        }

        LOGGER.debug("Lumberjack Guard {} tree root qualification mode={} candidateLogs={} acceptedRoots={}",
                this.guard.getUuidAsString(),
                "local-radius",
                candidateLogs,
                acceptedRoots);
        return uniqueRoots;
    }

    private RootQualificationResult tryAddQualifiedRoot(ServerWorld world,
                                                        BlockPos candidateLog,
                                                        @Nullable Set<BlockPos> cachedBells,
                                                        Set<BlockPos> uniqueRoots,
                                                        @Nullable ScanQualificationContext qualificationContext,
                                                        ScanMetrics metrics) {
        BlockPos root = qualificationContext != null
                ? qualificationContext.normalizeRootCached(world, candidateLog)
                : normalizeRoot(world, candidateLog);
        RootQualificationResult qualification = qualificationContext != null
                ? qualificationContext.qualifyRootCached(world, root, cachedBells, metrics)
                : qualifyRoot(world, root, cachedBells, null);
        if (!qualification.accepted()) {
            return qualification;
        }
        if (!uniqueRoots.add(root)) {
            return new RootQualificationResult(false, REJECTED_DUPLICATE_ROOT);
        }
        return new RootQualificationResult(true, "");
    }

    private RootQualificationResult tryAddQualifiedRoot(ServerWorld world,
                                                        BlockPos candidateLog,
                                                        @Nullable Set<BlockPos> cachedBells,
                                                        Set<BlockPos> uniqueRoots) {
        return tryAddQualifiedRoot(world, candidateLog, cachedBells, uniqueRoots, null, new ScanMetrics());
    }

    private @Nullable MappedBoundsSearchContext resolveMappedBoundsSearchContext(ServerWorld world, BlockPos center) {
        List<CartographerBehavior.CartographerPairing> nearbyCartographers =
                CartographerBehavior.getNearbyPairings(world, center, CARTOGRAPHER_INFLUENCE_RADIUS);

        if (nearbyCartographers.isEmpty()) {
            LOGGER.debug("Lumberjack Guard {} no cartographer job-sites found within {} blocks",
                    this.guard.getUuidAsString(),
                    CARTOGRAPHER_INFLUENCE_RADIUS);
            return null;
        }

        List<VillageMappedBoundsState.MappedBounds> allBounds = new ArrayList<>();
        for (CartographerBehavior.CartographerPairing candidate : nearbyCartographers) {
            List<VillageMappedBoundsState.MappedBounds> chestBounds =
                    CartographerMapChestUtil.collectPopulatedMapBounds(world, candidate.chestPos());
            LOGGER.debug("Lumberjack Guard {} mapped-bounds candidate cartographerJob={} chest={} populatedMaps={}",
                    this.guard.getUuidAsString(),
                    candidate.jobPos().toShortString(),
                    candidate.chestPos().toShortString(),
                    chestBounds.size());
            allBounds.addAll(chestBounds);
        }

        List<VillageMappedBoundsState.MappedBounds> uniqueValidBounds = uniqueValidMappedBounds(allBounds);
        if (!isMappedModeEnabled(uniqueValidBounds)) {
            LOGGER.debug("Lumberjack Guard {} mapped-bounds fallback: zero valid populated map bounds remain",
                    this.guard.getUuidAsString());
            return null;
        }

        List<VillageMappedBoundsState.MappedBounds> mergedBounds = mergeOverlappingMappedBounds(uniqueValidBounds);
        LOGGER.debug("Lumberjack Guard {} mapped-bounds enabled from {} cartographer(s) with {} valid unique populated map bounds (merged={})",
                this.guard.getUuidAsString(),
                nearbyCartographers.size(),
                uniqueValidBounds.size(),
                mergedBounds.size());
        return new MappedBoundsSearchContext(mergedBounds, nearbyCartographers.size());
    }

    static boolean isMappedModeEnabled(List<VillageMappedBoundsState.MappedBounds> bounds) {
        return !uniqueValidMappedBounds(bounds).isEmpty();
    }

    static List<VillageMappedBoundsState.MappedBounds> uniqueValidMappedBounds(List<VillageMappedBoundsState.MappedBounds> bounds) {
        if (bounds.isEmpty()) {
            return List.of();
        }

        List<VillageMappedBoundsState.MappedBounds> uniqueValid = new ArrayList<>();
        Set<VillageMappedBoundsState.MappedBounds> seen = new HashSet<>();
        for (VillageMappedBoundsState.MappedBounds candidate : bounds) {
            if (candidate.minX() > candidate.maxX() || candidate.minZ() > candidate.maxZ()) {
                continue;
            }
            if (seen.add(candidate)) {
                uniqueValid.add(candidate);
            }
        }
        return uniqueValid;
    }

    static List<VillageMappedBoundsState.MappedBounds> mergeOverlappingMappedBounds(List<VillageMappedBoundsState.MappedBounds> bounds) {
        List<VillageMappedBoundsState.MappedBounds> merged = new ArrayList<>();
        for (VillageMappedBoundsState.MappedBounds candidate : bounds) {
            VillageMappedBoundsState.MappedBounds running = candidate;
            boolean didMerge;
            do {
                didMerge = false;
                for (int i = 0; i < merged.size(); i++) {
                    VillageMappedBoundsState.MappedBounds existing = merged.get(i);
                    if (existing.minX() > running.maxX() || existing.maxX() < running.minX()
                            || existing.minZ() > running.maxZ() || existing.maxZ() < running.minZ()) {
                        continue;
                    }
                    merged.remove(i);
                    running = new VillageMappedBoundsState.MappedBounds(
                            Math.min(existing.minX(), running.minX()),
                            Math.max(existing.maxX(), running.maxX()),
                            Math.min(existing.minZ(), running.minZ()),
                            Math.max(existing.maxZ(), running.maxZ()));
                    didMerge = true;
                    break;
                }
            } while (didMerge);
            merged.add(running);
        }
        return merged;
    }

    private static BlockPos getPairedBaseCenter(BlockPos table, @Nullable BlockPos chest) {
        return chest == null
                ? table
                : new BlockPos((table.getX() + chest.getX()) / 2, Math.min(table.getY(), chest.getY()), (table.getZ() + chest.getZ()) / 2);
    }

    static boolean isCandidateInScanMode(BlockPos center, BlockPos candidate, @Nullable VillageMappedBoundsState.MappedBounds mappedBounds) {
        return isCandidateInScanMode(center, candidate, mappedBounds, TREE_SEARCH_RADIUS);
    }

    static boolean isCandidateInScanMode(BlockPos center,
                                         BlockPos candidate,
                                         @Nullable VillageMappedBoundsState.MappedBounds mappedBounds,
                                         int localSearchRadius) {
        if (mappedBounds != null) {
            return mappedBounds.contains(candidate);
        }
        return center.isWithinDistance(candidate, localSearchRadius);
    }

    static boolean shouldEarlyCompleteScanPass(boolean hasMappedBounds, int passIndex, int acceptedRoots, int acceptedLocalRoots) {
        if (acceptedRoots >= INITIAL_SCAN_ACCEPTED_ROOT_TARGET) {
            return true;
        }
        return passIndex == 0
                && hasMappedBounds
                && acceptedLocalRoots >= INITIAL_SCAN_LOCAL_ROOT_TARGET;
    }

    private record MappedBoundsSearchContext(List<VillageMappedBoundsState.MappedBounds> bounds,
                                             int cartographerCount) {
    }

    private record ScanBounds(BlockPos min,
                              BlockPos max,
                              long candidateCount,
                              @Nullable VillageMappedBoundsState.MappedBounds mappedBounds,
                              boolean usesLocalBellExclusion) {
        static ScanBounds fromLocalRadius(BlockPos center) {
            return fromLocalRadius(center, TREE_SEARCH_RADIUS);
        }

        static ScanBounds fromLocalRadius(BlockPos center, int radius) {
            BlockPos min = center.add(-radius, -TREE_SEARCH_HEIGHT, -radius);
            BlockPos max = center.add(radius, TREE_SEARCH_HEIGHT, radius);
            long width = (long) (max.getX() - min.getX() + 1);
            long height = (long) (max.getY() - min.getY() + 1);
            long depth = (long) (max.getZ() - min.getZ() + 1);
            return new ScanBounds(min, max, width * height * depth, null, true);
        }

        static ScanBounds fromMapped(BlockPos center, VillageMappedBoundsState.MappedBounds bounds) {
            BlockPos min = new BlockPos(bounds.minX(), center.getY() - TREE_SEARCH_HEIGHT, bounds.minZ());
            BlockPos max = new BlockPos(bounds.maxX(), center.getY() + TREE_SEARCH_HEIGHT, bounds.maxZ());
            long width = (long) (max.getX() - min.getX() + 1);
            long height = (long) (max.getY() - min.getY() + 1);
            long depth = (long) (max.getZ() - min.getZ() + 1);
            return new ScanBounds(min, max, width * height * depth, bounds, false);
        }
    }

    @FunctionalInterface
    private interface RootCollector {
        RootQualificationResult evaluate(ServerWorld world,
                                         BlockPos candidateLog,
                                         @Nullable Set<BlockPos> cachedBells,
                                         Set<BlockPos> uniqueRoots,
                                         @Nullable ScanQualificationContext qualificationContext,
                                         ScanMetrics metrics);
    }

    private record RootQualificationResult(boolean accepted, String rejectReason) {}

    private static final class ScanMetrics {
        private final long startNanos = System.nanoTime();
        private long visitedBlocks;
        private long candidateLogs;
        private int acceptedRoots;
        private long rootQualificationCacheHits;
        private long rootQualificationCacheMisses;
        private final Map<String, Integer> skippedReasons = new LinkedHashMap<>();

        void visited() {
            this.visitedBlocks++;
        }

        void candidate() {
            this.candidateLogs++;
        }

        void accepted() {
            this.acceptedRoots++;
        }

        void rootQualificationCacheHit() {
            this.rootQualificationCacheHits++;
        }

        void rootQualificationCacheMiss() {
            this.rootQualificationCacheMisses++;
        }

        void skipped(String reason) {
            this.skippedReasons.merge(reason, 1, Integer::sum);
        }

        long elapsedMs() {
            return (System.nanoTime() - this.startNanos) / 1_000_000L;
        }
    }

    private static final class WorldScanBudgetManager {
        private static final Map<RegistryKey<net.minecraft.world.World>, TickBudgetState> BUDGET_BY_WORLD = new HashMap<>();

        private static int remaining(ServerWorld world) {
            TickBudgetState state = BUDGET_BY_WORLD.computeIfAbsent(world.getRegistryKey(),
                    key -> new TickBudgetState(Long.MIN_VALUE, 0));
            long tick = world.getTime();
            int cap = getConfiguredTreeScanWorldSharedBudgetCap();
            if (state.tick != tick) {
                state.tick = tick;
                state.remaining = cap;
            }
            return Math.max(0, state.remaining);
        }

        private static void consume(ServerWorld world, int budgetRequested) {
            remaining(world);
            TickBudgetState state = BUDGET_BY_WORLD.get(world.getRegistryKey());
            if (state == null) {
                return;
            }
            state.remaining = Math.max(0, state.remaining - Math.max(0, budgetRequested));
        }

        private static final class TickBudgetState {
            private long tick;
            private int remaining;

            private TickBudgetState(long tick, int remaining) {
                this.tick = tick;
                this.remaining = remaining;
            }
        }
    }

    private static final class TreeTargetScanSession {
        private static final List<Integer> INITIAL_LAYER_OFFSETS = List.of(0, 1, -1, 2, -2, 3);
        private static final List<Integer> SECONDARY_LAYER_OFFSETS = List.of(4, -3, 5, -4, 6, -5, 7, -6, 8, -7, 9, -8, 10, -9, -10);

        private final String guardId;
        private final BlockPos center;
        private final int localSearchRadius;
        private final boolean hasMappedBounds;
        private final int cartographerCount;
        private final List<ScanBounds> regions;
        private final @Nullable Set<BlockPos> nearbyBells;
        private final ScanQualificationContext qualificationContext;
        private final Set<BlockPos> uniqueRoots = new HashSet<>();
        private final ScanMetrics metrics = new ScanMetrics();
        private final int[] regionLayerIndices;
        private final int[] regionX;
        private final int[] regionZ;
        private final boolean[] regionCursorInitialized;
        private final int[] regionVisitsThisPass;
        private int regionIndex;
        private int layerIndex;
        private int x;
        private int z;
        private int passIndex;
        private int localAcceptedRoots;
        private int debugRejectLogs;
        private boolean complete;
        private boolean earlyCompleted;

        private TreeTargetScanSession(String guardId,
                                      BlockPos center,
                                      int localSearchRadius,
                                      boolean hasMappedBounds,
                                      int cartographerCount,
                                      List<ScanBounds> regions,
                                      @Nullable Set<BlockPos> nearbyBells,
                                      ScanQualificationContext qualificationContext) {
            this.guardId = guardId;
            this.center = center;
            this.localSearchRadius = localSearchRadius;
            this.hasMappedBounds = hasMappedBounds;
            this.cartographerCount = cartographerCount;
            this.regions = regions;
            this.nearbyBells = nearbyBells;
            this.qualificationContext = qualificationContext;
            this.regionLayerIndices = new int[regions.size()];
            this.regionX = new int[regions.size()];
            this.regionZ = new int[regions.size()];
            this.regionCursorInitialized = new boolean[regions.size()];
            this.regionVisitsThisPass = new int[regions.size()];
        }

        void scanNextBudget(ServerWorld world, int budget, RootCollector collector) {
            int remaining = Math.max(1, budget);
            while (remaining > 0 && !this.complete) {
                if (this.regionIndex >= this.regions.size()) {
                    advancePassIfNeeded();
                    continue;
                }
                ScanBounds region = this.regions.get(this.regionIndex);
                if (this.x == 0 && this.z == 0 && this.layerIndex == 0) {
                    initializeOrRestoreRegionCursor(region);
                }

                int y = this.center.getY() + activeLayers().get(this.layerIndex);
                if (y < region.min().getY() || y > region.max().getY()) {
                    advanceLayer(region);
                    continue;
                }

                BlockPos pos = new BlockPos(this.x, y, this.z);
                remaining--;
                this.metrics.visited();
                this.regionVisitsThisPass[this.regionIndex]++;

                if (region.mappedBounds() != null && !isWithinAnyMappedBounds(pos, this.regions, this.center)) {
                    this.metrics.skipped("out_of_bounds");
                } else if (!isCandidateInScanMode(this.center, pos, region.mappedBounds(), this.localSearchRadius)) {
                    this.metrics.skipped("out_of_bounds");
                } else if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                    this.metrics.skipped("non_log");
                } else {
                    this.metrics.candidate();
                    Set<BlockPos> bellExclusion = region.usesLocalBellExclusion() ? this.nearbyBells : null;
                    RootQualificationResult qualificationResult = collector.evaluate(
                            world,
                            pos,
                            bellExclusion,
                            this.uniqueRoots,
                            this.qualificationContext,
                            this.metrics);
                    if (qualificationResult.accepted()) {
                        this.metrics.accepted();
                        if (region.usesLocalBellExclusion()) {
                            this.localAcceptedRoots++;
                        }
                        if (shouldEarlyCompleteCurrentPass()) {
                            this.earlyCompleted = true;
                            this.complete = true;
                            saveRegionCursor();
                            break;
                        }
                    } else {
                        this.metrics.skipped(qualificationResult.rejectReason());
                        if (this.debugRejectLogs < MAX_REJECT_REASON_DEBUG_PER_SCAN) {
                            LOGGER.debug("Lumberjack Guard {} rejected_root candidate={} reason={}",
                                    this.guardId,
                                    pos.toShortString(),
                                    qualificationResult.rejectReason());
                            this.debugRejectLogs++;
                        }
                    }
                }

                this.x++;
                if (this.x > region.max().getX()) {
                    this.x = region.min().getX();
                    this.z++;
                    if (this.z > region.max().getZ()) {
                        this.z = region.min().getZ();
                        advanceLayer(region);
                    }
                }

                if (!this.complete
                        && this.regionIndex < this.regions.size()
                        && this.regionVisitsThisPass[this.regionIndex] >= MAX_REGION_BLOCK_VISITS_PER_PASS) {
                    this.metrics.skipped("region_visit_cap");
                    advanceRegionWithCheckpoint();
                }
            }
        }

        private static boolean isWithinAnyMappedBounds(BlockPos pos, List<ScanBounds> regions, BlockPos center) {
            for (ScanBounds region : regions) {
                if (region.mappedBounds() == null) {
                    continue;
                }
                if (isCandidateInScanMode(center, pos, region.mappedBounds())) {
                    return true;
                }
            }
            return false;
        }

        private List<Integer> activeLayers() {
            return this.passIndex == 0 ? INITIAL_LAYER_OFFSETS : SECONDARY_LAYER_OFFSETS;
        }

        private void advanceLayer(ScanBounds region) {
            this.layerIndex++;
            if (this.layerIndex >= activeLayers().size()) {
                this.layerIndex = 0;
                advanceRegionWithCheckpoint();
            }
        }

        private void advancePassIfNeeded() {
            if (shouldEarlyCompleteCurrentPass()) {
                this.earlyCompleted = true;
                this.complete = true;
                return;
            }
            if (this.passIndex == 0 && this.uniqueRoots.size() < INITIAL_SCAN_ACCEPTED_ROOT_TARGET) {
                this.passIndex = 1;
                this.regionIndex = 0;
                this.layerIndex = 0;
                this.x = 0;
                this.z = 0;
                for (int i = 0; i < this.regionVisitsThisPass.length; i++) {
                    this.regionVisitsThisPass[i] = 0;
                }
                return;
            }
            this.complete = true;
        }

        private boolean shouldEarlyCompleteCurrentPass() {
            return shouldEarlyCompleteScanPass(
                    this.hasMappedBounds,
                    this.passIndex,
                    this.uniqueRoots.size(),
                    this.localAcceptedRoots);
        }

        private void advanceRegionWithCheckpoint() {
            saveRegionCursor();
            this.regionIndex++;
            this.layerIndex = 0;
            this.x = 0;
            this.z = 0;
        }

        private void initializeOrRestoreRegionCursor(ScanBounds region) {
            if (!this.regionCursorInitialized[this.regionIndex]) {
                this.layerIndex = this.regionLayerIndices[this.regionIndex];
                this.x = region.min().getX();
                this.z = region.min().getZ();
                this.regionCursorInitialized[this.regionIndex] = true;
                return;
            }
            this.layerIndex = this.regionLayerIndices[this.regionIndex];
            this.x = this.regionX[this.regionIndex];
            this.z = this.regionZ[this.regionIndex];
        }

        private void saveRegionCursor() {
            if (this.regionIndex < 0 || this.regionIndex >= this.regions.size()) {
                return;
            }
            this.regionLayerIndices[this.regionIndex] = this.layerIndex;
            this.regionX[this.regionIndex] = this.x;
            this.regionZ[this.regionIndex] = this.z;
            this.regionCursorInitialized[this.regionIndex] = true;
        }

        boolean complete() {
            return this.complete;
        }

        long metricsElapsedMs() {
            return this.metrics.elapsedMs();
        }

        boolean usedMappedBounds() {
            return this.hasMappedBounds;
        }

        boolean earlyCompleted() {
            return this.earlyCompleted;
        }

        List<BlockPos> sortedRoots() {
            List<BlockPos> sorted = new ArrayList<>(this.uniqueRoots);
            sorted.sort(Comparator.comparingDouble(this.center::getSquaredDistance));
            return sorted;
        }

        void logMetrics(String guardId, boolean infoLevel, boolean verboseLogging) {
            if (!infoLevel && !verboseLogging) {
                return;
            }
            if (infoLevel) {
                LOGGER.info("Lumberjack Guard {} tree_scan_metrics mode={} cartographers={} regions={} elapsedMs={} visitedBlocks={} candidateLogs={} acceptedRoots={} rootQualificationCacheHits={} rootQualificationCacheMisses={} skipped={}",
                        guardId,
                        this.hasMappedBounds ? "local+mapped" : "local-only",
                        this.cartographerCount,
                        this.regions.size(),
                        this.metrics.elapsedMs(),
                        this.metrics.visitedBlocks,
                        this.metrics.candidateLogs,
                        this.metrics.acceptedRoots,
                        this.metrics.rootQualificationCacheHits,
                        this.metrics.rootQualificationCacheMisses,
                        this.metrics.skippedReasons);
                return;
            }
            LOGGER.debug("Lumberjack Guard {} tree_scan_metrics mode={} cartographers={} regions={} elapsedMs={} visitedBlocks={} candidateLogs={} acceptedRoots={} rootQualificationCacheHits={} rootQualificationCacheMisses={} skipped={}",
                    guardId,
                    this.hasMappedBounds ? "local+mapped" : "local-only",
                    this.cartographerCount,
                    this.regions.size(),
                    this.metrics.elapsedMs(),
                    this.metrics.visitedBlocks,
                    this.metrics.candidateLogs,
                    this.metrics.acceptedRoots,
                    this.metrics.rootQualificationCacheHits,
                    this.metrics.rootQualificationCacheMisses,
                    this.metrics.skippedReasons);
        }
    }

    private static final class ScanQualificationContext {
        private final Set<Long> protectedPoiDoorInfluence;
        private final Map<BlockPos, RootQualificationResult> rootQualificationMemo = new HashMap<>();
        private final Map<BlockPos, BlockPos> normalizedRootsMemo = new HashMap<>();

        private ScanQualificationContext(Set<Long> protectedPoiDoorInfluence) {
            this.protectedPoiDoorInfluence = protectedPoiDoorInfluence;
        }

        static ScanQualificationContext create(ServerWorld world, List<ScanBounds> regions, int housePoiRadius) {
            Set<Long> influence = buildProtectedPoiDoorInfluence(world, regions, housePoiRadius);
            return new ScanQualificationContext(influence);
        }

        private static Set<Long> buildProtectedPoiDoorInfluence(ServerWorld world, List<ScanBounds> regions, int radius) {
            if (regions.isEmpty()) {
                return Set.of();
            }
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (ScanBounds region : regions) {
                minX = Math.min(minX, region.min().getX());
                minY = Math.min(minY, region.min().getY());
                minZ = Math.min(minZ, region.min().getZ());
                maxX = Math.max(maxX, region.max().getX());
                maxY = Math.max(maxY, region.max().getY());
                maxZ = Math.max(maxZ, region.max().getZ());
            }

            Set<Long> influence = new HashSet<>();
            PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;
            int squareRadius = Math.max(maxX - centerX, maxZ - centerZ) + radius;
            BlockPos poiCenter = new BlockPos(centerX, (minY + maxY) / 2, centerZ);
            poiStorage.getInSquare(
                            type -> type.isIn(PointOfInterestTypeTags.ACQUIRABLE_JOB_SITE) || type.matchesKey(PointOfInterestTypes.HOME),
                            poiCenter,
                            squareRadius,
                            PointOfInterestStorage.OccupationStatus.ANY)
                    .forEach(poi -> addSquareInfluence(influence, poi.getPos(), radius, minX, maxX, minY, maxY, minZ, maxZ));

            BlockPos minDoor = new BlockPos(minX - radius, minY - 2, minZ - radius);
            BlockPos maxDoor = new BlockPos(maxX + radius, maxY + 2, maxZ + radius);
            for (BlockPos cursor : BlockPos.iterate(minDoor, maxDoor)) {
                BlockState state = world.getBlockState(cursor);
                if (state.isIn(BlockTags.WOODEN_DOORS) || state.isOf(Blocks.IRON_DOOR)) {
                    addDoorInfluence(influence, cursor.toImmutable(), radius, minX, maxX, minY, maxY, minZ, maxZ);
                }
            }
            return influence;
        }

        private static void addSquareInfluence(Set<Long> influence,
                                               BlockPos source,
                                               int radius,
                                               int minX,
                                               int maxX,
                                               int minY,
                                               int maxY,
                                               int minZ,
                                               int maxZ) {
            int startX = Math.max(minX, source.getX() - radius);
            int endX = Math.min(maxX, source.getX() + radius);
            int startZ = Math.max(minZ, source.getZ() - radius);
            int endZ = Math.min(maxZ, source.getZ() + radius);
            for (int x = startX; x <= endX; x++) {
                for (int z = startZ; z <= endZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        influence.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }

        private static void addDoorInfluence(Set<Long> influence,
                                             BlockPos source,
                                             int radius,
                                             int minX,
                                             int maxX,
                                             int minY,
                                             int maxY,
                                             int minZ,
                                             int maxZ) {
            int startX = Math.max(minX, source.getX() - radius);
            int endX = Math.min(maxX, source.getX() + radius);
            int startY = Math.max(minY, source.getY() - 2);
            int endY = Math.min(maxY, source.getY() + 2);
            int startZ = Math.max(minZ, source.getZ() - radius);
            int endZ = Math.min(maxZ, source.getZ() + radius);
            for (int x = startX; x <= endX; x++) {
                for (int y = startY; y <= endY; y++) {
                    for (int z = startZ; z <= endZ; z++) {
                        influence.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }

        BlockPos normalizeRootCached(ServerWorld world, BlockPos candidateLog) {
            return this.normalizedRootsMemo.computeIfAbsent(candidateLog.toImmutable(),
                    key -> normalizeRootStatic(world, key));
        }

        RootQualificationResult qualifyRootCached(ServerWorld world,
                                                  BlockPos root,
                                                  @Nullable Set<BlockPos> cachedBells,
                                                  ScanMetrics metrics) {
            RootQualificationResult cached = this.rootQualificationMemo.get(root);
            if (cached != null) {
                metrics.rootQualificationCacheHit();
                return cached;
            }
            metrics.rootQualificationCacheMiss();
            RootQualificationResult computed = qualifyRoot(world, root, cachedBells, this);
            this.rootQualificationMemo.put(root.toImmutable(), computed);
            return computed;
        }

        boolean isWithinProtectedPoiDoorInfluence(BlockPos root) {
            return this.protectedPoiDoorInfluence.contains(root.asLong());
        }
    }

    /** Collect all bell block positions within {@code radius} blocks of {@code center}. */
    private static Set<BlockPos> collectBellsNear(ServerWorld world, BlockPos center, int radius) {
        Set<BlockPos> bells = new HashSet<>();
        BlockPos min = center.add(-radius, -8, -radius);
        BlockPos max = center.add(radius, 8, radius);
        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            if (world.getBlockState(cursor).isOf(Blocks.BELL)) {
                bells.add(cursor.toImmutable());
            }
        }
        return bells;
    }

    private BlockPos normalizeRoot(ServerWorld world, BlockPos pos) {
        BlockPos.Mutable mutable = pos.mutableCopy();
        while (mutable.getY() > world.getBottomY() && world.getBlockState(mutable.down()).isIn(BlockTags.LOGS)) {
            mutable.move(0, -1, 0);
        }
        return mutable.toImmutable();
    }

    private boolean isEligibleRoot(ServerWorld world, BlockPos pos) {
        return isEligibleRootImpl(world, pos, null);
    }

    /**
     * Shared eligibility logic for both the instance and static code paths.
     *
     * <p>A log position qualifies as a harvestable tree root when ALL of the following hold:
     * <ol>
     *   <li>It is tagged {@code #logs}.</li>
     *   <li>The block immediately below it is a natural ground block (not a log, not planks,
     *       not stone, not any other artificial material).</li>
     *   <li>The block two below is not a log (catches stilted/stacked log cabins where the
     *       direct ground check passes but the structure continues below).</li>
     *   <li>No {@link Blocks#BELL} exists within {@value BELL_EXCLUSION_RADIUS} horizontal
     *       blocks (village-center decorative trees are excluded).</li>
     *   <li>It passes the minimum tree-structure check (≥4 connected logs + canopy).</li>
     * </ol>
     *
     * @param cachedBells pre-collected bell positions for the scan area, or {@code null} to
     *                    scan the world on demand (slower — only use for one-off checks)
     */
    private static boolean isEligibleRootImpl(ServerWorld world, BlockPos pos, Set<BlockPos> cachedBells) {
        return qualifyRoot(world, pos, cachedBells, null).accepted();
    }

    private static RootQualificationResult qualifyRoot(ServerWorld world,
                                                       BlockPos pos,
                                                       Set<BlockPos> cachedBells,
                                                       @Nullable ScanQualificationContext qualificationContext) {
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LOGS)) {
            return new RootQualificationResult(false, REJECTED_NOT_LOG);
        }

        // Structure guard: avoid harvesting logs that are integrated into nearby village houses.
        // In vanilla villages, structural logs are commonly adjacent to planks or other
        // wood-construction blocks at trunk height / just above trunk height.
        if (isAdjacentToStructureLikeBlocks(world, pos, getConfiguredStructureProximityRadius())) {
            return new RootQualificationResult(false, REJECTED_STRUCTURE_PROXIMITY);
        }

        BlockState below = world.getBlockState(pos.down());
        if (below.isIn(BlockTags.LOGS)) {
            return new RootQualificationResult(false, REJECTED_STILTED_LOG);
        }
        if (!isNaturalGroundBlock(below)) {
            return new RootQualificationResult(false, REJECTED_BAD_SOIL);
        }

        // Stilted structure guard: if the block two below is also a log, this is not a tree root
        // (e.g. a log cabin whose bottom row sits on dirt but continues below as another log layer).
        if (world.getBlockState(pos.down(2)).isIn(BlockTags.LOGS)) {
            return new RootQualificationResult(false, REJECTED_STILTED_LOG);
        }

        // Bell-proximity guard: skip logs that are decorative village-center trees.
        // Use pre-cached bell set when available to avoid repeated world scans.
        if (isNearBell(world, pos, cachedBells)) {
            return new RootQualificationResult(false, REJECTED_BELL_RADIUS);
        }

        if (isNearProtectedVillagePoi(world, pos, getConfiguredHousePoiProtectionRadius(), qualificationContext)) {
            return new RootQualificationResult(false, REJECTED_HOUSE_POI_RADIUS);
        }

        if (!isNaturalTreeLogCandidate(world, pos)) {
            return new RootQualificationResult(false, REJECTED_NO_LEAVES);
        }

        return new RootQualificationResult(true, "");
    }

    private static boolean isAdjacentToStructureLikeBlocks(ServerWorld world, BlockPos root, int radius) {
        for (BlockPos scanPos : BlockPos.iterate(root.add(-radius, -1, -radius), root.add(radius, 2, radius))) {
            if (scanPos.equals(root)) {
                continue;
            }
            BlockState neighbor = world.getBlockState(scanPos);
            if (isStructureTaggedBlock(neighbor, BlockTags.PLANKS)
                    || isStructureTaggedBlock(neighbor, BlockTags.WOODEN_STAIRS)
                    || isStructureTaggedBlock(neighbor, BlockTags.WOODEN_SLABS)
                    || isStructureTaggedBlock(neighbor, BlockTags.WOODEN_DOORS)
                    || isStructureTaggedBlock(neighbor, BlockTags.BEDS)
                    || isStructureTaggedBlock(neighbor, BlockTags.FENCES)
                    || isStructureTaggedBlock(neighbor, BlockTags.FENCE_GATES)
                    || neighbor.isOf(Blocks.GLASS)
                    || neighbor.isOf(Blocks.GLASS_PANE)
                    || neighbor.isOf(Blocks.TORCH)
                    || neighbor.isOf(Blocks.WALL_TORCH)
                    || neighbor.isOf(Blocks.SOUL_TORCH)
                    || neighbor.isOf(Blocks.SOUL_WALL_TORCH)
                    || neighbor.isOf(Blocks.LANTERN)
                    || neighbor.isOf(Blocks.SOUL_LANTERN)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStructureTaggedBlock(BlockState state, TagKey<net.minecraft.block.Block> tag) {
        return !state.isAir() && !state.isIn(BlockTags.LOGS) && state.isIn(tag);
    }

    private static boolean isNaturalGroundBlock(BlockState state) {
        return state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.PODZOL)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT)
                || state.isOf(Blocks.MOSS_BLOCK)
                || state.isOf(Blocks.MYCELIUM);
    }

    /**
     * Returns {@code true} if any bell block exists within {@value BELL_EXCLUSION_RADIUS}
     * horizontal blocks (and ±4 vertical) of {@code pos}.
     *
     * <p>If {@code cachedBells} is non-null, the check is O(n) over the cached set with no
     * block reads. Otherwise falls back to a world scan (only for one-off call sites).
     */
    private static boolean isNearBell(ServerWorld world, BlockPos pos, Set<BlockPos> cachedBells) {
        if (cachedBells != null) {
            for (BlockPos bell : cachedBells) {
                int dx = Math.abs(bell.getX() - pos.getX());
                int dy = Math.abs(bell.getY() - pos.getY());
                int dz = Math.abs(bell.getZ() - pos.getZ());
                if (dx <= BELL_EXCLUSION_RADIUS && dy <= 4 && dz <= BELL_EXCLUSION_RADIUS) {
                    return true;
                }
            }
            return false;
        }
        // Fallback: scan the world (one-off use only, e.g. recovery session targeting)
        BlockPos min = pos.add(-BELL_EXCLUSION_RADIUS, -4, -BELL_EXCLUSION_RADIUS);
        BlockPos max = pos.add(BELL_EXCLUSION_RADIUS, 4, BELL_EXCLUSION_RADIUS);
        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            if (world.getBlockState(cursor).isOf(Blocks.BELL)) {
                return true;
            }
        }
        return false;
    }

    private static int getConfiguredLeafSearchRadius() {
        return Math.max(1, GuardVillagersConfig.lumberjackNaturalLeafSearchRadius);
    }

    private static int getConfiguredRequiredLeafCount() {
        return Math.max(1, GuardVillagersConfig.lumberjackNaturalRequiredLeafCount);
    }

    private static int getConfiguredStructureProximityRadius() {
        return Math.max(1, GuardVillagersConfig.lumberjackStructureProximityRadius);
    }

    private static int getConfiguredHousePoiProtectionRadius() {
        return Math.max(1, GuardVillagersConfig.lumberjackHousePoiProtectionRadius);
    }

    private static int getConfiguredTreeScanPerGuardBudgetCap() {
        return MathHelper.clamp(
                GuardVillagersConfig.lumberjackTreeScanPerGuardBudgetCap,
                GuardVillagersConfig.MIN_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET,
                GuardVillagersConfig.MAX_LUMBERJACK_TREE_SCAN_PER_GUARD_BUDGET);
    }

    private static int getConfiguredTreeScanPerGuardBudgetFloor(int cap) {
        return Math.max(64, cap / 4);
    }

    private static int getConfiguredTreeScanWorldSharedBudgetCap() {
        return MathHelper.clamp(
                GuardVillagersConfig.lumberjackTreeScanWorldSharedBudgetCap,
                GuardVillagersConfig.MIN_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET,
                GuardVillagersConfig.MAX_LUMBERJACK_TREE_SCAN_WORLD_SHARED_BUDGET);
    }

    private static boolean isNearProtectedVillagePoi(ServerWorld world,
                                                     BlockPos root,
                                                     int radius,
                                                     @Nullable ScanQualificationContext qualificationContext) {
        if (qualificationContext != null) {
            return qualificationContext.isWithinProtectedPoiDoorInfluence(root);
        }
        PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        boolean nearBedOrJob = poiStorage.getInSquare(
                        type -> type.isIn(PointOfInterestTypeTags.ACQUIRABLE_JOB_SITE) || type.matchesKey(PointOfInterestTypes.HOME),
                        root,
                        radius,
                        PointOfInterestStorage.OccupationStatus.ANY)
                .findAny()
                .isPresent();
        if (nearBedOrJob) {
            return true;
        }

        BlockPos min = root.add(-radius, -2, -radius);
        BlockPos max = root.add(radius, 2, radius);
        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockState state = world.getBlockState(cursor);
            if (state.isIn(BlockTags.WOODEN_DOORS) || state.isOf(Blocks.IRON_DOOR)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNaturalTreeLogCandidate(ServerWorld world, BlockPos root) {
        return hasMinimumTreeStructureImpl(world, root);
    }

    private boolean isEligibleLog(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isIn(BlockTags.LOGS);
    }

    private TreeTeardownResult teardownTree(ServerWorld world, BlockPos root) {
        ConnectedLogScanResult scanResult = collectConnectedLogsWithinTreeBounds(world, root);
        Set<BlockPos> logs = scanResult.logs();
        Set<BlockPos> attachedLeaves = new HashSet<>();

        for (BlockPos logPos : logs) {
            for (BlockPos adjacent : BlockPos.iterate(logPos.add(-1, -1, -1), logPos.add(1, 1, 1))) {
                BlockPos candidate = adjacent.toImmutable();
                BlockState candidateState = world.getBlockState(candidate);
                if (candidateState.getBlock() instanceof LeavesBlock
                        && !candidateState.get(LeavesBlock.PERSISTENT)
                        && candidateState.contains(LeavesBlock.DISTANCE)
                        && candidateState.get(LeavesBlock.DISTANCE) <= 2) {
                    attachedLeaves.add(candidate);
                }
            }
        }

        LOGGER.debug("Lumberjack Guard {} tree teardown at {} accepted {} logs and rejected {} cross-tree log candidates",
                this.guard.getUuidAsString(),
                root,
                logs.size(),
                scanResult.rejectedCrossTreeCandidates());

        int brokenLogs = 0;
        int brokenLeaves = 0;

        for (BlockPos logPos : logs) {
            if (breakAndCollect(world, logPos)) {
                brokenLogs++;
            }
        }
        for (BlockPos leafPos : attachedLeaves) {
            if (breakAndCollect(world, leafPos)) {
                brokenLeaves++;
            }
        }

        LOGGER.info("Lumberjack Guard {} chopped tree at {} ({} logs, {} attached leaves)",
                this.guard.getUuidAsString(),
                root,
                brokenLogs,
                brokenLeaves);

        return new TreeTeardownResult(logs.size(), brokenLogs, brokenLeaves);
    }

    private int updateAndGetFocusedRootTicks(BlockPos targetRoot) {
        BlockPos immutableTarget = targetRoot.toImmutable();
        this.rootFocusTicks.keySet().removeIf(root -> !root.equals(immutableTarget));
        return this.rootFocusTicks.merge(immutableTarget, 1, Integer::sum);
    }

    private void executeForcedTreeRemoval(ServerWorld world, BlockPos root, String reason) {
        TreeTeardownResult forcedTeardown = teardownTree(world, root);
        RemainingLogCountResult afterForcedTeardown = countRemainingConnectedLogs(world, root);
        int remainingAfterForcedTeardown = afterForcedTeardown.count();

        LOGGER.warn("Lumberjack Guard {} forced tree removal result root={} reason={} brokenLogs={} brokenLeaves={} remainingLogs={}",
                this.guard.getUuidAsString(),
                root,
                reason,
                forcedTeardown.brokenLogs(),
                forcedTeardown.brokenLeaves(),
                remainingAfterForcedTeardown);

        if (remainingAfterForcedTeardown > 0) {
            recordFailedTargetAttempt(root, "forced tree removal could not clear all logs; remaining=" + remainingAfterForcedTeardown);
        } else {
            this.rootTeardownRetryAttempts.remove(root);
            this.rootFocusTicks.remove(root);
            this.completedSessionRoots.add(root.toImmutable());
            this.guard.setSessionTargetsRemaining(this.guard.getSessionTargetsRemaining() - 1);
        }

        collectNearbyWoodDrops(world);
        this.guard.getSelectedTreeTargets().remove(0);
    }

    private RemainingLogCountResult countRemainingConnectedLogs(ServerWorld world, BlockPos root) {
        ConnectedLogScanResult scanResult = collectConnectedLogsWithinTreeBounds(world, root);
        return new RemainingLogCountResult(scanResult.logs().size(), scanResult.usedFallbackSeed());
    }

    private ConnectedLogScanResult collectConnectedLogsWithinTreeBounds(ServerWorld world, BlockPos root) {
        return collectConnectedLogsWithinTreeBounds(root, pos -> isEligibleLog(world, pos), this::getTrunkAdjacent);
    }

    static ConnectedLogScanResult collectConnectedLogsWithinTreeBounds(BlockPos root,
                                                                       Predicate<BlockPos> isEligibleLog,
                                                                       Function<BlockPos, List<BlockPos>> adjacentProvider) {
        Set<BlockPos> logs = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        boolean usedFallbackSeed = false;
        BlockPos seed = root;
        if (!isEligibleLog.test(root)) {
            seed = findFallbackSeedWithinTreeBounds(root, isEligibleLog);
            usedFallbackSeed = seed != null;
        }
        if (seed == null) {
            return new ConnectedLogScanResult(logs, 0, false);
        }

        queue.add(seed.toImmutable());
        int rejectedCrossTreeCandidates = 0;

        while (!queue.isEmpty() && logs.size() < MAX_LOGS_PER_TREE) {
            BlockPos pos = queue.poll();
            if (!isWithinCrownRadius(root, pos)) {
                rejectedCrossTreeCandidates++;
                continue;
            }
            if (!isEligibleLog.test(pos)) {
                continue;
            }
            if (!logs.add(pos.toImmutable())) {
                continue;
            }

            for (BlockPos candidate : adjacentProvider.apply(pos)) {
                if (logs.contains(candidate)) {
                    continue;
                }
                if (!isEligibleLog.test(candidate)) {
                    continue;
                }
                if (isWithinCrownRadius(root, candidate)) {
                    queue.add(candidate.toImmutable());
                } else {
                    rejectedCrossTreeCandidates++;
                }
            }
        }

        return new ConnectedLogScanResult(logs, rejectedCrossTreeCandidates, usedFallbackSeed);
    }

    static BlockPos findFallbackSeedWithinTreeBounds(BlockPos root, Predicate<BlockPos> isEligibleLog) {
        BlockPos bestSeed = null;
        int bestDistanceSq = Integer.MAX_VALUE;
        BlockPos min = root.add(-TREE_CROWN_RADIUS, -ROOT_STRUCTURE_MAX_HEIGHT, -TREE_CROWN_RADIUS);
        BlockPos max = root.add(TREE_CROWN_RADIUS, ROOT_STRUCTURE_MAX_HEIGHT, TREE_CROWN_RADIUS);

        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockPos candidate = cursor.toImmutable();
            if (!isEligibleLog.test(candidate) || !isWithinCrownRadius(root, candidate)) {
                continue;
            }
            int distanceSq = squaredDistance(root, candidate);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestSeed = candidate;
            }
        }

        return bestSeed;
    }

    private static int squaredDistance(BlockPos first, BlockPos second) {
        int x = first.getX() - second.getX();
        int y = first.getY() - second.getY();
        int z = first.getZ() - second.getZ();
        return x * x + y * y + z * z;
    }

    record ConnectedLogScanResult(Set<BlockPos> logs, int rejectedCrossTreeCandidates, boolean usedFallbackSeed) {
    }

    private record RemainingLogCountResult(int count, boolean fallbackSeedUsed) {
    }

    private record TreeTeardownResult(int initialCandidateLogs, int brokenLogs, int brokenLeaves) {
    }

    static boolean isWithinCrownRadius(BlockPos root, BlockPos candidate) {
        return Math.abs(candidate.getX() - root.getX()) <= TREE_CROWN_RADIUS
                && Math.abs(candidate.getZ() - root.getZ()) <= TREE_CROWN_RADIUS;
    }

    private List<BlockPos> getTrunkAdjacent(BlockPos pos) {
        List<BlockPos> adjacent = new ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    adjacent.add(pos.add(x, y, z));
                }
            }
        }
        return adjacent;
    }

    private boolean breakAndCollect(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDroppedStacks(state, world, pos, blockEntity, this.guard, ItemStack.EMPTY);
        if (!world.removeBlock(pos, false)) {
            return false;
        }

        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                bufferStack(drop.copy());
            }
        }
        return true;
    }

    private void collectNearbyWoodDrops(ServerWorld world) {
        Box pickupBox = this.guard.getBoundingBox().expand(ITEM_PICKUP_RADIUS, 1.0D, ITEM_PICKUP_RADIUS);
        List<ItemEntity> nearbyItems = world.getEntitiesByClass(ItemEntity.class,
                pickupBox,
                entity -> entity.isAlive() && !entity.getStack().isEmpty() && isGatherableTreeDrop(entity.getStack()));

        for (ItemEntity itemEntity : nearbyItems) {
            bufferStack(itemEntity.getStack().copy());
            itemEntity.discard();
        }
    }

    static boolean isGatherableTreeDrop(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS)
                || stack.isIn(ItemTags.PLANKS)
                || stack.isOf(Items.STICK)
                || stack.isOf(Items.CHARCOAL)
                || stack.isIn(ItemTags.SAPLINGS)
                || stack.isOf(Items.APPLE);
    }

    private void bufferStack(ItemStack incoming) {
        List<ItemStack> buffer = this.guard.getGatheredStackBuffer();
        for (ItemStack existing : buffer) {
            if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                int move = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                existing.increment(move);
                incoming.decrement(move);
                if (incoming.isEmpty()) {
                    return;
                }
            }
        }

        if (!incoming.isEmpty()) {
            buffer.add(incoming);
        }
    }

    private Inventory resolveChestInventory(ServerWorld world) {
        BlockPos chestPos = this.guard.getPairedChestPos();
        if (chestPos == null || !world.getBlockState(chestPos).isOf(Blocks.CHEST)) {
            return null;
        }

        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }

        return ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
    }

    private int countByPredicate(Inventory inventory, java.util.function.Predicate<ItemStack> predicate) {
        if (inventory == null) {
            return 0;
        }

        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countByPredicate(List<ItemStack> stacks, java.util.function.Predicate<ItemStack> predicate) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countByItem(Inventory inventory, Item item) {
        return countByPredicate(inventory, stack -> stack.isOf(item));
    }

    private int countByItem(List<ItemStack> stacks, Item item) {
        return countByPredicate(stacks, stack -> stack.isOf(item));
    }

}
