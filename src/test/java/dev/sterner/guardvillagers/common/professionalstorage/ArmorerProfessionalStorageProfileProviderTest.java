package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.ArmorerBlastFurnaceGoal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorerProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("81000000-0000-0000-0000-000000000002");

    @Test
    void stableArmorerRoleRegistersProvider() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(ArmorerWorkMetrics.ARMORER_ROLE));
        assertEquals("minecraft:armorer", ArmorerWorkMetrics.ARMORER_ROLE.toString());
        assertEquals("Armorer Storage", ProfessionalStorageSnapshotFactory.titleFor(
                ArmorerWorkMetrics.ARMORER_ROLE,
                1,
                1));
    }

    @Test
    void tabsRowsIdsValuesAndTonesAreExact() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, true, true, ArmorerBlastFurnaceGoal.FurnaceState.SMELTING, 5, 7)),
                new ArmorerProfessionalStorageProfileProvider.ArmorerStorageCounts(12, 8, 3),
                ArmorerBlastFurnaceGoal.FurnaceState.SMELTING,
                5,
                7);

        assertEquals(List.of("overview", "smelting", "crafting", "distribution"),
                tabs.stream().map(ProfessionalStorageTab::id).toList());
        assertEquals(List.of("Overview", "Smelting", "Crafting", "Distribution"),
                tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of("Status", "Crafting table", "Blast furnace"), labels(tabs.get(0)));
        assertEquals(List.of("Furnace state", "Processable materials", "Fuel stock", "Smelted output collected"),
                labels(tabs.get(1)));
        assertEquals(List.of("Craftable armor", "Armor crafted"), labels(tabs.get(2)));
        assertEquals(List.of("Armor awaiting placement", "Eligible armor slots", "Armor equipped"),
                labels(tabs.get(3)));
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("Yes", value(tabs, "Crafting table"));
        assertEquals("Yes", value(tabs, "Blast furnace"));
        assertEquals("Smelting", value(tabs, "Furnace state"));
        assertEquals("12", value(tabs, "Processable materials"));
        assertEquals("8", value(tabs, "Fuel stock"));
        assertEquals("17", value(tabs, "Smelted output collected"));
        assertEquals("5", value(tabs, "Craftable armor"));
        assertEquals("11", value(tabs, "Armor crafted"));
        assertEquals("3", value(tabs, "Armor awaiting placement"));
        assertEquals("7", value(tabs, "Eligible armor slots"));
        assertEquals("13", value(tabs, "Armor equipped"));
        assertEquals(ProfessionalStorageRow.Tone.PAIRED, row(tabs, "Furnace state").tone());
        assertFalse(tabs.stream().flatMap(tab -> tab.rows().stream())
                .anyMatch(row -> row.label().toLowerCase().contains("iron routing")));
    }

    @Test
    void storageClassificationCountsUnitsAndExcludesUnclassifiedItems() {
        assertEquals(
                new ArmorerProfessionalStorageProfileProvider.ArmorerStorageCounts(9, 6, 4),
                ArmorerProfessionalStorageProfileProvider.countStorageViews(List.of(
                        stack(true, false, false, 9),
                        stack(false, true, false, 6),
                        stack(false, false, true, 4),
                        stack(false, false, false, 64))));
    }

    @Test
    void combinedStorageContentsAreResolvedExactlyOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        ArmorerProfessionalStorageProfileProvider.ArmorerStorageCounts counts =
                ArmorerProfessionalStorageProfileProvider.countResolvedStorageContents(() -> {
                    resolutions.incrementAndGet();
                    return List.of(stack(true, false, false, 4), stack(false, true, true, 2));
                });
        assertEquals(new ArmorerProfessionalStorageProfileProvider.ArmorerStorageCounts(4, 2, 2), counts);
        assertEquals(1, resolutions.get());
    }

    @Test
    void deterministicRepresentativeAndSharedReadinessAreStable() {
        List<ArmorerProfessionalStorageProfileProvider.ArmorerWorkerView> workers = List.of(
                loaded(SECOND, true, false, ArmorerBlastFurnaceGoal.FurnaceState.MISSING, 8, 6),
                loaded(FIRST, false, true, ArmorerBlastFurnaceGoal.FurnaceState.OUTPUT_READY, 3, 2));
        assertEquals(FIRST, ArmorerProfessionalStorageProfileProvider.selectRepresentative(workers).orElseThrow());
        List<ProfessionalStorageTab> tabs = tabs(
                workers,
                new ArmorerProfessionalStorageProfileProvider.ArmorerStorageCounts(0, 0, 0),
                ArmorerBlastFurnaceGoal.FurnaceState.OUTPUT_READY,
                3,
                2);
        assertEquals("1 / 2 ready", value(tabs, "Crafting table"));
        assertEquals("1 / 2 ready", value(tabs, "Blast furnace"));
        assertEquals("Output ready", value(tabs, "Furnace state"));
        assertEquals("3", value(tabs, "Craftable armor"));
        assertEquals("2", value(tabs, "Eligible armor slots"));
    }

    @Test
    void partialAndFullyUnavailablePresentationsUseWarnings() {
        List<ProfessionalStorageTab> partial = tabs(
                List.of(
                        loaded(FIRST, true, true, ArmorerBlastFurnaceGoal.FurnaceState.LOADED, 3, 2),
                        unloaded(SECOND)),
                new ArmorerProfessionalStorageProfileProvider.ArmorerStorageCounts(1, 2, 3),
                ArmorerBlastFurnaceGoal.FurnaceState.LOADED,
                3,
                2);
        assertEquals("Worker unavailable", value(partial, "Status"));
        assertEquals("Partial: Yes", value(partial, "Crafting table"));
        assertEquals("Partial: Yes", value(partial, "Blast furnace"));
        assertEquals("Partial: Loaded", value(partial, "Furnace state"));
        assertEquals("Partial: 3", value(partial, "Craftable armor"));
        assertEquals("Partial: 2", value(partial, "Eligible armor slots"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(partial, "Eligible armor slots").tone());

        List<ProfessionalStorageTab> unavailable = tabs(
                List.of(unloaded(FIRST), unloaded(SECOND)),
                new ArmorerProfessionalStorageProfileProvider.ArmorerStorageCounts(1, 2, 3),
                null,
                null,
                null);
        assertEquals("Unknown", value(unavailable, "Crafting table"));
        assertEquals("Unknown", value(unavailable, "Blast furnace"));
        assertEquals("Not measured", value(unavailable, "Furnace state"));
        assertEquals("Not measured", value(unavailable, "Craftable armor"));
        assertEquals("Not measured", value(unavailable, "Eligible armor slots"));
    }

    @Test
    void singleLoadedWorkerWithMissingWorksitesReportsNoAndZeroCraftable() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, false, false, ArmorerBlastFurnaceGoal.FurnaceState.MISSING, 0, 0)),
                new ArmorerProfessionalStorageProfileProvider.ArmorerStorageCounts(0, 0, 0),
                ArmorerBlastFurnaceGoal.FurnaceState.MISSING,
                0,
                0);
        assertEquals("No", value(tabs, "Crafting table"));
        assertEquals("No", value(tabs, "Blast furnace"));
        assertEquals("Missing", value(tabs, "Furnace state"));
        assertEquals("0", value(tabs, "Craftable armor"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(tabs, "Furnace state").tone());
    }

    @Test
    void careerTotalsAggregateDistinctLoadedAndUnloadedWorkerIds() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ArmorerWorkMetrics.record(state, FIRST, ArmorerWorkMetrics.ARMOR_CRAFTED, 3, true);
        ArmorerWorkMetrics.record(state, SECOND, ArmorerWorkMetrics.ARMOR_CRAFTED, 5, true);
        ArmorerWorkMetrics.record(state, SECOND, ArmorerWorkMetrics.SMELTED_OUTPUT_COLLECTED, 7, true);
        ArmorerWorkMetrics.record(state, FIRST, ArmorerWorkMetrics.ARMOR_EQUIPPED, 2, true);

        assertEquals(
                new ArmorerProfessionalStorageProfileProvider.ArmorerCareerTotals(8, 7, 2),
                ArmorerProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST, SECOND, FIRST),
                        ArmorerWorkMetrics.ARMORER_ROLE));
    }

    private static List<ProfessionalStorageTab> tabs(
            List<ArmorerProfessionalStorageProfileProvider.ArmorerWorkerView> workers,
            ArmorerProfessionalStorageProfileProvider.ArmorerStorageCounts storage,
            ArmorerBlastFurnaceGoal.FurnaceState furnaceState,
            Integer craftable,
            Integer slots
    ) {
        return ArmorerProfessionalStorageProfileProvider.buildTabs(
                workers,
                storage,
                furnaceState,
                craftable,
                slots,
                new ArmorerProfessionalStorageProfileProvider.ArmorerCareerTotals(11, 17, 13));
    }

    private static ArmorerProfessionalStorageProfileProvider.ArmorerWorkerView loaded(
            UUID uuid,
            boolean table,
            boolean furnace,
            ArmorerBlastFurnaceGoal.FurnaceState state,
            int craftable,
            int slots
    ) {
        return new ArmorerProfessionalStorageProfileProvider.ArmorerWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                table,
                furnace,
                state,
                craftable,
                slots);
    }

    private static ArmorerProfessionalStorageProfileProvider.ArmorerWorkerView unloaded(UUID uuid) {
        return ArmorerProfessionalStorageProfileProvider.ArmorerWorkerView.unloaded(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.UNLOADED);
    }

    private static ArmorerProfessionalStorageProfileProvider.ArmorerStorageStackView stack(
            boolean processable,
            boolean fuel,
            boolean armor,
            long count
    ) {
        return new ArmorerProfessionalStorageProfileProvider.ArmorerStorageStackView(
                processable,
                fuel,
                armor,
                count);
    }

    private static List<String> labels(ProfessionalStorageTab tab) {
        return tab.rows().stream().map(ProfessionalStorageRow::label).toList();
    }

    private static String value(List<ProfessionalStorageTab> tabs, String label) {
        return row(tabs, label).value();
    }

    private static ProfessionalStorageRow row(List<ProfessionalStorageTab> tabs, String label) {
        return tabs.stream()
                .flatMap(tab -> tab.rows().stream())
                .filter(row -> row.label().equals(label))
                .findFirst()
                .orElseThrow();
    }
}
