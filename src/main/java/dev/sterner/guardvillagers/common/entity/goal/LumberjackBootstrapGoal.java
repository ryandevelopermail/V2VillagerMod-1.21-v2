package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.LumberjackProfession;
import dev.sterner.guardvillagers.common.villager.behavior.LumberjackBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class LumberjackBootstrapGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackBootstrapGoal.class);
    private static final double MOVE_SPEED = 0.72D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int TREE_SCAN_RADIUS = 24;
    private static final int TREE_SCAN_Y = 10;
    private static final int SURFACE_TREE_SCAN_RADIUS = 40;
    private static final int SURFACE_TREE_SCAN_DEPTH = 20;
    private static final int TREE_CLUSTER_RADIUS = 10;
    private static final int MAX_LOGS_PER_TREE = 96;
    private static final int RETRY_TICKS = 80;
    private static final long STARTUP_COUNTDOWN_TICKS = 20L * 60L;

    private final VillagerEntity villager;

    private BlockPos jobPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateStart;

    private BlockPos currentTreeRoot;
    private int treeTarget;
    private int treesChopped;
    private boolean syntheticBootstrapInjected;

    public LumberjackBootstrapGoal(VillagerEntity villager, BlockPos jobPos) {
        this.villager = villager;
        setJobPos(jobPos);
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    public void setJobPos(BlockPos jobPos) {
        this.jobPos = jobPos.toImmutable();
        this.stage = Stage.IDLE;
        this.currentTreeRoot = null;
    }

    public void requestImmediateStart() {
        this.immediateStart = true;
        this.nextCheckTime = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!villager.isAlive() || villager.getVillagerData().getProfession() != LumberjackProfession.LUMBERJACK) {
            return false;
        }
        if (!world.getBlockState(jobPos).isOf(Blocks.CRAFTING_TABLE)) {
            return false;
        }
        if (JobBlockPairingHelper.findNearbyChest(world, jobPos).isPresent()) {
            return false;
        }
        if (!immediateStart && world.getTime() < nextCheckTime) {
            return false;
        }

        Inventory inventory = villager.getInventory();
        currentTreeRoot = findNearestTreeRoot(world).orElse(null);
        boolean hasStartupMaterials = hasStartupMaterials(inventory);

        if (currentTreeRoot != null) {
            LOGGER.info("Lumberjack {} bootstrap branch: local tree path selected at {}",
                    villager.getUuidAsString(),
                    currentTreeRoot.toShortString());
        } else {
            currentTreeRoot = findNearestSurfaceTreeRoot(world).orElse(null);
            if (currentTreeRoot != null) {
                LOGGER.info("Lumberjack {} bootstrap branch: surface fallback selected at {}",
                        villager.getUuidAsString(),
                        currentTreeRoot.toShortString());
            } else if (!hasStartupMaterials) {
                injectSyntheticBootstrapMaterials(inventory);
                syntheticBootstrapInjected = true;
                hasStartupMaterials = true;
                LOGGER.info("Lumberjack {} bootstrap branch: synthetic bootstrap materials fallback selected",
                        villager.getUuidAsString());
            }
        }

        if (currentTreeRoot == null && hasStartupMaterials) {
            LOGGER.info("Lumberjack {} bootstrap will proceed without tree chopping using available startup materials",
                    villager.getUuidAsString());
        }

        treeTarget = currentTreeRoot == null ? 0 : MathHelper.nextInt(villager.getRandom(), 1, 3);
        treesChopped = 0;
        immediateStart = false;
        nextCheckTime = world.getTime() + 20L;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return villager.isAlive() && stage != Stage.DONE;
    }

    @Override
    public void start() {
        if (currentTreeRoot == null) {
            stage = Stage.RETURN_TO_TABLE;
            moveTo(jobPos);
            return;
        }

        stage = Stage.MOVE_TO_TREE;
        moveTo(currentTreeRoot);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.DONE;
        currentTreeRoot = null;
        syntheticBootstrapInjected = false;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.MOVE_TO_TREE) {
            if (currentTreeRoot == null) {
                stage = Stage.RETURN_TO_TABLE;
                moveTo(jobPos);
                return;
            }

            villager.getLookControl().lookAt(Vec3d.ofCenter(currentTreeRoot));
            if (isNear(currentTreeRoot)) {
                stage = Stage.CHOP_TREE;
                return;
            }

            if (villager.getNavigation().isIdle()) {
                moveTo(currentTreeRoot);
            }
            return;
        }

        if (stage == Stage.CHOP_TREE) {
            if (currentTreeRoot == null) {
                stage = Stage.RETURN_TO_TABLE;
                moveTo(jobPos);
                return;
            }

            int brokenLogs = chopTree(world, currentTreeRoot, villager.getInventory());
            if (brokenLogs > 0) {
                treesChopped++;
            }

            if (treesChopped >= treeTarget) {
                stage = Stage.RETURN_TO_TABLE;
                moveTo(jobPos);
                return;
            }

            currentTreeRoot = findNearestTreeRoot(world).orElse(null);
            if (currentTreeRoot == null) {
                stage = Stage.RETURN_TO_TABLE;
                moveTo(jobPos);
                return;
            }

            stage = Stage.MOVE_TO_TREE;
            moveTo(currentTreeRoot);
            return;
        }

        if (stage == Stage.RETURN_TO_TABLE) {
            if (isNear(jobPos)) {
                boolean completed = completeStartupWorkflow(world);
                if (!completed) {
                    nextCheckTime = world.getTime() + RETRY_TICKS;
                }
                stage = Stage.DONE;
                return;
            }

            if (villager.getNavigation().isIdle()) {
                moveTo(jobPos);
            }
        }
    }

    private boolean completeStartupWorkflow(ServerWorld world) {
        if (JobBlockPairingHelper.findNearbyChest(world, jobPos).isPresent()) {
            return true;
        }

        Inventory inventory = villager.getInventory();
        applyStartupConversions(inventory);

        if (!ensureChestCrafted(inventory)) {
            LOGGER.info("Lumberjack {} startup delayed: unable to craft chest yet", villager.getUuidAsString());
            return false;
        }

        if (!ensureWoodenAxeCrafted(inventory)) {
            LOGGER.info("Lumberjack {} startup delayed: unable to craft wooden axe yet", villager.getUuidAsString());
            return false;
        }

        Optional<BlockPos> chestPlacement = findAdjacentPlacement(world, jobPos, Blocks.CHEST.getDefaultState());
        if (chestPlacement.isEmpty()) {
            LOGGER.info("Lumberjack {} startup delayed: no valid chest placement near crafting table {}",
                    villager.getUuidAsString(),
                    jobPos.toShortString());
            return false;
        }

        int consumedChest = consumeMatching(inventory, stack -> stack.isOf(Items.CHEST), 1);
        if (consumedChest < 1) {
            LOGGER.info("Lumberjack {} startup delayed: chest item missing before placement", villager.getUuidAsString());
            return false;
        }

        BlockPos chestPos = chestPlacement.get();
        world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());

        Inventory chestInventory = getChestInventory(world, chestPos).orElse(null);
        if (chestInventory == null) {
            LOGGER.info("Lumberjack {} startup delayed: placed chest at {} but inventory was unavailable",
                    villager.getUuidAsString(),
                    chestPos.toShortString());
            return false;
        }

        transferAll(inventory, chestInventory);
        chestInventory.markDirty();
        inventory.markDirty();

        LumberjackBehavior.queueInitialCountdown(villager, STARTUP_COUNTDOWN_TICKS);
        JobBlockPairingHelper.handlePairingBlockPlacement(world, chestPos, world.getBlockState(chestPos));

        LOGGER.info("Lumberjack {} startup complete: chopped {} tree(s), placed chest at {}, and queued {}-tick countdown",
                villager.getUuidAsString(),
                treesChopped,
                chestPos.toShortString(),
                STARTUP_COUNTDOWN_TICKS);

        if (syntheticBootstrapInjected) {
            LOGGER.info("Lumberjack {} startup completion consumed synthetic bootstrap materials fallback",
                    villager.getUuidAsString());
        }
        return true;
    }

    private void injectSyntheticBootstrapMaterials(Inventory inventory) {
        ItemStack plankRemainder = insertStack(inventory, new ItemStack(Items.OAK_PLANKS, 11));
        if (!plankRemainder.isEmpty()) {
            villager.dropStack(plankRemainder);
        }

        ItemStack stickRemainder = insertStack(inventory, new ItemStack(Items.STICK, 2));
        if (!stickRemainder.isEmpty()) {
            villager.dropStack(stickRemainder);
        }

        inventory.markDirty();
    }

    private void applyStartupConversions(Inventory inventory) {
        int totalLogs = countMatching(inventory, this::isBurnableLog);
        int logsForPlanks = totalLogs / 2;
        int logsForSticks = totalLogs / 4;

        if (logsForPlanks > 0) {
            int consumedForPlanks = consumeMatching(inventory, this::isBurnableLog, logsForPlanks);
            if (consumedForPlanks > 0) {
                insertStack(inventory, new ItemStack(Items.OAK_PLANKS, consumedForPlanks * 4));
            }
        }

        if (logsForSticks > 0) {
            int consumedForSticks = consumeMatching(inventory, this::isBurnableLog, logsForSticks);
            if (consumedForSticks > 0) {
                insertStack(inventory, new ItemStack(Items.STICK, consumedForSticks * 8));
            }
        }
    }

    private boolean ensureChestCrafted(Inventory inventory) {
        if (countMatching(inventory, stack -> stack.isOf(Items.CHEST)) > 0) {
            return true;
        }

        if (!ensurePlanks(inventory, 8)) {
            return false;
        }

        int consumed = consumeMatching(inventory, this::isPlanks, 8);
        if (consumed < 8) {
            return false;
        }

        ItemStack remainder = insertStack(inventory, new ItemStack(Items.CHEST, 1));
        if (!remainder.isEmpty()) {
            villager.dropStack(remainder);
        }
        return true;
    }

    private boolean ensureWoodenAxeCrafted(Inventory inventory) {
        if (hasAnyAxe(inventory)) {
            return true;
        }

        if (!ensurePlanks(inventory, 3)) {
            return false;
        }
        if (!ensureSticks(inventory, 2)) {
            return false;
        }

        int consumedPlanks = consumeMatching(inventory, this::isPlanks, 3);
        int consumedSticks = consumeMatching(inventory, stack -> stack.isOf(Items.STICK), 2);
        if (consumedPlanks < 3 || consumedSticks < 2) {
            return false;
        }

        ItemStack remainder = insertStack(inventory, new ItemStack(Items.WOODEN_AXE, 1));
        if (!remainder.isEmpty()) {
            villager.dropStack(remainder);
        }
        return true;
    }

    private boolean hasAnyAxe(Inventory inventory) {
        if (villager.getMainHandStack().getItem() instanceof net.minecraft.item.AxeItem) {
            return true;
        }
        return countMatching(inventory, stack -> stack.getItem() instanceof net.minecraft.item.AxeItem) > 0;
    }

    private boolean ensurePlanks(Inventory inventory, int requiredPlanks) {
        int currentPlanks = countMatching(inventory, this::isPlanks);
        if (currentPlanks >= requiredPlanks) {
            return true;
        }

        int missingPlanks = requiredPlanks - currentPlanks;
        int logsNeeded = (int) Math.ceil(missingPlanks / 4.0D);
        int consumedLogs = consumeMatching(inventory, this::isBurnableLog, logsNeeded);
        if (consumedLogs > 0) {
            insertStack(inventory, new ItemStack(Items.OAK_PLANKS, consumedLogs * 4));
        }

        return countMatching(inventory, this::isPlanks) >= requiredPlanks;
    }

    private boolean ensureSticks(Inventory inventory, int requiredSticks) {
        int currentSticks = countMatching(inventory, stack -> stack.isOf(Items.STICK));
        if (currentSticks >= requiredSticks) {
            return true;
        }

        int missingSticks = requiredSticks - currentSticks;
        int planksNeeded = (int) Math.ceil(missingSticks / 2.0D);
        if (!ensurePlanks(inventory, planksNeeded)) {
            return false;
        }

        int consumedPlanks = consumeMatching(inventory, this::isPlanks, planksNeeded);
        if (consumedPlanks > 0) {
            insertStack(inventory, new ItemStack(Items.STICK, consumedPlanks * 2));
        }

        return countMatching(inventory, stack -> stack.isOf(Items.STICK)) >= requiredSticks;
    }

    private boolean hasStartupMaterials(Inventory inventory) {
        return countMatching(inventory, this::isBurnableLog) > 0
                || countMatching(inventory, this::isPlanks) > 0
                || countMatching(inventory, stack -> stack.isOf(Items.STICK)) > 0
                || countMatching(inventory, stack -> stack.isOf(Items.CHEST)) > 0;
    }

    private Optional<BlockPos> findNearestTreeRoot(ServerWorld world) {
        BlockPos center = villager.getBlockPos();
        double bestDistance = Double.MAX_VALUE;
        BlockPos best = null;

        for (BlockPos candidate : BlockPos.iterate(
                center.add(-TREE_SCAN_RADIUS, -TREE_SCAN_Y, -TREE_SCAN_RADIUS),
                center.add(TREE_SCAN_RADIUS, TREE_SCAN_Y, TREE_SCAN_RADIUS))) {
            BlockState state = world.getBlockState(candidate);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }

            if (world.getBlockState(candidate.down()).isIn(BlockTags.LOGS)) {
                continue;
            }

            double distance = villager.squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.toImmutable();
            }
        }

        return Optional.ofNullable(best);
    }

    private Optional<BlockPos> findNearestSurfaceTreeRoot(ServerWorld world) {
        double bestDistance = Double.MAX_VALUE;
        BlockPos best = null;

        for (int x = jobPos.getX() - SURFACE_TREE_SCAN_RADIUS; x <= jobPos.getX() + SURFACE_TREE_SCAN_RADIUS; x++) {
            for (int z = jobPos.getZ() - SURFACE_TREE_SCAN_RADIUS; z <= jobPos.getZ() + SURFACE_TREE_SCAN_RADIUS; z++) {
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                int minY = Math.max(world.getBottomY(), topY - SURFACE_TREE_SCAN_DEPTH);
                int maxY = Math.min(world.getTopYInclusive(), topY + 2);

                for (int y = maxY; y >= minY; y--) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(candidate);
                    if (!state.isIn(BlockTags.LOGS)) {
                        continue;
                    }
                    if (world.getBlockState(candidate.down()).isIn(BlockTags.LOGS)) {
                        continue;
                    }

                    double distance = jobPos.getSquaredDistance(candidate);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate.toImmutable();
                    }
                }
            }
        }

        return Optional.ofNullable(best);
    }

    private int chopTree(ServerWorld world, BlockPos rootPos, Inventory inventory) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(rootPos);

        int broken = 0;
        while (!queue.isEmpty() && broken < MAX_LOGS_PER_TREE) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (!rootPos.isWithinDistance(current, TREE_CLUSTER_RADIUS)) {
                continue;
            }

            BlockState state = world.getBlockState(current);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }

            Item droppedItem = state.getBlock().asItem();
            if (world.breakBlock(current, false, villager)) {
                broken++;
                if (droppedItem != Items.AIR) {
                    ItemStack remainder = insertStack(inventory, new ItemStack(droppedItem, 1));
                    if (!remainder.isEmpty()) {
                        villager.dropStack(remainder);
                    }
                }
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = current.add(dx, dy, dz);
                        if (!visited.contains(next) && world.getBlockState(next).isIn(BlockTags.LOGS)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }

        inventory.markDirty();
        return broken;
    }

    private Optional<BlockPos> findAdjacentPlacement(ServerWorld world, BlockPos anchor, BlockState toPlace) {
        for (BlockPos candidate : List.of(anchor.north(), anchor.south(), anchor.east(), anchor.west())) {
            if (!world.getBlockState(candidate).isReplaceable()) {
                continue;
            }
            if (!toPlace.canPlaceAt(world, candidate)) {
                continue;
            }
            if (!world.getBlockState(candidate.up()).isAir()) {
                continue;
            }
            if (!world.getBlockState(candidate).getFluidState().isEmpty()) {
                continue;
            }
            if (!world.getBlockState(candidate.up()).getFluidState().isEmpty()) {
                continue;
            }
            return Optional.of(candidate.toImmutable());
        }
        return Optional.empty();
    }

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private void transferAll(Inventory source, Inventory target) {
        for (int slot = 0; slot < source.size(); slot++) {
            ItemStack stack = source.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack remainder = insertStack(target, stack.copy());
            source.setStack(slot, remainder);
        }
    }

    private int countMatching(Inventory inventory, Predicate<ItemStack> matcher) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int consumeMatching(Inventory inventory, Predicate<ItemStack> matcher, int requestedCount) {
        int remaining = requestedCount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }

            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }
        if (remaining != requestedCount) {
            inventory.markDirty();
        }
        return requestedCount - remaining;
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

    private boolean isBurnableLog(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.LOGS);
    }

    private boolean isPlanks(ItemStack stack) {
        return stack.isIn(ItemTags.PLANKS);
    }

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private enum Stage {
        IDLE,
        MOVE_TO_TREE,
        CHOP_TREE,
        RETURN_TO_TABLE,
        DONE
    }
}
