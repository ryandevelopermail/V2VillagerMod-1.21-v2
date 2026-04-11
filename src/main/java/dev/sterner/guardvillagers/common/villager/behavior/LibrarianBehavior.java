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
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.InventoryChangedListener;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
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
    private static final Map<VillagerEntity, ChestListener> CHEST_LISTENERS = new WeakHashMap<>();
    private static final Map<VillagerEntity, Long> LAST_IMMEDIATE_REQUEST_TICK = new WeakHashMap<>();
    private static final Map<VillagerEntity, Boolean> INVENTORY_DIRTY_FLAGS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LastQuartermasterPair> LAST_QUARTERMASTER_PAIR = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            demoteQuartermaster(world, villager, "villager_not_alive");
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.LIBRARIAN, world.getBlockState(jobPos))) {
            demoteQuartermaster(world, villager, "invalid_job_site");
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            demoteQuartermaster(world, villager, "invalid_pair_distance");
            clearChestListener(villager);
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
        updateChestListener(world, villager, chestPos);
        scheduleImmediateInventoryRefresh(world, villager, true);

        syncQuartermasterState(world, villager, jobPos, chestPos, "chest_paired");
        PAIRED_CHEST_POS.put(villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!villager.isAlive()) {
            demoteQuartermaster(world, villager, "villager_not_alive");
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.LIBRARIAN, world.getBlockState(jobPos))) {
            demoteQuartermaster(world, villager, "invalid_job_site");
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            demoteQuartermaster(world, villager, "invalid_pair_distance");
            clearChestListener(villager);
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
        updateChestListener(world, villager, chestPos);
        scheduleImmediateInventoryRefresh(world, villager, true);
        syncQuartermasterState(world, villager, jobPos, chestPos, "pairing_refresh");
    }

    private void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Inventory inventory = getChestInventory(world, chestPos);
        ChestListener existing = CHEST_LISTENERS.get(villager);
        boolean bypassDebounce = false;
        if (existing != null && existing.inventory() == inventory) {
            return;
        }
        if (existing != null) {
            removeChestListener(existing);
            CHEST_LISTENERS.remove(villager);
            bypassDebounce = true;
        }
        if (!(inventory instanceof SimpleInventory simpleInventory)) {
            return;
        }
        InventoryChangedListener listener = sender -> {
            LibrarianCraftingGoal goal = CRAFTING_GOALS.get(villager);
            if (goal != null && villager.getWorld() instanceof ServerWorld serverWorld) {
                scheduleImmediateInventoryRefresh(serverWorld, villager, false);
            }
        };
        simpleInventory.addListener(listener);
        CHEST_LISTENERS.put(villager, new ChestListener(simpleInventory, listener));
        if (bypassDebounce) {
            scheduleImmediateInventoryRefresh(world, villager, true);
        }
    }

    private void clearChestListener(VillagerEntity villager) {
        ChestListener existing = CHEST_LISTENERS.remove(villager);
        if (existing != null) {
            removeChestListener(existing);
        }
        INVENTORY_DIRTY_FLAGS.remove(villager);
        LAST_IMMEDIATE_REQUEST_TICK.remove(villager);
    }

    private void removeChestListener(ChestListener existing) {
        existing.inventory().removeListener(existing.listener());
    }

    private void scheduleImmediateInventoryRefresh(ServerWorld world, VillagerEntity villager, boolean bypassDebounce) {
        INVENTORY_DIRTY_FLAGS.put(villager, true);
        long currentTick = world.getTime();
        long lastTick = LAST_IMMEDIATE_REQUEST_TICK.getOrDefault(villager, Long.MIN_VALUE);
        if (!bypassDebounce && currentTick - lastTick < INVENTORY_MUTATION_DEBOUNCE_TICKS) {
            return;
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

    private Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
    }

    private record ChestListener(SimpleInventory inventory, InventoryChangedListener listener) {
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
