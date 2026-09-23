package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeaponsmithStandManagerReadOnlyTest {
    private static final UUID STAND = UUID.fromString("73000000-0000-0000-0000-000000000001");

    @Test
    void emptyStandRequiresAvailableWeaponAndCleanMemory() {
        assertTrue(WeaponsmithStandManager.isStandEligibleReadOnly(view(STAND, true, true, true, null, true, false)));
        assertFalse(WeaponsmithStandManager.isStandEligibleReadOnly(view(STAND, true, true, true, null, false, false)));

        WeaponsmithStandManager.StandProgress remembered = new WeaponsmithStandManager.StandProgress();
        remembered.markSlot(EquipmentSlot.MAINHAND);
        assertFalse(WeaponsmithStandManager.isStandEligibleReadOnly(
                view(STAND, true, true, true, remembered, true, false)));
    }

    @Test
    void occupiedStandRequiresARealUpgrade() {
        assertTrue(WeaponsmithStandManager.isStandEligibleReadOnly(
                view(STAND, true, true, false, null, true, true)));
        assertFalse(WeaponsmithStandManager.isStandEligibleReadOnly(
                view(STAND, true, true, false, null, true, false)));
    }

    @Test
    void deadOrUntaggedStandIsNeverEligible() {
        assertFalse(WeaponsmithStandManager.isStandEligibleReadOnly(
                view(STAND, false, true, true, null, true, false)));
        assertFalse(WeaponsmithStandManager.isStandEligibleReadOnly(
                view(STAND, true, false, true, null, true, false)));
    }

    @Test
    void eligibleStandCountDeduplicatesByUuid() {
        WeaponsmithStandManager.StandEligibilityView eligible =
                view(STAND, true, true, true, null, true, false);
        assertEquals(1, WeaponsmithStandManager.countUniqueEligibleStandViews(List.of(eligible, eligible)));
    }

    @Test
    void readOnlySnapshotDoesNotMutateProgressMemory() {
        WeaponsmithStandManager.StandProgress progress = new WeaponsmithStandManager.StandProgress();
        progress.markSlot(EquipmentSlot.OFFHAND);
        int before = progress.getHandMask();

        WeaponsmithStandManager.StandEligibilityView snapshot =
                view(STAND, true, true, true, progress, true, false);

        assertEquals(before, progress.getHandMask());
        assertFalse(snapshot.rememberedMainHand());
        assertFalse(snapshot.memoryComplete());
    }

    private static WeaponsmithStandManager.StandEligibilityView view(
            UUID id,
            boolean alive,
            boolean tagged,
            boolean emptyMainHand,
            WeaponsmithStandManager.StandProgress progress,
            boolean candidateAvailable,
            boolean validUpgrade
    ) {
        return WeaponsmithStandManager.snapshotStandEligibility(
                id, alive, tagged, emptyMainHand, progress, candidateAvailable, validUpgrade);
    }
}
