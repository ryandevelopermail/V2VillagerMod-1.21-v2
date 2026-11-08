package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.ButcherSmokerGoal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButcherProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("85000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("85000000-0000-0000-0000-000000000002");

    @Test
    void nativeButcherRegistersWithoutRegisteringButcherGuard() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(ButcherWorkMetrics.BUTCHER_ROLE));
        assertFalse(ProfessionalStorageProfileProviders.hasProvider(ProfessionalRoleId.BUTCHER_GUARD));
        assertEquals("minecraft:butcher", ButcherWorkMetrics.BUTCHER_ROLE.toString());
        assertEquals("Butcher Storage", ProfessionalStorageSnapshotFactory.titleFor(
                ButcherWorkMetrics.BUTCHER_ROLE,
                1,
                1));
    }

    @Test
    void tabsRowsIdsValuesAndTonesAreExact() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, true, true, ButcherSmokerGoal.SmokerState.SMOKING, 1)),
                new ButcherProfessionalStorageProfileProvider.ButcherStorageCounts(12, 8, 3, 5),
                ButcherSmokerGoal.SmokerState.SMOKING,
                1);

        assertEquals(List.of("overview", "cooking", "crafting", "distribution"),
                tabs.stream().map(ProfessionalStorageTab::id).toList());
        assertEquals(List.of("Overview", "Cooking", "Crafting", "Distribution"),
                tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of("Status", "Crafting table", "Smoker"), labels(tabs.get(0)));
        assertEquals(List.of("Smoker state", "Smokable food", "Fuel stock", "Cooked food collected"),
                labels(tabs.get(1)));
        assertEquals(List.of("Craftable smokers", "Smokers crafted"), labels(tabs.get(2)));
        assertEquals(List.of("Meals awaiting", "Meals delivered", "Leather/hide stock", "Leather/hide delivered"),
                labels(tabs.get(3)));
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("Yes", value(tabs, "Crafting table"));
        assertEquals("Yes", value(tabs, "Smoker"));
        assertEquals("Smoking", value(tabs, "Smoker state"));
        assertEquals("12", value(tabs, "Smokable food"));
        assertEquals("8", value(tabs, "Fuel stock"));
        assertEquals("17", value(tabs, "Cooked food collected"));
        assertEquals("1", value(tabs, "Craftable smokers"));
        assertEquals("11", value(tabs, "Smokers crafted"));
        assertEquals("3", value(tabs, "Meals awaiting"));
        assertEquals("13", value(tabs, "Meals delivered"));
        assertEquals("5", value(tabs, "Leather/hide stock"));
        assertEquals("19", value(tabs, "Leather/hide delivered"));
        assertEquals(ProfessionalStorageRow.Tone.PAIRED, row(tabs, "Smoker state").tone());
    }

    @Test
    void storageClassificationCountsOnlyMatchingUnits() {
        assertEquals(
                new ButcherProfessionalStorageProfileProvider.ButcherStorageCounts(9, 6, 4, 7),
                ButcherProfessionalStorageProfileProvider.countStorageViews(List.of(
                        stack(true, false, false, false, 9),
                        stack(false, true, false, false, 6),
                        stack(false, false, true, false, 4),
                        stack(false, false, false, true, 7),
                        stack(false, false, false, false, 64))));
    }

    @Test
    void combinedStorageContentsAreResolvedExactlyOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        ButcherProfessionalStorageProfileProvider.ButcherStorageCounts counts =
                ButcherProfessionalStorageProfileProvider.countResolvedStorageContents(() -> {
                    resolutions.incrementAndGet();
                    return List.of(
                            stack(true, false, true, false, 4),
                            stack(false, true, false, true, 2));
                });
        assertEquals(new ButcherProfessionalStorageProfileProvider.ButcherStorageCounts(4, 2, 4, 2), counts);
        assertEquals(1, resolutions.get());
    }

    @Test
    void deterministicRepresentativeAndSharedReadinessAreStable() {
        List<ButcherProfessionalStorageProfileProvider.ButcherWorkerView> workers = List.of(
                loaded(SECOND, true, false, ButcherSmokerGoal.SmokerState.MISSING, 0),
                loaded(FIRST, false, true, ButcherSmokerGoal.SmokerState.OUTPUT_READY, 1));
        assertEquals(FIRST, ButcherProfessionalStorageProfileProvider.selectRepresentative(workers).orElseThrow());
        List<ProfessionalStorageTab> tabs = tabs(
                workers,
                new ButcherProfessionalStorageProfileProvider.ButcherStorageCounts(0, 0, 0, 0),
                ButcherSmokerGoal.SmokerState.OUTPUT_READY,
                1);
        assertEquals("1 / 2 ready", value(tabs, "Crafting table"));
        assertEquals("1 / 2 ready", value(tabs, "Smoker"));
        assertEquals("Output ready", value(tabs, "Smoker state"));
        assertEquals("1", value(tabs, "Craftable smokers"));
    }

    @Test
    void partialAndFullyUnavailablePresentationsUseWarnings() {
        List<ProfessionalStorageTab> partial = tabs(
                List.of(
                        loaded(FIRST, true, true, ButcherSmokerGoal.SmokerState.LOADED, 1),
                        unloaded(SECOND)),
                new ButcherProfessionalStorageProfileProvider.ButcherStorageCounts(1, 2, 3, 4),
                ButcherSmokerGoal.SmokerState.LOADED,
                1);
        assertEquals("Worker unavailable", value(partial, "Status"));
        assertEquals("Partial: Yes", value(partial, "Crafting table"));
        assertEquals("Partial: Yes", value(partial, "Smoker"));
        assertEquals("Partial: Loaded", value(partial, "Smoker state"));
        assertEquals("Partial: 1", value(partial, "Craftable smokers"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(partial, "Craftable smokers").tone());

        List<ProfessionalStorageTab> unavailable = tabs(
                List.of(unloaded(FIRST), unloaded(SECOND)),
                new ButcherProfessionalStorageProfileProvider.ButcherStorageCounts(1, 2, 3, 4),
                null,
                null);
        assertEquals("Unknown", value(unavailable, "Crafting table"));
        assertEquals("Unknown", value(unavailable, "Smoker"));
        assertEquals("Not measured", value(unavailable, "Smoker state"));
        assertEquals("Not measured", value(unavailable, "Craftable smokers"));
    }

    @Test
    void missingWorksitesReportNoMissingAndZeroCraftable() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, false, false, ButcherSmokerGoal.SmokerState.MISSING, 0)),
                new ButcherProfessionalStorageProfileProvider.ButcherStorageCounts(0, 0, 0, 0),
                ButcherSmokerGoal.SmokerState.MISSING,
                0);
        assertEquals("No", value(tabs, "Crafting table"));
        assertEquals("No", value(tabs, "Smoker"));
        assertEquals("Missing", value(tabs, "Smoker state"));
        assertEquals("0", value(tabs, "Craftable smokers"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(tabs, "Smoker state").tone());
    }

    @Test
    void careerTotalsAggregateDistinctNativeButcherIds() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.COOKED_FOOD_COLLECTED, 3, true);
        ButcherWorkMetrics.record(state, SECOND, ButcherWorkMetrics.COOKED_FOOD_COLLECTED, 5, true);
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.SMOKERS_CRAFTED, 2, true);
        ButcherWorkMetrics.record(state, SECOND, ButcherWorkMetrics.MEALS_DELIVERED, 7, true);
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.LEATHER_HIDE_DELIVERED, 11, true);
        assertEquals(
                new ButcherProfessionalStorageProfileProvider.ButcherCareerTotals(8, 2, 7, 11),
                ButcherProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST, SECOND, FIRST),
                        ButcherWorkMetrics.BUTCHER_ROLE));
        assertEquals(0, state.aggregate(
                List.of(FIRST, SECOND),
                ProfessionalRoleId.BUTCHER_GUARD,
                ButcherWorkMetrics.COOKED_FOOD_COLLECTED));
    }

    private static List<ProfessionalStorageTab> tabs(
            List<ButcherProfessionalStorageProfileProvider.ButcherWorkerView> workers,
            ButcherProfessionalStorageProfileProvider.ButcherStorageCounts storage,
            ButcherSmokerGoal.SmokerState state,
            Integer craftable
    ) {
        return ButcherProfessionalStorageProfileProvider.buildTabs(
                workers,
                storage,
                state,
                craftable,
                new ButcherProfessionalStorageProfileProvider.ButcherCareerTotals(17, 11, 13, 19));
    }

    private static ButcherProfessionalStorageProfileProvider.ButcherWorkerView loaded(
            UUID uuid,
            boolean table,
            boolean smoker,
            ButcherSmokerGoal.SmokerState state,
            int craftable
    ) {
        return new ButcherProfessionalStorageProfileProvider.ButcherWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                table,
                smoker,
                state,
                craftable);
    }

    private static ButcherProfessionalStorageProfileProvider.ButcherWorkerView unloaded(UUID uuid) {
        return ButcherProfessionalStorageProfileProvider.ButcherWorkerView.unloaded(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.UNLOADED);
    }

    private static ButcherProfessionalStorageProfileProvider.ButcherStorageStackView stack(
            boolean smokable,
            boolean fuel,
            boolean meal,
            boolean leather,
            long count
    ) {
        return new ButcherProfessionalStorageProfileProvider.ButcherStorageStackView(
                smokable,
                fuel,
                meal,
                leather,
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
