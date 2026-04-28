package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Optional;

public class MoreVillagersOceanographerPaperCraftingGoal extends Goal {
    private static final Identifier OCEANOGRAPHER_PROFESSION_ID = Identifier.of("morevillagers", "oceanographer");
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final int PATH_RETRY_INTERVAL_TICKS = 20;
    private static final int SUGAR_CANE_BOOTSTRAP_RESERVE = 4;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private @Nullable BlockPos craftingTablePos;
    private Stage stage = Stage.IDLE;
    private BlockPos currentNavigationTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private long nextCheckTime;
    private boolean immediateCheckPending;
    private int lastCheckCount;

    public MoreVillagersOceanographerPaperCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.craftingTablePos = craftingTablePos == null ? null : craftingTablePos.toImmutable();
        this.stage = Stage.IDLE;
        this.currentNavigationTarget = null;
        this.lastPathRequestTick = Long.MIN_VALUE;
    }

    public @Nullable BlockPos getCraftingTablePos() {
        return craftingTablePos;
    }

    public void requestImmediateCraft(ServerWorld world) {
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay() || !villager.isAlive() || !matchesProfession()) {
            return false;
        }
        if (craftingTablePos == null || !world.getBlockState(craftingTablePos).isOf(Blocks.CRAFTING_TABLE)) {
            return false;
        }
        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        Optional<Inventory> chestInventory = getChestInventory(world);
        lastCheckCount = chestInventory.map(this::countCraftablePaperBatches).orElse(0);
        CraftingCheckLogger.report(world, "Oceanographer", immediateCheckPending ? "immediate request" : "natural interval", formatCheckResult(lastCheckCount));
        immediateCheckPending = false;
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        return lastCheckCount > 0;
    }

    @Override
    public boolean shouldContinue() {
        return villager.isAlive() && stage != Stage.IDLE && stage != Stage.DONE;
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_TABLE;
        moveTo(craftingTablePos);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        stage = Stage.IDLE;
    }

    @Override
    public void tick() {
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
            case CRAFT -> {
                craftPaper(world);
                stage = Stage.DONE;
            }
            case DONE -> stage = Stage.IDLE;
            case IDLE -> {
            }
        }
    }

    private void craftPaper(ServerWorld world) {
        Optional<Inventory> chestInventory = getChestInventory(world);
        if (chestInventory.isEmpty() || countCraftablePaperBatches(chestInventory.get()) <= 0) {
            return;
        }

        if (consumeSugarCane(chestInventory.get(), 3)) {
            insertStack(chestInventory.get(), new ItemStack(Items.PAPER, 3));
            chestInventory.get().markDirty();
            CraftingCheckLogger.report(world, "Oceanographer", formatCraftedResult(lastCheckCount));
        }
    }

    private int countCraftablePaperBatches(Inventory inventory) {
        int cane = countSugarCane(inventory);
        int craftableCane = Math.max(0, cane - SUGAR_CANE_BOOTSTRAP_RESERVE);
        return craftableCane / 3;
    }

    private int countSugarCane(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.SUGAR_CANE)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean consumeSugarCane(Inventory inventory, int count) {
        if (countSugarCane(inventory) - count < SUGAR_CANE_BOOTSTRAP_RESERVE) {
            return false;
        }

        int remaining = count;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.SUGAR_CANE)) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }
        inventory.markDirty();
        return remaining == 0;
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, false));
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

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining) || !inventory.isValid(slot, remaining)) {
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

    private boolean isNear(BlockPos target) {
        return target != null && villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private boolean matchesProfession() {
        return OCEANOGRAPHER_PROFESSION_ID.equals(Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()));
    }

    private String formatCheckResult(int craftableCount) {
        if (craftableCount == 1) {
            return "1 paper batch available to craft";
        }
        return craftableCount + " paper batches available to craft";
    }

    private String formatCraftedResult(int craftableCount) {
        if (craftableCount == 1) {
            return "1 paper batch available to craft - 3 Paper crafted";
        }
        return craftableCount + " paper batches available to craft - 3 Paper crafted";
    }

    private enum Stage {
        IDLE,
        GO_TO_TABLE,
        CRAFT,
        DONE
    }
}
