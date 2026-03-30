package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.FishermanGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.FishermanCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.FishermanDistributionGoal;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.GuardConversionHelper;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public class FishermanBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(FishermanBehavior.class);
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, FishermanCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, FishermanDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> BARREL_WATCHERS_BY_POS = new HashMap<>();
    private static final Map<VillagerEntity, Long> NEXT_CONVERSION_SCAN_TICK = new WeakHashMap<>();
    private static final long STORAGE_SCAN_COOLDOWN_TICKS = 20L;

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.FISHERMAN, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        LOGGER.debug("Fisherman {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        FishermanDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new FishermanDistributionGoal(villager, jobPos, chestPos, null);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        }
        distributionGoal.requestImmediateDistribution();

        FishermanCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal != null) {
            goal.setTargets(jobPos, chestPos, goal.getCraftingTablePos());
        }

        updateChestListener(world, villager, chestPos);
        updateBarrelListener(villager, jobPos);
        tryConvertWithRod(world, villager, jobPos, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.FISHERMAN, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        if (!world.getBlockState(craftingTablePos).isOf(Blocks.CRAFTING_TABLE)) {
            clearChestListener(villager);
            return;
        }

        LOGGER.debug("Fisherman {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        FishermanCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new FishermanCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        goal.requestImmediateCraft(world);

        FishermanDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new FishermanDistributionGoal(villager, jobPos, chestPos, craftingTablePos);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            villager.goalSelector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        distributionGoal.requestImmediateDistribution();

        updateChestListener(world, villager, chestPos);
        updateBarrelListener(villager, jobPos);
        tryConvertWithRod(world, villager, jobPos, chestPos);
    }

    public static void tryConvertFishermenWithRod(ServerWorld world) {
        // Use the candidate index only — no world-bounds fallback scan.
        // getWorldBounds() = entire world-border box (~60k×60k), O(all entities), called every 40 ticks.
        // The candidate index covers all newly-promoted fishermen; the loop body already filters by JOB_SITE,
        // so any villager not in the index with a job site will be picked up on the next index mark cycle.
        Set<VillagerEntity> candidates = new LinkedHashSet<>(VillagerConversionCandidateIndex.pollCandidates(world, VillagerProfession.FISHERMAN));

        for (VillagerEntity villager : candidates) {
            Optional<BlockPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE).map(net.minecraft.util.math.GlobalPos::pos);
            if (jobSite.isEmpty()) {
                continue;
            }

            BlockPos jobPos = jobSite.get();
            if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.FISHERMAN, world.getBlockState(jobPos))) {
                continue;
            }

            Optional<BlockPos> chestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos, jobPos)
                    .filter(foundChestPos -> jobPos.isWithinDistance(foundChestPos, 3.0D));

            tryConvertWithRod(world, villager, jobPos, chestPos.orElse(null));
        }
    }

    private static void tryConvertWithRod(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        FishermanGuardEntity guard = GuardVillagers.FISHERMAN_GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        ItemStack rodStack = takeRodFromStorage(world, jobPos, chestPos);
        if (rodStack.isEmpty()) {
            return;
        }

        GuardConversionHelper.initializeConvertedGuard(world, villager, guard, jobPos);
        GuardConversionHelper.applyStandardEquipmentDropChances(guard);
        guard.equipStack(EquipmentSlot.MAINHAND, rodStack);
        if (chestPos != null) {
            guard.setPairedChestPos(chestPos);
        }
        guard.setPairedJobPos(jobPos);

        ConvertedWorkerJobSiteReservationManager.reserve(world, jobPos, guard.getUuid(), VillagerProfession.FISHERMAN, "fisherman conversion");

        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);

        LOGGER.info("Fisherman converted into guard using fishing rod ({})",
                GuardConversionHelper.buildConversionMetadata(villager, guard, jobPos, chestPos, "fisherman paired storage"));

        GuardConversionHelper.cleanupVillagerAfterConversion(villager);
    }

    private static ItemStack takeRodFromStorage(ServerWorld world, BlockPos jobPos, BlockPos chestPos) {
        for (Inventory inventory : getStorageInventoriesForRodSearch(world, jobPos, chestPos)) {
            ItemStack extracted = takeRodFromInventory(inventory);
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    private static List<Inventory> getStorageInventoriesForRodSearch(ServerWorld world, BlockPos jobPos, BlockPos chestPos) {
        List<Inventory> inventories = new ArrayList<>(2);

        if (world.getBlockEntity(jobPos) instanceof BarrelBlockEntity barrelInventory) {
            inventories.add(barrelInventory);
        }

        if (chestPos != null) {
            BlockState chestState = world.getBlockState(chestPos);
            if (chestState.getBlock() instanceof ChestBlock chestBlock) {
                Inventory chestInventory = ChestBlock.getInventory(chestBlock, chestState, world, chestPos, false);
                if (chestInventory != null) {
                    inventories.add(chestInventory);
                }
            }
        }

        return inventories;
    }

    private static ItemStack takeRodFromInventory(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(Items.FISHING_ROD)) {
                ItemStack extracted = stack.split(1);
                inventory.markDirty();
                return extracted;
            }
        }

        return ItemStack.EMPTY;
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
        }

        for (BlockPos observedPos : observedChestPositions) {
            CHEST_WATCHERS_BY_POS.computeIfAbsent(observedPos, ignored -> new HashSet<>()).add(villager);
        }

        CHEST_REGISTRATIONS.put(villager, new ChestRegistration(villager, observedChestPositions));
    }

    private void updateBarrelListener(VillagerEntity villager, BlockPos jobPos) {
        BARREL_WATCHERS_BY_POS.computeIfAbsent(jobPos.toImmutable(), ignored -> new HashSet<>()).add(villager);
    }

    public static void onChestInventoryMutated(ServerWorld world, BlockPos chestPos) {
        Set<VillagerEntity> villagers = CHEST_WATCHERS_BY_POS.get(chestPos);
        if (villagers == null || villagers.isEmpty()) {
            return;
        }

        handleStorageMutation(world, Set.copyOf(villagers));
    }

    public static void onBarrelInventoryMutated(ServerWorld world, BlockPos barrelPos) {
        Set<VillagerEntity> watchedVillagers = BARREL_WATCHERS_BY_POS.get(barrelPos);

        // If the watcher map already has live entries for this barrel, use them directly
        // and skip the expensive world entity scan.
        if (watchedVillagers != null && !watchedVillagers.isEmpty()) {
            Set<VillagerEntity> snapshot = Set.copyOf(watchedVillagers);
            if (snapshot.stream().anyMatch(v -> v.isAlive() && v.getWorld() == world)) {
                handleStorageMutation(world, snapshot);
                return;
            }
        }

        // Fallback scan: watcher map is empty or stale (fisherman just converted/died).
        Box scanBox = new Box(barrelPos).expand(24.0D);
        Set<VillagerEntity> villagers = new java.util.LinkedHashSet<>(world.getEntitiesByClass(
                VillagerEntity.class,
                scanBox,
                villager -> villager.isAlive()
                        && villager.getVillagerData().getProfession() == VillagerProfession.FISHERMAN
                        && villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                        .map(net.minecraft.util.math.GlobalPos::pos)
                        .filter(barrelPos::equals)
                        .isPresent()
        ));

        if (villagers.isEmpty()) {
            return;
        }

        handleStorageMutation(world, Set.copyOf(villagers));
    }

    private static void handleStorageMutation(ServerWorld world, Set<VillagerEntity> snapshot) {
        boolean shouldRunConversionHooks = false;

        for (VillagerEntity villager : snapshot) {
            if (!villager.isAlive() || villager.getWorld() != world) {
                continue;
            }

            FishermanCraftingGoal goal = CRAFTING_GOALS.get(villager);
            if (goal != null) {
                goal.requestImmediateCraft(world);
            }

            FishermanDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
            if (distributionGoal != null) {
                distributionGoal.requestImmediateDistribution();
            }

            VillagerConversionCandidateIndex.markCandidate(world, villager);

            if (!isConversionScanOnCooldown(world, villager)) {
                shouldRunConversionHooks = true;
            }
        }

        if (shouldRunConversionHooks) {
            ProfessionDefinitions.runConversionHooks(world);
        }
    }

    private static boolean isConversionScanOnCooldown(ServerWorld world, VillagerEntity villager) {
        long now = world.getTime();
        long nextAllowedTick = NEXT_CONVERSION_SCAN_TICK.getOrDefault(villager, 0L);
        if (now < nextAllowedTick) {
            return true;
        }

        NEXT_CONVERSION_SCAN_TICK.put(villager, now + STORAGE_SCAN_COOLDOWN_TICKS);
        return false;
    }

    private void clearChestListener(VillagerEntity villager) {
        ChestRegistration existing = CHEST_REGISTRATIONS.remove(villager);
        if (existing != null) {
            removeChestListener(existing);
        }

        for (Set<VillagerEntity> watchers : BARREL_WATCHERS_BY_POS.values()) {
            watchers.remove(villager);
        }
        BARREL_WATCHERS_BY_POS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        NEXT_CONVERSION_SCAN_TICK.remove(villager);
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
        Set<BlockPos> positions = new HashSet<>();

        if (state.getBlock() instanceof net.minecraft.block.BarrelBlock) {
            // Barrel is a single-block container — just watch the one position.
            positions.add(chestPos.toImmutable());
            return positions;
        }

        if (!(state.getBlock() instanceof ChestBlock)) {
            return Set.of();
        }

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
}
