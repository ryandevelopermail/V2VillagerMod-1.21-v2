package dev.sterner.guardvillagers.common.developer;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure geometry and collision rules for temporary V1 terrain isolation. */
final class DeveloperV1IsolationSafety {
    private DeveloperV1IsolationSafety() {
    }

    static Optional<Geometry> planCanonicalFootprint(
            Cell jobSurface,
            Cell spawnSurface,
            int radius,
            Map<Column, Integer> perimeterSurfaceY,
            Collection<Column> protectedColumns
    ) {
        if (radius < 1
                || Math.max(Math.abs(jobSurface.x() - spawnSurface.x()),
                Math.abs(jobSurface.z() - spawnSurface.z())) != radius) {
            return Optional.empty();
        }

        int canonicalY = Math.max(jobSurface.y(), spawnSurface.y());
        if (Math.abs(jobSurface.y() - spawnSurface.y()) > 1) {
            return Optional.empty();
        }

        Cell job = new Cell(jobSurface.x(), canonicalY, jobSurface.z());
        Cell spawn = new Cell(spawnSurface.x(), canonicalY, spawnSurface.z());
        Set<Column> perimeter = perimeterColumns(spawn.column(), radius);
        if (!perimeter.contains(job.column())
                || perimeter.stream().anyMatch(protectedColumns::contains)
                || protectedColumns.contains(spawn.column())) {
            return Optional.empty();
        }

        Set<Cell> barriers = new HashSet<>();
        Set<Cell> naturalLowerWalls = new HashSet<>();
        for (Column column : perimeter) {
            Integer surfaceY = perimeterSurfaceY.get(column);
            if (surfaceY == null || Math.abs(surfaceY - canonicalY) > 1) {
                return Optional.empty();
            }
            Cell lower = new Cell(column.x(), canonicalY, column.z());
            Cell upper = lower.up();
            if (column.equals(job.column())) {
                barriers.add(upper);
            } else if (surfaceY == canonicalY + 1) {
                naturalLowerWalls.add(lower);
                barriers.add(upper);
            } else {
                barriers.add(lower);
                barriers.add(upper);
            }
        }

        Set<Cell> fills = new HashSet<>();
        if (jobSurface.y() < canonicalY) {
            fills.add(job.down());
        }
        if (spawnSurface.y() < canonicalY) {
            fills.add(spawn.down());
        }
        Geometry geometry = new Geometry(
                canonicalY,
                job,
                spawn,
                Set.copyOf(perimeter),
                Set.copyOf(barriers),
                Set.copyOf(naturalLowerWalls),
                Set.of(spawn, spawn.up()),
                Set.copyOf(fills),
                Set.of(spawn));
        return hasEffectiveTwoBlockWall(geometry) && workstationReachable(geometry)
                ? Optional.of(geometry)
                : Optional.empty();
    }

    static Set<Column> perimeterColumns(Column center, int radius) {
        Set<Column> columns = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == radius) {
                    columns.add(new Column(center.x() + dx, center.z() + dz));
                }
            }
        }
        return Set.copyOf(columns);
    }

    static boolean hasEffectiveTwoBlockWall(Geometry geometry) {
        for (Column column : geometry.perimeterColumns()) {
            Cell lower = new Cell(column.x(), geometry.canonicalY(), column.z());
            Cell upper = lower.up();
            boolean lowerBlocked = geometry.barrierPositions().contains(lower)
                    || geometry.naturalLowerWallPositions().contains(lower)
                    || geometry.jobPos().equals(lower);
            if (!lowerBlocked || !geometry.barrierPositions().contains(upper)) {
                return false;
            }
        }
        return true;
    }

    static boolean workstationReachable(Geometry geometry) {
        int dx = Math.abs(geometry.jobPos().x() - geometry.spawnPos().x());
        int dz = Math.abs(geometry.jobPos().z() - geometry.spawnPos().z());
        return geometry.jobPos().y() == geometry.canonicalY()
                && Math.max(dx, dz) == 1
                && !geometry.barrierPositions().contains(geometry.jobPos());
    }

    static boolean isInsideInterior(Cell feetPosition, Geometry geometry) {
        return geometry.interiorPositions().contains(feetPosition);
    }

    static Optional<Cell> touchedBarrier(Cell feetPosition, Geometry geometry) {
        if (geometry.barrierPositions().contains(feetPosition)) {
            return Optional.of(feetPosition);
        }
        Cell below = feetPosition.down();
        return geometry.barrierPositions().contains(below) ? Optional.of(below) : Optional.empty();
    }

    static boolean footprintsOverlap(Geometry first, Geometry second) {
        return first.footprintColumns().stream().anyMatch(second.footprintColumns()::contains);
    }

    static <P> boolean needsInteriorCorrection(
            P feetPosition,
            P positionBelowFeet,
            Collection<P> interiorPositions,
            Collection<P> barrierPositions
    ) {
        return !interiorPositions.contains(feetPosition)
                || barrierPositions.contains(feetPosition)
                || barrierPositions.contains(positionBelowFeet);
    }

    static <P> boolean avoidsProtectedPositions(
            Collection<P> barrierPositions,
            Collection<P> clearPositions,
            Collection<P> protectedPositions
    ) {
        return avoidsProtectedPositions(barrierPositions, clearPositions, Set.of(), protectedPositions);
    }

    static <P> boolean avoidsProtectedPositions(
            Collection<P> barrierPositions,
            Collection<P> clearPositions,
            Collection<P> fillPositions,
            Collection<P> protectedPositions
    ) {
        return barrierPositions.stream().noneMatch(protectedPositions::contains)
                && clearPositions.stream().noneMatch(protectedPositions::contains)
                && fillPositions.stream().noneMatch(protectedPositions::contains);
    }

    static <P> boolean canRemoveTemporaryPosition(P position, Collection<P> protectedPositions) {
        return !protectedPositions.contains(position);
    }

    static <P> boolean canRemoveOwnedTemporaryPosition(
            P position,
            Collection<P> ownedTemporaryPositions,
            Collection<P> protectedPositions
    ) {
        return ownedTemporaryPositions.contains(position)
                && canRemoveTemporaryPosition(position, protectedPositions);
    }

    record Column(int x, int z) {
    }

    record Cell(int x, int y, int z) {
        Cell up() {
            return new Cell(x, y + 1, z);
        }

        Cell down() {
            return new Cell(x, y - 1, z);
        }

        Column column() {
            return new Column(x, z);
        }
    }

    record Geometry(
            int canonicalY,
            Cell jobPos,
            Cell spawnPos,
            Set<Column> perimeterColumns,
            Set<Cell> barrierPositions,
            Set<Cell> naturalLowerWallPositions,
            Set<Cell> clearPositions,
            Set<Cell> fillPositions,
            Set<Cell> interiorPositions
    ) {
        Set<Column> footprintColumns() {
            Set<Column> columns = new HashSet<>(perimeterColumns);
            columns.add(jobPos.column());
            columns.add(spawnPos.column());
            return Set.copyOf(columns);
        }
    }
}
