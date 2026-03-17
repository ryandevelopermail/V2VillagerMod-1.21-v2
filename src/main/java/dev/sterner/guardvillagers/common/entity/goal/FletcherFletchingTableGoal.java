package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Countdown-based goal that lets a Fletcher use their fletching-table job block
 * to produce arrows from flint + sticks + feathers in the paired chest.
 *
 * <p>Trigger: once per day, after a random countdown (600–1200 ticks), if the
 * chest contains ≥1 flint + ≥1 stick + ≥1 feather the fletcher walks to the
 * fletching table, consumes 1 of each ingredient, and deposits 4 arrows.
 *
 * <p>No crafting table required — the fletching table IS the job block.
 */
public class FletcherFletchingTableGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(FletcherFletchingTableGoal.class);

    // Countdown window: 30–60 s at 20 tps
    private static final int COUNTDOWN_MIN_TICKS = 600;
    private static final int COUNTDOWN_MAX_TICKS = 1200;
    // How many arrows one craft yields (matches vanilla 1 flint + 1 stick + 1 feather → 4 arrows)
    private static final int ARROWS_PER_CRAFT = 4;
    // Maximum arrow batches per day
    private static final int MAX_CRAFTS_PER_DAY = 6;
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int PATH_RETRY_INTERVAL_TICKS = 20;

    private final VillagerEntity villager;
    private BlockPos jobPos;   // the fletching table block
    private BlockPos chestPos;

    private Stage stage = Stage.IDLE;
    private long countdownEndTick = 0L;
    private long lastCraftDay = -1L;
    private int craftsToday = 0;
    private boolean immediateCheckPending = false;

    // Navigation helpers
    private @Nullable BlockPos currentNavTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;

    public FletcherFletchingTableGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    /** Called when chest inventory mutates — lets the goal re-evaluate sooner. */
    public void requestImmediateCheck() {
        immediateCheckPending = true;
        // Only accelerate if we haven't fired today's max already
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.FLETCHER) {
            return false;
        }
        if (jobPos == null || chestPos == null) {
            return false;
        }

        refreshDailyLimit(world);
        if (craftsToday >= MAX_CRAFTS_PER_DAY) {
            return false;
        }

        // Arm the countdown on first eligible check of the day
        if (countdownEndTick == 0L) {
            int delay = COUNTDOWN_MIN_TICKS
                    + villager.getRandom().nextInt(COUNTDOWN_MAX_TICKS - COUNTDOWN_MIN_TICKS + 1);
            countdownEndTick = world.getTime() + delay;
            LOGGER.debug("Fletcher {} fletching-table countdown armed: {} ticks", villager.getUuidAsString(), delay);
            immediateCheckPending = false;
            return false;
        }

        // If an immediate check is requested and materials are ready, skip remaining countdown
        boolean countdownElapsed = world.getTime() >= countdownEndTick;
        if (!countdownElapsed && !immediateCheckPending) {
            return false;
        }
        immediateCheckPending = false;

        return hasArrowMaterials(world);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_TABLE;
        moveTo(jobPos);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case GO_TO_TABLE -> {
                if (isNear(jobPos)) {
                    stage = Stage.CRAFT;
                } else {
                    moveTo(jobPos);
                }
            }
            case CRAFT -> {
                craftArrows(world);
                stage = Stage.DONE;
            }
            case IDLE, DONE -> {
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void craftArrows(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }
        if (!consumeArrowIngredients(inventory)) {
            LOGGER.debug("Fletcher {} fletching-table craft failed: missing materials at execution time",
                    villager.getUuidAsString());
            return;
        }

        ItemStack arrows = new ItemStack(Items.ARROW, ARROWS_PER_CRAFT);
        insertStack(inventory, arrows);
        inventory.markDirty();
        craftsToday++;

        // Reset countdown for next batch
        int delay = COUNTDOWN_MIN_TICKS
                + villager.getRandom().nextInt(COUNTDOWN_MAX_TICKS - COUNTDOWN_MIN_TICKS + 1);
        countdownEndTick = world.getTime() + delay;

        LOGGER.info("Fletcher {} crafted {}x arrow at fletching table (craftsToday={}/{}); next countdown in {} ticks",
                villager.getUuidAsString(), ARROWS_PER_CRAFT, craftsToday, MAX_CRAFTS_PER_DAY, delay);
    }

    private boolean hasArrowMaterials(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }
        boolean hasFlint = false, hasStick = false, hasFeather = false;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            if (stack.isOf(Items.FLINT)) hasFlint = true;
            else if (stack.isOf(Items.STICK)) hasStick = true;
            else if (stack.isOf(Items.FEATHER)) hasFeather = true;
            if (hasFlint && hasStick && hasFeather) return true;
        }
        return false;
    }

    private boolean consumeArrowIngredients(Inventory inventory) {
        // Verify first, then consume — avoids partial consumption
        boolean hasFlint = false, hasStick = false, hasFeather = false;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            if (stack.isOf(Items.FLINT)) hasFlint = true;
            else if (stack.isOf(Items.STICK)) hasStick = true;
            else if (stack.isOf(Items.FEATHER)) hasFeather = true;
        }
        if (!hasFlint || !hasStick || !hasFeather) {
            return false;
        }

        boolean flintConsumed = false, stickConsumed = false, featherConsumed = false;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            if (!flintConsumed && stack.isOf(Items.FLINT)) {
                stack.decrement(1);
                if (stack.isEmpty()) inventory.setStack(slot, ItemStack.EMPTY);
                flintConsumed = true;
            } else if (!stickConsumed && stack.isOf(Items.STICK)) {
                stack.decrement(1);
                if (stack.isEmpty()) inventory.setStack(slot, ItemStack.EMPTY);
                stickConsumed = true;
            } else if (!featherConsumed && stack.isOf(Items.FEATHER)) {
                stack.decrement(1);
                if (stack.isEmpty()) inventory.setStack(slot, ItemStack.EMPTY);
                featherConsumed = true;
            }
            if (flintConsumed && stickConsumed && featherConsumed) break;
        }
        return flintConsumed && stickConsumed && featherConsumed;
    }

    private void refreshDailyLimit(ServerWorld world) {
        long day = world.getTimeOfDay() / 24000L;
        if (day != lastCraftDay) {
            lastCraftDay = day;
            craftsToday = 0;
            countdownEndTick = 0L;
            immediateCheckPending = false;
        }
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private void insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) continue;
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack toInsert = remaining.copy();
                toInsert.setCount(moved);
                inventory.setStack(slot, toInsert);
                remaining.decrement(moved);
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining) && inventory.isValid(slot, remaining)) {
                int space = existing.getMaxCount() - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(space, remaining.getCount());
                    existing.increment(moved);
                    remaining.decrement(moved);
                }
            }
        }
    }

    private void moveTo(BlockPos target) {
        if (target == null) return;
        long now = villager.getWorld().getTime();
        boolean shouldRepath = !target.equals(currentNavTarget)
                || villager.getNavigation().isIdle()
                || now - lastPathRequestTick >= PATH_RETRY_INTERVAL_TICKS;
        if (!shouldRepath) return;
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        currentNavTarget = target.toImmutable();
        lastPathRequestTick = now;
    }

    private boolean isNear(BlockPos target) {
        return target != null
                && villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private enum Stage {
        IDLE,
        GO_TO_TABLE,
        CRAFT,
        DONE
    }
}
