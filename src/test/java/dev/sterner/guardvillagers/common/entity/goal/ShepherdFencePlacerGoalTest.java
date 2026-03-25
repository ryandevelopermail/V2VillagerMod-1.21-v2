package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShepherdFencePlacerGoalTest {

    @Test
    void computePerimeterFencePositions_slopedTerrain_placesFenceTopOneAboveLocalGround() {
        IntBinaryOperator slopedSurfaceY = (x, z) -> 64 + Math.floorDiv(x, 2) - Math.floorDiv(z, 3);

        List<BlockPos> fences = ShepherdFencePlacerGoal.computePerimeterFencePositions(100, 200, slopedSurfaceY);
        BlockPos gatePos = ShepherdFencePlacerGoal.computeGatePosition(100, 200, slopedSurfaceY);

        assertEquals(23, fences.size());
        for (BlockPos fencePos : fences) {
            assertEquals(slopedSurfaceY.applyAsInt(fencePos.getX(), fencePos.getZ()) + 1, fencePos.getY());
        }
        assertEquals(slopedSurfaceY.applyAsInt(gatePos.getX(), gatePos.getZ()) + 1, gatePos.getY());
    }

    @Test
    void computePerimeterFencePositions_slopedTerrain_keepsContinuousEnclosureWithSingleGateGap() {
        IntBinaryOperator steppedSurfaceY = (x, z) -> 70 + ((x + z) % 3);
        int baseX = 10;
        int baseZ = -5;

        List<BlockPos> fences = ShepherdFencePlacerGoal.computePerimeterFencePositions(baseX, baseZ, steppedSurfaceY);
        BlockPos gatePos = ShepherdFencePlacerGoal.computeGatePosition(baseX, baseZ, steppedSurfaceY);

        Set<BlockPos> allPerimeter = new HashSet<>();
        for (int dx = 0; dx < 7; dx++) {
            allPerimeter.add(new BlockPos(baseX + dx, steppedSurfaceY.applyAsInt(baseX + dx, baseZ) + 1, baseZ));
            int southZ = baseZ + 6;
            allPerimeter.add(new BlockPos(baseX + dx, steppedSurfaceY.applyAsInt(baseX + dx, southZ) + 1, southZ));
        }
        for (int dz = 1; dz < 6; dz++) {
            allPerimeter.add(new BlockPos(baseX, steppedSurfaceY.applyAsInt(baseX, baseZ + dz) + 1, baseZ + dz));
            int eastX = baseX + 6;
            allPerimeter.add(new BlockPos(eastX, steppedSurfaceY.applyAsInt(eastX, baseZ + dz) + 1, baseZ + dz));
        }

        Set<BlockPos> placed = new HashSet<>(fences);
        placed.add(gatePos);

        assertEquals(24, allPerimeter.size());
        assertEquals(allPerimeter, placed);
        assertTrue(fences.stream().noneMatch(gatePos::equals));
    }

    @Test
    void strictFlatnessChecks_flatPatchPasses() {
        int[] surface = filledSurface(64);

        assertTrue(ShepherdFencePlacerGoal.isPerimeterFlat(surface, 0));
        assertEquals(64, ShepherdFencePlacerGoal.getSharedFlatY(surface, 0));
    }

    @Test
    void strictFlatnessChecks_twoAndThreeLevelPatchesFail() {
        int[] twoLevel = filledSurface(64);
        twoLevel[indexFor(3, 3)] = 65;

        int[] threeLevel = filledSurface(64);
        threeLevel[indexFor(0, 0)] = 63;
        threeLevel[indexFor(6, 6)] = 65;

        assertNull(ShepherdFencePlacerGoal.getSharedFlatY(twoLevel, 0));
        assertNull(ShepherdFencePlacerGoal.getSharedFlatY(threeLevel, 0));
    }

    @Test
    void findFirstFlatCandidate_skipsSlopedCandidatesUntilFlatPatchFound() {
        IntBinaryOperator terrain = (x, z) -> {
            if (x >= 8 && x <= 14 && z >= -4 && z <= 2) {
                return 72;
            }
            return 64 + Math.floorDiv(x + z, 4);
        };

        BlockPos found = ShepherdFencePlacerGoal.findFirstFlatCandidateForTest(10, 10, 32, terrain, 0);

        assertNotNull(found);
        assertEquals(new BlockPos(8, 72, -4), found);

        int[] sampled = new int[49];
        int i = 0;
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                sampled[i++] = terrain.applyAsInt(found.getX() + dx, found.getZ() + dz);
            }
        }
        assertArrayEquals(filledSurface(72), sampled);
    }

    private static int[] filledSurface(int y) {
        int[] surface = new int[49];
        for (int i = 0; i < surface.length; i++) surface[i] = y;
        return surface;
    }

    private static int indexFor(int dx, int dz) {
        return dx * 7 + dz;
    }
}
