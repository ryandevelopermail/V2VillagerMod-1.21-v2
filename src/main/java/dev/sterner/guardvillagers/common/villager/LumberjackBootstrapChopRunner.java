package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.entity.goal.LumberjackGuardChopTreesGoal;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
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
    private static final int CRAFTING_TABLE_PLANK_COST = 4;
    private static final int MAX_PLACEMENT_RETRY_DELAY_TICKS = 20 * 30;
    private static final int CHOP_PATH_TIMEOUT_TICKS = 20 * 20;

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
                LumberjackBootstrapCoordinator.markFailure(world, villager,
                        LumberjackBootstrapCoordinator.BootstrapFailure.NO_VALID_TREE_FOUND);
                LOGGER.debug("lumberjack-bootstrap no eligible root for villager={} anchor={}",
                        villager.getUuidAsString(),
                        anchor.toShortString());
                return;
            }
            LumberjackBootstrapCoordinator.markChoppingOneTree(world, villager);
            state.pathStartTick = world.getTime();
        }

        if (!LumberjackGuardChopTreesGoal.isEligibleTreeRoot(world, state.targetRoot)) {
            state.failed = true;
            LumberjackBootstrapCoordinator.markFailure(world, villager,
                    LumberjackBootstrapCoordinator.BootstrapFailure.NO_VALID_TREE_FOUND);
            LOGGER.debug("lumberjack-bootstrap root became invalid villager={} root={}",
                    villager.getUuidAsString(),
                    state.targetRoot.toShortString());
            return;
        }

        double distanceSq = villager.squaredDistanceTo(Vec3d.ofCenter(state.targetRoot));
        if (distanceSq > APPROACH_DISTANCE_SQ) {
            if (world.getTime() - state.pathStartTick > CHOP_PATH_TIMEOUT_TICKS) {
                state.failed = true;
                LumberjackBootstrapCoordinator.markFailure(world, villager,
                        LumberjackBootstrapCoordinator.BootstrapFailure.CHOP_OR_PATH_TIMEOUT);
                LOGGER.warn("lumberjack-bootstrap path timeout villager={} root={} timeout_ticks={}",
                        villager.getUuidAsString(),
                        state.targetRoot.toShortString(),
                        CHOP_PATH_TIMEOUT_TICKS);
                return;
            }
            villager.getNavigation().startMovingTo(state.targetRoot.getX() + 0.5D, state.targetRoot.getY(), state.targetRoot.getZ() + 0.5D, 0.8D);
            return;
        }

        LumberjackGuardChopTreesGoal.TreeTeardownExecutionResult teardown =
                LumberjackGuardChopTreesGoal.executeTreeTeardownWithSafeguards(world, villager, state.targetRoot, stack -> bufferStack(state.buffer, stack));

        collectNearbyWoodDrops(world, villager, state.buffer);
        int remainingLogs = LumberjackGuardChopTreesGoal.countRemainingLogsWithinTreeBounds(world, state.targetRoot);

        if (teardown.brokenLogs() <= 0 || remainingLogs > 0) {
            state.failed = true;
            LumberjackBootstrapCoordinator.markFailure(world, villager,
                    LumberjackBootstrapCoordinator.BootstrapFailure.CHOP_OR_PATH_TIMEOUT);
            LOGGER.warn("lumberjack-bootstrap chop failed villager={} root={} brokenLogs={} remainingLogs={} bufferedStacks={}",
                    villager.getUuidAsString(),
                    state.targetRoot.toShortString(),
                    teardown.brokenLogs(),
                    remainingLogs,
                    state.buffer.size());
            return;
        }

        state.completed = true;
        LumberjackBootstrapCoordinator.markNeedsTable(world, villager);
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

    public static void clearState(VillagerEntity villager) {
        STATES.remove(villager.getUuid());
    }

    public static List<ItemStack> drainBufferedDrops(VillagerEntity villager) {
        RunnerState state = STATES.get(villager.getUuid());
        if (state == null || state.buffer.isEmpty()) {
            return List.of();
        }

        List<ItemStack> drained = new ArrayList<>(state.buffer.size());
        for (ItemStack stack : state.buffer) {
            if (!stack.isEmpty()) {
                drained.add(stack.copy());
            }
        }
        state.buffer.clear();
        return drained;
    }

    @Nullable
    public static BlockPos getPlacedTablePos(VillagerEntity villager) {
        RunnerState state = STATES.get(villager.getUuid());
        return state == null ? null : state.placedTablePos;
    }

    public static void ensureBootstrapCraftingTable(ServerWorld world, VillagerEntity villager) {
        RunnerState state = STATES.get(villager.getUuid());
        if (state == null || !state.completed || state.failed) {
            return;
        }

        if (state.placedTablePos != null && world.getBlockState(state.placedTablePos).isOf(Blocks.CRAFTING_TABLE)) {
            return;
        }

        long now = world.getTime();
        if (now < state.nextPlacementAttemptTick) {
            return;
        }

        PlacementResources resources = countPlacementResources(state.buffer);
        if (!resources.canCraftingTable()) {
            LumberjackBootstrapCoordinator.markFailure(world, villager,
                    LumberjackBootstrapCoordinator.BootstrapFailure.INSUFFICIENT_WOOD_FOR_TABLE);
            schedulePlacementRetry(state, now);
            return;
        }

        BlockPos placement = findPlacementNearVillager(world, villager);
        if (placement == null) {
            LumberjackBootstrapCoordinator.markFailure(world, villager,
                    LumberjackBootstrapCoordinator.BootstrapFailure.TABLE_PLACEMENT_BLOCKED);
            schedulePlacementRetry(state, now);
            return;
        }

        if (!consumeCraftingTableCost(state.buffer)) {
            LumberjackBootstrapCoordinator.markFailure(world, villager,
                    LumberjackBootstrapCoordinator.BootstrapFailure.INSUFFICIENT_WOOD_FOR_TABLE);
            schedulePlacementRetry(state, now);
            return;
        }

        if (!world.setBlockState(placement, Blocks.CRAFTING_TABLE.getDefaultState(), 3)) {
            LumberjackBootstrapCoordinator.markFailure(world, villager,
                    LumberjackBootstrapCoordinator.BootstrapFailure.TABLE_PLACEMENT_BLOCKED);
            schedulePlacementRetry(state, now);
            return;
        }

        ConvertedWorkerJobSiteReservationManager.reserve(
                world,
                placement,
                villager.getUuid(),
                VillagerProfession.NONE,
                "bootstrap-table-placement");
        state.placedTablePos = placement;
        state.placementRetryCount = 0;
        state.nextPlacementAttemptTick = now;
        LumberjackBootstrapCoordinator.recordPlacedTable(world, villager, placement);
        LumberjackBootstrapCoordinator.markReadyToConvert(world, villager);
        LOGGER.info("lumberjack-bootstrap placed crafting table villager={} pos={} resources={}",
                villager.getUuidAsString(),
                placement.toShortString(),
                resources);
        UnemployedLumberjackConversionHook.convert(world, villager, placement, "bootstrap-table-placed");
    }

    private static void collectNearbyWoodDrops(ServerWorld world, VillagerEntity villager, List<ItemStack> buffer) {
        Box pickupBox = villager.getBoundingBox().expand(PICKUP_RADIUS, 1.0D, PICKUP_RADIUS);
        List<ItemEntity> nearbyItems = world.getEntitiesByClass(
                ItemEntity.class,
                pickupBox,
                entity -> entity.isAlive() && !entity.getStack().isEmpty() && isGatherableBootstrapTreeDrop(entity.getStack()));
        for (ItemEntity itemEntity : nearbyItems) {
            bufferStack(buffer, itemEntity.getStack().copy());
            itemEntity.discard();
        }
    }

    static boolean isGatherableBootstrapTreeDrop(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS)
                || stack.isIn(ItemTags.PLANKS)
                || stack.isOf(Items.STICK)
                || stack.isOf(Items.CHARCOAL)
                || stack.isIn(ItemTags.SAPLINGS)
                || stack.isOf(Items.APPLE);
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

    @Nullable
    private static BlockPos findPlacementNearVillager(ServerWorld world, VillagerEntity villager) {
        BlockPos center = villager.getBlockPos();
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -1, -range), center.add(range, 1, range))) {
            if (!center.isWithinDistance(checkPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) {
                continue;
            }

            BlockPos candidate = checkPos.toImmutable();
            if (!world.getBlockState(candidate).isReplaceable()) {
                continue;
            }
            if (!world.getBlockState(candidate.down()).isSolidBlock(world, candidate.down())) {
                continue;
            }
            if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, candidate)) {
                continue;
            }
            if (UnemployedLumberjackConversionHook.isCraftingTableAlreadyPaired(world, candidate)) {
                continue;
            }
            if (!isReachable(villager, candidate)) {
                continue;
            }

            double distance = center.getSquaredDistance(candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean isReachable(VillagerEntity villager, BlockPos targetPos) {
        var path = villager.getNavigation().findPathTo(targetPos, 0);
        return path != null && path.reachesTarget();
    }

    private static void schedulePlacementRetry(RunnerState state, long now) {
        state.placementRetryCount++;
        int exponent = Math.min(state.placementRetryCount, 8);
        int delay = Math.min(1 << exponent, MAX_PLACEMENT_RETRY_DELAY_TICKS);
        state.nextPlacementAttemptTick = now + delay;
    }

    private static PlacementResources countPlacementResources(List<ItemStack> buffer) {
        int tables = countItems(buffer, Items.CRAFTING_TABLE);
        int planks = countTagged(buffer, ItemTags.PLANKS);
        int logs = countTagged(buffer, ItemTags.LOGS);
        return new PlacementResources(tables, planks, logs);
    }

    private static boolean consumeCraftingTableCost(List<ItemStack> buffer) {
        if (removeItemCount(buffer, Items.CRAFTING_TABLE, 1)) {
            return true;
        }

        int availablePlanks = countTagged(buffer, ItemTags.PLANKS);
        if (availablePlanks < CRAFTING_TABLE_PLANK_COST) {
            int logsNeeded = (int) Math.ceil((CRAFTING_TABLE_PLANK_COST - availablePlanks) / 4.0D);
            if (!removeTaggedCount(buffer, ItemTags.LOGS, logsNeeded)) {
                return false;
            }
            // Convert logs to planks in the bootstrap buffer at 1:4.
            bufferStack(buffer, new ItemStack(Items.OAK_PLANKS, logsNeeded * 4));
        }
        return removeTaggedCount(buffer, ItemTags.PLANKS, CRAFTING_TABLE_PLANK_COST);
    }

    private static int countItems(List<ItemStack> buffer, Item item) {
        int total = 0;
        for (ItemStack stack : buffer) {
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countTagged(List<ItemStack> buffer, net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
        int total = 0;
        for (ItemStack stack : buffer) {
            if (stack.isIn(tag)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean removeItemCount(List<ItemStack> buffer, Item item, int amount) {
        int remaining = amount;
        for (int i = buffer.size() - 1; i >= 0 && remaining > 0; i--) {
            ItemStack stack = buffer.get(i);
            if (!stack.isOf(item)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            stack.decrement(take);
            remaining -= take;
            if (stack.isEmpty()) {
                buffer.remove(i);
            }
        }
        return remaining == 0;
    }

    private static boolean removeTaggedCount(List<ItemStack> buffer, net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag, int amount) {
        int remaining = amount;
        for (int i = buffer.size() - 1; i >= 0 && remaining > 0; i--) {
            ItemStack stack = buffer.get(i);
            if (!stack.isIn(tag)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            stack.decrement(take);
            remaining -= take;
            if (stack.isEmpty()) {
                buffer.remove(i);
            }
        }
        return remaining == 0;
    }

    private static final class RunnerState {
        @Nullable
        private BlockPos targetRoot;
        private final List<ItemStack> buffer = new ArrayList<>();
        @Nullable
        private BlockPos placedTablePos;
        private int placementRetryCount;
        private long nextPlacementAttemptTick;
        private boolean completed;
        private boolean failed;
        private long pathStartTick;
    }

    private record PlacementResources(int tables, int planks, int logs) {
        private boolean canCraftingTable() {
            return tables > 0 || (planks + logs * 4) >= CRAFTING_TABLE_PLANK_COST;
        }
    }
}
