package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperV1IsolationGeometryTest {
    @Test
    void flatTerrainProducesValidTwoBlockIsolationWall() {
        DeveloperV1IsolationSafety.Geometry geometry = flatGeometry(0, 0);

        assertEquals(64, geometry.canonicalY());
        assertEquals(15, geometry.barrierPositions().size());
        assertTrue(DeveloperV1IsolationSafety.hasEffectiveTwoBlockWall(geometry));
    }

    @Test
    void terrainAwareIsolationCannotCreateStepableBarrier() {
        DeveloperV1IsolationSafety.Column lowerColumn =
                new DeveloperV1IsolationSafety.Column(-1, 0);
        Map<DeveloperV1IsolationSafety.Column, Integer> surfaces = flatSurfaces(0, 0, 64);
        surfaces.put(lowerColumn, 63);

        DeveloperV1IsolationSafety.Geometry geometry = plan(0, 0, 64, 64, surfaces, Set.of());

        DeveloperV1IsolationSafety.Cell lower = new DeveloperV1IsolationSafety.Cell(-1, 64, 0);
        assertTrue(geometry.barrierPositions().contains(lower));
        assertTrue(geometry.barrierPositions().contains(lower.up()));
        assertFalse(geometry.barrierPositions().contains(new DeveloperV1IsolationSafety.Cell(-1, 63, 0)));
        assertTrue(DeveloperV1IsolationSafety.hasEffectiveTwoBlockWall(geometry));
    }

    @Test
    void oneBlockHigherTerrainCreatesSolidLowerWallWithoutGapOrClimbableEdge() {
        DeveloperV1IsolationSafety.Column higherColumn =
                new DeveloperV1IsolationSafety.Column(-1, 0);
        Map<DeveloperV1IsolationSafety.Column, Integer> surfaces = flatSurfaces(0, 0, 64);
        surfaces.put(higherColumn, 65);

        DeveloperV1IsolationSafety.Geometry geometry = plan(0, 0, 64, 64, surfaces, Set.of());

        DeveloperV1IsolationSafety.Cell lower = new DeveloperV1IsolationSafety.Cell(-1, 64, 0);
        assertTrue(geometry.naturalLowerWallPositions().contains(lower));
        assertTrue(geometry.barrierPositions().contains(lower.up()));
        assertTrue(DeveloperV1IsolationSafety.hasEffectiveTwoBlockWall(geometry));
    }

    @Test
    void mixedHeightTerrainNormalizesToOneCanonicalIsolationLevel() {
        Map<DeveloperV1IsolationSafety.Column, Integer> surfaces = flatSurfaces(0, 0, 64);
        surfaces.put(new DeveloperV1IsolationSafety.Column(-1, -1), 63);
        surfaces.put(new DeveloperV1IsolationSafety.Column(-1, 0), 65);

        DeveloperV1IsolationSafety.Geometry geometry = plan(0, 0, 64, 63, surfaces, Set.of());

        assertEquals(64, geometry.canonicalY());
        assertEquals(64, geometry.jobPos().y());
        assertEquals(64, geometry.spawnPos().y());
        assertTrue(geometry.fillPositions().contains(geometry.spawnPos().down()));
        assertTrue(DeveloperV1IsolationSafety.hasEffectiveTwoBlockWall(geometry));
    }

    @Test
    void villagerInteriorPositionIsNeverBarrierColumnCell() {
        DeveloperV1IsolationSafety.Geometry geometry = flatGeometry(0, 0);

        assertTrue(DeveloperV1IsolationSafety.isInsideInterior(geometry.spawnPos(), geometry));
        assertFalse(geometry.barrierPositions().contains(geometry.spawnPos()));
        assertTrue(DeveloperV1IsolationSafety.touchedBarrier(geometry.spawnPos(), geometry).isEmpty());
    }

    @Test
    void perimeterRemainsUnwalkableFromInside() {
        DeveloperV1IsolationSafety.Geometry geometry = flatGeometry(0, 0);

        assertTrue(DeveloperV1IsolationSafety.hasEffectiveTwoBlockWall(geometry));
        assertEquals(8, geometry.perimeterColumns().size());
    }

    @Test
    void workstationRemainsInsideWallEdgeAndReachableForAcquisition() {
        DeveloperV1IsolationSafety.Geometry geometry = flatGeometry(0, 0);

        assertTrue(geometry.perimeterColumns().contains(geometry.jobPos().column()));
        assertTrue(DeveloperV1IsolationSafety.workstationReachable(geometry));
        assertFalse(geometry.barrierPositions().contains(geometry.jobPos()));
        assertTrue(geometry.barrierPositions().contains(geometry.jobPos().up()));
    }

    @Test
    void overlappingProtectedTaskFootprintRejectsCandidate() {
        DeveloperV1IsolationSafety.Column protectedColumn =
                new DeveloperV1IsolationSafety.Column(-1, 0);

        assertTrue(DeveloperV1IsolationSafety.planCanonicalFootprint(
                new DeveloperV1IsolationSafety.Cell(1, 64, 0),
                new DeveloperV1IsolationSafety.Cell(0, 64, 0),
                1,
                flatSurfaces(0, 0, 64),
                Set.of(protectedColumn)).isEmpty());
    }

    @Test
    void failedPreflightPerformsNoPartialWorldMutation() {
        Map<DeveloperV1IsolationSafety.Column, Integer> incomplete = flatSurfaces(0, 0, 64);
        incomplete.remove(new DeveloperV1IsolationSafety.Column(-1, 0));
        AtomicInteger mutations = new AtomicInteger();

        DeveloperV1IsolationSafety.planCanonicalFootprint(
                        new DeveloperV1IsolationSafety.Cell(1, 64, 0),
                        new DeveloperV1IsolationSafety.Cell(0, 64, 0),
                        1,
                        incomplete,
                        Set.of())
                .ifPresent(ignored -> mutations.incrementAndGet());

        assertEquals(0, mutations.get());
    }

    @Test
    void pendingVillagerCannotStandOnIsolationBarrier() {
        DeveloperV1IsolationSafety.Geometry geometry = flatGeometry(0, 0);
        DeveloperV1IsolationSafety.Cell barrierTop =
                new DeveloperV1IsolationSafety.Cell(-1, 66, 0);

        assertTrue(DeveloperV1IsolationSafety.needsInteriorCorrection(
                barrierTop,
                barrierTop.down(),
                geometry.interiorPositions(),
                geometry.barrierPositions()));
        assertTrue(DeveloperV1IsolationSafety.touchedBarrier(barrierTop, geometry).isPresent());
        assertFalse(DeveloperV1IsolationSafety.needsInteriorCorrection(
                geometry.spawnPos(),
                geometry.spawnPos().down(),
                geometry.interiorPositions(),
                geometry.barrierPositions()));
    }

    @Test
    void villagerEscapingInteriorRequiresSafeReturnToSpawn() {
        DeveloperV1IsolationSafety.Geometry geometry = flatGeometry(0, 0);
        DeveloperV1IsolationSafety.Cell escaped = new DeveloperV1IsolationSafety.Cell(2, 64, 0);

        assertTrue(DeveloperV1IsolationSafety.needsInteriorCorrection(
                escaped,
                escaped.down(),
                geometry.interiorPositions(),
                geometry.barrierPositions()));
        assertEquals(Set.of(geometry.spawnPos()), geometry.interiorPositions());
    }

    @Test
    void finalWaveConcurrentSpawningPreservesIndependentEnclosures() {
        List<DeveloperV1IsolationSafety.Geometry> geometries = new ArrayList<>();
        for (int index = 0; index < DeveloperV1PlacementGrid.MAX_CONCURRENT; index++) {
            geometries.add(flatGeometry(index * 4, 0));
        }

        for (int first = 0; first < geometries.size(); first++) {
            for (int second = first + 1; second < geometries.size(); second++) {
                assertFalse(DeveloperV1IsolationSafety.footprintsOverlap(
                        geometries.get(first), geometries.get(second)));
            }
        }
    }

    @Test
    void isolationCleanupRemovesOnlyOwnedTemporaryBarrierBlocks() {
        Set<String> owned = Set.of("wall-a", "wall-b");
        Set<String> protectedPositions = Set.of("job", "wall-b");

        assertTrue(DeveloperV1IsolationSafety.canRemoveOwnedTemporaryPosition(
                "wall-a", owned, protectedPositions));
        assertFalse(DeveloperV1IsolationSafety.canRemoveOwnedTemporaryPosition(
                "wall-b", owned, protectedPositions));
        assertFalse(DeveloperV1IsolationSafety.canRemoveOwnedTemporaryPosition(
                "foreign-wall", owned, protectedPositions));
    }

    private static DeveloperV1IsolationSafety.Geometry flatGeometry(int centerX, int centerZ) {
        return plan(centerX, centerZ, 64, 64, flatSurfaces(centerX, centerZ, 64), Set.of());
    }

    private static DeveloperV1IsolationSafety.Geometry plan(
            int centerX,
            int centerZ,
            int jobSurfaceY,
            int spawnSurfaceY,
            Map<DeveloperV1IsolationSafety.Column, Integer> surfaces,
            Set<DeveloperV1IsolationSafety.Column> protectedColumns
    ) {
        return DeveloperV1IsolationSafety.planCanonicalFootprint(
                new DeveloperV1IsolationSafety.Cell(centerX + 1, jobSurfaceY, centerZ),
                new DeveloperV1IsolationSafety.Cell(centerX, spawnSurfaceY, centerZ),
                1,
                surfaces,
                protectedColumns).orElseThrow();
    }

    private static Map<DeveloperV1IsolationSafety.Column, Integer> flatSurfaces(
            int centerX,
            int centerZ,
            int y
    ) {
        Map<DeveloperV1IsolationSafety.Column, Integer> surfaces = new HashMap<>();
        for (DeveloperV1IsolationSafety.Column column :
                DeveloperV1IsolationSafety.perimeterColumns(
                        new DeveloperV1IsolationSafety.Column(centerX, centerZ), 1)) {
            surfaces.put(column, y);
        }
        return surfaces;
    }
}
