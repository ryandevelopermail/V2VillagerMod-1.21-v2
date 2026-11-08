package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.ClericBrewingGoal;
import dev.sterner.guardvillagers.common.villager.behavior.ClericBehavior;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClericProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("89000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("89000000-0000-0000-0000-000000000002");

    @Test
    void nativeClericRegistersWithExpectedTitle() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(ClericWorkMetrics.CLERIC_ROLE));
        assertEquals("minecraft:cleric", ClericWorkMetrics.CLERIC_ROLE.toString());
        assertEquals("Cleric Storage", ProfessionalStorageSnapshotFactory.titleFor(
                ClericWorkMetrics.CLERIC_ROLE,
                1,
                1));
    }

    @Test
    void tabsRowsOrderValuesAndTonesAreExact() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, true, true, ClericBrewingGoal.BottleStage.AWKWARD_READY,
                        "Splash Healing", 8, 1, 6, 2)),
                new ClericProfessionalStorageProfileProvider.ClericStorageCounts(12, 4, 11),
                ClericBrewingGoal.BottleStage.AWKWARD_READY,
                "Splash Healing",
                8,
                1,
                6L,
                2);

        assertEquals(List.of("overview", "brewing", "crafting", "support"),
                tabs.stream().map(ProfessionalStorageTab::id).toList());
        assertEquals(List.of("Overview", "Brewing", "Crafting", "Support"),
                tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of("Status", "Crafting table", "Brewing stand"), labels(tabs.get(0)));
        assertEquals(List.of("Bottle stage", "Brew target", "Reachable potions",
                "Potions in storage", "Potions completed"), labels(tabs.get(1)));
        assertEquals(List.of("Craftable brewing stands", "Brewing stands crafted"), labels(tabs.get(2)));
        assertEquals(List.of("Healing reserve", "Injured guards", "Healing potions thrown",
                "Potions awaiting delivery", "Potions delivered"), labels(tabs.get(3)));
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("Yes", value(tabs, "Crafting table"));
        assertEquals("Yes", value(tabs, "Brewing stand"));
        assertEquals("Awkward ready", value(tabs, "Bottle stage"));
        assertEquals("Splash Healing", value(tabs, "Brew target"));
        assertEquals("8", value(tabs, "Reachable potions"));
        assertEquals("12", value(tabs, "Potions in storage"));
        assertEquals("17", value(tabs, "Potions completed"));
        assertEquals("1", value(tabs, "Craftable brewing stands"));
        assertEquals("11", value(tabs, "Brewing stands crafted"));
        assertEquals("6", value(tabs, "Healing reserve"));
        assertEquals("2", value(tabs, "Injured guards"));
        assertEquals("13", value(tabs, "Healing potions thrown"));
        assertEquals("11", value(tabs, "Potions awaiting delivery"));
        assertEquals("19", value(tabs, "Potions delivered"));
    }

    @Test
    void everyBottleStageIsPresentedWithoutInventingBrewingActivity() {
        Map<ClericBrewingGoal.BottleStage, String> expected = Map.of(
                ClericBrewingGoal.BottleStage.MISSING, "Missing",
                ClericBrewingGoal.BottleStage.EMPTY, "Empty",
                ClericBrewingGoal.BottleStage.WATER_PARTIAL, "Water partial",
                ClericBrewingGoal.BottleStage.WATER_READY, "Water ready",
                ClericBrewingGoal.BottleStage.AWKWARD_READY, "Awkward ready",
                ClericBrewingGoal.BottleStage.POTION_READY, "Potion ready",
                ClericBrewingGoal.BottleStage.SPLASH_READY, "Splash ready",
                ClericBrewingGoal.BottleStage.INVALID, "Invalid");
        Map<ClericBrewingGoal.BottleStage, String> actual = expected.keySet().stream()
                .collect(Collectors.toMap(stage -> stage, stage -> value(tabs(
                        List.of(loaded(FIRST, true, stage != ClericBrewingGoal.BottleStage.MISSING,
                                stage, "None", 0, 0, 0, 0)),
                        new ClericProfessionalStorageProfileProvider.ClericStorageCounts(0, 0, 0),
                        stage,
                        "None",
                        0,
                        0,
                        0L,
                        0), "Bottle stage")));
        assertEquals(expected, actual);
    }

    @Test
    void storageIsResolvedOnceAndSharedPotionUnitsAreCountedOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        ClericProfessionalStorageProfileProvider.ClericStorageCounts counts =
                ClericProfessionalStorageProfileProvider.countResolvedStorageContents(() -> {
                    resolutions.incrementAndGet();
                    return List.of(
                            stack(true, true, 3),
                            stack(true, false, 5),
                            stack(false, false, 64));
                });
        assertEquals(new ClericProfessionalStorageProfileProvider.ClericStorageCounts(8, 3, 7), counts);
        assertEquals(1, resolutions.get());
    }

    @Test
    void deterministicRepresentativeUsesLowestLoadedUuid() {
        List<ClericProfessionalStorageProfileProvider.ClericWorkerView> workers = List.of(
                loaded(SECOND, true, true, ClericBrewingGoal.BottleStage.EMPTY,
                        "None", 0, 0, 2, 0),
                loaded(FIRST, false, true, ClericBrewingGoal.BottleStage.SPLASH_READY,
                        "Splash Healing", 1, 1, 5, 3));
        assertEquals(FIRST, ClericProfessionalStorageProfileProvider.selectRepresentative(workers).orElseThrow());
        List<ProfessionalStorageTab> tabs = tabs(
                workers,
                new ClericProfessionalStorageProfileProvider.ClericStorageCounts(0, 0, 0),
                ClericBrewingGoal.BottleStage.SPLASH_READY,
                "Splash Healing",
                1,
                1,
                5L,
                3);
        assertEquals("1 / 2 ready", value(tabs, "Crafting table"));
        assertEquals("2 / 2 ready", value(tabs, "Brewing stand"));
        assertEquals("Splash ready", value(tabs, "Bottle stage"));
        assertEquals("5", value(tabs, "Healing reserve"));
    }

    @Test
    void partialAndUnavailableWorkersUseEstablishedWarningPresentation() {
        List<ProfessionalStorageTab> partial = tabs(
                List.of(
                        loaded(FIRST, true, true, ClericBrewingGoal.BottleStage.WATER_READY,
                                "Healing", 4, 1, 2, 1),
                        unloaded(SECOND)),
                new ClericProfessionalStorageProfileProvider.ClericStorageCounts(2, 1, 1),
                ClericBrewingGoal.BottleStage.WATER_READY,
                "Healing",
                4,
                1,
                2L,
                1);
        assertEquals("Worker unavailable", value(partial, "Status"));
        assertEquals("Partial: Yes", value(partial, "Crafting table"));
        assertEquals("Partial: Water ready", value(partial, "Bottle stage"));
        assertEquals("Partial: Healing", value(partial, "Brew target"));
        assertEquals("Partial: 2", value(partial, "Healing reserve"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(partial, "Injured guards").tone());

        List<ProfessionalStorageTab> unavailable = tabs(
                List.of(unloaded(FIRST), unloaded(SECOND)),
                new ClericProfessionalStorageProfileProvider.ClericStorageCounts(2, 1, 1),
                null,
                null,
                null,
                null,
                null,
                null);
        assertEquals("Unknown", value(unavailable, "Crafting table"));
        assertEquals("Unknown", value(unavailable, "Brewing stand"));
        assertEquals("Not measured", value(unavailable, "Bottle stage"));
        assertEquals("Not measured", value(unavailable, "Brew target"));
        assertEquals("Not measured", value(unavailable, "Reachable potions"));
        assertEquals("Not measured", value(unavailable, "Craftable brewing stands"));
        assertEquals("Not measured", value(unavailable, "Healing reserve"));
        assertEquals("Not measured", value(unavailable, "Injured guards"));
    }

    @Test
    void representativeInventoryReserveAddsToStorageOnceAndSaturates() {
        assertEquals(7, ClericBehavior.combineHealingReserve(2, 5));
        assertEquals(5, ClericBehavior.combineHealingReserve(-2, 5));
        assertEquals(Long.MAX_VALUE, ClericBehavior.combineHealingReserve(Long.MAX_VALUE, 5));
    }

    @Test
    void careerTotalsAggregateDistinctResolvedClerics() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.POTIONS_COMPLETED, 3, true);
        ClericWorkMetrics.record(state, SECOND, ClericWorkMetrics.POTIONS_COMPLETED, 5, true);
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.BREWING_STANDS_CRAFTED, 2, true);
        ClericWorkMetrics.record(state, SECOND, ClericWorkMetrics.HEALING_POTIONS_THROWN, 7, true);
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.POTIONS_DELIVERED, 11, true);
        assertEquals(
                new ClericProfessionalStorageProfileProvider.ClericCareerTotals(8, 2, 7, 11),
                ClericProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST, SECOND, FIRST),
                        ClericWorkMetrics.CLERIC_ROLE));
    }

    private static List<ProfessionalStorageTab> tabs(
            List<ClericProfessionalStorageProfileProvider.ClericWorkerView> workers,
            ClericProfessionalStorageProfileProvider.ClericStorageCounts storage,
            ClericBrewingGoal.BottleStage stage,
            String target,
            Integer reachable,
            Integer craftable,
            Long reserve,
            Integer injured
    ) {
        return ClericProfessionalStorageProfileProvider.buildTabs(
                workers,
                storage,
                stage,
                target,
                reachable,
                craftable,
                reserve,
                injured,
                new ClericProfessionalStorageProfileProvider.ClericCareerTotals(17, 11, 13, 19));
    }

    private static ClericProfessionalStorageProfileProvider.ClericWorkerView loaded(
            UUID uuid,
            boolean table,
            boolean stand,
            ClericBrewingGoal.BottleStage stage,
            String target,
            int reachable,
            int craftable,
            long reserve,
            int injured
    ) {
        return new ClericProfessionalStorageProfileProvider.ClericWorkerView(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                table,
                stand,
                stage,
                target,
                reachable,
                craftable,
                reserve,
                injured);
    }

    private static ClericProfessionalStorageProfileProvider.ClericWorkerView unloaded(UUID uuid) {
        return ClericProfessionalStorageProfileProvider.ClericWorkerView.unloaded(
                uuid,
                ProfessionalStorageResolution.WorkerAvailability.UNLOADED);
    }

    private static ClericProfessionalStorageProfileProvider.ClericStorageStackView stack(
            boolean supported,
            boolean healing,
            long count
    ) {
        return new ClericProfessionalStorageProfileProvider.ClericStorageStackView(supported, healing, count);
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
