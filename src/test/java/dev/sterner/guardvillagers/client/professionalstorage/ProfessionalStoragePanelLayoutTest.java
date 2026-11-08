package dev.sterner.guardvillagers.client.professionalstorage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionalStoragePanelLayoutTest {
    @Test
    void wrappedLineCountsDetermineRowHeight() {
        assertEquals(27, ProfessionalStoragePanelLayout.rowHeight(1, 1));
        assertEquals(45, ProfessionalStoragePanelLayout.rowHeight(2, 2));

        ProfessionalStorageTextLayout.WrappedText wrapped = ProfessionalStorageTextLayout.wrap(
                "Farmland coverage remains readable",
                12,
                String::length);
        assertEquals(List.of("Farmland", "coverage", "remains", "readable"), wrapped.lines());
        assertFalse(wrapped.ellipsized());
        assertEquals(54, ProfessionalStoragePanelLayout.rowHeight(wrapped.lines().size(), 1));
    }

    @Test
    void unbreakableTokenEllipsizesAndReportsTooltipNeed() {
        ProfessionalStorageTextLayout.WrappedText wrapped = ProfessionalStorageTextLayout.wrap(
                "unbreakable-value",
                8,
                String::length);

        assertEquals(List.of("unbreak…"), wrapped.lines());
        assertTrue(wrapped.ellipsized());
    }

    @Test
    void scrollRangeAndClampingCoverFitAndOverflow() {
        assertEquals(0, ProfessionalStoragePanelLayout.maxScroll(80, 100));
        assertEquals(55, ProfessionalStoragePanelLayout.maxScroll(155, 100));
        assertEquals(0, ProfessionalStoragePanelLayout.clampScroll(-4, 55));
        assertEquals(31, ProfessionalStoragePanelLayout.clampScroll(31, 55));
        assertEquals(55, ProfessionalStoragePanelLayout.clampScroll(90, 55));
    }

    @Test
    void tabOffsetsRemainIndependentAndAreClampedOnRefresh() {
        ProfessionalStorageScrollState state = new ProfessionalStorageScrollState();
        state.synchronizeTabs(List.of("overview", "statistics"));
        assertEquals("overview", state.selectedTabId());

        state.setOffset("overview", 20, 60);
        state.select("statistics");
        state.setOffset("statistics", 45, 80);
        state.select("overview");
        assertEquals(20, state.offset("overview"));
        assertEquals(45, state.offset("statistics"));

        state.clampOffsets(Map.of("overview", 10, "statistics", 0));
        assertEquals(10, state.offset("overview"));
        assertEquals(0, state.offset("statistics"));
    }

    @Test
    void scrollbarThumbSizingAndPositionsAreProportionalAndBounded() {
        assertEquals(100, ProfessionalStoragePanelLayout.scrollbarThumbHeight(100, 80));
        assertEquals(50, ProfessionalStoragePanelLayout.scrollbarThumbHeight(100, 200));
        assertEquals(25, ProfessionalStoragePanelLayout.scrollbarThumbOffset(50, 100, 100, 50));
        assertEquals(50, ProfessionalStoragePanelLayout.scrollOffsetForThumb(25, 100, 100, 50));
        assertEquals(50, ProfessionalStoragePanelLayout.scrollbarThumbOffset(1000, 100, 100, 50));
    }

    @Test
    void combinedContainerLayoutNeverUsesANegativeLeftEdge() {
        assertEquals(6, ProfessionalStoragePanelLayout.clampContainerX(320, 308));
        assertEquals(0, ProfessionalStoragePanelLayout.clampContainerX(300, 308));
    }
}
