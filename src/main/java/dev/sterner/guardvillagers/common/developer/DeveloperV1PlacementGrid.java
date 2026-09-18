package dev.sterner.guardvillagers.common.developer;

/** Compact orderly grid for V1 job-site placement. */
final class DeveloperV1PlacementGrid {
    static final int MAX_CONCURRENT = 4;
    static final int VANILLA_JOB_SITE_SEARCH_RADIUS = 48;
    private static final int MAX_COLUMNS = 4;
    private static final int SPACING = 4;

    private DeveloperV1PlacementGrid() {
    }

    static Offset offsetFor(int index, int total) {
        if (index < 0 || total < 1 || index >= total) {
            throw new IllegalArgumentException("Invalid V1 grid index " + index + " for total " + total);
        }
        int columns = Math.min(MAX_COLUMNS, Math.max(1, (int) Math.ceil(Math.sqrt(total))));
        int rows = (int) Math.ceil((double) total / columns);
        int column = index % columns;
        int row = index / columns;
        int centeredX = column * SPACING - (columns - 1) * SPACING / 2;
        int centeredZ = row * SPACING - (rows - 1) * SPACING / 2;
        return new Offset(centeredX, centeredZ);
    }

    record Offset(int x, int z) {
    }
}
