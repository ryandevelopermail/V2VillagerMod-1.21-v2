package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class LumberjackChestTriggerBehavior {
    private static final Map<LumberjackGuardEntity, Set<BlockPos>> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<LumberjackGuardEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();

    private LumberjackChestTriggerBehavior() {
    }

    public static void updateChestWatcher(ServerWorld world, LumberjackGuardEntity guard, BlockPos chestPos) {
        Set<BlockPos> observedChestPositions = getObservedChestPositions(world, chestPos);
        if (observedChestPositions.isEmpty()) {
            clearChestWatcher(guard);
            return;
        }

        Set<BlockPos> existing = CHEST_REGISTRATIONS.get(guard);
        if (existing != null && existing.equals(observedChestPositions)) {
            return;
        }

        clearChestWatcher(guard);
        for (BlockPos observedPos : observedChestPositions) {
            CHEST_WATCHERS_BY_POS.computeIfAbsent(observedPos, ignored -> new HashSet<>()).add(guard);
        }
        CHEST_REGISTRATIONS.put(guard, observedChestPositions);
    }

    public static void clearChestWatcher(LumberjackGuardEntity guard) {
        Set<BlockPos> existing = CHEST_REGISTRATIONS.remove(guard);
        if (existing == null) {
            return;
        }
        for (BlockPos observedPos : existing) {
            Set<LumberjackGuardEntity> watchers = CHEST_WATCHERS_BY_POS.get(observedPos);
            if (watchers == null) {
                continue;
            }
            watchers.remove(guard);
            if (watchers.isEmpty()) {
                CHEST_WATCHERS_BY_POS.remove(observedPos);
            }
        }
    }

    public static void onChestInventoryMutated(ServerWorld world, BlockPos chestPos) {
        Set<LumberjackGuardEntity> guards = CHEST_WATCHERS_BY_POS.get(chestPos);
        if (guards == null || guards.isEmpty()) {
            return;
        }

        Set<LumberjackGuardEntity> snapshot = Set.copyOf(guards);
        for (LumberjackGuardEntity guard : snapshot) {
            if (!guard.isAlive() || guard.getWorld() != world) {
                continue;
            }
            guard.requestTriggerEvaluation();
        }
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
            if (otherState.getBlock() instanceof ChestBlock && otherState.get(ChestBlock.FACING) == facing) {
                positions.add(otherHalfPos.toImmutable());
            }
        }

        return positions;
    }
}
