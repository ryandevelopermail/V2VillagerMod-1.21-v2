package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.util.CartographerMapChestUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartographerProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("91000000-0000-0000-0000-000000000002");

    @Test
    void nativeCartographerRegistersWithExpectedTitle() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(CartographerWorkMetrics.CARTOGRAPHER_ROLE));
        assertEquals("minecraft:cartographer", CartographerWorkMetrics.CARTOGRAPHER_ROLE.toString());
        assertEquals("Cartographer Storage", ProfessionalStorageSnapshotFactory.titleFor(
                CartographerWorkMetrics.CARTOGRAPHER_ROLE,
                1,
                1));
    }

    @Test
    void tabsRowsOrderAndValuesAreExactWithoutDeliveryInformation() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, true, true, 5)),
                new CartographerProfessionalStorageProfileProvider.CartographerStorageCounts(
                        true, 6, 6, 2, 8, 4, true),
                5);

        assertEquals(List.of("overview", "exploration", "crafting", "map_wall"),
                tabs.stream().map(ProfessionalStorageTab::id).toList());
        assertEquals(List.of("Overview", "Exploration", "Crafting", "Map Wall"),
                tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of("Status", "Crafting table", "Cartography table", "Cold protection"),
                labels(tabs.get(0)));
        assertEquals(List.of("Empty map tokens", "Completed maps stored", "Unique map regions",
                "Maps completed", "Maps copied"), labels(tabs.get(1)));
        assertEquals(List.of("Craftable recipes", "Materials crafted"), labels(tabs.get(2)));
        assertEquals(List.of("Wall maps available", "Item frames", "Wall materials ready", "Maps displayed"),
                labels(tabs.get(3)));
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("Yes", value(tabs, "Crafting table"));
        assertEquals("Yes", value(tabs, "Cartography table"));
        assertEquals("Yes", value(tabs, "Cold protection"));
        assertEquals("6", value(tabs, "Empty map tokens"));
        assertEquals("6", value(tabs, "Completed maps stored"));
        assertEquals("2", value(tabs, "Unique map regions"));
        assertEquals("17", value(tabs, "Maps completed"));
        assertEquals("11", value(tabs, "Maps copied"));
        assertEquals("5", value(tabs, "Craftable recipes"));
        assertEquals("13", value(tabs, "Materials crafted"));
        assertEquals("8", value(tabs, "Wall maps available"));
        assertEquals("4", value(tabs, "Item frames"));
        assertEquals("Yes", value(tabs, "Wall materials ready"));
        assertEquals("19", value(tabs, "Maps displayed"));
        assertFalse(tabs.stream().flatMap(tab -> tab.rows().stream())
                .anyMatch(row -> row.label().toLowerCase().contains("delivery")));
    }

    @Test
    void oneStorageScanClassifiesInvalidAndPopulatedMapsWithDistinctRules() {
        CartographerMapChestUtil.MapSignature first = CartographerMapChestUtil.MapSignature.of(0, 0, 2);
        CartographerMapChestUtil.MapSignature second = CartographerMapChestUtil.MapSignature.of(256, 0, 2);
        CartographerProfessionalStorageProfileProvider.CartographerStorageCounts counts =
                CartographerProfessionalStorageProfileProvider.countStorageViews(List.of(
                        stack(true, false, false, null, false, false, 1),
                        stack(false, true, false, null, false, false, 4),
                        stack(false, true, false, null, true, false, 2),
                        stack(false, false, true, first, true, false, 3),
                        stack(false, false, true, first, true, false, 2),
                        stack(false, false, true, second, true, false, 1),
                        stack(false, false, false, null, false, true, 4),
                        stack(false, false, false, null, false, false, 10)));
        assertEquals(new CartographerProfessionalStorageProfileProvider.CartographerStorageCounts(
                true, 6, 6, 2, 8, 4, true), counts);
    }

    @Test
    void resolvedSharedStorageIsEvaluatedExactlyOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        CartographerProfessionalStorageProfileProvider.CartographerStorageCounts counts =
                CartographerProfessionalStorageProfileProvider.countResolvedStorageContents(() -> {
                    resolutions.incrementAndGet();
                    return List.of(stack(false, true, false, null, true, false, 3));
                });
        assertEquals(1, resolutions.get());
        assertEquals(3, counts.emptyMapTokens());
        assertEquals(3, counts.wallMapsAvailable());
        assertEquals(0, counts.completedMapsStored());
    }

    @Test
    void deterministicRepresentativeAndMultiworkerReadinessAreStable() {
        List<CartographerProfessionalStorageProfileProvider.CartographerWorkerView> workers = List.of(
                loaded(SECOND, true, false, 2),
                loaded(FIRST, false, true, 4));
        assertEquals(FIRST, CartographerProfessionalStorageProfileProvider.selectRepresentative(workers).orElseThrow());
        List<ProfessionalStorageTab> tabs = tabs(
                workers,
                new CartographerProfessionalStorageProfileProvider.CartographerStorageCounts(
                        false, 0, 0, 0, 0, 0, false),
                4);
        assertEquals("1 / 2 ready", value(tabs, "Crafting table"));
        assertEquals("1 / 2 ready", value(tabs, "Cartography table"));
        assertEquals("4", value(tabs, "Craftable recipes"));
        assertEquals("No", value(tabs, "Cold protection"));
    }

    @Test
    void partialAndUnavailableWorkersUseEstablishedPresentation() {
        List<ProfessionalStorageTab> partial = tabs(
                List.of(loaded(FIRST, true, true, 3), unloaded(SECOND)),
                new CartographerProfessionalStorageProfileProvider.CartographerStorageCounts(
                        false, 0, 0, 0, 0, 0, false),
                3);
        assertEquals("Worker unavailable", value(partial, "Status"));
        assertEquals("Partial: Yes", value(partial, "Crafting table"));
        assertEquals("Partial: Yes", value(partial, "Cartography table"));
        assertEquals("Partial: 3", value(partial, "Craftable recipes"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(partial, "Craftable recipes").tone());

        List<ProfessionalStorageTab> unavailable = tabs(
                List.of(unloaded(FIRST), unloaded(SECOND)),
                new CartographerProfessionalStorageProfileProvider.CartographerStorageCounts(
                        false, 0, 0, 0, 0, 0, false),
                null);
        assertEquals("Unknown", value(unavailable, "Crafting table"));
        assertEquals("Unknown", value(unavailable, "Cartography table"));
        assertEquals("Not measured", value(unavailable, "Craftable recipes"));
    }

    @Test
    void missingCraftingTableShowsZeroCraftableRecipes() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, false, true, 0)),
                new CartographerProfessionalStorageProfileProvider.CartographerStorageCounts(
                        false, 0, 0, 0, 0, 0, false),
                0);
        assertEquals("No", value(tabs, "Crafting table"));
        assertEquals("0", value(tabs, "Craftable recipes"));
    }

    @Test
    void careerTotalsAggregateDistinctResolvedCartographers() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_COMPLETED, 3, true);
        CartographerWorkMetrics.record(state, SECOND, CartographerWorkMetrics.MAPS_COMPLETED, 5, true);
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_COPIED, 2, true);
        CartographerWorkMetrics.record(state, SECOND, CartographerWorkMetrics.MATERIALS_CRAFTED, 7, true);
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_DISPLAYED, 11, true);
        assertEquals(
                new CartographerProfessionalStorageProfileProvider.CartographerCareerTotals(8, 2, 7, 11),
                CartographerProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST, SECOND, FIRST),
                        CartographerWorkMetrics.CARTOGRAPHER_ROLE));
    }

    private static List<ProfessionalStorageTab> tabs(
            List<CartographerProfessionalStorageProfileProvider.CartographerWorkerView> workers,
            CartographerProfessionalStorageProfileProvider.CartographerStorageCounts storage,
            Integer craftable
    ) {
        return CartographerProfessionalStorageProfileProvider.buildTabs(
                workers,
                storage,
                craftable,
                new CartographerProfessionalStorageProfileProvider.CartographerCareerTotals(17, 11, 13, 19));
    }

    private static CartographerProfessionalStorageProfileProvider.CartographerWorkerView loaded(
            UUID uuid,
            boolean craftingTable,
            boolean cartographyTable,
            int craftable
    ) {
        return new CartographerProfessionalStorageProfileProvider.CartographerWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                craftingTable,
                cartographyTable,
                craftable);
    }

    private static CartographerProfessionalStorageProfileProvider.CartographerWorkerView unloaded(UUID uuid) {
        return CartographerProfessionalStorageProfileProvider.CartographerWorkerView.unloaded(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.UNLOADED);
    }

    private static CartographerProfessionalStorageProfileProvider.CartographerStorageStackView stack(
            boolean boots,
            boolean empty,
            boolean completed,
            CartographerMapChestUtil.MapSignature signature,
            boolean wallMap,
            boolean frame,
            long count
    ) {
        return new CartographerProfessionalStorageProfileProvider.CartographerStorageStackView(
                boots, empty, completed, signature, wallMap, frame, count);
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
