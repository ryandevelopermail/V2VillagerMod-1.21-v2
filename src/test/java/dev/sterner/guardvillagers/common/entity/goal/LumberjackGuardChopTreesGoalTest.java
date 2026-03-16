package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
    void resolveReplacementRoot_rootRemovedButTrunkRemains_returnsConnectedFallbackLog() {
        BlockPos root = new BlockPos(0, 64, 0);
        Set<BlockPos> logs = Set.of(root.up(), root.up(2));

        BlockPos replacement = LumberjackGuardChopTreesGoal.resolveReplacementRoot(
                root,
                logs::contains,
                Function.identity(),
                candidate -> false,
                this::adjacent
        );

        assertEquals(root.up(), replacement);
    }

    @Test
    void resolveReplacementRoot_prefersStrictRootCandidateWhenAvailable() {
        BlockPos root = new BlockPos(0, 64, 0);
        BlockPos strictRootCandidate = root.add(1, 0, 0);
        Set<BlockPos> logs = Set.of(strictRootCandidate, root.up());

        BlockPos replacement = LumberjackGuardChopTreesGoal.resolveReplacementRoot(
                root,
                logs::contains,
                Function.identity(),
                strictRootCandidate::equals,
                this::adjacent
        );

        assertEquals(strictRootCandidate, replacement);
    }

    @Test
    void resolveReplacementRoot_doesNotHopAcrossTreeBeyondCrownRadius() {
        BlockPos root = new BlockPos(0, 64, 0);
        BlockPos farOtherTree = root.add(5, 0, 0);

        BlockPos replacement = LumberjackGuardChopTreesGoal.resolveReplacementRoot(
                root,
                farOtherTree::equals,
                Function.identity(),
                candidate -> false,
                this::adjacent
        );

        assertTrue(replacement == null);
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
