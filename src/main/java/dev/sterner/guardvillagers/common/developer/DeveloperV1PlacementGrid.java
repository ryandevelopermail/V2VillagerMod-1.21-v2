package dev.sterner.guardvillagers.common.developer;

/** Pure orderly grid layout for sequential V1 job-site placement. */
final class DeveloperV1PlacementGrid {
    private static final int MAX_COLUMNS = 4;
    private static final int SPACING = 6;

    private DeveloperV1PlacementGrid() {
    }

    static Offset offsetFor(int index, int total) {
        if (index < 0 || total < 1 || index >= total) {
            throw new IllegalArgumentException("Invalid V1 grid index " + index + " for total " + total);
        }
        int columns = Math.min(MAX_COLUMNS, Math.max(1, (int) Math.ceil(Math.sqrt(total))));
        int column = index % columns;
        int row = index / columns;
        int centeredX = column * SPACING - (columns - 1) * SPACING / 2;
        return new Offset(centeredX, row * SPACING);
    }

    record Offset(int x, int z) {
    }
}
