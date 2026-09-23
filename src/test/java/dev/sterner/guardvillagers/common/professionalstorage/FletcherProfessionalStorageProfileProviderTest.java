package dev.sterner.guardvillagers.common.professionalstorage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FletcherProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("75000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("75000000-0000-0000-0000-000000000002");

    @Test
    void stableFletcherRoleRegistersProvider() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(FletcherWorkMetrics.FLETCHER_ROLE));
        assertEquals("minecraft:fletcher", FletcherWorkMetrics.FLETCHER_ROLE.toString());
    }

    @Test
    void tabsRowsAndValuesAppearInExactRequiredOrder() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, true, 5)),
                new FletcherProfessionalStorageProfileProvider.FletcherStorageCounts(12, 2),
                5,
                true);

        assertEquals(List.of("Overview", "Crafting", "Distribution"),
                tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of("Status", "Crafting table", "Arrows in storage", "Ranged weapons in storage"),
                labels(tabs.get(0)));
        assertEquals(List.of("Craftable outputs", "Arrow batch ready", "Fletching goods crafted"),
                labels(tabs.get(1)));
        assertEquals(List.of("Ranged weapons equipped", "Arrows delivered", "Sticks delivered"),
                labels(tabs.get(2)));
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("Yes", value(tabs, "Crafting table"));
        assertEquals("12", value(tabs, "Arrows in storage"));
        assertEquals("2", value(tabs, "Ranged weapons in storage"));
        assertEquals("5", value(tabs, "Craftable outputs"));
        assertEquals("Yes", value(tabs, "Arrow batch ready"));
        assertEquals("11", value(tabs, "Fletching goods crafted"));
        assertEquals("12", value(tabs, "Ranged weapons equipped"));
        assertEquals("13", value(tabs, "Arrows delivered"));
        assertEquals("14", value(tabs, "Sticks delivered"));
    }

    @Test
    void storageCountsAllArrowTaggedStacksAndOnlyBowOrCrossbowViews() {
        assertEquals(
                new FletcherProfessionalStorageProfileProvider.FletcherStorageCounts(12, 3),
                FletcherProfessionalStorageProfileProvider.countStorageViews(List.of(
                        stack(true, false, 5),
                        stack(true, false, 7),
                        stack(false, true, 1),
                        stack(false, true, 2),
                        stack(false, false, 99))));
    }

    @Test
    void combinedDoubleChestContentsAreResolvedExactlyOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        FletcherProfessionalStorageProfileProvider.FletcherStorageCounts counts =
                FletcherProfessionalStorageProfileProvider.countResolvedStorageContents(() -> {
                    resolutions.incrementAndGet();
                    return List.of(stack(true, false, 4), stack(false, true, 2));
                });
        assertEquals(new FletcherProfessionalStorageProfileProvider.FletcherStorageCounts(4, 2), counts);
        assertEquals(1, resolutions.get());
    }

    @Test
    void deterministicRepresentativePreventsSharedCraftableCountMultiplication() {
        List<FletcherProfessionalStorageProfileProvider.FletcherWorkerView> workers = List.of(
                loaded(SECOND, true, 6),
                loaded(FIRST, false, 6));
        assertEquals(FIRST, FletcherProfessionalStorageProfileProvider.selectRepresentative(workers).orElseThrow());
        List<ProfessionalStorageTab> tabs = tabs(
                workers,
                new FletcherProfessionalStorageProfileProvider.FletcherStorageCounts(0, 0),
                6,
                false);
        assertEquals("1 / 2 ready", value(tabs, "Crafting table"));
        assertEquals("6", value(tabs, "Craftable outputs"));
    }

    @Test
    void partialAndFullyUnavailableDisplaysAreExact() {
        List<ProfessionalStorageTab> partial = tabs(
                List.of(loaded(FIRST, true, 3), unloaded(SECOND)),
                new FletcherProfessionalStorageProfileProvider.FletcherStorageCounts(1, 1),
                3,
                false);
        assertEquals("Worker unavailable", value(partial, "Status"));
        assertEquals("Partial: Yes", value(partial, "Crafting table"));
        assertEquals("3", value(partial, "Craftable outputs"));
        assertEquals("No", value(partial, "Arrow batch ready"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(partial, "Arrow batch ready").tone());

        List<ProfessionalStorageTab> unavailable = tabs(
                List.of(unloaded(FIRST), unloaded(SECOND)),
                new FletcherProfessionalStorageProfileProvider.FletcherStorageCounts(1, 1),
                null,
                true);
        assertEquals("Worker unavailable", value(unavailable, "Status"));
        assertEquals("Unknown", value(unavailable, "Crafting table"));
        assertEquals("Not measured", value(unavailable, "Craftable outputs"));
        assertEquals("Yes", value(unavailable, "Arrow batch ready"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(unavailable, "Craftable outputs").tone());
    }

    @Test
    void singleLoadedWorkerWithoutTableReportsNoAndZeroCraftableOutputs() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, false, 0)),
                new FletcherProfessionalStorageProfileProvider.FletcherStorageCounts(0, 0),
                0,
                false);
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("No", value(tabs, "Crafting table"));
        assertEquals("0", value(tabs, "Craftable outputs"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(tabs, "Crafting table").tone());
    }

    @Test
    void careerTotalsAggregateDistinctLoadedAndUnloadedWorkerIds() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        FletcherWorkMetrics.record(state, FIRST, FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED, 3, true);
        FletcherWorkMetrics.record(state, SECOND, FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED, 5, true);
        FletcherWorkMetrics.record(state, FIRST, FletcherWorkMetrics.RANGED_WEAPONS_EQUIPPED, 2, true);
        FletcherWorkMetrics.record(state, SECOND, FletcherWorkMetrics.ARROWS_DELIVERED, 7, true);
        FletcherWorkMetrics.record(state, SECOND, FletcherWorkMetrics.STICKS_DELIVERED, 11, true);

        assertEquals(
                new FletcherProfessionalStorageProfileProvider.FletcherCareerTotals(8, 2, 7, 11),
                FletcherProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST, SECOND, FIRST),
                        FletcherWorkMetrics.FLETCHER_ROLE));
    }

    private static List<ProfessionalStorageTab> tabs(
            List<FletcherProfessionalStorageProfileProvider.FletcherWorkerView> workers,
            FletcherProfessionalStorageProfileProvider.FletcherStorageCounts storage,
            Integer craftable,
            boolean batchReady
    ) {
        return FletcherProfessionalStorageProfileProvider.buildTabs(
                workers,
                storage,
                craftable,
                batchReady,
                new FletcherProfessionalStorageProfileProvider.FletcherCareerTotals(11, 12, 13, 14));
    }

    private static FletcherProfessionalStorageProfileProvider.FletcherWorkerView loaded(
            UUID uuid,
            boolean table,
            int craftable
    ) {
        return new FletcherProfessionalStorageProfileProvider.FletcherWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                table,
                craftable);
    }

    private static FletcherProfessionalStorageProfileProvider.FletcherWorkerView unloaded(UUID uuid) {
        return new FletcherProfessionalStorageProfileProvider.FletcherWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.UNLOADED,
                null,
                null);
    }

    private static FletcherProfessionalStorageProfileProvider.FletcherStorageStackView stack(
            boolean arrow,
            boolean ranged,
            long count
    ) {
        return new FletcherProfessionalStorageProfileProvider.FletcherStorageStackView(arrow, ranged, count);
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
