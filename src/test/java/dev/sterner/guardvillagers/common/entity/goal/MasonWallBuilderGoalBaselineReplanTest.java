package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonWallBuilderGoalBaselineReplanTest {

    @Test
    void replanTargetsRemainWithinFrozenBaselinePlusThreeAfterPartialPlacements() {
        List<BlockPos> perimeterColumns = MasonWallBuilderGoal.computePerimeterTraversal(2, 4, 7, 9);
        Map<Long, Integer> frozenBaseline = new HashMap<>();
        for (BlockPos column : perimeterColumns) {
            frozenBaseline.put(MasonWallBuilderGoal.perimeterColumnKey(column.getX(), column.getZ()), 60);
        }

        // Initial plan, before any placements.
        List<BlockPos> initialTargets = MasonWallBuilderGoal.computeWallSegmentTargetsFromBaseline(perimeterColumns, frozenBaseline);

        // Simulate partial placements: terrain might now read higher on live heightmap for some columns.
        // Replan must still use the frozen baseline map from project initialization.
        Map<Long, Integer> hypotheticalLiveGround = new HashMap<>(frozenBaseline);
        BlockPos firstColumn = perimeterColumns.get(0);
        hypotheticalLiveGround.put(MasonWallBuilderGoal.perimeterColumnKey(firstColumn.getX(), firstColumn.getZ()), 72);
        List<BlockPos> replannedTargets = MasonWallBuilderGoal.computeWallSegmentTargetsFromBaseline(perimeterColumns, frozenBaseline);

        assertTrue(replannedTargets.stream().allMatch(pos -> {
                    int baseline = frozenBaseline.get(MasonWallBuilderGoal.perimeterColumnKey(pos.getX(), pos.getZ()));
                    return pos.getY() <= baseline + 3;
                }),
                "Replanned targets must never exceed frozen baseline+3.");
        assertTrue(initialTargets.stream().allMatch(pos -> {
                    int baseline = frozenBaseline.get(MasonWallBuilderGoal.perimeterColumnKey(pos.getX(), pos.getZ()));
                    return pos.getY() <= baseline + 3;
                }),
                "Initial targets must never exceed frozen baseline+3.");
        assertTrue(hypotheticalLiveGround.get(MasonWallBuilderGoal.perimeterColumnKey(firstColumn.getX(), firstColumn.getZ()))
                        > frozenBaseline.get(MasonWallBuilderGoal.perimeterColumnKey(firstColumn.getX(), firstColumn.getZ())),
                "Test setup should simulate a taller live heightmap for a placed column.");
    }
}
