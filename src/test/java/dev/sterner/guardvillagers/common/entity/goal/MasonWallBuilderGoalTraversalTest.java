package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonWallBuilderGoalTraversalTest {

    @Test
    void computePerimeterTraversal_includesSamePerimeterCoordinatesAsOppositeFaceOrdering() {
        int minX = 3;
        int minZ = -4;
        int maxX = 8;
        int maxZ = 2;
        int y = 70;

        List<BlockPos> clockwise = MasonWallBuilderGoal.computePerimeterTraversal(minX, minZ, maxX, maxZ, y);
        Set<BlockPos> previousOrdering = new HashSet<>();

        for (int x = minX; x <= maxX; x++) {
            previousOrdering.add(new BlockPos(x, y, minZ));
            previousOrdering.add(new BlockPos(x, y, maxZ));
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            previousOrdering.add(new BlockPos(minX, y, z));
            previousOrdering.add(new BlockPos(maxX, y, z));
        }

        assertEquals(previousOrdering.size(), clockwise.size());
        assertEquals(previousOrdering, new HashSet<>(clockwise));
    }

    @Test
    void computePerimeterTraversal_flatRectangleHasLocalSuccessiveSteps() {
        List<BlockPos> traversal = MasonWallBuilderGoal.computePerimeterTraversal(10, 20, 16, 26, 64);

        for (int i = 1; i < traversal.size(); i++) {
            int distance = manhattan2d(traversal.get(i - 1), traversal.get(i));
            assertTrue(distance <= 1, "Expected neighboring perimeter steps, got distance=" + distance + " at index " + i);
        }
    }

    private static int manhattan2d(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }
}
