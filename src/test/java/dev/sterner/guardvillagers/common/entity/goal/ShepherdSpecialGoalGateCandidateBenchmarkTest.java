package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShepherdSpecialGoalGateCandidateBenchmarkTest {

    @Test
    void benchmarkCandidateDiscoveryCountsAndRuntime_largeRadius() {
        BlockPos sortOrigin = new BlockPos(0, 64, 0);
        List<BlockPos> anchors = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(64, 64, 64),
                new BlockPos(-64, 64, -48)
        );
        int radius = 128;
        int minY = 40;
        int maxY = 88;
        int limit = 512;

        Set<BlockPos> indexedGateUniverse = generateIndexedGateUniverse(sortOrigin, radius, minY, maxY, 5000, 42L);

        long legacyStart = System.nanoTime();
        List<BlockPos> legacy = legacyDiscovery(sortOrigin, anchors, indexedGateUniverse, radius, minY, maxY, limit);
        long legacyElapsedNanos = System.nanoTime() - legacyStart;

        long indexedStart = System.nanoTime();
        List<BlockPos> indexed = ShepherdSpecialGoal.filterGateCandidatesByRangeAndHeight(
                sortOrigin,
                anchors,
                indexedGateUniverse,
                radius,
                minY,
                maxY,
                limit
        );
        long indexedElapsedNanos = System.nanoTime() - indexedStart;

        assertEquals(legacy, indexed, "Indexed candidate filtering should match legacy scan output ordering and cap");

        System.out.println("[benchmark] shepherd gate candidate discovery");
        System.out.println("[benchmark] anchors=" + anchors.size() + ", radius=" + radius + ", indexedUniverse=" + indexedGateUniverse.size());
        System.out.println("[benchmark] legacy_count=" + legacy.size() + ", legacy_ms=" + nanosToMillis(legacyElapsedNanos));
        System.out.println("[benchmark] indexed_count=" + indexed.size() + ", indexed_ms=" + nanosToMillis(indexedElapsedNanos));
        System.out.println("[benchmark] speedup_x=" + String.format("%.2f", speedup(legacyElapsedNanos, indexedElapsedNanos)));
    }

    private static Set<BlockPos> generateIndexedGateUniverse(
            BlockPos center,
            int radius,
            int minY,
            int maxY,
            int count,
            long seed
    ) {
        Random random = new Random(seed);
        Set<BlockPos> gates = new HashSet<>();
        while (gates.size() < count) {
            int x = center.getX() + random.nextInt(radius * 4) - (radius * 2);
            int y = minY + random.nextInt(Math.max(1, maxY - minY + 1));
            int z = center.getZ() + random.nextInt(radius * 4) - (radius * 2);
            gates.add(new BlockPos(x, y, z));
        }
        return gates;
    }

    private static List<BlockPos> legacyDiscovery(
            BlockPos sortOrigin,
            List<BlockPos> anchors,
            Set<BlockPos> gateUniverse,
            int radius,
            int minY,
            int maxY,
            int limit
    ) {
        LinkedHashSet<BlockPos> gateSet = new LinkedHashSet<>();
        for (BlockPos anchor : anchors) {
            int yStart = Math.max(minY, anchor.getY() - 24);
            int yEnd = Math.min(maxY, anchor.getY() + 24);
            for (int x = anchor.getX() - radius; x <= anchor.getX() + radius; x++) {
                for (int z = anchor.getZ() - radius; z <= anchor.getZ() + radius; z++) {
                    for (int y = yStart; y <= yEnd; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (gateUniverse.contains(pos)) {
                            gateSet.add(pos.toImmutable());
                        }
                    }
                }
            }
        }

        List<BlockPos> gates = new ArrayList<>(gateSet);
        gates.sort(Comparator.comparingDouble(sortOrigin::getSquaredDistance));
        if (gates.size() > limit) {
            return new ArrayList<>(gates.subList(0, limit));
        }
        return gates;
    }

    private static String nanosToMillis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0D);
    }

    private static double speedup(long baselineNanos, long optimizedNanos) {
        if (optimizedNanos <= 0L) {
            return Double.POSITIVE_INFINITY;
        }
        return baselineNanos / (double) optimizedNanos;
    }
}
