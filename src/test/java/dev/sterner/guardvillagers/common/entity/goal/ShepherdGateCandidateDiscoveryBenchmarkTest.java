package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShepherdGateCandidateDiscoveryBenchmarkTest {

    @Test
    void candidateDiscoveryStrategies_returnSameCandidateCounts() {
        BenchmarkFixture fixture = BenchmarkFixture.create(0x51F71EAL, 7, 7_500, 96, 40);

        List<BlockPos> legacy = legacyDiscover(
                fixture.bannerAnchors,
                fixture.gates,
                fixture.radius,
                fixture.minY,
                fixture.maxY,
                fixture.limit,
                fixture.sortOrigin
        );
        List<BlockPos> indexed = indexedDiscover(
                fixture.bannerAnchors,
                fixture.gateIndex,
                fixture.radius,
                fixture.minY,
                fixture.maxY,
                fixture.limit,
                fixture.sortOrigin
        );

        assertEquals(legacy, indexed);
    }

    @Test
    void benchmarkUtility_largeRadiusComparison_printsCandidateDiscoveryRuntime() {
        BenchmarkFixture fixture = BenchmarkFixture.create(0xCA7E1234L, 12, 20_000, 160, 40);

        int warmupIterations = 5;
        int measuredIterations = 25;

        runIterations(warmupIterations, fixture, true);
        runIterations(warmupIterations, fixture, false);

        long legacyNanos = runIterations(measuredIterations, fixture, true);
        long indexedNanos = runIterations(measuredIterations, fixture, false);

        int legacyCount = legacyDiscover(
                fixture.bannerAnchors,
                fixture.gates,
                fixture.radius,
                fixture.minY,
                fixture.maxY,
                fixture.limit,
                fixture.sortOrigin
        ).size();
        int indexedCount = indexedDiscover(
                fixture.bannerAnchors,
                fixture.gateIndex,
                fixture.radius,
                fixture.minY,
                fixture.maxY,
                fixture.limit,
                fixture.sortOrigin
        ).size();

        assertEquals(legacyCount, indexedCount);

        double legacyMillis = legacyNanos / 1_000_000.0D;
        double indexedMillis = indexedNanos / 1_000_000.0D;
        System.out.printf(
                "Shepherd gate candidate benchmark -> legacy: %.3f ms, indexed: %.3f ms, candidates: %d%n",
                legacyMillis,
                indexedMillis,
                legacyCount
        );
    }

    private static long runIterations(int iterations, BenchmarkFixture fixture, boolean legacyMode) {
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            if (legacyMode) {
                legacyDiscover(
                        fixture.bannerAnchors,
                        fixture.gates,
                        fixture.radius,
                        fixture.minY,
                        fixture.maxY,
                        fixture.limit,
                        fixture.sortOrigin
                );
            } else {
                indexedDiscover(
                        fixture.bannerAnchors,
                        fixture.gateIndex,
                        fixture.radius,
                        fixture.minY,
                        fixture.maxY,
                        fixture.limit,
                        fixture.sortOrigin
                );
            }
        }
        return System.nanoTime() - start;
    }

    private static List<BlockPos> legacyDiscover(
            List<BlockPos> bannerAnchors,
            Set<BlockPos> gateLookup,
            int radius,
            int minY,
            int maxY,
            int limit,
            BlockPos sortOrigin
    ) {
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        for (BlockPos anchor : bannerAnchors) {
            int yStart = Math.max(minY, anchor.getY() - 24);
            int yEnd = Math.min(maxY, anchor.getY() + 24);
            for (int x = anchor.getX() - radius; x <= anchor.getX() + radius; x++) {
                for (int z = anchor.getZ() - radius; z <= anchor.getZ() + radius; z++) {
                    for (int y = yStart; y <= yEnd; y++) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (gateLookup.contains(candidate)) {
                            candidates.add(candidate.toImmutable());
                        }
                    }
                }
            }
        }

        List<BlockPos> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(sortOrigin::getSquaredDistance));
        if (sorted.size() > limit) {
            return new ArrayList<>(sorted.subList(0, limit));
        }
        return sorted;
    }

    private static List<BlockPos> indexedDiscover(
            List<BlockPos> bannerAnchors,
            Map<Long, List<BlockPos>> gateIndex,
            int radius,
            int minY,
            int maxY,
            int limit,
            BlockPos sortOrigin
    ) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos anchor : bannerAnchors) {
            minX = Math.min(minX, anchor.getX() - radius);
            maxX = Math.max(maxX, anchor.getX() + radius);
            minZ = Math.min(minZ, anchor.getZ() - radius);
            maxZ = Math.max(maxZ, anchor.getZ() + radius);
        }

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        LinkedHashSet<BlockPos> collected = new LinkedHashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                List<BlockPos> chunkGates = gateIndex.get(chunkKey(chunkX, chunkZ));
                if (chunkGates == null || chunkGates.isEmpty()) {
                    continue;
                }
                for (BlockPos gatePos : chunkGates) {
                    if (gatePos.getY() < minY || gatePos.getY() > maxY) {
                        continue;
                    }
                    for (BlockPos anchor : bannerAnchors) {
                        if (anchor.isWithinDistance(gatePos, radius)) {
                            collected.add(gatePos.toImmutable());
                            break;
                        }
                    }
                }
            }
        }

        List<BlockPos> sorted = new ArrayList<>(collected);
        sorted.sort(Comparator.comparingDouble(sortOrigin::getSquaredDistance));
        if (sorted.size() > limit) {
            return new ArrayList<>(sorted.subList(0, limit));
        }
        return sorted;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private record BenchmarkFixture(
            BlockPos sortOrigin,
            List<BlockPos> bannerAnchors,
            Set<BlockPos> gates,
            Map<Long, List<BlockPos>> gateIndex,
            int radius,
            int minY,
            int maxY,
            int limit
    ) {
        private static BenchmarkFixture create(long seed, int bannerCount, int gateCount, int radius, int limit) {
            Random random = new Random(seed);
            BlockPos sortOrigin = new BlockPos(0, 64, 0);
            int minY = 40;
            int maxY = 88;

            List<BlockPos> banners = new ArrayList<>();
            for (int i = 0; i < bannerCount; i++) {
                int x = random.nextInt(1_000) - 500;
                int z = random.nextInt(1_000) - 500;
                int y = minY + random.nextInt(maxY - minY + 1);
                banners.add(new BlockPos(x, y, z));
            }

            Set<BlockPos> gates = new HashSet<>();
            while (gates.size() < gateCount) {
                int x = random.nextInt(1_400) - 700;
                int z = random.nextInt(1_400) - 700;
                int y = minY + random.nextInt(maxY - minY + 1);
                gates.add(new BlockPos(x, y, z));
            }

            Map<Long, List<BlockPos>> gateIndex = new HashMap<>();
            for (BlockPos gate : gates) {
                long key = chunkKey(gate.getX() >> 4, gate.getZ() >> 4);
                gateIndex.computeIfAbsent(key, ignored -> new ArrayList<>()).add(gate);
            }

            return new BenchmarkFixture(sortOrigin, banners, gates, gateIndex, radius, minY, maxY, limit);
        }
    }
}
