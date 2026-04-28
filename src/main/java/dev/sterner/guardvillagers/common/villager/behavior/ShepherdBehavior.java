package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ShepherdBedCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdBedPlacerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdFenceCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdFencePlacerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdSpecialGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdToLibrarianDistributionGoal;
import dev.sterner.guardvillagers.common.util.ShepherdPenStateHolder;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class ShepherdBehavior implements VillagerProfessionBehavior {
    enum FenceCraftingOwner {
        NONE,
        DEDICATED_FENCE_GOAL
    }
    private static final long CHEST_WAKE_DEBOUNCE_TICKS = 10L;
    private static final long CHEST_WAKE_MAX_COALESCE_DELAY_TICKS = 40L;
    private static final int SPECIAL_GOAL_PRIORITY = 3;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final int BED_CRAFTING_GOAL_PRIORITY = 6;
    private static final int BED_PLACER_GOAL_PRIORITY = 7;
    private static final int FENCE_CRAFTING_GOAL_PRIORITY = 8;
    private static final int FENCE_PLACER_GOAL_PRIORITY = 9;
    private static final Map<VillagerEntity, ShepherdCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdSpecialGoal> SPECIAL_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdToLibrarianDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdBedCraftingGoal> BED_CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdBedPlacerGoal> BED_PLACER_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdFenceCraftingGoal> FENCE_CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdFencePlacerGoal> FENCE_PLACER_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();
    private static final Map<VillagerEntity, Long> LAST_WAKE_TICKS = new WeakHashMap<>();
    private static final Map<VillagerEntity, Long> QUEUED_WAKE_TICKS = new WeakHashMap<>();
    private static final Map<VillagerEntity, Integer> DIRTY_WAKE_FLAGS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestCategorySnapshot> LAST_CHEST_SNAPSHOTS = new WeakHashMap<>();
    private static final int DIRTY_SPECIAL = 1 << 0;
    private static final int DIRTY_CRAFTING = 1 << 1;
    private static final int DIRTY_DISTRIBUTION = 1 << 2;
    private static final int DIRTY_BED = 1 << 3;
    private static final int DIRTY_FENCE = 1 << 4;

    static FenceCraftingOwner resolveFenceCraftingOwner(boolean craftingTablePaired) {
        return craftingTablePaired ? FenceCraftingOwner.DEDICATED_FENCE_GOAL : FenceCraftingOwner.NONE;
    }

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.SHEPHERD, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        ShepherdSpecialGoal specialGoal = SPECIAL_GOALS.get(villager);
        if (specialGoal == null) {
            specialGoal = new ShepherdSpecialGoal(villager, jobPos, chestPos);
            SPECIAL_GOALS.put(villager, specialGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(SPECIAL_GOAL_PRIORITY, specialGoal);
        } else {
            specialGoal.setTargets(jobPos, chestPos);
        }
        specialGoal.requestImmediateCheck();
        LAST_WAKE_TICKS.put(villager, world.getTime());
        ensureSeedLead(villager);

        ShepherdToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new ShepherdToLibrarianDistributionGoal(villager, jobPos, chestPos, null);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        }
        distributionGoal.requestImmediateDistribution();

        ShepherdCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }

        // Bed placer (runs on chest-pair only; doesn't need a crafting table)
        ShepherdBedPlacerGoal bedPlacerGoal = BED_PLACER_GOALS.get(villager);
        if (bedPlacerGoal == null) {
            bedPlacerGoal = new ShepherdBedPlacerGoal(villager, jobPos, chestPos);
            BED_PLACER_GOALS.put(villager, bedPlacerGoal);
            villager.goalSelector.add(BED_PLACER_GOAL_PRIORITY, bedPlacerGoal);
        } else {
            bedPlacerGoal.setTargets(jobPos, chestPos);
        }
        bedPlacerGoal.requestImmediateCheck();

        // Fence placer (runs on chest-pair; no crafting table needed — only places blocks)
        ShepherdFencePlacerGoal fencePlacerGoal = FENCE_PLACER_GOALS.get(villager);
        if (fencePlacerGoal == null) {
            fencePlacerGoal = new ShepherdFencePlacerGoal(villager, jobPos, chestPos);
            FENCE_PLACER_GOALS.put(villager, fencePlacerGoal);
            villager.goalSelector.add(FENCE_PLACER_GOAL_PRIORITY, fencePlacerGoal);
        } else {
            fencePlacerGoal.setTargets(jobPos, chestPos);
        }
        fencePlacerGoal.requestImmediateCheck();
        specialGoal.setFenceConstructionRequester(fencePlacerGoal::requestImmediateCheck);

        updateChestListener(world, villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        ShepherdCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new ShepherdCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        goal.requestImmediateCraft(world);
        ensureSeedLead(villager);

        // Bed crafting (requires crafting table)
        ShepherdBedCraftingGoal bedCraftingGoal = BED_CRAFTING_GOALS.get(villager);
        if (bedCraftingGoal == null) {
            bedCraftingGoal = new ShepherdBedCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            BED_CRAFTING_GOALS.put(villager, bedCraftingGoal);
            villager.goalSelector.add(BED_CRAFTING_GOAL_PRIORITY, bedCraftingGoal);
        } else {
            bedCraftingGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        bedCraftingGoal.requestImmediateCraft(world);

        if (resolveFenceCraftingOwner(true) == FenceCraftingOwner.DEDICATED_FENCE_GOAL) {
            // Fence crafting ownership lives in ShepherdFenceCraftingGoal and is table-paired only.
            ShepherdFenceCraftingGoal fenceCraftingGoal = FENCE_CRAFTING_GOALS.get(villager);
            if (fenceCraftingGoal == null) {
                fenceCraftingGoal = new ShepherdFenceCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
                FENCE_CRAFTING_GOALS.put(villager, fenceCraftingGoal);
                villager.goalSelector.add(FENCE_CRAFTING_GOAL_PRIORITY, fenceCraftingGoal);
            } else {
                fenceCraftingGoal.setTargets(jobPos, chestPos, craftingTablePos);
            }
            fenceCraftingGoal.requestImmediateCraft(world);
        }

        ShepherdToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new ShepherdToLibrarianDistributionGoal(villager, jobPos, chestPos, craftingTablePos);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            villager.goalSelector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        distributionGoal.requestImmediateDistribution();
        updateChestListener(world, villager, chestPos);
    }

    private static void ensureSeedLead(VillagerEntity villager) {
        if (!(villager instanceof ShepherdPenStateHolder holder) || holder.guardvillagers$hasSeededLead()) {
            return;
        }
        ItemStack lead = new ItemStack(Items.LEAD);
        ItemStack remaining = insertStack(villager.getInventory(), lead);
        if (remaining.isEmpty()) {
            holder.guardvillagers$setHasSeededLead(true);
            villager.getInventory().markDirty();
        }
    }

    private static ItemStack insertStack(Inventory inventory, ItemStack stack) {
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
                ItemStack inserted = remaining.copy();
                inserted.setCount(moved);
                inventory.setStack(slot, inserted);
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

    private void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Set<BlockPos> observedChestPositions = getObservedChestPositions(world, chestPos);
        if (observedChestPositions.isEmpty()) {
            clearChestListener(villager);
            return;
        }

        ChestRegistration existing = CHEST_REGISTRATIONS.get(villager);
        if (existing != null && existing.observedChestPositions().equals(observedChestPositions)) {
            return;
        }

        if (existing != null) {
            removeChestListener(existing);
            CHEST_REGISTRATIONS.remove(villager);
            clearWakeScheduler(villager);
        }

        for (BlockPos observedPos : observedChestPositions) {
            CHEST_WATCHERS_BY_POS.computeIfAbsent(observedPos, ignored -> new HashSet<>()).add(villager);
        }

        CHEST_REGISTRATIONS.put(villager, new ChestRegistration(villager, observedChestPositions));
    }

    public static void onChestInventoryMutated(ServerWorld world, BlockPos chestPos) {
        Set<VillagerEntity> villagers = CHEST_WATCHERS_BY_POS.get(chestPos);
        if (villagers == null || villagers.isEmpty()) {
            return;
        }

        Set<VillagerEntity> snapshot = Set.copyOf(villagers);
        for (VillagerEntity villager : snapshot) {
            if (!villager.isAlive() || villager.getWorld() != world) {
                continue;
            }
            int dirtyFlags = detectDirtyCategories(world, villager, chestPos);
            scheduleVillagerWakeup(world, villager, dirtyFlags);
        }
    }

    private static int detectDirtyCategories(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        ChestCategorySnapshot currentSnapshot = ChestCategorySnapshot.capture(world, chestPos);
        ChestCategorySnapshot previousSnapshot = LAST_CHEST_SNAPSHOTS.put(villager, currentSnapshot);
        if (previousSnapshot == null) {
            return DIRTY_SPECIAL | DIRTY_CRAFTING | DIRTY_DISTRIBUTION | DIRTY_BED | DIRTY_FENCE;
        }
        return computeDirtyFlags(previousSnapshot, currentSnapshot);
    }

    private static int computeDirtyFlags(ChestCategorySnapshot previous, ChestCategorySnapshot current) {
        if (previous.equals(current)) {
            return 0;
        }
        int flags = 0;
        boolean specialItemsChanged = previous.banners != current.banners
                || previous.shears != current.shears
                || previous.wheat != current.wheat;
        if (specialItemsChanged) {
            flags |= DIRTY_SPECIAL;
        }

        boolean craftingMaterialsChanged = previous.wool != current.wool
                || previous.sticks != current.sticks;
        if (craftingMaterialsChanged) {
            flags |= DIRTY_CRAFTING;
        }

        boolean distributionItemsChanged = previous.wool != current.wool
                || previous.stringCount != current.stringCount;
        if (distributionItemsChanged) {
            flags |= DIRTY_DISTRIBUTION;
        }

        boolean bedRelatedChanged = previous.beds != current.beds
                || previous.wool != current.wool
                || previous.planks != current.planks;
        if (bedRelatedChanged) {
            flags |= DIRTY_BED;
        }

        boolean fenceRelatedChanged = previous.fences != current.fences
                || previous.fenceGates != current.fenceGates
                || previous.planks != current.planks
                || previous.sticks != current.sticks;
        if (fenceRelatedChanged) {
            flags |= DIRTY_FENCE;
        }
        return flags;
    }

    private static void scheduleVillagerWakeup(ServerWorld world, VillagerEntity villager, int dirtyFlags) {
        if (dirtyFlags == 0) {
            return;
        }
        int accumulatedFlags = DIRTY_WAKE_FLAGS.getOrDefault(villager, 0) | dirtyFlags;
        DIRTY_WAKE_FLAGS.put(villager, accumulatedFlags);

        long now = world.getTime();
        long lastWakeTick = LAST_WAKE_TICKS.getOrDefault(villager, Long.MIN_VALUE);
        if (now - lastWakeTick >= CHEST_WAKE_DEBOUNCE_TICKS) {
            QUEUED_WAKE_TICKS.remove(villager);
            triggerChestWakeups(world, villager, accumulatedFlags);
            DIRTY_WAKE_FLAGS.remove(villager);
            LAST_WAKE_TICKS.put(villager, now);
            return;
        }

        long targetTick = lastWakeTick + CHEST_WAKE_MAX_COALESCE_DELAY_TICKS;
        long existingTarget = QUEUED_WAKE_TICKS.getOrDefault(villager, Long.MAX_VALUE);
        if (targetTick < existingTarget) {
            QUEUED_WAKE_TICKS.put(villager, targetTick);
        }
        requestDeferredWake(villager, world, targetTick, accumulatedFlags);
    }

    private static void requestDeferredWake(VillagerEntity villager, ServerWorld world, long targetTick, int dirtyFlags) {
        if ((dirtyFlags & DIRTY_SPECIAL) != 0) {
            ShepherdSpecialGoal specialGoal = SPECIAL_GOALS.get(villager);
            if (specialGoal != null) {
                specialGoal.onChestInventoryChanged(world);
                specialGoal.requestCheckNoSoonerThan(targetTick);
            }
        }
        if ((dirtyFlags & DIRTY_CRAFTING) != 0) {
            ShepherdCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
            if (craftingGoal != null) {
                craftingGoal.requestCraftNoSoonerThan(targetTick);
            }
        }
        if ((dirtyFlags & DIRTY_BED) != 0) {
            ShepherdBedCraftingGoal bedCraftingGoal = BED_CRAFTING_GOALS.get(villager);
            if (bedCraftingGoal != null) {
                bedCraftingGoal.requestCraftNoSoonerThan(targetTick);
            }
        }
        if ((dirtyFlags & DIRTY_FENCE) != 0 && resolveFenceCraftingOwner(FENCE_CRAFTING_GOALS.containsKey(villager)) == FenceCraftingOwner.DEDICATED_FENCE_GOAL) {
            ShepherdFenceCraftingGoal fenceCraftingGoal = FENCE_CRAFTING_GOALS.get(villager);
            if (fenceCraftingGoal != null) {
                fenceCraftingGoal.requestCraftNoSoonerThan(targetTick);
            }
        }
    }

    private static void triggerChestWakeups(ServerWorld world, VillagerEntity villager, int dirtyFlags) {
        if ((dirtyFlags & DIRTY_SPECIAL) != 0) {
            ShepherdSpecialGoal specialGoal = SPECIAL_GOALS.get(villager);
            if (specialGoal != null) {
                specialGoal.onChestInventoryChanged(world);
                specialGoal.requestImmediateCheck();
            }
        }
        if ((dirtyFlags & DIRTY_CRAFTING) != 0) {
            ShepherdCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
            if (craftingGoal != null) {
                craftingGoal.requestImmediateCraft(world);
            }
        }
        if ((dirtyFlags & DIRTY_DISTRIBUTION) != 0) {
            ShepherdToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
            if (distributionGoal != null) {
                distributionGoal.requestImmediateDistribution();
            }
        }
        if ((dirtyFlags & DIRTY_BED) != 0) {
            ShepherdBedCraftingGoal bedCraftingGoal = BED_CRAFTING_GOALS.get(villager);
            if (bedCraftingGoal != null) {
                bedCraftingGoal.requestImmediateCraft(world);
            }
            ShepherdBedPlacerGoal bedPlacerGoal = BED_PLACER_GOALS.get(villager);
            if (bedPlacerGoal != null) {
                bedPlacerGoal.requestImmediateCheck();
            }
        }
        if ((dirtyFlags & DIRTY_FENCE) != 0) {
            if (resolveFenceCraftingOwner(FENCE_CRAFTING_GOALS.containsKey(villager)) == FenceCraftingOwner.DEDICATED_FENCE_GOAL) {
                ShepherdFenceCraftingGoal fenceCraftingGoal = FENCE_CRAFTING_GOALS.get(villager);
                if (fenceCraftingGoal != null) {
                    fenceCraftingGoal.requestImmediateCraft(world);
                }
            }
            ShepherdFencePlacerGoal fencePlacerGoal = FENCE_PLACER_GOALS.get(villager);
            if (fencePlacerGoal != null) {
                fencePlacerGoal.requestImmediateCheck();
            }
        }
    }

    private static void clearWakeScheduler(VillagerEntity villager) {
        LAST_WAKE_TICKS.remove(villager);
        QUEUED_WAKE_TICKS.remove(villager);
        DIRTY_WAKE_FLAGS.remove(villager);
        LAST_CHEST_SNAPSHOTS.remove(villager);
    }

    private void clearChestListener(VillagerEntity villager) {
        ChestRegistration existing = CHEST_REGISTRATIONS.remove(villager);
        clearWakeScheduler(villager);
        if (existing != null) {
            removeChestListener(existing);
        }
    }

    private void removeChestListener(ChestRegistration existing) {
        for (BlockPos observedPos : existing.observedChestPositions()) {
            Set<VillagerEntity> watchers = CHEST_WATCHERS_BY_POS.get(observedPos);
            if (watchers == null) {
                continue;
            }
            watchers.remove(existing.villager());
            if (watchers.isEmpty()) {
                CHEST_WATCHERS_BY_POS.remove(observedPos);
            }
        }
    }

    private Set<BlockPos> getObservedChestPositions(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return Set.of();
        }

        Set<BlockPos> positions = new HashSet<>();
        positions.add(chestPos.toImmutable());

        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType != ChestType.SINGLE) {
            Direction facing = state.get(ChestBlock.FACING);
            Direction offsetDirection = chestType == ChestType.LEFT
                    ? facing.rotateYClockwise()
                    : facing.rotateYCounterclockwise();
            BlockPos otherHalfPos = chestPos.offset(offsetDirection);
            BlockState otherState = world.getBlockState(otherHalfPos);
            if (otherState.getBlock() instanceof ChestBlock && otherState.get(ChestBlock.FACING) == facing) {
                positions.add(otherHalfPos.toImmutable());
            }
        }

        return positions;
    }

    private record ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
        private ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
            this.villager = villager;
            this.observedChestPositions = Set.copyOf(observedChestPositions);
        }
    }

    private record ChestCategorySnapshot(int banners, int shears, int wheat, int wool, int sticks, int planks,
                                         int beds, int fences, int fenceGates, int stringCount) {
        private static ChestCategorySnapshot capture(ServerWorld world, BlockPos chestPos) {
            BlockState state = world.getBlockState(chestPos);
            if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
                return empty();
            }
            Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
            if (inventory == null) {
                return empty();
            }

            int banners = 0;
            int shears = 0;
            int wheat = 0;
            int wool = 0;
            int sticks = 0;
            int planks = 0;
            int beds = 0;
            int fences = 0;
            int fenceGates = 0;
            int stringCount = 0;
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                int count = stack.getCount();
                if (stack.isIn(ItemTags.BANNERS)) banners += count;
                if (stack.isOf(Items.SHEARS)) shears += count;
                if (stack.isOf(Items.WHEAT)) wheat += count;
                if (stack.isIn(ItemTags.WOOL)) wool += count;
                if (stack.isOf(Items.STICK)) sticks += count;
                if (stack.isIn(ItemTags.PLANKS)) planks += count;
                if (stack.isIn(ItemTags.BEDS)) beds += count;
                if (stack.isIn(ItemTags.FENCES)) fences += count;
                if (stack.isIn(ItemTags.FENCE_GATES)) fenceGates += count;
                if (stack.isOf(Items.STRING)) stringCount += count;
            }
            return new ChestCategorySnapshot(banners, shears, wheat, wool, sticks, planks, beds, fences, fenceGates, stringCount);
        }

        private static ChestCategorySnapshot empty() {
            return new ChestCategorySnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
