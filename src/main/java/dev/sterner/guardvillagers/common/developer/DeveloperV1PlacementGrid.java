package dev.sterner.guardvillagers.common.developer;

/** Pure grid layout that isolates concurrently unclaimed V1 job sites. */
final class DeveloperV1PlacementGrid {
    static final int CONCURRENT_LANES = 4;
    static final int VANILLA_JOB_SITE_SEARCH_RADIUS = 48;
    // Vanilla FindPointOfInterestTask scans 48 blocks; search drift can move each site by two blocks.
    private static final int LANE_SPACING = 64;
    private static final int COMPLETED_SITE_SPACING = 6;

    private DeveloperV1PlacementGrid() {
    }

    static Offset offsetFor(int index, int total) {
        if (index < 0 || total < 1 || index >= total) {
            throw new IllegalArgumentException("Invalid V1 grid index " + index + " for total " + total);
        }
        int lanes = Math.min(CONCURRENT_LANES, total);
        int waves = (int) Math.ceil((double) total / lanes);
        int lane = laneFor(index);
        int wave = index / lanes;
        int centeredX = lane * LANE_SPACING - (lanes - 1) * LANE_SPACING / 2;
        int centeredZ = wave * COMPLETED_SITE_SPACING - (waves - 1) * COMPLETED_SITE_SPACING / 2;
        return new Offset(centeredX, centeredZ);
    }

    static int laneFor(int index) {
        return index % CONCURRENT_LANES;
    }

    record Offset(int x, int z) {
    }
}
