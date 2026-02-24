package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.InventoryChangedListener;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.function.Supplier;

public abstract class AbstractPairedProfessionBehavior implements VillagerProfessionBehavior {
    protected static final double CHEST_PAIR_RANGE = 3.0D;

    protected boolean checkPairingPreconditions(ServerWorld world,
                                                VillagerEntity villager,
                                                BlockPos jobPos,
                                                BlockPos chestPos,
                                                JobSiteValidator jobSiteValidator,
                                                Runnable onInvalid) {
        if (!villager.isAlive() || !jobSiteValidator.isValid(world, jobPos) || !jobPos.isWithinDistance(chestPos, CHEST_PAIR_RANGE)) {
            onInvalid.run();
            return false;
        }
        return true;
    }

    protected <T extends Goal> T upsertGoal(Map<VillagerEntity, T> goalMap,
                                            VillagerEntity villager,
                                            int priority,
                                            Supplier<T> factory) {
        T goal = goalMap.get(villager);
        if (goal != null) {
            return goal;
        }

        goal = factory.get();
        goalMap.put(villager, goal);
        villager.goalSelector.add(priority, goal);
        return goal;
    }

    protected void updateChestListener(ServerWorld world,
                                       VillagerEntity villager,
                                       BlockPos chestPos,
                                       Map<VillagerEntity, ChestListenerRegistration> listenerMap,
                                       ChestListenerFactory listenerFactory) {
        Inventory inventory = getChestInventory(world, chestPos);
        ChestListenerRegistration existing = listenerMap.get(villager);
        if (existing != null && existing.inventory() == inventory) {
            return;
        }
        clearChestListener(listenerMap, villager);

        if (!(inventory instanceof SimpleInventory simpleInventory)) {
            return;
        }

        InventoryChangedListener listener = listenerFactory.create(world, villager);
        simpleInventory.addListener(listener);
        listenerMap.put(villager, new ChestListenerRegistration(simpleInventory, listener));
    }

    protected void clearChestListener(Map<VillagerEntity, ChestListenerRegistration> listenerMap, VillagerEntity villager) {
        ChestListenerRegistration existing = listenerMap.remove(villager);
        if (existing != null) {
            existing.inventory().removeListener(existing.listener());
        }
    }

    protected Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    @FunctionalInterface
    protected interface JobSiteValidator {
        boolean isValid(ServerWorld world, BlockPos jobPos);
    }

    @FunctionalInterface
    protected interface ChestListenerFactory {
        InventoryChangedListener create(ServerWorld world, VillagerEntity villager);
    }

    protected record ChestListenerRegistration(SimpleInventory inventory, InventoryChangedListener listener) {
    }
}
