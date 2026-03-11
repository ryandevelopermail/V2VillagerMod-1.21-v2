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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LumberjackGuardChopTreesGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackGuardChopTreesGoal.class);
    private static final int SESSION_TARGET_MIN = 3;
    private static final int SESSION_TARGET_MAX = 5;
    private static final int SESSION_MIN_PLANKS = 11;
    private static final int SESSION_MIN_STICKS = 4;
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
    private static final double STALL_PROGRESS_DELTA_SQ = 0.25D;
    private static final double STALL_JITTER_DELTA_SQ = 0.03D;

    private final LumberjackGuardEntity guard;
    private final Set<BlockPos> completedSessionRoots = new HashSet<>();
    private final Set<BlockPos> failedSessionRoots = new HashSet<>();
    private double lastTreeDistanceSq = Double.MAX_VALUE;
    private int stalledTicks;
    private int stallRecoveryAttempts;

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
        teardownTree(world, targetRoot);
        collectNearbyWoodDrops(world);
        this.completedSessionRoots.add(targetRoot.toImmutable());
        this.guard.getSelectedTreeTargets().remove(0);
        this.guard.setSessionTargetsRemaining(this.guard.getSessionTargetsRemaining() - 1);

        if (this.guard.getSessionTargetsRemaining() <= 0 || this.guard.getSelectedTreeTargets().isEmpty()) {
            if (!extendSessionIfMaterialThresholdUnmet(world)) {
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

        if (plankCount >= SESSION_MIN_PLANKS && stickCount >= SESSION_MIN_STICKS) {
            return false;
        }

        Set<BlockPos> excludedRoots = new HashSet<>(this.completedSessionRoots);
        excludedRoots.addAll(this.failedSessionRoots);
        excludedRoots.addAll(this.guard.getSelectedTreeTargets());

        BlockPos nextRetryRoot = null;
        for (BlockPos candidate : findTreeTargets(world)) {
            if (excludedRoots.contains(candidate)) {
                continue;
            }
            nextRetryRoot = candidate;
            break;
        }

        if (nextRetryRoot == null) {
            LOGGER.info("Lumberjack Guard {} did not meet material threshold (planks {}, sticks {}), retry cycle selected root: none",
                    this.guard.getUuidAsString(),
                    plankCount,
                    stickCount);
            return false;
        }

        this.guard.getSelectedTreeTargets().add(nextRetryRoot);
        this.guard.setSessionTargetsRemaining(this.guard.getSessionTargetsRemaining() + 1);
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_TREE);
        this.stalledTicks = 0;
        this.stallRecoveryAttempts = 0;
        this.lastTreeDistanceSq = Double.MAX_VALUE;

        LOGGER.info("Lumberjack Guard {} extending active session for material threshold (planks {}, sticks {}); retry cycle selected root {}",
                this.guard.getUuidAsString(),
                plankCount,
                stickCount,
                nextRetryRoot);
        return true;
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
        BlockPos min = originalRoot.add(-2, -3, -2);
        BlockPos max = originalRoot.add(2, 3, 2);
        BlockPos replacement = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockPos candidate = cursor.toImmutable();
            if (!isEligibleLog(world, candidate)) {
                continue;
            }

            BlockPos normalized = normalizeRoot(world, candidate);
            if (!isEligibleRoot(world, normalized)) {
                continue;
            }

            double distanceSq = normalized.getSquaredDistance(originalRoot);
            if (distanceSq < bestDistance) {
                bestDistance = distanceSq;
                replacement = normalized;
            }
        }

        LOGGER.debug("Lumberjack Guard {} target {} replacement resolution result: {}",
                this.guard.getUuidAsString(),
                originalRoot,
                replacement);
        return replacement;
    }

    private void recordFailedTargetAttempt(BlockPos targetRoot, String reason) {
        this.guard.setSessionTargetsRemaining(this.guard.getSessionTargetsRemaining() - 1);
        this.failedSessionRoots.add(targetRoot.toImmutable());
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

        return world.breakBlock(pos, true, this.guard);
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
        long remaining = this.guard.getNextChopTick() - world.getTime();
        LOGGER.info("Lumberjack Guard {} chop countdown {}% ({} ticks remaining)",
                this.guard.getUuidAsString(),
                step * 25,
                Math.max(remaining, 0L));
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

    private void teardownTree(ServerWorld world, BlockPos root) {
        Set<BlockPos> logs = new HashSet<>();
        Set<BlockPos> attachedLeaves = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(root);
        int rejectedCrossTreeCandidates = 0;

        while (!queue.isEmpty() && logs.size() < MAX_LOGS_PER_TREE) {
            BlockPos pos = queue.poll();
            if (!isWithinCrownRadius(root, pos)) {
                rejectedCrossTreeCandidates++;
                continue;
            }
            if (!logs.add(pos)) {
                continue;
            }

            for (BlockPos candidate : getTrunkAdjacent(pos)) {
                if (logs.contains(candidate)) {
                    continue;
                }
                BlockState candidateState = world.getBlockState(candidate);
                if (candidateState.isIn(BlockTags.LOGS)) {
                    if (isWithinCrownRadius(root, candidate)) {
                        queue.add(candidate.toImmutable());
                    } else {
                        rejectedCrossTreeCandidates++;
                    }
                }
            }
        }

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
                rejectedCrossTreeCandidates);

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
    }

    private boolean isWithinCrownRadius(BlockPos root, BlockPos candidate) {
        return Math.abs(candidate.getX() - root.getX()) <= TREE_CROWN_RADIUS
                && Math.abs(candidate.getZ() - root.getZ()) <= TREE_CROWN_RADIUS;
    }

    private List<BlockPos> getTrunkAdjacent(BlockPos pos) {
        return List.of(
                pos.up(),
                pos.down(),
                pos.north(),
                pos.south(),
                pos.east(),
                pos.west());
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
                entity -> entity.isAlive() && !entity.getStack().isEmpty() && isGatherableWoodDrop(entity.getStack()));

        for (ItemEntity itemEntity : nearbyItems) {
            bufferStack(itemEntity.getStack().copy());
            itemEntity.discard();
        }
    }

    private boolean isGatherableWoodDrop(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS)
                || stack.isIn(ItemTags.PLANKS)
                || stack.isOf(Items.STICK)
                || stack.isOf(Items.CHARCOAL);
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
