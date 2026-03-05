package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.LumberjackProfession;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class LumberjackGatheringGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackGatheringGoal.class);
    private static final double MOVE_SPEED = 0.72D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int TREE_SCAN_RADIUS = 24;
    private static final int TREE_SCAN_Y = 10;
    private static final int MAX_LOGS_PER_TREE = 96;
    private static final int TREE_CLUSTER_RADIUS = 10;
    private static final int NO_AXE_RETRY_TICKS = 80;
    private static final int SESSION_COOLDOWN_MIN_TICKS = 20 * 60;
    private static final int SESSION_COOLDOWN_MAX_TICKS = 20 * 60;

    private final VillagerEntity villager;

    private BlockPos jobPos;
    private BlockPos chestPos;
    private BlockPos craftingTablePos;

    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateCheckPending;

    private long lastScheduleDay = -1L;
    private int sessionsCompletedToday;
    private int sessionsTargetToday;
    private boolean firstAxeSessionCompleted;

    private long sessionCountdownStartTick;
    private long sessionCountdownTotalTicks;
    private long nextSessionStartTick;
    private int lastCountdownLogStep;
    private boolean sessionCountdownActive;

    private BlockPos currentTreeRoot;
    private int selectedTreeTargetCount;
    private int treesChoppedThisSession;
    private boolean sessionUsesAxe;

    public LumberjackGatheringGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.craftingTablePos = craftingTablePos == null ? null : craftingTablePos.toImmutable();
        this.stage = Stage.IDLE;
        this.currentTreeRoot = null;
    }

    public void requestImmediateCheck() {
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay() || !villager.isAlive()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != LumberjackProfession.LUMBERJACK) {
            return false;
        }
        if (chestPos == null) {
            return false;
        }
        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }

        sessionUsesAxe = ensureAxeReady(inventory);
        refreshDailySchedule(world, sessionUsesAxe);

        if (sessionUsesAxe) {
            if (sessionCountdownActive && nextSessionStartTick > 0L) {
                logSessionCountdownProgress(world);
                if (world.getTime() < nextSessionStartTick) {
                    return false;
                }
            }

            if (sessionsCompletedToday >= sessionsTargetToday) {
                return false;
            }
        }

        currentTreeRoot = findNearestTreeRoot(world).orElse(null);
        if (currentTreeRoot == null) {
            nextCheckTime = world.getTime() + NO_AXE_RETRY_TICKS;
            immediateCheckPending = false;
            return false;
        }

        selectedTreeTargetCount = sessionUsesAxe
                ? MathHelper.nextInt(villager.getRandom(), 3, 5)
                : MathHelper.nextInt(villager.getRandom(), 1, 2);
        treesChoppedThisSession = 0;
        immediateCheckPending = false;
        nextCheckTime = world.getTime() + 20L;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return villager.isAlive() && stage != Stage.DONE;
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_TREE;
        if (currentTreeRoot != null) {
            moveTo(currentTreeRoot);
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.DONE;
        currentTreeRoot = null;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.MOVE_TO_TREE) {
            if (currentTreeRoot == null) {
                finishSession(world, "no tree target");
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
                finishSession(world, "lost current tree");
                return;
            }

            int brokenLogs = chopTree(world, currentTreeRoot);
            if (brokenLogs > 0) {
                treesChoppedThisSession++;
            }

            if (treesChoppedThisSession >= selectedTreeTargetCount) {
                finishSession(world, "tree target reached");
                return;
            }

            currentTreeRoot = findNearestTreeRoot(world).orElse(null);
            if (currentTreeRoot == null) {
                finishSession(world, "no additional trees found");
                return;
            }

            stage = Stage.MOVE_TO_TREE;
            moveTo(currentTreeRoot);
        }
    }

    private void refreshDailySchedule(ServerWorld world, boolean hasAxe) {
        long day = world.getTimeOfDay() / 24000L;
        if (day != lastScheduleDay) {
            lastScheduleDay = day;
            sessionsCompletedToday = 0;
            sessionsTargetToday = firstAxeSessionCompleted
                    ? MathHelper.nextInt(villager.getRandom(), 2, 4)
                    : (hasAxe ? 1 : 0);
            clearSessionCountdown();
            return;
        }

        if (!firstAxeSessionCompleted && hasAxe && sessionsTargetToday == 0) {
            sessionsTargetToday = 1;
        }
    }

    private void finishSession(ServerWorld world, String reason) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory != null) {
            applyPostChopAllocation(inventory);
            inventory.markDirty();
        }

        if (sessionUsesAxe) {
            sessionsCompletedToday++;

            if (!firstAxeSessionCompleted) {
                firstAxeSessionCompleted = true;
                sessionsTargetToday = sessionsCompletedToday + MathHelper.nextInt(villager.getRandom(), 2, 4);
            }

            startSessionCountdown(world, reason);
        } else {
            nextCheckTime = world.getTime() + NO_AXE_RETRY_TICKS;
        }

        LOGGER.info("Lumberjack {} finished chopping session: treesChopped={}, target={}, usedAxe={}, reason={}",
                villager.getUuidAsString(),
                treesChoppedThisSession,
                selectedTreeTargetCount,
                sessionUsesAxe,
                reason);

        stage = Stage.DONE;
    }

    private void startSessionCountdown(ServerWorld world, String reason) {
        startExternalCountdown(world,
                MathHelper.nextInt(villager.getRandom(), SESSION_COOLDOWN_MIN_TICKS, SESSION_COOLDOWN_MAX_TICKS),
                reason);
    }
    public void startExternalCountdown(ServerWorld world, long countdownTicks, String reason) {
        sessionCountdownTotalTicks = Math.max(20L, countdownTicks);
        sessionCountdownStartTick = world.getTime();
        nextSessionStartTick = sessionCountdownStartTick + sessionCountdownTotalTicks;
        lastCountdownLogStep = 0;
        sessionCountdownActive = true;

        LOGGER.info("Lumberjack {} chopping countdown started ({} ticks) {}",
                villager.getUuidAsString(),
                sessionCountdownTotalTicks,
                reason);
    }

    private void logSessionCountdownProgress(ServerWorld world) {
        if (!sessionCountdownActive || sessionCountdownTotalTicks <= 0L) {
            return;
        }

        long elapsedTicks = world.getTime() - sessionCountdownStartTick;
        long remainingTicks = Math.max(0L, nextSessionStartTick - world.getTime());
        int step = Math.min(4, (int) ((elapsedTicks * 4L) / sessionCountdownTotalTicks));
        if (step <= lastCountdownLogStep || step == 0) {
            return;
        }

        lastCountdownLogStep = step;
        LOGGER.info("Lumberjack {} chopping countdown {}% ({} ticks remaining)",
                villager.getUuidAsString(),
                step * 25,
                remainingTicks);
    }

    private void clearSessionCountdown() {
        sessionCountdownStartTick = 0L;
        sessionCountdownTotalTicks = 0L;
        nextSessionStartTick = 0L;
        lastCountdownLogStep = 0;
        sessionCountdownActive = false;
    }

    private Optional<BlockPos> findNearestTreeRoot(ServerWorld world) {
        BlockPos center = villager.getBlockPos();
        double bestDistance = Double.MAX_VALUE;
        BlockPos best = null;

        for (BlockPos candidate : BlockPos.iterate(
                center.add(-TREE_SCAN_RADIUS, -TREE_SCAN_Y, -TREE_SCAN_RADIUS),
                center.add(TREE_SCAN_RADIUS, TREE_SCAN_Y, TREE_SCAN_RADIUS))) {
            BlockState state = world.getBlockState(candidate);
            if (!isLog(state)) {
                continue;
            }

            BlockPos below = candidate.down();
            if (isLog(world.getBlockState(below))) {
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

    private int chopTree(ServerWorld world, BlockPos rootPos) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return 0;
        }

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
            if (!isLog(state)) {
                continue;
            }

            Item droppedItem = state.getBlock().asItem();
            if (world.breakBlock(current, false, villager)) {
                broken++;
                if (droppedItem != Items.AIR) {
                    ItemStack remaining = insertStack(inventory, new ItemStack(droppedItem));
                    if (!remaining.isEmpty()) {
                        villager.dropStack(remaining);
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
                        if (!visited.contains(next) && isLog(world.getBlockState(next))) {
                            queue.add(next);
                        }
                    }
                }
            }
        }

        inventory.markDirty();
        return broken;
    }

    private void applyPostChopAllocation(Inventory inventory) {
        int totalLogs = countMatching(inventory, this::isBurnableLog);
        int logsForPlanks = totalLogs / 2;
        if (logsForPlanks <= 0) {
            return;
        }

        int consumedLogs = consumeMatching(inventory, this::isBurnableLog, logsForPlanks);
        if (consumedLogs <= 0) {
            return;
        }

        ItemStack plankOutput = new ItemStack(Items.OAK_PLANKS, consumedLogs * 4);
        ItemStack plankRemainder = insertStack(inventory, plankOutput);
        if (!plankRemainder.isEmpty()) {
            villager.dropStack(plankRemainder);
        }

        int producedPlanks = consumedLogs * 4;
        int planksToConvert = producedPlanks / 2;
        int availablePlanks = countMatching(inventory, this::isPlanks);
        planksToConvert = Math.min(planksToConvert, availablePlanks);

        if (planksToConvert > 0) {
            int consumedPlanks = consumeMatching(inventory, this::isPlanks, planksToConvert);
            if (consumedPlanks > 0) {
                ItemStack sticksOutput = new ItemStack(Items.STICK, consumedPlanks * 2);
                ItemStack sticksRemainder = insertStack(inventory, sticksOutput);
                if (!sticksRemainder.isEmpty()) {
                    villager.dropStack(sticksRemainder);
                }
            }
        }
    }

    private boolean ensureAxeReady(Inventory chestInventory) {
        ItemStack currentMainHand = villager.getMainHandStack();
        if (currentMainHand.getItem() instanceof AxeItem) {
            return true;
        }

        ItemStack fromVillagerInventory = takeOne(villager.getInventory(), stack -> stack.getItem() instanceof AxeItem);
        if (!fromVillagerInventory.isEmpty()) {
            villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, fromVillagerInventory);
            return true;
        }

        ItemStack fromChest = takeOne(chestInventory, stack -> stack.getItem() instanceof AxeItem);
        if (!fromChest.isEmpty()) {
            villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, fromChest);
            chestInventory.markDirty();
            return true;
        }

        if (!craftBestAvailableAxe(chestInventory)) {
            return false;
        }

        ItemStack craftedFromChest = takeOne(chestInventory, stack -> stack.getItem() instanceof AxeItem);
        if (!craftedFromChest.isEmpty()) {
            villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, craftedFromChest);
            chestInventory.markDirty();
            return true;
        }

        return villager.getMainHandStack().getItem() instanceof AxeItem;
    }

    private boolean craftBestAvailableAxe(Inventory inventory) {
        ensureStickSupply(inventory, 2);

        AxeRecipe recipe = AxeRecipe.findBestCraftable(inventory);
        if (recipe == null) {
            return false;
        }

        int consumedToolMaterial = consumeMatching(inventory, recipe.materialPredicate, 3);
        if (consumedToolMaterial < 3) {
            return false;
        }

        int consumedSticks = consumeMatching(inventory, stack -> stack.isOf(Items.STICK), 2);
        if (consumedSticks < 2) {
            return false;
        }

        ItemStack remaining = insertStack(inventory, recipe.output.copy());
        if (!remaining.isEmpty()) {
            villager.dropStack(remaining);
        }
        inventory.markDirty();
        LOGGER.info("Lumberjack {} crafted {}", villager.getUuidAsString(), recipe.output.getItem());
        return true;
    }

    private void ensureStickSupply(Inventory inventory, int requiredSticks) {
        int currentSticks = countMatching(inventory, stack -> stack.isOf(Items.STICK));
        if (currentSticks >= requiredSticks) {
            return;
        }

        int planksNeeded = (int) Math.ceil((requiredSticks - currentSticks) / 2.0D);
        int availablePlanks = countMatching(inventory, this::isPlanks);

        if (availablePlanks < planksNeeded) {
            int missingPlanks = planksNeeded - availablePlanks;
            int logsToConvert = (int) Math.ceil(missingPlanks / 4.0D);
            int consumedLogs = consumeMatching(inventory, this::isBurnableLog, logsToConvert);
            if (consumedLogs > 0) {
                insertStack(inventory, new ItemStack(Items.OAK_PLANKS, consumedLogs * 4));
            }
        }

        int planksForSticks = Math.min(countMatching(inventory, this::isPlanks), planksNeeded);
        if (planksForSticks > 0) {
            int consumedPlanks = consumeMatching(inventory, this::isPlanks, planksForSticks);
            if (consumedPlanks > 0) {
                insertStack(inventory, new ItemStack(Items.STICK, consumedPlanks * 2));
            }
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

    private boolean isLog(BlockState state) {
        return state.isIn(BlockTags.LOGS);
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
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
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private enum Stage {
        IDLE,
        MOVE_TO_TREE,
        CHOP_TREE,
        DONE
    }

    private enum AxeRecipe {
        DIAMOND(new ItemStack(Items.DIAMOND_AXE), stack -> stack.isOf(Items.DIAMOND)),
        IRON(new ItemStack(Items.IRON_AXE), stack -> stack.isOf(Items.IRON_INGOT)),
        STONE(new ItemStack(Items.STONE_AXE), stack -> stack.isOf(Items.COBBLESTONE)),
        GOLDEN(new ItemStack(Items.GOLDEN_AXE), stack -> stack.isOf(Items.GOLD_INGOT)),
        WOODEN(new ItemStack(Items.WOODEN_AXE), stack -> stack.isIn(ItemTags.PLANKS));

        private final ItemStack output;
        private final Predicate<ItemStack> materialPredicate;

        AxeRecipe(ItemStack output, Predicate<ItemStack> materialPredicate) {
            this.output = output;
            this.materialPredicate = materialPredicate;
        }

        private static AxeRecipe findBestCraftable(Inventory inventory) {
            for (AxeRecipe recipe : values()) {
                int materials = 0;
                int sticks = 0;
                for (int slot = 0; slot < inventory.size(); slot++) {
                    ItemStack stack = inventory.getStack(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    if (recipe.materialPredicate.test(stack)) {
                        materials += stack.getCount();
                    }
                    if (stack.isOf(Items.STICK)) {
                        sticks += stack.getCount();
                    }
                }

                if (materials >= 3 && sticks >= 2) {
                    return recipe;
                }
            }

            return null;
        }
    }
}

