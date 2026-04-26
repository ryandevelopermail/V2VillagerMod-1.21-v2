package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.entity.goal.LumberjackGuardChopTreesGoal;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Narrow one-tree bootstrap runner for selected unemployed villagers.
 *
 * <p>This runner intentionally performs only one chop workflow and does not
 * attach full lumberjack goals to villagers.
 */
public final class LumberjackBootstrapChopRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackBootstrapChopRunner.class);
    private static final int SEARCH_RADIUS = 14;
    private static final int SEARCH_HEIGHT = 8;
    private static final double APPROACH_DISTANCE_SQ = 6.25D;
    private static final double PICKUP_RADIUS = 2.5D;

    private static final Map<UUID, RunnerState> STATES = new HashMap<>();

    private LumberjackBootstrapChopRunner() {
    }

    public static void tick(ServerWorld world, VillagerEntity villager) {
        if (!villager.isAlive() || villager.isRemoved()) {
            STATES.remove(villager.getUuid());
            return;
        }

        RunnerState state = STATES.computeIfAbsent(villager.getUuid(), ignored -> new RunnerState());
        if (state.completed || state.failed) {
            return;
        }

        BlockPos anchor = villager.getBlockPos();
        if (state.targetRoot == null) {
            state.targetRoot = LumberjackGuardChopTreesGoal.selectEligibleTreeRootNearAnchor(
                    world,
                    anchor,
                    SEARCH_RADIUS,
                    SEARCH_HEIGHT,
                    new HashSet<>());
            if (state.targetRoot == null) {
                state.failed = true;
                LumberjackBootstrapCoordinator.markFailed(world, villager);
                LOGGER.debug("lumberjack-bootstrap no eligible root for villager={} anchor={}",
                        villager.getUuidAsString(),
                        anchor.toShortString());
                return;
            }
            LumberjackBootstrapCoordinator.markChoppingOneTree(world, villager);
        }

        if (!LumberjackGuardChopTreesGoal.isEligibleTreeRoot(world, state.targetRoot)) {
            state.failed = true;
            LumberjackBootstrapCoordinator.markFailed(world, villager);
            LOGGER.debug("lumberjack-bootstrap root became invalid villager={} root={}",
                    villager.getUuidAsString(),
                    state.targetRoot.toShortString());
            return;
        }

        double distanceSq = villager.squaredDistanceTo(Vec3d.ofCenter(state.targetRoot));
        if (distanceSq > APPROACH_DISTANCE_SQ) {
            villager.getNavigation().startMovingTo(state.targetRoot.getX() + 0.5D, state.targetRoot.getY(), state.targetRoot.getZ() + 0.5D, 0.8D);
            return;
        }

        LumberjackGuardChopTreesGoal.TreeTeardownExecutionResult teardown =
                LumberjackGuardChopTreesGoal.executeTreeTeardownWithSafeguards(world, villager, state.targetRoot, stack -> bufferStack(state.buffer, stack));

        collectNearbyWoodDrops(world, villager, state.buffer);
        int remainingLogs = LumberjackGuardChopTreesGoal.countRemainingLogsWithinTreeBounds(world, state.targetRoot);

        if (teardown.brokenLogs() <= 0 || remainingLogs > 0) {
            state.failed = true;
            LumberjackBootstrapCoordinator.markFailed(world, villager);
            LOGGER.warn("lumberjack-bootstrap chop failed villager={} root={} brokenLogs={} remainingLogs={} bufferedStacks={}",
                    villager.getUuidAsString(),
                    state.targetRoot.toShortString(),
                    teardown.brokenLogs(),
                    remainingLogs,
                    state.buffer.size());
            return;
        }

        state.completed = true;
        LumberjackBootstrapCoordinator.markDone(world, villager);
        LOGGER.info("lumberjack-bootstrap chop complete villager={} root={} brokenLogs={} bufferedStacks={}",
                villager.getUuidAsString(),
                state.targetRoot.toShortString(),
                teardown.brokenLogs(),
                state.buffer.size());
    }

    public static boolean isCompleted(VillagerEntity villager) {
        RunnerState state = STATES.get(villager.getUuid());
        return state != null && state.completed;
    }

    public static boolean isFailed(VillagerEntity villager) {
        RunnerState state = STATES.get(villager.getUuid());
        return state != null && state.failed;
    }

    private static void collectNearbyWoodDrops(ServerWorld world, VillagerEntity villager, List<ItemStack> buffer) {
        Box pickupBox = villager.getBoundingBox().expand(PICKUP_RADIUS, 1.0D, PICKUP_RADIUS);
        List<ItemEntity> nearbyItems = world.getEntitiesByClass(
                ItemEntity.class,
                pickupBox,
                entity -> entity.isAlive() && !entity.getStack().isEmpty() && entity.getStack().isIn(ItemTags.LOGS));
        for (ItemEntity itemEntity : nearbyItems) {
            bufferStack(buffer, itemEntity.getStack().copy());
            itemEntity.discard();
        }
    }

    private static void bufferStack(List<ItemStack> buffer, ItemStack incoming) {
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

    private static final class RunnerState {
        @Nullable
        private BlockPos targetRoot;
        private final List<ItemStack> buffer = new ArrayList<>();
        private boolean completed;
        private boolean failed;
    }
}
