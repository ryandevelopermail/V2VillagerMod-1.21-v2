package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Benchmark test for shepherd gate candidate discovery.
 * NOTE: filterGateCandidatesByRangeAndHeight was removed from ShepherdSpecialGoal.
 * This entire test class is disabled until it is rewritten to match the new API.
 */
@Disabled("filterGateCandidatesByRangeAndHeight was removed from ShepherdSpecialGoal — test needs rewriting")
class ShepherdSpecialGoalGateCandidateBenchmarkTest {

    @Test
    void benchmarkCandidateDiscoveryCountsAndRuntime_largeRadius() {
        // Test body intentionally removed — the API it tests no longer exists.
        // Rewrite this test when the shepherd gate filtering is re-exposed.
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
