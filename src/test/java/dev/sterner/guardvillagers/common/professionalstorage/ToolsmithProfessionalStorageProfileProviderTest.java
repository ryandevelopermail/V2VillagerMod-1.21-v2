package dev.sterner.guardvillagers.common.professionalstorage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsmithProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Test
    void stableToolsmithRoleRegistersProvider() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(ToolsmithWorkMetrics.TOOLSMITH_ROLE));
        assertEquals("minecraft:toolsmith", ToolsmithWorkMetrics.TOOLSMITH_ROLE.toString());
    }

    @Test
    void tabsAndRowsAppearInExactRequiredOrder() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, true)),
                7,
                new ToolsmithProfessionalStorageProfileProvider.ToolsmithDemandView(2, 3, 4, 5));

        assertEquals(List.of("Overview", "Crafting", "Distribution"),
                tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of("Status", "Crafting table", "Tools in storage"), labels(tabs.get(0)));
        assertEquals(List.of("Tools crafted", "Smithing jobs completed"), labels(tabs.get(1)));
        assertEquals(List.of(
                "Recipient demand",
                "Pickaxes needed",
                "Hoes needed",
                "Shears needed",
                "Fishing rods needed",
                "Tools distributed"), labels(tabs.get(2)));
        assertEquals("Demand pending", value(tabs, "Status"));
        assertEquals("Yes", value(tabs, "Crafting table"));
        assertEquals("7", value(tabs, "Tools in storage"));
        assertEquals("14", value(tabs, "Recipient demand"));
    }

    @Test
    void displayedToolCountExcludesShovelsArmorAndUnrelatedContents() {
        long count = ToolsmithProfessionalStorageProfileProvider.countDisplayedTools(List.of(
                stack(ToolsmithProfessionalStorageProfileProvider.StorageToolKind.PICKAXE, 2),
                stack(ToolsmithProfessionalStorageProfileProvider.StorageToolKind.HOE, 3),
                stack(ToolsmithProfessionalStorageProfileProvider.StorageToolKind.SHEARS, 4),
                stack(ToolsmithProfessionalStorageProfileProvider.StorageToolKind.FISHING_ROD, 5),
                stack(ToolsmithProfessionalStorageProfileProvider.StorageToolKind.OTHER, 99),
                stack(ToolsmithProfessionalStorageProfileProvider.StorageToolKind.OTHER, 7)));

        assertEquals(14L, count);
    }

    @Test
    void oneCombinedDoubleChestViewIsCountedOnce() {
        List<ToolsmithProfessionalStorageProfileProvider.StorageStackView> combinedContents = List.of(
                stack(ToolsmithProfessionalStorageProfileProvider.StorageToolKind.PICKAXE, 2),
                stack(ToolsmithProfessionalStorageProfileProvider.StorageToolKind.FISHING_ROD, 1));

        assertEquals(3L, ToolsmithProfessionalStorageProfileProvider.countDisplayedTools(combinedContents));
    }

    @Test
    void demandClampsNegativeDeficitsAndSumsOnlyDisplayedTypes() {
        ToolsmithProfessionalStorageProfileProvider.ToolsmithDemandView demand =
                new ToolsmithProfessionalStorageProfileProvider.ToolsmithDemandView(-4, 3, -2, 5);
        List<ProfessionalStorageTab> tabs = tabs(List.of(loaded(FIRST, false)), 0, demand);

        assertEquals("8", value(tabs, "Recipient demand"));
        assertEquals("0", value(tabs, "Pickaxes needed"));
        assertEquals("3", value(tabs, "Hoes needed"));
        assertEquals("0", value(tabs, "Shears needed"));
        assertEquals("5", value(tabs, "Fishing rods needed"));
    }

    @Test
    void representativeWorkerIsDeterministicAndDemandIsNotMultiplied() {
        List<ToolsmithProfessionalStorageProfileProvider.ToolsmithWorkerView> workers = List.of(
                loaded(SECOND, true),
                loaded(FIRST, true));
        assertEquals(FIRST, ToolsmithProfessionalStorageProfileProvider.selectRepresentative(workers).orElseThrow());

        List<ProfessionalStorageTab> tabs = tabs(
                workers,
                0,
                new ToolsmithProfessionalStorageProfileProvider.ToolsmithDemandView(1, 1, 1, 1));
        assertEquals("4", value(tabs, "Recipient demand"));
        assertEquals("2 / 2 ready", value(tabs, "Crafting table"));
    }

    @Test
    void partialAndFullyUnavailableFormattingIsExplicit() {
        ToolsmithProfessionalStorageProfileProvider.ToolsmithWorkerView unloaded = unloaded(SECOND);
        List<ProfessionalStorageTab> partial = tabs(
                List.of(loaded(FIRST, true), unloaded),
                2,
                new ToolsmithProfessionalStorageProfileProvider.ToolsmithDemandView(1, 0, 0, 0));
        assertEquals("Worker unavailable", value(partial, "Status"));
        assertEquals("Partial: Yes", value(partial, "Crafting table"));
        assertEquals("Partial: 1", value(partial, "Recipient demand"));
        assertEquals("1", value(partial, "Pickaxes needed"));

        List<ProfessionalStorageTab> unavailable = tabs(List.of(unloaded(FIRST), unloaded), 2, null);
        assertEquals("Worker unavailable", value(unavailable, "Status"));
        assertEquals("Unknown", value(unavailable, "Crafting table"));
        assertEquals("Not measured", value(unavailable, "Recipient demand"));
        assertEquals("Not measured", value(unavailable, "Fishing rods needed"));
    }

    @Test
    void persistentTotalsAggregateDistinctLoadedAndUnloadedWorkerIds() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ToolsmithWorkMetrics.record(state, FIRST, ToolsmithWorkMetrics.TOOLS_CRAFTED, 3, true);
        ToolsmithWorkMetrics.record(state, SECOND, ToolsmithWorkMetrics.TOOLS_CRAFTED, 5, true);
        ToolsmithWorkMetrics.record(state, FIRST, ToolsmithWorkMetrics.SMITHING_JOBS_COMPLETED, 2, true);
        ToolsmithWorkMetrics.record(state, SECOND, ToolsmithWorkMetrics.TOOLS_DISTRIBUTED, 7, true);

        ToolsmithProfessionalStorageProfileProvider.ToolsmithCareerTotals totals =
                ToolsmithProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST, SECOND, FIRST),
                        ToolsmithWorkMetrics.TOOLSMITH_ROLE);
        assertEquals(new ToolsmithProfessionalStorageProfileProvider.ToolsmithCareerTotals(8, 2, 7), totals);
    }

    private static List<ProfessionalStorageTab> tabs(
            List<ToolsmithProfessionalStorageProfileProvider.ToolsmithWorkerView> workers,
            long storage,
            ToolsmithProfessionalStorageProfileProvider.ToolsmithDemandView demand
    ) {
        return ToolsmithProfessionalStorageProfileProvider.buildTabs(
                workers,
                storage,
                demand,
                new ToolsmithProfessionalStorageProfileProvider.ToolsmithCareerTotals(11, 12, 13));
    }

    private static ToolsmithProfessionalStorageProfileProvider.ToolsmithWorkerView loaded(UUID uuid, boolean table) {
        return new ToolsmithProfessionalStorageProfileProvider.ToolsmithWorkerView(
                uuid, ProfessionalStorageResolution.WorkerAvailability.LOADED, table);
    }

    private static ToolsmithProfessionalStorageProfileProvider.ToolsmithWorkerView unloaded(UUID uuid) {
        return new ToolsmithProfessionalStorageProfileProvider.ToolsmithWorkerView(
                uuid, ProfessionalStorageResolution.WorkerAvailability.UNLOADED, null);
    }

    private static ToolsmithProfessionalStorageProfileProvider.StorageStackView stack(
            ToolsmithProfessionalStorageProfileProvider.StorageToolKind kind,
            long count
    ) {
        return new ToolsmithProfessionalStorageProfileProvider.StorageStackView(kind, count);
    }

    private static List<String> labels(ProfessionalStorageTab tab) {
        return tab.rows().stream().map(ProfessionalStorageRow::label).toList();
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
