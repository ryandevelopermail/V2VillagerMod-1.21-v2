package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ClericBrewingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ClericCraftingGoal;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.InventoryChangedListener;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public class ClericBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClericBehavior.class);
    private static final int BREWING_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, ClericBrewingGoal> BREWING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ClericCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListener> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.BREWING_STAND)) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        LOGGER.info("Cleric {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        ClericBrewingGoal brewingGoal = BREWING_GOALS.get(villager);
        if (brewingGoal == null) {
            brewingGoal = new ClericBrewingGoal(villager, jobPos, chestPos);
            BREWING_GOALS.put(villager, brewingGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(BREWING_GOAL_PRIORITY, brewingGoal);
        } else {
            brewingGoal.setTargets(jobPos, chestPos);
        }

        ClericCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }
        updateChestListener(world, villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        ClericCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new ClericCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        goal.requestImmediateCraft(world);
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
            ClericBrewingGoal goal = BREWING_GOALS.get(villager);
            if (goal != null && villager.getWorld() instanceof ServerWorld) {
                goal.requestImmediateCheck();
            }
            ClericCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
            if (craftingGoal != null && villager.getWorld() instanceof ServerWorld serverWorld) {
                craftingGoal.requestImmediateCraft(serverWorld);
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
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private record ChestListener(SimpleInventory inventory, InventoryChangedListener listener) {
    }
}
