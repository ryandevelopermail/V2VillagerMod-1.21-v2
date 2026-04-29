package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public abstract class AbstractCraftingGoal<R> extends Goal {
    protected static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
    private static final int PATH_RETRY_INTERVAL_TICKS = 20;

    protected final VillagerEntity villager;
    protected BlockPos jobPos;
    protected BlockPos chestPos;
    protected @Nullable BlockPos craftingTablePos;
    protected Stage stage = Stage.IDLE;

    private long nextCheckTime;
    private long lastCraftDay = -1L;
    private int dailyCraftLimit;
    protected int craftedToday;
    protected int lastCheckCount;
    private boolean immediateCheckPending;
    private @Nullable R selectedRecipe;
    private @Nullable BlockPos currentNavigationTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;

    protected AbstractCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public final void setTargets(BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        BlockPos updatedJobPos = jobPos.toImmutable();
        BlockPos updatedChestPos = chestPos.toImmutable();
        BlockPos updatedCraftingPos = craftingTablePos == null ? null : craftingTablePos.toImmutable();
        if (updatedJobPos.equals(this.jobPos)
                && updatedChestPos.equals(this.chestPos)
                && Objects.equals(updatedCraftingPos, this.craftingTablePos)) {
            return;
        }
        this.jobPos = updatedJobPos;
        this.chestPos = updatedChestPos;
        this.craftingTablePos = updatedCraftingPos;
        this.stage = Stage.IDLE;
        this.selectedRecipe = null;
        this.currentNavigationTarget = null;
        this.lastPathRequestTick = Long.MIN_VALUE;
    }

    public @Nullable BlockPos getCraftingTablePos() {
        return craftingTablePos;
    }

    public void requestImmediateCraft(ServerWorld world) {
        refreshDailyLimit(world);
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    public void requestCraftNoSoonerThan(long targetTick) {
        if (nextCheckTime == 0L || nextCheckTime > targetTick) {
            nextCheckTime = targetTick;
        }
    }

    @Override
    public final boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay() || !hasRequiredProfession()) {
            return false;
        }
        if (jobPos == null || chestPos == null) {
            return false;
        }
        if (isBlockedByDeferredWorkflow(world)) {
            return false;
        }
        if (requiresCraftingTable() && !hasCraftingTable(world)) {
            return false;
        }

        refreshDailyLimit(world);
        if (craftedToday >= dailyCraftLimit) {
            return false;
        }
        boolean bypassCooldown = shouldBypassCooldown(world, immediateCheckPending);
        if (!bypassCooldown && world.getTime() < nextCheckTime) {
            return false;
        }

        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }

        List<R> craftableRecipes = discoverRecipes(world, inventory);
        lastCheckCount = craftableRecipes.size();
        logCheck(world, bypassCooldown, lastCheckCount);
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        immediateCheckPending = false;
        onCheckPerformed(world);

        if (craftableRecipes.isEmpty()) {
            selectedRecipe = null;
            return false;
        }

        selectedRecipe = chooseRecipe(craftableRecipes);
        return true;
    }

    @Override
    public final boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public final void start() {
        R recipe = selectedRecipe;
        if (recipe == null) {
            stage = Stage.DONE;
            return;
        }

        if (requiresCraftingTableForRecipe(recipe) && craftingTablePos != null) {
            stage = Stage.GO_TO_TABLE;
            moveTo(craftingTablePos);
            return;
        }

        stage = Stage.GO_TO_WORK;
        moveTo(getWorkPos(recipe));
    }

    @Override
    public final void stop() {
        villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        stage = Stage.DONE;
        selectedRecipe = null;
    }

    @Override
    public final void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case GO_TO_TABLE -> {
                if (isNear(craftingTablePos)) {
                    stage = Stage.CRAFT;
                } else {
                    moveTo(craftingTablePos);
                }
            }
            case GO_TO_WORK -> {
                BlockPos workPos = getWorkPos(selectedRecipe);
                if (isNear(workPos)) {
                    stage = Stage.CRAFT;
                } else {
                    moveTo(workPos);
                }
            }
            case CRAFT -> {
                craftOnce(world);
                stage = Stage.DONE;
            }
            case IDLE, DONE -> {
            }
        }
    }

    protected void logCheck(ServerWorld world, boolean immediate, int craftableCount) {
        CraftingCheckLogger.report(world, getGoalName(), immediate ? "immediate request" : "natural interval", formatCheckResult(craftableCount));
    }

    protected void onDailyReset(ServerWorld world) {
    }

    protected void onCheckPerformed(ServerWorld world) {
    }

    protected void onCraftSucceeded(ServerWorld world, R recipe) {
    }

    protected abstract boolean hasRequiredProfession();

    protected abstract String getGoalName();

    protected abstract int getDailyCraftLimit(ServerWorld world);

    protected abstract List<R> discoverRecipes(ServerWorld world, Inventory inventory);

    protected abstract boolean canStillCraftRecipe(ServerWorld world, Inventory inventory, R recipe);

    protected abstract boolean craftRecipe(ServerWorld world, Inventory inventory, R recipe);

    protected abstract ItemStack getRecipeOutput(R recipe);

    protected abstract String formatCheckResult(int craftableCount);

    protected abstract String formatCraftedResult(int craftableCount, ItemStack crafted);

    protected boolean shouldBypassCooldown(ServerWorld world, boolean immediateCheckPending) {
        return immediateCheckPending;
    }

    protected boolean isBlockedByDeferredWorkflow(ServerWorld world) {
        return false;
    }

    protected boolean requiresCraftingTable() {
        return true;
    }

    protected boolean requiresCraftingTableForRecipe(R recipe) {
        return requiresCraftingTable();
    }

    protected BlockPos getWorkPos(@Nullable R recipe) {
        return chestPos != null ? chestPos : jobPos;
    }

    protected R chooseRecipe(List<R> craftableRecipes) {
        return craftableRecipes.get(villager.getRandom().nextInt(craftableRecipes.size()));
    }

    protected Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        // Pass open=false: we are reading inventory programmatically, not via player interaction.
        // open=true would trigger the ViewerCountManager → play open-chest sound / animate lid
        // every time canStart() polls the chest, which is audibly noticeable to players.
        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        return Optional.ofNullable(inventory);
    }

    protected ItemStack insertStack(Inventory inventory, ItemStack stack) {
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

    protected boolean canInsertOutput(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return true;
            }

            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
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
            remaining.decrement(moved);
        }

        return remaining.isEmpty();
    }

    protected void moveTo(@Nullable BlockPos target) {
        if (target == null) {
            return;
        }

        long currentTick = villager.getWorld().getTime();
        boolean shouldRequestPath = !target.equals(currentNavigationTarget)
                || villager.getNavigation().isIdle()
                || currentTick - lastPathRequestTick >= PATH_RETRY_INTERVAL_TICKS;
        if (!shouldRequestPath) {
            return;
        }

        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        currentNavigationTarget = target.toImmutable();
        lastPathRequestTick = currentTick;
    }

    protected boolean isNear(@Nullable BlockPos target) {
        return target != null
                && villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void refreshDailyLimit(ServerWorld world) {
        long day = world.getTime() / 24000L;
        if (day != lastCraftDay) {
            lastCraftDay = day;
            dailyCraftLimit = getDailyCraftLimit(world);
            craftedToday = 0;
            immediateCheckPending = false;
            onDailyReset(world);
        }
    }

    private void craftOnce(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        R recipe = selectedRecipe;
        if (inventory == null || recipe == null) {
            return;
        }
        if (requiresCraftingTableForRecipe(recipe) && !hasCraftingTable(world)) {
            return;
        }
        if (!canStillCraftRecipe(world, inventory, recipe)) {
            return;
        }
        if (craftRecipe(world, inventory, recipe)) {
            inventory.markDirty();
            craftedToday++;
            onCraftSucceeded(world, recipe);
            CraftingCheckLogger.report(world, getGoalName(), formatCraftedResult(lastCheckCount, getRecipeOutput(recipe)));
        }
    }

    private boolean hasCraftingTable(ServerWorld world) {
        return craftingTablePos != null && world.getBlockState(craftingTablePos).isOf(Blocks.CRAFTING_TABLE);
    }

    protected enum Stage {
        IDLE,
        GO_TO_TABLE,
        GO_TO_WORK,
        CRAFT,
        DONE
    }
}
