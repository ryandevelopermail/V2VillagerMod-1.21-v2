package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperV1BatchProgressTest {
    @Test
    void expandsRepeatedAndMixedProfessionsInStableOrder() {
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 2),
                new DeveloperProfessionSelection(DeveloperProfession.FLETCHER, 1)));

        assertEquals(3, progress.total());
        assertEquals(DeveloperProfession.FARMER, progress.currentProfession());
        progress.markPrepared();
        progress.markSubjectReleased();
        progress.finishCurrent(true);
        assertEquals(DeveloperProfession.FARMER, progress.currentProfession());
        progress.markPrepared();
        progress.markSubjectReleased();
        progress.finishCurrent(true);
        assertEquals(DeveloperProfession.FLETCHER, progress.currentProfession());
    }

    @Test
    void failedVillagerDoesNotPreventRestOfBatch() {
        DeveloperV1BatchProgress progress = new DeveloperV1BatchProgress(List.of(
                new DeveloperProfessionSelection(DeveloperProfession.FARMER, 1),
                new DeveloperProfessionSelection(DeveloperProfession.SHEPHERD, 1)));

        progress.markPrepared();
        assertTrue(progress.releaseRequired());
        progress.markSubjectReleased();
        progress.finishCurrent(false);

        assertFalse(progress.isComplete());
        assertEquals(DeveloperProfession.SHEPHERD, progress.currentProfession());
        progress.markPrepared();
        progress.markSubjectReleased();
        progress.finishCurrent(true);
        assertTrue(progress.isComplete());
        assertEquals(1, progress.successful());
        assertEquals(1, progress.failed());
        assertEquals(2, progress.processed());
    }

    @Test
    void mixedBatchPlacementGridKeepsEveryJobSiteDistinct() {
        int total = 13;
        Set<DeveloperV1PlacementGrid.Offset> offsets = IntStream.range(0, total)
                .mapToObj(index -> DeveloperV1PlacementGrid.offsetFor(index, total))
                .collect(Collectors.toSet());

        assertEquals(total, offsets.size());
        for (DeveloperV1PlacementGrid.Offset first : offsets) {
            for (DeveloperV1PlacementGrid.Offset second : offsets) {
                if (first.equals(second)) {
                    continue;
                }
                int dx = first.x() - second.x();
                int dz = first.z() - second.z();
                assertTrue(dx * dx + dz * dz >= 36);
            }
        }
    }
}
