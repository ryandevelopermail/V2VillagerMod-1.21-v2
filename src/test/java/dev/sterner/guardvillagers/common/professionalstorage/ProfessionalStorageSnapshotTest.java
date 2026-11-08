package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfessionalStorageSnapshotTest {
    @Test
    void snapshotCopiesMultipleTabsAndNestedRows() {
        List<ProfessionalStorageRow> overviewRows = new ArrayList<>();
        overviewRows.add(row("Status"));
        List<ProfessionalStorageTab> tabs = new ArrayList<>();
        tabs.add(new ProfessionalStorageTab("overview", "Overview", overviewRows));
        tabs.add(new ProfessionalStorageTab("statistics", "Statistics", List.of(row("Crafted"))));

        ProfessionalStorageSnapshot snapshot = snapshot(tabs);
        overviewRows.add(row("Late mutation"));
        tabs.clear();

        assertEquals(List.of("overview", "statistics"),
                snapshot.tabs().stream().map(ProfessionalStorageTab::id).toList());
        assertEquals(List.of("Status"),
                snapshot.tabs().getFirst().rows().stream().map(ProfessionalStorageRow::label).toList());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.tabs().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.tabs().getFirst().rows().clear());
    }

    @Test
    void rejectsTooManyTabs() {
        List<ProfessionalStorageTab> tabs = java.util.stream.IntStream
                .range(0, ProfessionalStorageSnapshot.MAX_TABS + 1)
                .mapToObj(index -> new ProfessionalStorageTab("tab-" + index, "Tab " + index, List.of(row("Row"))))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> snapshot(tabs));
    }

    @Test
    void rejectsTooManyRowsInOneTab() {
        List<ProfessionalStorageRow> rows = java.util.stream.IntStream
                .range(0, ProfessionalStorageSnapshot.MAX_ROWS_PER_TAB + 1)
                .mapToObj(index -> row("Row " + index))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(
                new ProfessionalStorageTab("overview", "Overview", rows))));
    }

    @Test
    void rejectsBlankAndDuplicateTabIds() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(
                new ProfessionalStorageTab(" ", "Overview", List.of(row("Row"))))));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(
                new ProfessionalStorageTab("overview", "Overview", List.of(row("One"))),
                new ProfessionalStorageTab("overview", "Duplicate", List.of(row("Two"))))));
    }

    @Test
    void requiresAtLeastOneNonemptyTab() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(
                new ProfessionalStorageTab("overview", "Overview", List.of()))));
    }

    private static ProfessionalStorageSnapshot snapshot(List<ProfessionalStorageTab> tabs) {
        return new ProfessionalStorageSnapshot(
                1,
                "minecraft:overworld",
                BlockPos.ORIGIN,
                ProfessionalStorageSnapshot.StorageType.NORMAL_CHEST,
                ProfessionalStorageSnapshot.RoleState.SINGLE_WORKER,
                1,
                "Farmer Storage",
                false,
                tabs);
    }

    private static ProfessionalStorageRow row(String label) {
        return new ProfessionalStorageRow(label, "Value", ProfessionalStorageRow.Tone.NORMAL);
    }
}
