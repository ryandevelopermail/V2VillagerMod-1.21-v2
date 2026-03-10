package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
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
    private static final int CHOP_INTERVAL_MIN_TICKS = 20 * 60 * 3;
    private static final int CHOP_INTERVAL_MAX_TICKS = 20 * 60 * 8;
    private static final int TREE_SEARCH_RADIUS = 20;
    private static final int TREE_SEARCH_HEIGHT = 10;
    private static final int MAX_LOGS_PER_TREE = 256;

    private final LumberjackGuardEntity guard;

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
            this.guard.getSelectedTreeTargets().remove(0);
            return;
        }

        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_TREE);
        if (this.guard.squaredDistanceTo(Vec3d.ofCenter(targetRoot)) > 6.25D) {
            this.guard.getNavigation().startMovingTo(targetRoot.getX() + 0.5D, targetRoot.getY(), targetRoot.getZ() + 0.5D, 0.8D);
            return;
        }

        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.CHOPPING);
        teardownTree(world, targetRoot);
        this.guard.getSelectedTreeTargets().remove(0);
        this.guard.setSessionTargetsRemaining(this.guard.getSessionTargetsRemaining() - 1);

        if (this.guard.getSessionTargetsRemaining() <= 0 || this.guard.getSelectedTreeTargets().isEmpty()) {
            beginReturnToBase();
        }
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
        this.guard.setActiveSession(true);
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_TREE);

        LOGGER.info("Lumberjack Guard {} starting chop session with {} target(s)",
                this.guard.getUuidAsString(),
                selectedCount);
    }

    private void beginReturnToBase() {
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.RETURNING_TO_BASE);
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
        startChopCountdown((ServerWorld) this.guard.getWorld(), reason);
        LOGGER.info("Lumberjack Guard {} {} (buffer stacks: {})",
                this.guard.getUuidAsString(),
                reason,
                this.guard.getGatheredStackBuffer().size());
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
        return below.isOf(Blocks.DIRT)
                || below.isOf(Blocks.GRASS_BLOCK)
                || below.isOf(Blocks.PODZOL)
                || below.isOf(Blocks.COARSE_DIRT)
                || below.isOf(Blocks.ROOTED_DIRT)
                || below.isOf(Blocks.MOSS_BLOCK)
                || below.isOf(Blocks.MYCELIUM);
    }

    private boolean isEligibleLog(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isIn(BlockTags.LOGS);
    }

    private void teardownTree(ServerWorld world, BlockPos root) {
        Set<BlockPos> logs = new HashSet<>();
        Set<BlockPos> attachedLeaves = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty() && logs.size() < MAX_LOGS_PER_TREE) {
            BlockPos pos = queue.poll();
            if (!logs.add(pos)) {
                continue;
            }

            for (BlockPos adjacent : BlockPos.iterate(pos.add(-1, -1, -1), pos.add(1, 1, 1))) {
                BlockPos candidate = adjacent.toImmutable();
                if (logs.contains(candidate)) {
                    continue;
                }
                BlockState candidateState = world.getBlockState(candidate);
                if (candidateState.isIn(BlockTags.LOGS)) {
                    queue.add(candidate);
                } else if (candidateState.getBlock() instanceof LeavesBlock
                        && !candidateState.get(LeavesBlock.PERSISTENT)
                        && candidateState.contains(LeavesBlock.DISTANCE)
                        && candidateState.get(LeavesBlock.DISTANCE) <= 2) {
                    attachedLeaves.add(candidate);
                }
            }
        }

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
}
