package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ClericBrewingGoal;
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
    private static final int BREWING_GOAL_PRIORITY = 3;
    private static final Map<VillagerEntity, BlockPos> PAIRED_CHESTS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ClericBrewingGoal> GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListener> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearPairedChest(villager);
            return;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.BREWING_STAND)) {
            clearPairedChest(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearPairedChest(villager);
            return;
        }

        LOGGER.info("Cleric {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());
        PAIRED_CHESTS.put(villager, chestPos.toImmutable());

        ClericBrewingGoal goal = GOALS.get(villager);
        if (goal == null) {
            goal = new ClericBrewingGoal(villager, jobPos, chestPos);
            GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(BREWING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos);
        }
        goal.requestImmediateBrew();
        updateChestListener(world, villager, chestPos);
    }

    public static BlockPos getPairedChestPos(VillagerEntity villager) {
        BlockPos pos = PAIRED_CHESTS.get(villager);
        return pos == null ? null : pos.toImmutable();
    }

    public static void clearPairedChest(VillagerEntity villager) {
        PAIRED_CHESTS.remove(villager);
        ChestListener existing = CHEST_LISTENERS.remove(villager);
        if (existing != null) {
            existing.inventory().removeListener(existing.listener());
        }
    }

    private void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Inventory inventory = getChestInventory(world, chestPos);
        ChestListener existing = CHEST_LISTENERS.get(villager);
        if (existing != null && existing.inventory() == inventory) {
            return;
        }
        if (existing != null) {
            existing.inventory().removeListener(existing.listener());
            CHEST_LISTENERS.remove(villager);
        }
        if (!(inventory instanceof SimpleInventory simpleInventory)) {
            return;
        }
        InventoryChangedListener listener = sender -> {
            ClericBrewingGoal goal = GOALS.get(villager);
            if (goal != null) {
                goal.requestImmediateBrew();
            }
        };
        simpleInventory.addListener(listener);
        CHEST_LISTENERS.put(villager, new ChestListener(simpleInventory, listener));
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
