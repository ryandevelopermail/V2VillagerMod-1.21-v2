package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackGuardChopTreesGoalTest {

    @Test
    void collectConnectedLogsWithinTreeBounds_rootRemovedButAdjacentTrunkRemains_usesFallbackAndFindsLogs() {
        BlockPos root = new BlockPos(0, 64, 0);
        Set<BlockPos> survivingLogs = new HashSet<>(Set.of(
                root.up(),
                root.up(2),
                root.up(3)
        ));

        LumberjackGuardChopTreesGoal.ConnectedLogScanResult result = LumberjackGuardChopTreesGoal.collectConnectedLogsWithinTreeBounds(
                root,
                survivingLogs::contains,
                this::adjacent
        );

        assertTrue(result.usedFallbackSeed());
        assertTrue(result.logs().size() > 0);
        assertFalse(result.logs().contains(root));
    }


    @Test
    void runMidpointUpgradeNeedPass_oneNeedPerPass_craftsAtMostOneDemandBeforeImmediatePass() {
        AtomicInteger craftCalls = new AtomicInteger();
        AtomicInteger immediatePassCalls = new AtomicInteger();

        boolean acted = LumberjackGuardChopTreesGoal.runMidpointUpgradeNeedPass(
                LumberjackChestTriggerController.UpgradeDemand::v2CraftingTable,
                demand -> 0,
                demand -> {
                    craftCalls.incrementAndGet();
                    return true;
                },
                () -> {
                    immediatePassCalls.incrementAndGet();
                    return true;
                }
        );

        assertTrue(acted);
        assertEquals(1, craftCalls.get());
        assertEquals(1, immediatePassCalls.get());
    }

    @Test
    void runMidpointUpgradeNeedPass_prefersResolvedChestDemandOverTableDemand() {
        AtomicReference<LumberjackChestTriggerController.UpgradeDemand> craftedDemand = new AtomicReference<>();

        boolean acted = LumberjackGuardChopTreesGoal.runMidpointUpgradeNeedPass(
                LumberjackChestTriggerController.UpgradeDemand::v1Chest,
                demand -> 0,
                demand -> {
                    craftedDemand.set(demand);
                    return true;
                },
                () -> true
        );

        assertTrue(acted);
        assertEquals(LumberjackChestTriggerController.UpgradeDemand.v1Chest(), craftedDemand.get());
    }

    private List<BlockPos> adjacent(BlockPos pos) {
        return List.of(
                pos.up(),
                pos.down(),
                pos.north(),
                pos.south(),
                pos.east(),
                pos.west()
        );
    }
}
