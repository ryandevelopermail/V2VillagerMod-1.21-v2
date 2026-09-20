package dev.sterner.guardvillagers.common.developer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Pure bounded candidate ordering/scoring for terrain-aware V1 workstation placement. */
final class DeveloperV1TerrainPlanner {
    static final int MAX_SEARCH_RADIUS = 16;
    static final int MIN_JOB_SITE_SEPARATION = 3;

    private DeveloperV1TerrainPlanner() {
    }

    static <T> SearchResult<T> select(
            int anchorX,
            int anchorZ,
            int preferredY,
            Collection<Site> reservedSites,
            CandidateResolver<T> resolver
    ) {
        List<Offset> offsets = orderedOffsets(MAX_SEARCH_RADIUS);
        CandidateScore<T> best = null;
        int evaluated = 0;
        for (Offset offset : offsets) {
            long distanceSquared = offset.distanceSquared();
            if (best != null && distanceSquared > best.distanceSquared()) {
                break;
            }
            evaluated++;
            int x = anchorX + offset.x();
            int z = anchorZ + offset.z();
            if (isTooClose(x, z, reservedSites)) {
                continue;
            }
            Optional<Candidate<T>> resolved = resolver.resolve(x, z);
            if (resolved.isEmpty()) {
                continue;
            }
            Candidate<T> candidate = resolved.orElseThrow();
            if (candidate.site().x() != x || candidate.site().z() != z) {
                throw new IllegalArgumentException("Terrain candidate must stay in its requested X/Z column.");
            }
            if (candidate.terrainAdjustmentCost() < 0) {
                throw new IllegalArgumentException("Terrain adjustment cost cannot be negative.");
            }
            CandidateScore<T> score = new CandidateScore<>(
                    candidate,
                    distanceSquared,
                    Math.abs(candidate.site().y() - preferredY),
                    nearestReservedDistanceSquared(candidate.site(), reservedSites));
            if (best == null || compare(score, best) < 0) {
                best = score;
            }
        }
        Optional<Selection<T>> selection = best == null
                ? Optional.empty()
                : Optional.of(new Selection<>(
                best.candidate().site(),
                best.candidate().payload(),
                best.candidate().terrainAdjustmentCost()));
        return new SearchResult<>(selection, evaluated, offsets.size());
    }

    static List<Offset> orderedOffsets(int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("Search radius cannot be negative.");
        }
        List<Offset> offsets = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                offsets.add(new Offset(dx, dz));
            }
        }
        offsets.sort(Comparator
                .comparingLong(Offset::distanceSquared)
                .thenComparingInt(Offset::x)
                .thenComparingInt(Offset::z));
        return List.copyOf(offsets);
    }

    private static boolean isTooClose(int x, int z, Collection<Site> reservedSites) {
        long minimumSquared = (long) MIN_JOB_SITE_SEPARATION * MIN_JOB_SITE_SEPARATION;
        for (Site reserved : reservedSites) {
            long dx = (long) x - reserved.x();
            long dz = (long) z - reserved.z();
            if (dx * dx + dz * dz < minimumSquared) {
                return true;
            }
        }
        return false;
    }

    private static long nearestReservedDistanceSquared(Site site, Collection<Site> reservedSites) {
        long nearest = Long.MAX_VALUE;
        for (Site reserved : reservedSites) {
            long dx = (long) site.x() - reserved.x();
            long dz = (long) site.z() - reserved.z();
            nearest = Math.min(nearest, dx * dx + dz * dz);
        }
        return nearest;
    }

    private static <T> int compare(CandidateScore<T> first, CandidateScore<T> second) {
        int comparison = Long.compare(first.distanceSquared(), second.distanceSquared());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(
                first.candidate().terrainAdjustmentCost(),
                second.candidate().terrainAdjustmentCost());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(first.elevationDifference(), second.elevationDifference());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Long.compare(second.nearestReservedDistanceSquared(), first.nearestReservedDistanceSquared());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(first.candidate().site().x(), second.candidate().site().x());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(first.candidate().site().z(), second.candidate().site().z());
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(first.candidate().site().y(), second.candidate().site().y());
    }

    @FunctionalInterface
    interface CandidateResolver<T> {
        Optional<Candidate<T>> resolve(int x, int z);
    }

    record Site(int x, int y, int z) {
    }

    record Candidate<T>(Site site, int terrainAdjustmentCost, T payload) {
    }

    record Selection<T>(Site site, T payload, int terrainAdjustmentCost) {
    }

    record SearchResult<T>(Optional<Selection<T>> selection, int evaluatedCandidates, int candidateLimit) {
    }

    record Offset(int x, int z) {
        long distanceSquared() {
            return (long) x * x + (long) z * z;
        }
    }

    private record CandidateScore<T>(
            Candidate<T> candidate,
            long distanceSquared,
            int elevationDifference,
            long nearestReservedDistanceSquared
    ) {
    }
}
