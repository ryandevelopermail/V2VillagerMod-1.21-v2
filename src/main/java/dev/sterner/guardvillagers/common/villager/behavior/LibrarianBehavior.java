package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.LibrarianCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.LibrarianBellChestDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.QuartermasterGoal;
import dev.sterner.guardvillagers.common.util.QuartermasterPrerequisiteHelper;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

public class LibrarianBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(LibrarianBehavior.class);
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;
    private static final int QUARTERMASTER_GOAL_PRIORITY = 3;
    private static final long INVENTORY_MUTATION_DEBOUNCE_TICKS = 30L;
    private static final long QUARTERMASTER_PAIR_REVALIDATION_GUARD_TICKS = 1L;
    private static final Map<VillagerEntity, LibrarianCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LibrarianBellChestDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, QuartermasterGoal> QUARTERMASTER_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, BlockPos> PAIRED_CHEST_POS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();
    private static final Map<VillagerEntity, Long> LAST_IMMEDIATE_REQUEST_TICK = new WeakHashMap<>();
    private static final Map<VillagerEntity, Boolean> INVENTORY_DIRTY_FLAGS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LastQuartermasterPair> LAST_QUARTERMASTER_PAIR = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            demoteQuartermaster(world, villager, "villager_not_alive");
            clearChestWatcher(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.LIBRARIAN, world.getBlockState(jobPos))) {
            demoteQuartermaster(world, villager, "invalid_job_site");
            clearChestWatcher(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            demoteQuartermaster(world, villager, "invalid_pair_distance");
            clearChestWatcher(villager);
            return;
        }

        LOGGER.info("Librarian {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        LibrarianCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal == null) {
            craftingGoal = new LibrarianCraftingGoal(villager, jobPos, chestPos, null);
            CRAFTING_GOALS.put(villager, craftingGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, craftingGoal);
        } else {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }

        LibrarianBellChestDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new LibrarianBellChestDistributionGoal(villager, jobPos, chestPos, null);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        }
        updateChestWatcher(world, villager, chestPos);
        scheduleImmediateInventoryRefresh(world, villager, true);

        syncQuartermasterState(world, villager, jobPos, chestPos, "chest_paired");
        PAIRED_CHEST_POS.put(villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!villager.isAlive()) {
            demoteQuartermaster(world, villager, "villager_not_alive");
            clearChestWatcher(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.LIBRARIAN, world.getBlockState(jobPos))) {
            demoteQuartermaster(world, villager, "invalid_job_site");
            clearChestWatcher(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            demoteQuartermaster(world, villager, "invalid_pair_distance");
            clearChestWatcher(villager);
            return;
        }

        LibrarianCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new LibrarianCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }

        LibrarianBellChestDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new LibrarianBellChestDistributionGoal(villager, jobPos, chestPos, craftingTablePos);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        updateChestWatcher(world, villager, chestPos);
        scheduleImmediateInventoryRefresh(world, villager, true);
        syncQuartermasterState(world, villager, jobPos, chestPos, "pairing_refresh");
    }

    private void updateChestWatcher(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Set<BlockPos> observedChestPositions = getObservedChestPositions(world, chestPos);
        if (observedChestPositions.isEmpty()) {
            clearChestWatcher(villager);
            return;
        }

        ChestRegistration existing = CHEST_REGISTRATIONS.get(villager);
        if (existing != null && existing.observedChestPositions().equals(observedChestPositions)) {
            return;
        }

        if (existing != null) {
            removeChestRegistration(existing);
            CHEST_REGISTRATIONS.remove(villager);
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

        for (VillagerEntity villager : Set.copyOf(villagers)) {
            if (!villager.isAlive() || villager.getWorld() != world) {
                continue;
            }
            scheduleImmediateInventoryRefresh(world, villager, false);
        }
    }

    private void clearChestWatcher(VillagerEntity villager) {
        ChestRegistration existing = CHEST_REGISTRATIONS.remove(villager);
        if (existing != null) {
            removeChestRegistration(existing);
        }
        INVENTORY_DIRTY_FLAGS.remove(villager);
        LAST_IMMEDIATE_REQUEST_TICK.remove(villager);
    }

    private void removeChestRegistration(ChestRegistration existing) {
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

    private static void scheduleImmediateInventoryRefresh(ServerWorld world, VillagerEntity villager, boolean bypassDebounce) {
        INVENTORY_DIRTY_FLAGS.put(villager, true);
        long currentTick = world.getTime();
        Long lastTick = LAST_IMMEDIATE_REQUEST_TICK.get(villager);
        if (lastTick != null) {
            if (currentTick == lastTick) {
                return;
            }
            if (!bypassDebounce && currentTick - lastTick < INVENTORY_MUTATION_DEBOUNCE_TICKS) {
                return;
            }
        }

        LibrarianCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal != null) {
            goal.requestImmediateCraft(world);
        }
        LibrarianBellChestDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal != null) {
            distributionGoal.requestImmediateDistribution();
        }
        QuartermasterGoal quartermasterGoal = QUARTERMASTER_GOALS.get(villager);
        if (quartermasterGoal != null) {
            quartermasterGoal.requestImmediatePrerequisiteRevalidation();
            quartermasterGoal.requestImmediateDemandReplan();
        }
        LAST_IMMEDIATE_REQUEST_TICK.put(villager, currentTick);
        INVENTORY_DIRTY_FLAGS.put(villager, false);
    }

    private static Set<BlockPos> getObservedChestPositions(ServerWorld world, BlockPos chestPos) {
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
            if (otherState.getBlock() instanceof ChestBlock
                    && otherState.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE
                    && otherState.get(ChestBlock.FACING) == facing) {
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

    private void syncQuartermasterState(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, String reason) {
        long currentTick = world.getTime();
        LastQuartermasterPair lastPair = LAST_QUARTERMASTER_PAIR.get(villager);
        boolean samePairRecentlyValidated = lastPair != null
                && lastPair.matches(jobPos, chestPos)
                && currentTick - lastPair.tick() <= QUARTERMASTER_PAIR_REVALIDATION_GUARD_TICKS;

        QuartermasterPrerequisiteHelper.Result prerequisites =
                QuartermasterPrerequisiteHelper.validate(world, villager, jobPos, chestPos);
        if (!prerequisites.valid()) {
            if (samePairRecentlyValidated && QUARTERMASTER_GOALS.containsKey(villager)) {
                return;
            }
            demoteQuartermaster(world, villager, "missing_or_invalid_chest");
            return;
        }

        LAST_QUARTERMASTER_PAIR.put(villager, new LastQuartermasterPair(jobPos.toImmutable(), chestPos.toImmutable(), currentTick));
        if (QUARTERMASTER_GOALS.containsKey(villager)) {
            return;
        }
        QuartermasterGoal qmGoal = new QuartermasterGoal(villager, jobPos, chestPos);
        QUARTERMASTER_GOALS.put(villager, qmGoal);
        villager.goalSelector.add(QUARTERMASTER_GOAL_PRIORITY, qmGoal);
        QuartermasterGoal.registerActiveQuartermaster(world, chestPos, villager.getUuid());
        LOGGER.info("Librarian {} promoted to Quartermaster (reason={}, chest={} second_chest={} job_site={})",
                villager.getUuidAsString(),
                reason,
                chestPos.toShortString(),
                prerequisites.secondChestPos().toShortString(),
                jobPos.toShortString());
    }

    private void demoteQuartermaster(ServerWorld world, VillagerEntity villager, String reason) {
        QuartermasterGoal qmGoal = QUARTERMASTER_GOALS.remove(villager);
        if (qmGoal == null) {
            return;
        }
        villager.goalSelector.remove(qmGoal);
        BlockPos pairedChestPos = PAIRED_CHEST_POS.get(villager);
        if (pairedChestPos != null) {
            QuartermasterGoal.unregisterActiveQuartermaster(world, pairedChestPos, villager.getUuid());
        }
        QuartermasterGoal.clearBootstrapState(world, villager.getUuid());
        if (pairedChestPos != null && world.getServer() != null) {
            VillageAnchorState.get(world.getServer()).unregister(world, pairedChestPos);
        }
        PAIRED_CHEST_POS.remove(villager);
        LAST_QUARTERMASTER_PAIR.remove(villager);
        LOGGER.info("Librarian {} removed from Quartermaster role (reason={})",
                villager.getUuidAsString(),
                reason);
    }

    private record LastQuartermasterPair(BlockPos jobPos, BlockPos chestPos, long tick) {
        private boolean matches(BlockPos otherJobPos, BlockPos otherChestPos) {
            return Objects.equals(jobPos, otherJobPos) && Objects.equals(chestPos, otherChestPos);
        }
    }
}
