package dev.sterner.guardvillagers.common.professionalstorage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeaponsmithProfessionalStorageProfileProviderTest {
    private static final UUID FIRST = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("70000000-0000-0000-0000-000000000002");

    @Test
    void stableWeaponsmithRoleRegistersProvider() {
        ProfessionalStorageProfileProviders.registerDefaults();
        assertTrue(ProfessionalStorageProfileProviders.hasProvider(WeaponsmithWorkMetrics.WEAPONSMITH_ROLE));
        assertEquals("minecraft:weaponsmith", WeaponsmithWorkMetrics.WEAPONSMITH_ROLE.toString());
    }

    @Test
    void tabsAndRowsAppearInExactRequiredOrder() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, true, 4)), 6, 4, 2, 3);

        assertEquals(List.of("Overview", "Crafting", "Repair", "Distribution"),
                tabs.stream().map(ProfessionalStorageTab::title).toList());
        assertEquals(List.of("Status", "Crafting table", "Weapons in storage"), labels(tabs.get(0)));
        assertEquals(List.of("Craftable weapons", "Weapons crafted"), labels(tabs.get(1)));
        assertEquals(List.of("Repair pairs ready", "Weapons repaired"), labels(tabs.get(2)));
        assertEquals(List.of("Eligible guard stands", "Weapons equipped"), labels(tabs.get(3)));
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("Yes", value(tabs, "Crafting table"));
        assertEquals("6", value(tabs, "Weapons in storage"));
        assertEquals("4", value(tabs, "Craftable weapons"));
        assertEquals("2", value(tabs, "Repair pairs ready"));
        assertEquals("3", value(tabs, "Eligible guard stands"));
    }

    @Test
    void storageCountIncludesOnlySupportedDistributableWeaponClasses() {
        assertEquals(21L, WeaponsmithProfessionalStorageProfileProvider.countWeaponViews(List.of(
                weapon(1), weapon(2), weapon(3), weapon(4), weapon(5), weapon(6),
                excluded(9), excluded(7), excluded(5), excluded(3), excluded(1))));
    }

    @Test
    void combinedDoubleChestContentsAreResolvedAndCountedOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        long count = WeaponsmithProfessionalStorageProfileProvider.countResolvedStorageContents(() -> {
            resolutions.incrementAndGet();
            return List.of(weapon(2), weapon(3), excluded(20));
        });
        assertEquals(5L, count);
        assertEquals(1, resolutions.get());
    }

    @Test
    void deterministicRepresentativePreventsSharedLiveValuesFromMultiplying() {
        List<WeaponsmithProfessionalStorageProfileProvider.WeaponsmithWorkerView> workers = List.of(
                loaded(SECOND, true, 5),
                loaded(FIRST, false, 5));
        assertEquals(FIRST, WeaponsmithProfessionalStorageProfileProvider.selectRepresentative(workers).orElseThrow());

        List<ProfessionalStorageTab> tabs = tabs(workers, 1, 5, 1, 2);
        assertEquals("1 / 2 ready", value(tabs, "Crafting table"));
        assertEquals("5", value(tabs, "Craftable weapons"));
        assertEquals("2", value(tabs, "Eligible guard stands"));
    }

    @Test
    void partialAndFullyUnavailableFormattingIsExact() {
        List<ProfessionalStorageTab> partial = tabs(
                List.of(loaded(FIRST, true, 2), unloaded(SECOND)), 3, 2, 1, 4);
        assertEquals("Worker unavailable", value(partial, "Status"));
        assertEquals("Partial: Yes", value(partial, "Crafting table"));
        assertEquals("2", value(partial, "Craftable weapons"));
        assertEquals("4", value(partial, "Eligible guard stands"));

        List<ProfessionalStorageTab> unavailable = tabs(
                List.of(unloaded(FIRST), unloaded(SECOND)), 3, null, 1, null);
        assertEquals("Worker unavailable", value(unavailable, "Status"));
        assertEquals("Unknown", value(unavailable, "Crafting table"));
        assertEquals("Not measured", value(unavailable, "Craftable weapons"));
        assertEquals("1", value(unavailable, "Repair pairs ready"));
        assertEquals("Not measured", value(unavailable, "Eligible guard stands"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(unavailable, "Craftable weapons").tone());
    }

    @Test
    void singleLoadedWorkerWithoutTableReportsNoAndZeroCraftableWeapons() {
        List<ProfessionalStorageTab> tabs = tabs(
                List.of(loaded(FIRST, false, 0)), 0, 0, 0, 0);
        assertEquals("Ready", value(tabs, "Status"));
        assertEquals("No", value(tabs, "Crafting table"));
        assertEquals("0", value(tabs, "Craftable weapons"));
        assertEquals(ProfessionalStorageRow.Tone.WARNING, row(tabs, "Crafting table").tone());
    }

    @Test
    void persistentTotalsAggregateDistinctLoadedAndUnloadedWorkerIds() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        WeaponsmithWorkMetrics.record(state, FIRST, WeaponsmithWorkMetrics.WEAPONS_CRAFTED, 3, true);
        WeaponsmithWorkMetrics.record(state, SECOND, WeaponsmithWorkMetrics.WEAPONS_CRAFTED, 5, true);
        WeaponsmithWorkMetrics.record(state, FIRST, WeaponsmithWorkMetrics.WEAPONS_REPAIRED, 2, true);
        WeaponsmithWorkMetrics.record(state, SECOND, WeaponsmithWorkMetrics.WEAPONS_EQUIPPED, 7, true);

        assertEquals(
                new WeaponsmithProfessionalStorageProfileProvider.WeaponsmithCareerTotals(8, 2, 7),
                WeaponsmithProfessionalStorageProfileProvider.aggregateCareerTotals(
                        state,
                        List.of(FIRST, SECOND, FIRST),
                        WeaponsmithWorkMetrics.WEAPONSMITH_ROLE));
    }

    private static List<ProfessionalStorageTab> tabs(
            List<WeaponsmithProfessionalStorageProfileProvider.WeaponsmithWorkerView> workers,
            long stored,
            Integer craftable,
            int repairPairs,
            Integer stands
    ) {
        return WeaponsmithProfessionalStorageProfileProvider.buildTabs(
                workers,
                stored,
                craftable,
                repairPairs,
                stands,
                new WeaponsmithProfessionalStorageProfileProvider.WeaponsmithCareerTotals(11, 12, 13));
    }

    private static WeaponsmithProfessionalStorageProfileProvider.WeaponsmithWorkerView loaded(
            UUID uuid,
            boolean table,
            int craftable
    ) {
        return new WeaponsmithProfessionalStorageProfileProvider.WeaponsmithWorkerView(
                uuid, ProfessionalStorageResolution.WorkerAvailability.LOADED, table, craftable);
    }

    private static WeaponsmithProfessionalStorageProfileProvider.WeaponsmithWorkerView unloaded(UUID uuid) {
        return new WeaponsmithProfessionalStorageProfileProvider.WeaponsmithWorkerView(
                uuid, ProfessionalStorageResolution.WorkerAvailability.UNLOADED, null, null);
    }

    private static WeaponsmithProfessionalStorageProfileProvider.WeaponStackView weapon(long count) {
        return new WeaponsmithProfessionalStorageProfileProvider.WeaponStackView(true, count);
    }

    private static WeaponsmithProfessionalStorageProfileProvider.WeaponStackView excluded(long count) {
        return new WeaponsmithProfessionalStorageProfileProvider.WeaponStackView(false, count);
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
