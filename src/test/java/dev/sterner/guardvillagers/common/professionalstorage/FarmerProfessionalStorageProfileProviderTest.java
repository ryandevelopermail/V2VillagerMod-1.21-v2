package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.FarmerHarvestGoal;
import net.minecraft.util.math.BlockPos;
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
    void singleFarmerProducesExactOrderedSixRowProfile() {
        List<ProfessionalStorageRow> rows = FarmerProfessionalStorageProfileProvider.buildRows(
                List.of(loaded(
                        FIRST,
                        FARM_A,
                        FarmerHarvestGoal.FarmerActivity.HARVESTING,
                        true,
                        coverage(7, 10))),
                false,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(42L, 35L, 9L));

        assertEquals(List.of(
                        "Status",
                        "Hoe available",
                        "Farmland coverage",
                        "Crops harvested",
                        "Crops planted",
                        "Ground tilled"),
                rows.stream().map(ProfessionalStorageRow::label).toList());
        assertEquals(List.of("Harvesting", "Yes", "7 / 10", "42", "35", "9"),
                rows.stream().map(ProfessionalStorageRow::value).toList());
        assertEquals(ProfessionalStorageRow.Tone.PAIRED, rows.get(0).tone());
        assertEquals(ProfessionalStorageRow.Tone.PAIRED, rows.get(1).tone());
    }

    @Test
    void sharedFarmersReportMultipleActivitiesAndDeduplicateOneFarmCoverage() {
        List<ProfessionalStorageRow> rows = FarmerProfessionalStorageProfileProvider.buildRows(
                List.of(
                        loaded(FIRST, FARM_A, FarmerHarvestGoal.FarmerActivity.TILLING, false, coverage(4, 10)),
                        loaded(SECOND, FARM_A, FarmerHarvestGoal.FarmerActivity.PLANTING, true, coverage(4, 10))),
                false,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(80L, 60L, 20L));

        assertEquals("Multiple activities", value(rows, "Status"));
        assertEquals("Yes", value(rows, "Hoe available"));
        assertEquals("4 / 10", value(rows, "Farmland coverage"));
    }

    @Test
    void distinctFarmCoverageAggregatesAndPartialCoverageIsExplicit() {
        List<ProfessionalStorageRow> complete = FarmerProfessionalStorageProfileProvider.buildRows(
                List.of(
                        loaded(FIRST, FARM_A, FarmerHarvestGoal.FarmerActivity.IDLE, false, coverage(4, 10)),
                        loaded(SECOND, FARM_B, FarmerHarvestGoal.FarmerActivity.IDLE, false, coverage(8, 12))),
                true,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(0L, 0L, 0L));
        assertEquals("12 / 22", value(complete, "Farmland coverage"));
        assertEquals("Idle", value(complete, "Status"));
        assertEquals("Yes", value(complete, "Hoe available"));

        List<ProfessionalStorageRow> partial = FarmerProfessionalStorageProfileProvider.buildRows(
                List.of(
                        loaded(FIRST, FARM_A, FarmerHarvestGoal.FarmerActivity.IDLE, false, coverage(4, 10)),
                        loaded(SECOND, FARM_B, FarmerHarvestGoal.FarmerActivity.IDLE, false, null)),
                false,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(0L, 0L, 0L));
        assertEquals("Partial: 4 / 10", value(partial, "Farmland coverage"));
    }

    @Test
    void unavailableWorkerOverridesStatusAndUnknownFarmIsNotMeasured() {
        List<ProfessionalStorageRow> rows = FarmerProfessionalStorageProfileProvider.buildRows(
                List.of(
                        loaded(FIRST, FARM_A, FarmerHarvestGoal.FarmerActivity.HARVESTING, false, null),
                        new FarmerProfessionalStorageProfileProvider.FarmerWorkerView(
                                SECOND,
                                ProfessionalStorageResolution.WorkerAvailability.UNLOADED,
                                null,
                                null)),
                false,
                new FarmerProfessionalStorageProfileProvider.FarmerCareerTotals(12L, 11L, 3L));

        assertEquals("Worker unavailable", value(rows, "Status"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, rows.get(0).tone());
        assertEquals("Not measured", value(rows, "Farmland coverage"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, rows.get(2).tone());
        assertEquals("No", value(rows, "Hoe available"));
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

    private static String value(List<ProfessionalStorageRow> rows, String label) {
        return rows.stream().filter(row -> row.label().equals(label)).findFirst().orElseThrow().value();
    }
}
