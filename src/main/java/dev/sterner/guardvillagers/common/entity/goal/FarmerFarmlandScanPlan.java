package dev.sterner.guardvillagers.common.entity.goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure cursor math for the Farmer's repeating, budgeted farmland coverage sweep. */
final class FarmerFarmlandScanPlan {
    private FarmerFarmlandScanPlan() {
    }

    static Slice nextSlice(int cursor, int budget, int radius, int minYOffset, int maxYOffset) {
        int total = totalCells(radius, minYOffset, maxYOffset);
        int safeCursor = cursor < 0 || cursor >= total ? 0 : cursor;
        int count = Math.min(Math.max(1, budget), total - safeCursor);
        List<Offset> offsets = new ArrayList<>(count);
        for (int index = safeCursor; index < safeCursor + count; index++) {
            offsets.add(offsetAt(index, radius, minYOffset, maxYOffset));
        }
        int rawNextCursor = safeCursor + count;
        boolean wrapped = rawNextCursor >= total;
        return new Slice(List.copyOf(offsets), wrapped ? 0 : rawNextCursor, wrapped);
    }

    static int totalCells(int radius, int minYOffset, int maxYOffset) {
        int safeRadius = Math.max(0, radius);
        int diameter = safeRadius * 2 + 1;
        int layers = Math.max(1, maxYOffset - minYOffset + 1);
        return diameter * diameter * layers;
    }

    static Offset offsetAt(int index, int radius, int minYOffset, int maxYOffset) {
        int total = totalCells(radius, minYOffset, maxYOffset);
        if (index < 0 || index >= total) {
            throw new IllegalArgumentException("Farmland scan index " + index + " outside 0.." + (total - 1));
        }
        int safeRadius = Math.max(0, radius);
        int diameter = safeRadius * 2 + 1;
        int layerSize = diameter * diameter;
        int yIndex = index / layerSize;
        int layerOffset = index % layerSize;
        int zIndex = layerOffset / diameter;
        int xIndex = layerOffset % diameter;
        return new Offset(xIndex - safeRadius, minYOffset + yIndex, zIndex - safeRadius);
    }

    static boolean isInsideHorizontalRadius(Offset offset, int radius) {
        int safeRadius = Math.max(0, radius);
        return offset.x() * offset.x() + offset.z() * offset.z() <= safeRadius * safeRadius;
    }

    static List<Offset> priorityOffsets(int radius, int minYOffset, int maxYOffset) {
        int safeRadius = Math.max(0, radius);
        List<Offset> offsets = new ArrayList<>();
        for (int y = minYOffset; y <= maxYOffset; y++) {
            for (int z = -safeRadius; z <= safeRadius; z++) {
                for (int x = -safeRadius; x <= safeRadius; x++) {
                    Offset offset = new Offset(x, y, z);
                    if (isInsideHorizontalRadius(offset, safeRadius)) {
                        offsets.add(offset);
                    }
                }
            }
        }
        offsets.sort(Comparator
                .comparingInt((Offset offset) -> offset.x() * offset.x() + offset.z() * offset.z())
                .thenComparingInt(offset -> Math.abs(offset.y()))
                .thenComparingInt(Offset::y)
                .thenComparingInt(Offset::x)
                .thenComparingInt(Offset::z));
        return List.copyOf(offsets);
    }

    static List<Offset> priorityRingOffsets(
            int innerExclusiveRadius,
            int outerInclusiveRadius,
            int minYOffset,
            int maxYOffset
    ) {
        int safeInnerRadius = Math.max(0, innerExclusiveRadius);
        int safeOuterRadius = Math.max(safeInnerRadius, outerInclusiveRadius);
        List<Offset> offsets = new ArrayList<>();
        for (int y = minYOffset; y <= maxYOffset; y++) {
            for (int z = -safeOuterRadius; z <= safeOuterRadius; z++) {
                for (int x = -safeOuterRadius; x <= safeOuterRadius; x++) {
                    Offset offset = new Offset(x, y, z);
                    if (isInsideHorizontalRadius(offset, safeOuterRadius)
                            && !isInsideHorizontalRadius(offset, safeInnerRadius)) {
                        offsets.add(offset);
                    }
                }
            }
        }
        offsets.sort(Comparator
                .comparingInt((Offset offset) -> offset.x() * offset.x() + offset.z() * offset.z())
                .thenComparingInt(offset -> Math.abs(offset.y()))
                .thenComparingInt(Offset::y)
                .thenComparingInt(Offset::x)
                .thenComparingInt(Offset::z));
        return List.copyOf(offsets);
    }

    static boolean completeAfterSlice(boolean previouslyComplete, Slice slice) {
        return previouslyComplete || slice.wrapped();
    }

    record Offset(int x, int y, int z) {
    }

    record Slice(List<Offset> offsets, int nextCursor, boolean wrapped) {
    }
}
