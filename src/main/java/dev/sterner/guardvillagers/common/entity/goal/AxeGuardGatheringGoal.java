package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class AxeGuardGatheringGoal extends Goal {
    private static final double MOVE_SPEED = 0.72D;
    private static final int TREE_SCAN_RADIUS = 24;
    private static final int TREE_SCAN_Y = 10;
    private static final int TREE_CLUSTER_RADIUS = 10;
    private static final int MAX_LOGS_PER_TREE = 96;
    private static final int NO_AXE_RETRY_TICKS = 80;
    private static final int SESSION_COOLDOWN_MIN_TICKS = 20 * 60;
    private static final int SESSION_COOLDOWN_MAX_TICKS = 20 * 60;

    private final AxeGuardEntity guard;
    private BlockPos currentTree;
    private long nextCheckTick;
    private int selectedTreeTargetCount;
    private int treesChoppedThisSession;
    private boolean sessionUsesAxe;

    public AxeGuardGatheringGoal(AxeGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    public void requestImmediateCheck() {
        nextCheckTick = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }
        if (guard.getChestPos() == null || world.getTime() < nextCheckTick) {
            return false;
        }
        if (guard.getNextSessionStartTick() > world.getTime()) {
            return false;
        }

        Inventory inventory = guard.getPairedChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }

        sessionUsesAxe = ensureAxeReady(inventory);
        if (!sessionUsesAxe) {
            nextCheckTick = world.getTime() + NO_AXE_RETRY_TICKS;
            return false;
        }

        currentTree = findNearestTreeRoot(world).orElse(null);
        selectedTreeTargetCount = MathHelper.nextInt(guard.getRandom(), 3, 5);
        treesChoppedThisSession = 0;
        nextCheckTick = world.getTime() + 20L;
        return currentTree != null;
    }

    @Override
    public boolean shouldContinue() {
        return guard.isAlive() && currentTree != null && treesChoppedThisSession < selectedTreeTargetCount;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world) || currentTree == null) {
            currentTree = null;
            return;
        }

        guard.getLookControl().lookAt(Vec3d.ofCenter(currentTree));
        if (guard.squaredDistanceTo(Vec3d.ofCenter(currentTree)) > 4.0D) {
            guard.getNavigation().startMovingTo(currentTree.getX() + 0.5D, currentTree.getY(), currentTree.getZ() + 0.5D, MOVE_SPEED);
            return;
        }

        if (chopTree(world, currentTree) > 0) {
            treesChoppedThisSession++;
        }

        if (treesChoppedThisSession >= selectedTreeTargetCount) {
            finishSession(world);
            return;
        }

        currentTree = findNearestTreeRoot(world).orElse(null);
    }

    private Optional<BlockPos> findNearestTreeRoot(ServerWorld world) {
        BlockPos center = guard.getBlockPos();
        double bestDistance = Double.MAX_VALUE;
        BlockPos best = null;

        for (BlockPos candidate : BlockPos.iterate(center.add(-TREE_SCAN_RADIUS, -TREE_SCAN_Y, -TREE_SCAN_RADIUS), center.add(TREE_SCAN_RADIUS, TREE_SCAN_Y, TREE_SCAN_RADIUS))) {
            BlockState state = world.getBlockState(candidate);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            if (world.getBlockState(candidate.down()).isIn(BlockTags.LOGS)) {
                continue;
            }
            double distance = guard.squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.toImmutable();
            }
        }

        return Optional.ofNullable(best);
    }

    private int chopTree(ServerWorld world, BlockPos rootPos) {
        Inventory inventory = guard.getPairedChestInventory(world).orElse(null);
        if (inventory == null) {
            return 0;
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(rootPos);

        int broken = 0;
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current) || !rootPos.isWithinDistance(current, TREE_CLUSTER_RADIUS)) {
                continue;
            }
            BlockState state = world.getBlockState(current);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            if (world.breakBlock(current, false, guard)) {
                broken++;
                Item droppedItem = state.getBlock().asItem();
                ItemStack remainder = guard.insertIntoInventory(inventory, new ItemStack(droppedItem));
                if (!remainder.isEmpty()) {
                    guard.dropStack(remainder);
                }
            }

            if (broken >= MAX_LOGS_PER_TREE) {
                break;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = current.add(dx, dy, dz);
                        if (!visited.contains(next) && world.getBlockState(next).isIn(BlockTags.LOGS)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }

        applyPostChopAllocation(inventory);
        inventory.markDirty();
        return broken;
    }

    private void finishSession(ServerWorld world) {
        currentTree = null;
        guard.incrementSessionsCompletedToday();
        guard.startWorkflowCountdown(world, MathHelper.nextInt(guard.getRandom(), SESSION_COOLDOWN_MIN_TICKS, SESSION_COOLDOWN_MAX_TICKS));
    }

    private void applyPostChopAllocation(Inventory inventory) {
        int totalLogs = guard.countMatching(inventory, this::isBurnableLog);
        int logsForPlanks = totalLogs / 2;
        if (logsForPlanks <= 0) {
            return;
        }

        int consumedLogs = guard.consumeMatching(inventory, this::isBurnableLog, logsForPlanks);
        if (consumedLogs <= 0) {
            return;
        }

        ItemStack plankRemainder = guard.insertIntoInventory(inventory, new ItemStack(Items.OAK_PLANKS, consumedLogs * 4));
        if (!plankRemainder.isEmpty()) {
            guard.dropStack(plankRemainder);
        }

        int producedPlanks = consumedLogs * 4;
        int planksToConvert = producedPlanks / 2;
        int availablePlanks = guard.countMatching(inventory, this::isPlanks);
        planksToConvert = Math.min(planksToConvert, availablePlanks);

        if (planksToConvert > 0) {
            int consumedPlanks = guard.consumeMatching(inventory, this::isPlanks, planksToConvert);
            if (consumedPlanks > 0) {
                ItemStack sticksRemainder = guard.insertIntoInventory(inventory, new ItemStack(Items.STICK, consumedPlanks * 2));
                if (!sticksRemainder.isEmpty()) {
                    guard.dropStack(sticksRemainder);
                }
            }
        }
    }

    private boolean ensureAxeReady(Inventory chestInventory) {
        ItemStack currentMainHand = guard.getMainHandStack();
        if (currentMainHand.getItem() instanceof AxeItem) {
            return true;
        }

        ItemStack fromInventory = takeOne(guard.getInventory(), stack -> stack.getItem() instanceof AxeItem);
        if (!fromInventory.isEmpty()) {
            guard.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, fromInventory);
            return true;
        }

        ItemStack fromChest = takeOne(chestInventory, stack -> stack.getItem() instanceof AxeItem);
        if (!fromChest.isEmpty()) {
            guard.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, fromChest);
            chestInventory.markDirty();
            return true;
        }

        return false;
    }

    private ItemStack takeOne(Inventory inventory, Predicate<ItemStack> matcher) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            return extracted;
        }
        return ItemStack.EMPTY;
    }

    private boolean isBurnableLog(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.LOGS);
    }

    private boolean isPlanks(ItemStack stack) {
        return stack.isIn(ItemTags.PLANKS);
    }
}
