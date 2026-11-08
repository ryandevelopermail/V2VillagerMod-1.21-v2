package dev.sterner.guardvillagers.common.professionalstorage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishermanProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("92000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("92000000-0000-0000-0000-000000000002");

    @Test
    void sameRoleAwareProviderRegistersForNativeFishermanAndFishermanGuard() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(FishermanWorkMetrics.FISHERMAN_ROLE));
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(ProfessionalRoleId.FISHERMAN_GUARD));
        assertTrue(FishermanProfessionalStorageProfileProvider.isSupportedRole(FishermanWorkMetrics.FISHERMAN_ROLE));
        assertTrue(FishermanProfessionalStorageProfileProvider.isSupportedRole(ProfessionalRoleId.FISHERMAN_GUARD));
        assertFalse(FishermanProfessionalStorageProfileProvider.isSupportedRole(ProfessionalRoleId.BUTCHER_GUARD));
        assertEquals("minecraft:fisherman", FishermanWorkMetrics.FISHERMAN_ROLE.toString());
        assertEquals("Fisherman Storage", ProfessionalStorageSnapshotFactory.titleFor(
                FishermanWorkMetrics.FISHERMAN_ROLE, 1, 1));
    }

    @Test
    void guardOnlyNativeMeasurementsRenderAsNotMeasured() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(new FishermanProfessionalStorageProfileProvider.FishermanWorkerView(
                        FIRST, ProfessionalStorageResolution.WorkerAvailability.LOADED,
                        null, true, null, 2)),
                new FishermanProfessionalStorageProfileProvider.FishermanStorageCounts(0, 0, 0, 0),
                null,
                2);
        assertEquals("Not measured", value(tabs, "Crafting table"));
        assertEquals("Yes", value(tabs, "Barrel"));
        assertEquals("Not measured", value(tabs, "Craftable recipes"));
        assertEquals("2", value(tabs, "Eligible Butchers"));
    }

    @Test
    void exactTabsAndRowsUseTheNativeFishermanScope() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, true, true, 4, 3)),
                new FishermanProfessionalStorageProfileProvider.FishermanStorageCounts(1, 2, 3, 4),
                4,
                3);
        assertEquals(List.of("overview", "crafting", "distribution"),
                tabs.stream().map(ProfessionalStorageTab::id).toList());
        assertEquals(List.of("Overview", "Crafting", "Distribution"),
                tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of("Status", "Crafting table", "Barrel", "Fishing rod ready"), labels(tabs.get(0)));
        assertEquals(List.of("Craftable recipes", "Fishing rods stored", "Buckets stored", "Boats stored",
                "Rods crafted", "Buckets crafted", "Boats crafted"), labels(tabs.get(1)));
        assertEquals(List.of("Fish stored", "Eligible Butchers", "Fish delivered"), labels(tabs.get(2)));
        assertEquals("Yes", value(tabs, "Crafting table"));
        assertEquals("Yes", value(tabs, "Barrel"));
        assertEquals("Yes", value(tabs, "Fishing rod ready"));
        assertEquals("4", value(tabs, "Craftable recipes"));
        assertEquals("1", value(tabs, "Fishing rods stored"));
        assertEquals("2", value(tabs, "Buckets stored"));
        assertEquals("3", value(tabs, "Boats stored"));
        assertEquals("4", value(tabs, "Fish stored"));
        assertEquals("3", value(tabs, "Eligible Butchers"));
        assertFalse(tabs.stream().flatMap(tab -> tab.rows().stream()).anyMatch(row ->
                row.label().toLowerCase().contains("caught") || row.label().toLowerCase().contains("trip")));
    }

    @Test
    void storageCategoriesCountEachPhysicalStackOnce() {
        FishermanProfessionalStorageProfileProvider.FishermanStorageCounts counts =
                FishermanProfessionalStorageProfileProvider.countStorageViews(List.of(
                        stack(true, false, false, false, 1),
                        stack(false, true, false, false, 5),
                        stack(false, false, true, false, 3),
                        stack(false, false, true, false, 2),
                        stack(false, false, false, true, 7),
                        stack(false, false, false, false, 100)));
        assertEquals(new FishermanProfessionalStorageProfileProvider.FishermanStorageCounts(1, 5, 5, 7), counts);
    }

    @Test
    void sharedResolvedStorageScansExactlyOnce() {
        AtomicInteger scans = new AtomicInteger();
        FishermanProfessionalStorageProfileProvider.FishermanStorageCounts counts =
                FishermanProfessionalStorageProfileProvider.countResolvedStorageContents(() -> {
                    scans.incrementAndGet();
                    return List.of(stack(true, true, true, true, 2));
                });
        assertEquals(1, scans.get());
        assertEquals(new FishermanProfessionalStorageProfileProvider.FishermanStorageCounts(2, 2, 2, 2), counts);
    }

    @Test
    void representativeSelectionAndPartialPresentationAreDeterministic() {
        List<FishermanProfessionalStorageProfileProvider.FishermanWorkerView> workers = List.of(
                loaded(SECOND, true, false, 1, 2),
                loaded(FIRST, false, true, 5, 7));
        assertEquals(FIRST, FishermanProfessionalStorageProfileProvider.selectRepresentative(workers).orElseThrow());
        List<ProfessionalStorageTab> multi = tabs(
                workers,
                new FishermanProfessionalStorageProfileProvider.FishermanStorageCounts(0, 0, 0, 0),
                5,
                7);
        assertEquals("1 / 2 ready", value(multi, "Crafting table"));
        assertEquals("1 / 2 ready", value(multi, "Barrel"));
        assertEquals("5", value(multi, "Craftable recipes"));
        assertEquals("7", value(multi, "Eligible Butchers"));

        List<ProfessionalStorageTab> partial = tabs(
                List.of(loaded(FIRST, true, true, 2, 1), unloaded(SECOND)),
                new FishermanProfessionalStorageProfileProvider.FishermanStorageCounts(0, 0, 0, 0),
                2,
                1);
        assertEquals("Worker unavailable", value(partial, "Status"));
        assertEquals("Partial: Yes", value(partial, "Crafting table"));
        assertEquals("Partial: Yes", value(partial, "Barrel"));
        assertEquals("Partial: 2", value(partial, "Craftable recipes"));
        assertEquals("Partial: 1", value(partial, "Eligible Butchers"));

        List<ProfessionalStorageTab> unavailable = tabs(
                List.of(unloaded(FIRST)),
                new FishermanProfessionalStorageProfileProvider.FishermanStorageCounts(0, 0, 0, 0),
                null,
                null);
        assertEquals("Unknown", value(unavailable, "Crafting table"));
        assertEquals("Unknown", value(unavailable, "Barrel"));
        assertEquals("Not measured", value(unavailable, "Craftable recipes"));
        assertEquals("Not measured", value(unavailable, "Eligible Butchers"));
    }

    @Test
    void careerTotalsAggregateEachResolvedFishermanOnce() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        FishermanWorkMetrics.record(state, FIRST, FishermanWorkMetrics.FISHING_RODS_CRAFTED, 3, true);
        FishermanWorkMetrics.record(state, SECOND, FishermanWorkMetrics.FISHING_RODS_CRAFTED, 5, true);
        FishermanWorkMetrics.record(state, FIRST, FishermanWorkMetrics.BUCKETS_CRAFTED, 2, true);
        FishermanWorkMetrics.record(state, SECOND, FishermanWorkMetrics.BOATS_CRAFTED, 7, true);
        FishermanWorkMetrics.record(state, FIRST, FishermanWorkMetrics.FISH_DELIVERED, 11, true);
        assertEquals(new FishermanProfessionalStorageProfileProvider.FishermanCareerTotals(8, 2, 7, 11),
                FishermanProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST, SECOND, FIRST),
                        FishermanWorkMetrics.FISHERMAN_ROLE));
    }

    @Test
    void careerTotalsUseTheResolvedGuardRole() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        state.increment(FIRST, FishermanWorkMetrics.FISHERMAN_ROLE,
                FishermanWorkMetrics.FISH_DELIVERED, 5);
        state.increment(FIRST, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.FISH_DELIVERED, 13);

        assertEquals(new FishermanProfessionalStorageProfileProvider.FishermanCareerTotals(0, 0, 0, 13),
                FishermanProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST),
                        ProfessionalRoleId.FISHERMAN_GUARD));
    }

    private static List<ProfessionalStorageTab> tabs(
            List<FishermanProfessionalStorageProfileProvider.FishermanWorkerView> workers,
            FishermanProfessionalStorageProfileProvider.FishermanStorageCounts storage,
            Integer craftable,
            Integer eligibleButchers
    ) {
        return FishermanProfessionalStorageProfileProvider.buildTabs(
                workers,
                storage,
                craftable,
                eligibleButchers,
                new FishermanProfessionalStorageProfileProvider.FishermanCareerTotals(17, 11, 13, 19));
    }

    private static FishermanProfessionalStorageProfileProvider.FishermanWorkerView loaded(
            UUID uuid, boolean table, boolean barrel, int craftable, int recipients
    ) {
        return new FishermanProfessionalStorageProfileProvider.FishermanWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                table,
                barrel,
                craftable,
                recipients);
    }

    private static FishermanProfessionalStorageProfileProvider.FishermanWorkerView unloaded(UUID uuid) {
        return FishermanProfessionalStorageProfileProvider.FishermanWorkerView.unloaded(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.UNLOADED);
    }

    private static FishermanProfessionalStorageProfileProvider.FishermanStorageStackView stack(
            boolean rod, boolean bucket, boolean boat, boolean rawFish, long count
    ) {
        return new FishermanProfessionalStorageProfileProvider.FishermanStorageStackView(
                rod, bucket, boat, rawFish, count);
    }

    private static List<String> labels(ProfessionalStorageTab tab) {
        return tab.rows().stream().map(ProfessionalStorageRow::label).toList();
    }

    private static String value(List<ProfessionalStorageTab> tabs, String label) {
        return tabs.stream().flatMap(tab -> tab.rows().stream()).filter(row -> row.label().equals(label))
                .findFirst().orElseThrow().value();
    }
}
