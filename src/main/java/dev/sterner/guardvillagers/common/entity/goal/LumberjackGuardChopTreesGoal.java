package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
    private static final int TREE_SEARCH_HEIGHT = 10;
    private static final int MAX_LOGS_PER_TREE = 256;
    private static final int TREE_CROWN_RADIUS = 4;
    private static final int MIN_ROOT_STRUCTURE_LOGS = 4;
    private static final int ROOT_STRUCTURE_MAX_HEIGHT = 12;
    private static final int ROOT_STRUCTURE_HORIZONTAL_RADIUS = 2;
    private static final int ROOT_CANOPY_SEARCH_RADIUS = 3;
    private static final int ROOT_CANOPY_SEARCH_HEIGHT = 8;
    private static final double ITEM_PICKUP_RADIUS = 2.5D;
    private static final int PATH_STALL_TICKS = 30;
    private static final int MAX_STALL_RECOVERY_ATTEMPTS = 3;
    private static final int MAX_TEARDOWN_RETRY_ATTEMPTS_PER_ROOT = 3;
    private static final int TARGET_STUCK_FALLBACK_TICKS = 20 * 30;
    private static final double STALL_PROGRESS_DELTA_SQ = 0.25D;
    private static final double STALL_JITTER_DELTA_SQ = 0.03D;

    private final LumberjackGuardEntity guard;
    private final Set<BlockPos> completedSessionRoots = new HashSet<>();
    private final Set<BlockPos> failedSessionRoots = new HashSet<>();
    private final Map<BlockPos, Integer> rootTeardownRetryAttempts = new HashMap<>();
    private final Map<BlockPos, Integer> rootFocusTicks = new HashMap<>();
    private double lastTreeDistanceSq = Double.MAX_VALUE;
    private int stalledTicks;
    private int stallRecoveryAttempts;
    private boolean bootstrapRetryTreeScheduled;

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

        LOGGER.info("Lumberjack Guard {} tree_teardown_verification root={} initialCandidates={} brokenLogs={} remainingLogs={} fallbackSeedUsed={}",
                this.guard.getUuidAsString(),
                targetRoot,
                teardownResult.initialCandidateLogs(),
                teardownResult.brokenLogs(),
                remainingLogs,
                remainingLogCountResult.fallbackSeedUsed());

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
        if (!this.guard.isChopCountdownActive()) {
            startChopCountdown(world, "initial schedule");
        }

        if (this.guard.isChopCountdownActive()) {
            logCountdownProgress(world);
        }

        if (this.guard.isChopCountdownActive() && world.getTime() >= this.guard.getNextChopTick()) {
            this.guard.clearChopCountdown();
            startSession(world);
        }
    }

    private void startSession(ServerWorld world) {
        List<BlockPos> targets = findTreeTargets(world);
        if (targets.isEmpty()) {
            LOGGER.info("Lumberjack Guard {} could not find eligible trees near paired base; rescheduling countdown",
                    this.guard.getUuidAsString());
            startChopCountdown(world, "no eligible tree targets");
            return;
        }

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
        startChopCountdown((ServerWorld) this.guard.getWorld(), reason);
        this.stalledTicks = 0;
        this.stallRecoveryAttempts = 0;
        this.lastTreeDistanceSq = Double.MAX_VALUE;
        LOGGER.info("Lumberjack Guard {} {} (buffer stacks: {})",
                this.guard.getUuidAsString(),
                reason,
                this.guard.getGatheredStackBuffer().size());
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
        return !state.isAir()
                && (state.isIn(BlockTags.LEAVES)
                || state.isIn(BlockTags.LOGS)
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
        this.guard.startChopCountdown(world.getTime(), totalTicks);
        LOGGER.info("Lumberjack Guard {} chop countdown started ({} ticks) {}",
                this.guard.getUuidAsString(),
                totalTicks,
                reason);
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
        LOGGER.info("Lumberjack Guard {} chop countdown {}% ({} ticks remaining)",
                this.guard.getUuidAsString(),
                step * 25,
                Math.max(remaining, 0L));
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

        Set<BlockPos> excluded = new HashSet<>(guard.getSelectedTreeTargets());
        BlockPos min = center.add(-TREE_SEARCH_RADIUS, -TREE_SEARCH_HEIGHT, -TREE_SEARCH_RADIUS);
        BlockPos max = center.add(TREE_SEARCH_RADIUS, TREE_SEARCH_HEIGHT, TREE_SEARCH_RADIUS);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockPos pos = cursor.toImmutable();
            if (!center.isWithinDistance(pos, TREE_SEARCH_RADIUS)) {
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

    private static BlockPos normalizeRootStatic(ServerWorld world, BlockPos pos) {
        BlockPos.Mutable mutable = pos.mutableCopy();
        while (mutable.getY() > world.getBottomY() && world.getBlockState(mutable.down()).isIn(BlockTags.LOGS)) {
            mutable.move(0, -1, 0);
        }
        return mutable.toImmutable();
    }

    private static boolean isEligibleRootStatic(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LOGS)) {
            return false;
        }
        BlockState below = world.getBlockState(pos.down());
        if (below.isIn(BlockTags.LOGS)) {
            return false;
        }
        if (!(below.isOf(Blocks.DIRT)
                || below.isOf(Blocks.GRASS_BLOCK)
                || below.isOf(Blocks.PODZOL)
                || below.isOf(Blocks.COARSE_DIRT)
                || below.isOf(Blocks.ROOTED_DIRT)
                || below.isOf(Blocks.MOSS_BLOCK)
                || below.isOf(Blocks.MYCELIUM))) {
            return false;
        }

        return hasMinimumTreeStructureStatic(world, pos);
    }

    private static boolean hasMinimumTreeStructureStatic(ServerWorld world, BlockPos root) {
        if (!world.getBlockState(root.up()).isIn(BlockTags.LOGS) && !hasNearbyNaturalLeavesStatic(world, root)) {
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

    private List<BlockPos> findTreeTargets(ServerWorld world) {
        BlockPos table = this.guard.getPairedCraftingTablePos();
        BlockPos chest = this.guard.getPairedChestPos();
        BlockPos center = chest == null
                ? table
                : new BlockPos((table.getX() + chest.getX()) / 2, Math.min(table.getY(), chest.getY()), (table.getZ() + chest.getZ()) / 2);
        Set<BlockPos> uniqueRoots = new HashSet<>();

        BlockPos min = center.add(-TREE_SEARCH_RADIUS, -TREE_SEARCH_HEIGHT, -TREE_SEARCH_RADIUS);
        BlockPos max = center.add(TREE_SEARCH_RADIUS, TREE_SEARCH_HEIGHT, TREE_SEARCH_RADIUS);

        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockPos pos = cursor.toImmutable();
            if (!center.isWithinDistance(pos, TREE_SEARCH_RADIUS)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!isEligibleLog(world, pos) || !state.isIn(BlockTags.LOGS)) {
                continue;
            }
            BlockPos root = normalizeRoot(world, pos);
            if (!hasMinimumTreeStructure(world, root)) {
                continue;
            }
            if (isEligibleRoot(world, root)) {
                uniqueRoots.add(root);
            }
        }

        List<BlockPos> sorted = new ArrayList<>(uniqueRoots);
        sorted.sort(Comparator.comparingDouble(center::getSquaredDistance));
        return sorted;
    }

    private BlockPos normalizeRoot(ServerWorld world, BlockPos pos) {
        BlockPos.Mutable mutable = pos.mutableCopy();
        while (mutable.getY() > world.getBottomY() && world.getBlockState(mutable.down()).isIn(BlockTags.LOGS)) {
            mutable.move(0, -1, 0);
        }
        return mutable.toImmutable();
    }

    private boolean isEligibleRoot(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LOGS)) {
            return false;
        }
        BlockState below = world.getBlockState(pos.down());
        if (below.isIn(BlockTags.LOGS)) {
            return false;
        }
        if (!(below.isOf(Blocks.DIRT)
                || below.isOf(Blocks.GRASS_BLOCK)
                || below.isOf(Blocks.PODZOL)
                || below.isOf(Blocks.COARSE_DIRT)
                || below.isOf(Blocks.ROOTED_DIRT)
                || below.isOf(Blocks.MOSS_BLOCK)
                || below.isOf(Blocks.MYCELIUM))) {
            return false;
        }

        return hasMinimumTreeStructure(world, pos);
    }

    private boolean hasMinimumTreeStructure(ServerWorld world, BlockPos root) {
        if (!isEligibleLog(world, root.up()) && !hasNearbyNaturalLeaves(world, root)) {
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

            for (BlockPos adjacent : getTrunkAdjacent(current)) {
                if (adjacent.getY() < minY || adjacent.getY() > maxY) {
                    continue;
                }
                if (Math.abs(adjacent.getX() - root.getX()) > ROOT_STRUCTURE_HORIZONTAL_RADIUS
                        || Math.abs(adjacent.getZ() - root.getZ()) > ROOT_STRUCTURE_HORIZONTAL_RADIUS) {
                    continue;
                }
                if (visited.contains(adjacent) || !isEligibleLog(world, adjacent)) {
                    continue;
                }
                queue.add(adjacent.toImmutable());
            }
        }

        return false;
    }

    private boolean hasNearbyNaturalLeaves(ServerWorld world, BlockPos root) {
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

        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
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
