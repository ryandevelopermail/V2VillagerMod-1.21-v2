package dev.sterner.guardvillagers.common.villager.behavior;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class ChestInventoryChangeDispatcher {
    private static final Map<ServerWorld, Map<BlockPos, Set<ChestChangeListener>>> LISTENERS = new WeakHashMap<>();

    private ChestInventoryChangeDispatcher() {
    }

    public static Subscription register(ServerWorld world, BlockPos chestPos, ChestChangeListener listener) {
        Set<BlockPos> watchedPositions = resolveWatchedChestPositions(world, chestPos);
        Map<BlockPos, Set<ChestChangeListener>> worldListeners = LISTENERS.computeIfAbsent(world, ignored -> new HashMap<>());
        for (BlockPos watchedPos : watchedPositions) {
            worldListeners.computeIfAbsent(watchedPos, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(listener);
        }
        return new Subscription(world, watchedPositions, listener);
    }

    public static void unregister(Subscription subscription) {
        Map<BlockPos, Set<ChestChangeListener>> worldListeners = LISTENERS.get(subscription.world());
        if (worldListeners == null) {
            return;
        }

        for (BlockPos watchedPos : subscription.watchedPositions()) {
            Set<ChestChangeListener> listeners = worldListeners.get(watchedPos);
            if (listeners == null) {
                continue;
            }
            listeners.remove(subscription.listener());
            if (listeners.isEmpty()) {
                worldListeners.remove(watchedPos);
            }
        }

        if (worldListeners.isEmpty()) {
            LISTENERS.remove(subscription.world());
        }
    }

    public static void notifyChestMarkedDirty(ServerWorld world, BlockPos chestPos) {
        Map<BlockPos, Set<ChestChangeListener>> worldListeners = LISTENERS.get(world);
        if (worldListeners == null) {
            return;
        }

        Set<ChestChangeListener> listeners = worldListeners.get(chestPos);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }

        ChestChangeListener[] snapshot = listeners.toArray(new ChestChangeListener[0]);
        for (ChestChangeListener listener : snapshot) {
            listener.onChestInventoryChanged(world, chestPos);
        }
    }

    private static Set<BlockPos> resolveWatchedChestPositions(ServerWorld world, BlockPos chestPos) {
        LinkedHashSet<BlockPos> watched = new LinkedHashSet<>();
        BlockPos basePos = chestPos.toImmutable();
        watched.add(basePos);

        BlockState state = world.getBlockState(basePos);
        if (!(state.getBlock() instanceof ChestBlock) || !state.contains(ChestBlock.CHEST_TYPE) || !state.contains(ChestBlock.FACING)) {
            return watched;
        }

        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) {
            return watched;
        }

        Direction facing = state.get(ChestBlock.FACING);
        Direction partnerOffset = chestType == ChestType.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
        watched.add(basePos.offset(partnerOffset).toImmutable());
        return watched;
    }

    @FunctionalInterface
    public interface ChestChangeListener {
        void onChestInventoryChanged(ServerWorld world, BlockPos changedPos);
    }

    public record Subscription(ServerWorld world, Set<BlockPos> watchedPositions, ChestChangeListener listener) {
    }
}
