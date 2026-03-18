package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.LibrarianCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.LibrarianBellChestDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.QuartermasterGoal;
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
import java.util.WeakHashMap;

public class LibrarianBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(LibrarianBehavior.class);
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;
    private static final int QUARTERMASTER_GOAL_PRIORITY = 3;
    private static final Map<VillagerEntity, LibrarianCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LibrarianBellChestDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, QuartermasterGoal> QUARTERMASTER_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, BlockPos> PAIRED_CHEST_POS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListener> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.LIBRARIAN, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
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

        // Cluster 3 — Quartermaster promotion: trigger if a double-chest exists near the job site.
        // We scan for any adjacent chest in the world rather than relying solely on the order that
        // onChestPaired is called — this handles simultaneous placement and world-load rehydration.
        if (!QUARTERMASTER_GOALS.containsKey(villager)) {
            BlockPos adjacentChest = findAdjacentChestInWorld(world, chestPos);
            if (adjacentChest != null) {
                QuartermasterGoal qmGoal = new QuartermasterGoal(villager, jobPos, chestPos);
                QUARTERMASTER_GOALS.put(villager, qmGoal);
                villager.goalSelector.add(QUARTERMASTER_GOAL_PRIORITY, qmGoal);
                LOGGER.info("Librarian {} promoted to Quartermaster (double-chest detected: {} + {})",
                        villager.getUuidAsString(), chestPos.toShortString(), adjacentChest.toShortString());
            } else {
                LOGGER.info("Librarian {} chest paired at {} — no adjacent chest found yet, Quartermaster pending",
                        villager.getUuidAsString(), chestPos.toShortString());
            }
        }
        PAIRED_CHEST_POS.put(villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.LIBRARIAN, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
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
        goal.requestImmediateCraft(world);

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
    }

    private void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Inventory inventory = getChestInventory(world, chestPos);
        ChestListener existing = CHEST_LISTENERS.get(villager);
        if (existing != null && existing.inventory() == inventory) {
            return;
        }
        if (existing != null) {
            removeChestListener(existing);
            CHEST_LISTENERS.remove(villager);
        }
        if (!(inventory instanceof SimpleInventory simpleInventory)) {
            return;
        }
        InventoryChangedListener listener = sender -> {
            LibrarianCraftingGoal goal = CRAFTING_GOALS.get(villager);
            if (goal != null && villager.getWorld() instanceof ServerWorld serverWorld) {
                goal.requestImmediateCraft(serverWorld);
            }
            LibrarianBellChestDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
            if (distributionGoal != null) {
                distributionGoal.requestImmediateDistribution();
            }
        };
        simpleInventory.addListener(listener);
        CHEST_LISTENERS.put(villager, new ChestListener(simpleInventory, listener));
    }

    private void clearChestListener(VillagerEntity villager) {
        ChestListener existing = CHEST_LISTENERS.remove(villager);
        if (existing != null) {
            removeChestListener(existing);
        }
    }

    private void removeChestListener(ChestListener existing) {
        existing.inventory().removeListener(existing.listener());
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

    /**
     * Scans the 6 face-adjacent positions of {@code chestPos} for another chest block.
     * Returns the position of the first adjacent chest found, or {@code null} if none.
     * Used for Quartermaster double-chest detection — works regardless of placement order.
     */
    private static BlockPos findAdjacentChestInWorld(ServerWorld world, BlockPos chestPos) {
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
            BlockPos candidate = chestPos.offset(dir);
            if (world.getBlockState(candidate).getBlock() instanceof ChestBlock) {
                return candidate.toImmutable();
            }
        }
        return null;
    }
}
