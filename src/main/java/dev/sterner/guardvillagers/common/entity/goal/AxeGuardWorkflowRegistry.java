package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class AxeGuardWorkflowRegistry {
    private static final Map<BlockPos, Set<AxeGuardEntity>> WATCHERS_BY_CHEST = new HashMap<>();
    private static final Map<AxeGuardEntity, Set<BlockPos>> CHESTS_BY_GUARD = new WeakHashMap<>();

    private AxeGuardWorkflowRegistry() {
    }

    public static void updateWatch(AxeGuardEntity guard, BlockPos chestPos) {
        clearWatch(guard);
        if (chestPos == null) {
            return;
        }
        BlockPos immutable = chestPos.toImmutable();
        WATCHERS_BY_CHEST.computeIfAbsent(immutable, ignored -> new HashSet<>()).add(guard);
        CHESTS_BY_GUARD.put(guard, Set.of(immutable));
    }

    public static void clearWatch(AxeGuardEntity guard) {
        Set<BlockPos> watched = CHESTS_BY_GUARD.remove(guard);
        if (watched == null) {
            return;
        }
        for (BlockPos pos : watched) {
            Set<AxeGuardEntity> guards = WATCHERS_BY_CHEST.get(pos);
            if (guards == null) {
                continue;
            }
            guards.remove(guard);
            if (guards.isEmpty()) {
                WATCHERS_BY_CHEST.remove(pos);
            }
        }
    }

    public static void onChestInventoryMutated(ServerWorld world, BlockPos chestPos) {
        Set<AxeGuardEntity> guards = WATCHERS_BY_CHEST.get(chestPos);
        if (guards == null || guards.isEmpty()) {
            return;
        }

        for (AxeGuardEntity guard : Set.copyOf(guards)) {
            if (!guard.isAlive() || guard.getWorld() != world) {
                continue;
            }
            guard.notifyWorkflowChestMutation();
        }
    }
}
