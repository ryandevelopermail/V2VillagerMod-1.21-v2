package dev.sterner.guardvillagers.common.professionalstorage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeatherworkerProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("77000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("77000000-0000-0000-0000-000000000002");

    @Test
    void stableLeatherworkerRoleRegistersProvider() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(LeatherworkerWorkMetrics.LEATHERWORKER_ROLE));
        assertEquals("minecraft:leatherworker", LeatherworkerWorkMetrics.LEATHERWORKER_ROLE.toString());
    }

    @Test
    void tabsRowsAndValuesAppearInExactRequiredOrder() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, true, 5, 3)),
                new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageCounts(12, 19),
                5,
                3);

        assertEquals(List.of("Overview", "Crafting", "Distribution"),
                tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of("Status", "Crafting table", "Leather stock"), labels(tabs.get(0)));
        assertEquals(List.of("Craftable recipes", "Frame demand", "Leather goods crafted"), labels(tabs.get(1)));
        assertEquals(List.of("Goods awaiting delivery", "Goods delivered"), labels(tabs.get(2)));
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("Yes", value(tabs, "Crafting table"));
        assertEquals("12", value(tabs, "Leather stock"));
        assertEquals("5", value(tabs, "Craftable recipes"));
        assertEquals("3", value(tabs, "Frame demand"));
        assertEquals("11", value(tabs, "Leather goods crafted"));
        assertEquals("19", value(tabs, "Goods awaiting delivery"));
        assertEquals("13", value(tabs, "Goods delivered"));
    }

    @Test
    void leatherStockExcludesRabbitHideWhileAwaitingUsesCompletePredicateViews() {
        assertEquals(
                new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageCounts(7, 39),
                LeatherworkerProfessionalStorageProfileProvider.countStorageViews(List.of(
                        stack(true, true, 7),
                        stack(false, true, 5),
                        stack(false, true, 3),
                        stack(false, true, 4),
                        stack(false, true, 6),
                        stack(false, true, 2),
                        stack(false, true, 1),
                        stack(false, true, 8),
                        stack(false, true, 3),
                        stack(false, false, 64))));
    }

    @Test
    void combinedDoubleChestContentsAreResolvedExactlyOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageCounts counts =
                LeatherworkerProfessionalStorageProfileProvider.countResolvedStorageContents(() -> {
                    resolutions.incrementAndGet();
                    return List.of(stack(true, true, 4), stack(false, true, 2));
                });
        assertEquals(new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageCounts(4, 6), counts);
        assertEquals(1, resolutions.get());
    }

    @Test
    void deterministicRepresentativePreventsSharedValuesFromMultiplying() {
        List<LeatherworkerProfessionalStorageProfileProvider.LeatherworkerWorkerView> workers = List.of(
                loaded(SECOND, true, 6, 4),
                loaded(FIRST, false, 6, 4));
        assertEquals(FIRST, LeatherworkerProfessionalStorageProfileProvider.selectRepresentative(workers).orElseThrow());
        List<ProfessionalStorageTab> tabs = tabs(
                workers,
                new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageCounts(0, 0),
                6,
                4);
        assertEquals("1 / 2 ready", value(tabs, "Crafting table"));
        assertEquals("6", value(tabs, "Craftable recipes"));
        assertEquals("4", value(tabs, "Frame demand"));
    }

    @Test
    void partialAndFullyUnavailableDisplaysAreExact() {
        List<ProfessionalStorageTab> partial = tabs(
                List.of(loaded(FIRST, true, 3, 2), unloaded(SECOND)),
                new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageCounts(1, 2),
                3,
                2);
        assertEquals("Worker unavailable", value(partial, "Status"));
        assertEquals("Partial: Yes", value(partial, "Crafting table"));
        assertEquals("3", value(partial, "Craftable recipes"));
        assertEquals("Partial: 2", value(partial, "Frame demand"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(partial, "Frame demand").tone());

        List<ProfessionalStorageTab> unavailable = tabs(
                List.of(unloaded(FIRST), unloaded(SECOND)),
                new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageCounts(1, 2),
                null,
                null);
        assertEquals("Worker unavailable", value(unavailable, "Status"));
        assertEquals("Unknown", value(unavailable, "Crafting table"));
        assertEquals("Not measured", value(unavailable, "Craftable recipes"));
        assertEquals("Not measured", value(unavailable, "Frame demand"));
    }

    @Test
    void singleLoadedWorkerWithoutTableReportsNoAndZeroCraftableRecipes() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, false, 0, 0)),
                new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageCounts(0, 0),
                0,
                0);
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("No", value(tabs, "Crafting table"));
        assertEquals("0", value(tabs, "Craftable recipes"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(tabs, "Crafting table").tone());
    }

    @Test
    void careerTotalsAggregateDistinctLoadedAndUnloadedWorkerIds() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        LeatherworkerWorkMetrics.record(
                state, FIRST, LeatherworkerWorkMetrics.LEATHER_GOODS_CRAFTED, 3, true);
        LeatherworkerWorkMetrics.record(
                state, SECOND, LeatherworkerWorkMetrics.LEATHER_GOODS_CRAFTED, 5, true);
        LeatherworkerWorkMetrics.record(
                state, SECOND, LeatherworkerWorkMetrics.GOODS_DELIVERED, 7, true);

        assertEquals(
                new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerCareerTotals(8, 7),
                LeatherworkerProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST, SECOND, FIRST),
                        LeatherworkerWorkMetrics.LEATHERWORKER_ROLE));
    }

    private static List<ProfessionalStorageTab> tabs(
            List<LeatherworkerProfessionalStorageProfileProvider.LeatherworkerWorkerView> workers,
            LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageCounts storage,
            Integer craftable,
            Integer frameDemand
    ) {
        return LeatherworkerProfessionalStorageProfileProvider.buildTabs(
                workers,
                storage,
                craftable,
                frameDemand,
                new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerCareerTotals(11, 13));
    }

    private static LeatherworkerProfessionalStorageProfileProvider.LeatherworkerWorkerView loaded(
            UUID uuid,
            boolean table,
            int craftable,
            int frameDemand
    ) {
        return new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                table,
                craftable,
                frameDemand);
    }

    private static LeatherworkerProfessionalStorageProfileProvider.LeatherworkerWorkerView unloaded(UUID uuid) {
        return new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.UNLOADED,
                null,
                null,
                null);
    }

    private static LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageStackView stack(
            boolean leather,
            boolean distributable,
            long count
    ) {
        return new LeatherworkerProfessionalStorageProfileProvider.LeatherworkerStorageStackView(
                leather, distributable, count);
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
