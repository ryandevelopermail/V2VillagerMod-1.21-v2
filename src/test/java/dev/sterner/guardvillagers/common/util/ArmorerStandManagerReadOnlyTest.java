package dev.sterner.guardvillagers.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArmorerStandManagerReadOnlyTest {
    private static final UUID FIRST = UUID.fromString("84000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("84000000-0000-0000-0000-000000000002");

    @Test
    void emptyEligibleStandContributesFourSlotsAndHelmetContributesThree() {
        assertEquals(7, ArmorerStandManager.countEligibleArmorSlots(List.of(
                view(FIRST, true, 0, 0),
                view(SECOND, true, 0, 0b0001))));
    }

    @Test
    void persistedMemoryAndPhysicalEquipmentBothBlockSlots() {
        assertEquals(1, ArmorerStandManager.countEligibleArmorSlots(List.of(
                view(FIRST, true, 0b0011, 0b0100))));
        assertEquals(0, ArmorerStandManager.countEligibleArmorSlots(List.of(
                view(SECOND, true, 0b1111, 0))));
    }

    @Test
    void duplicateStandIdsAreDeduplicatedAndMasksAreMerged() {
        assertEquals(2, ArmorerStandManager.countEligibleArmorSlots(List.of(
                view(FIRST, true, 0b0001, 0),
                view(FIRST, true, 0, 0b0010))));
    }

    @Test
    void deadOrInvalidStandContributesNoSlots() {
        assertEquals(0, ArmorerStandManager.countEligibleArmorSlots(List.of(
                view(FIRST, false, 0, 0))));
    }

    @Test
    void memoryInspectionCopiesMasksWithoutSynchronizingOrMutatingProgress() {
        ArmorerStandManager.StandProgress progress = new ArmorerStandManager.StandProgress();
        progress.setArmorMask(0b0101);
        Map<UUID, ArmorerStandManager.StandProgress> memory = new HashMap<>();
        memory.put(FIRST, progress);

        Map<UUID, Integer> copy = ArmorerStandManager.copyStandMemoryMasks(memory);
        assertEquals(Map.of(FIRST, 0b0101), copy);
        assertEquals(1, memory.size());
        assertEquals(0b0101, progress.getArmorMask());
    }

    private static ArmorerStandManager.StandReadOnlyView view(
            UUID id,
            boolean alive,
            int persisted,
            int equipped
    ) {
        return new ArmorerStandManager.StandReadOnlyView(id, alive, persisted, equipped);
    }
}
