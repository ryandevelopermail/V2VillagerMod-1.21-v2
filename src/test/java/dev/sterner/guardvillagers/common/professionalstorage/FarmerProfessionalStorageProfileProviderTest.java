package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.FarmerHarvestGoal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FarmerProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final BlockPos FARM_A = new BlockPos(2, 64, 2);
    private static final BlockPos FARM_B = new BlockPos(30, 64, 2);

    @Test
    void singleFarmerProducesExactOrderedOverviewAndStatisticsTabs() {
        List<ProfessionalStorageTab> tabs = FarmerProfessionalStorageProfileProvider.buildTabs(
                List.of(loaded(
                        FIRST,
                        FARM_A,
                        FarmerHarvestGoal.FarmerActivity.HARVESTING,
                        true,
                        coverage(7, 10))),
                false,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(42L, 35L, 9L, 6L));

        assertEquals(List.of("overview", "statistics"), tabs.stream().map(ProfessionalStorageTab::id).toList());
        assertEquals(List.of("Overview", "Statistics"), tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of(
                        "Status",
                        "Hoe available",
                        "Farmland coverage"),
                tabs.get(0).rows().stream().map(ProfessionalStorageRow::label).toList());
        assertEquals(List.of(
                        "Crops harvested",
                        "Crops planted",
                        "Ground tilled",
                        "Materials crafted"),
                tabs.get(1).rows().stream().map(ProfessionalStorageRow::label).toList());
        assertEquals(List.of("Harvesting", "Yes", "7 / 10"),
                tabs.get(0).rows().stream().map(ProfessionalStorageRow::value).toList());
        assertEquals(List.of("42", "35", "9", "6"),
                tabs.get(1).rows().stream().map(ProfessionalStorageRow::value).toList());
        assertEquals(ProfessionalStorageRow.Tone.PAIRED, tabs.get(0).rows().get(0).tone());
        assertEquals(ProfessionalStorageRow.Tone.PAIRED, tabs.get(0).rows().get(1).tone());
    }

    @Test
    void sharedFarmersReportMultipleActivitiesAndDeduplicateOneFarmCoverage() {
        List<ProfessionalStorageTab> tabs = FarmerProfessionalStorageProfileProvider.buildTabs(
                List.of(
                        loaded(FIRST, FARM_A, FarmerHarvestGoal.FarmerActivity.TILLING, false, coverage(4, 10)),
                        loaded(SECOND, FARM_A, FarmerHarvestGoal.FarmerActivity.PLANTING, true, coverage(4, 10))),
                false,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(80L, 60L, 20L, 7L));

        assertEquals("Multiple activities", value(tabs, "Status"));
        assertEquals("Yes", value(tabs, "Hoe available"));
        assertEquals("4 / 10", value(tabs, "Farmland coverage"));
        assertEquals("7", value(tabs, "Materials crafted"));
    }

    @Test
    void distinctFarmCoverageAggregatesAndPartialCoverageIsExplicit() {
        List<ProfessionalStorageTab> complete = FarmerProfessionalStorageProfileProvider.buildTabs(
                List.of(
                        loaded(FIRST, FARM_A, FarmerHarvestGoal.FarmerActivity.IDLE, false, coverage(4, 10)),
                        loaded(SECOND, FARM_B, FarmerHarvestGoal.FarmerActivity.IDLE, false, coverage(8, 12))),
                true,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(0L, 0L, 0L, 0L));
        assertEquals("12 / 22", value(complete, "Farmland coverage"));
        assertEquals("Idle", value(complete, "Status"));
        assertEquals("Yes", value(complete, "Hoe available"));

        List<ProfessionalStorageTab> partial = FarmerProfessionalStorageProfileProvider.buildTabs(
                List.of(
                        loaded(FIRST, FARM_A, FarmerHarvestGoal.FarmerActivity.IDLE, false, coverage(4, 10)),
                        loaded(SECOND, FARM_B, FarmerHarvestGoal.FarmerActivity.IDLE, false, null)),
                false,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(0L, 0L, 0L, 0L));
        assertEquals("Partial: 4 / 10", value(partial, "Farmland coverage"));
    }

    @Test
    void unavailableWorkerOverridesStatusAndUnknownFarmIsNotMeasured() {
        List<ProfessionalStorageTab> tabs = FarmerProfessionalStorageProfileProvider.buildTabs(
                List.of(
                        loaded(FIRST, FARM_A, FarmerHarvestGoal.FarmerActivity.HARVESTING, false, null),
                        new FarmerProfessionalStorageProfileProvider.FarmerWorkerView(
                                SECOND,
                                ProfessionalStorageResolution.WorkerAvailability.UNLOADED,
                                null,
                                null)),
                false,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(12L, 11L, 3L, 2L));

        assertEquals("Worker unavailable", value(tabs, "Status"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, tabs.getFirst().rows().get(0).tone());
        assertEquals("Not measured", value(tabs, "Farmland coverage"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, tabs.getFirst().rows().get(2).tone());
        assertEquals("No", value(tabs, "Hoe available"));
    }

    @Test
    void materialsCraftedAggregatesAcrossDistinctPairedFarmerUuids() {
        ProfessionalWorkStatsState stats = new ProfessionalWorkStatsState();
        ProfessionalRoleId farmer = new ProfessionalRoleId(Identifier.of("minecraft:farmer"));
        stats.increment(FIRST, farmer, FarmerWorkMetrics.MATERIALS_CRAFTED, 3L);
        stats.increment(SECOND, farmer, FarmerWorkMetrics.MATERIALS_CRAFTED, 5L);

        FarmerProfessionalStorageProfileProvider.FarmerCareerTotals totals =
                FarmerProfessionalStorageProfileProvider.aggregateCareerTotals(
                        stats,
                        List.of(FIRST, SECOND, FIRST),
                        farmer);

        assertEquals(8L, totals.materialsCrafted());
    }

    private static FarmerProfessionalStorageProfileProvider.FarmerWorkerView loaded(
            UUID uuid,
            BlockPos farm,
            FarmerHarvestGoal.FarmerActivity activity,
            boolean hoe,
            FarmerHarvestGoal.FarmerCoverageSnapshot coverage
    ) {
        return new FarmerProfessionalStorageProfileProvider.FarmerWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                farm,
                new FarmerHarvestGoal.FarmerLiveSnapshot(activity, hoe, coverage));
    }

    private static FarmerHarvestGoal.FarmerCoverageSnapshot coverage(int seeded, int accessible) {
        return new FarmerHarvestGoal.FarmerCoverageSnapshot(seeded, accessible);
    }

    private static String value(List<ProfessionalStorageTab> tabs, String label) {
        return tabs.stream()
                .flatMap(tab -> tab.rows().stream())
                .filter(row -> row.label().equals(label))
                .findFirst()
                .orElseThrow()
                .value();
    }
}
