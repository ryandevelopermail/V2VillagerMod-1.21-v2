package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalWorkStatsState;
import dev.sterner.guardvillagers.common.professionalstorage.WeaponsmithWorkMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeaponsmithCompletionSeamTest {
    private static final UUID WORKER = UUID.fromString("72000000-0000-0000-0000-000000000001");

    @Test
    void craftingPredicatePreservesWeaponClassesAndRejectsToolExclusions() {
        assertTrue(WeaponsmithCraftingGoal.isCraftedWeaponShape(true, false, false, false, false, false,
                false, false, false, false));
        assertTrue(WeaponsmithCraftingGoal.isCraftedWeaponShape(false, false, false, false, false, true,
                false, false, false, false));
        assertTrue(WeaponsmithCraftingGoal.isCraftedWeaponShape(false, false, false, false, false, false,
                true, false, false, false));
        assertFalse(WeaponsmithCraftingGoal.isCraftedWeaponShape(false, false, false, false, false, false,
                true, true, false, false));
        assertFalse(WeaponsmithCraftingGoal.isCraftedWeaponShape(false, false, false, false, false, false,
                true, false, true, false));
        assertFalse(WeaponsmithCraftingGoal.isCraftedWeaponShape(false, false, false, false, false, false,
                true, false, false, true));
        assertFalse(WeaponsmithCraftingGoal.isCraftedWeaponShape(false, false, false, false, false, false,
                false, false, false, false));
    }

    @Test
    void recipeRequiresSupportedOutputAndAvailableIngredients() {
        assertTrue(WeaponsmithCraftingGoal.isRecipeAvailable(true, true));
        assertFalse(WeaponsmithCraftingGoal.isRecipeAvailable(true, false));
        assertFalse(WeaponsmithCraftingGoal.isRecipeAvailable(false, true));
    }

    @Test
    void confirmedCraftShortCircuitsAndEffectsRunOnce() {
        AtomicInteger consumes = new AtomicInteger();
        AtomicInteger inserts = new AtomicInteger();
        assertFalse(WeaponsmithCraftingGoal.executeConfirmedCraft(
                () -> false,
                () -> { consumes.incrementAndGet(); return true; },
                () -> { inserts.incrementAndGet(); return true; }));
        assertEquals(0, consumes.get());
        assertEquals(0, inserts.get());

        AtomicInteger memoryWrites = new AtomicInteger();
        AtomicInteger metricWrites = new AtomicInteger();
        assertTrue(WeaponsmithCraftingGoal.executeConfirmedCraft(() -> true, () -> true, () -> true));
        WeaponsmithCraftingGoal.runConfirmedCraftEffects(memoryWrites::incrementAndGet, metricWrites::incrementAndGet);
        assertEquals(1, memoryWrites.get());
        assertEquals(1, metricWrites.get());
    }

    @Test
    void repairPairsAreDisjointAndRequireMatchingSupportedDamagedItems() {
        Object sword = new Object();
        Object axe = new Object();
        assertEquals(0, WeaponsmithRepairGoal.countRepairPairs(List.of(repair(sword))));
        assertEquals(1, WeaponsmithRepairGoal.countRepairPairs(List.of(repair(sword), repair(sword), repair(sword))));
        assertEquals(2, WeaponsmithRepairGoal.countRepairPairs(List.of(
                repair(sword), repair(sword), repair(sword), repair(sword))));
        assertEquals(0, WeaponsmithRepairGoal.countRepairPairs(List.of(repair(sword), repair(axe))));
        assertEquals(0, WeaponsmithRepairGoal.countRepairPairs(List.of(
                new WeaponsmithRepairGoal.RepairInputView(sword, true, false, true),
                new WeaponsmithRepairGoal.RepairInputView(sword, true, true, false))));
    }

    @Test
    void repairMetricOnlyRunsAfterCapacityConsumptionAndInsertionComplete() {
        AtomicInteger completions = new AtomicInteger();
        assertFalse(WeaponsmithRepairGoal.executeConfirmedRepair(
                () -> false, () -> true, () -> true, completions::incrementAndGet));
        assertFalse(WeaponsmithRepairGoal.executeConfirmedRepair(
                () -> true, () -> false, () -> true, completions::incrementAndGet));
        assertFalse(WeaponsmithRepairGoal.executeConfirmedRepair(
                () -> true, () -> true, () -> false, completions::incrementAndGet));
        assertTrue(WeaponsmithRepairGoal.executeConfirmedRepair(
                () -> true, () -> true, () -> true, completions::incrementAndGet));
        assertEquals(1, completions.get());
        assertTrue(WeaponsmithRepairGoal.isRepairWorksiteValid(true));
        assertFalse(WeaponsmithRepairGoal.isRepairWorksiteValid(false));
    }

    @Test
    void distributionAcceptsOnlySixWeaponFamiliesAndDirectRoute() {
        for (int family = 0; family < 6; family++) {
            boolean[] flags = new boolean[6];
            flags[family] = true;
            assertTrue(WeaponsmithDistributionGoal.isDistributableWeaponShape(
                    flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]));
        }
        assertFalse(WeaponsmithDistributionGoal.isDistributableWeaponShape(
                false, false, false, false, false, false));
        assertTrue(WeaponsmithDistributionGoal.isConfirmedEquipmentCompletion(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true));
        assertFalse(WeaponsmithDistributionGoal.isConfirmedEquipmentCompletion(
                AbstractInventoryDistributionGoal.TransferRoute.UNIVERSAL, true));
        assertFalse(WeaponsmithDistributionGoal.isConfirmedEquipmentCompletion(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, false));
    }

    @Test
    void equipmentRetryRecordsExactlyOneDirectCompletion() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.INCOMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true, () -> false, () -> 2L,
                        count -> state.increment(WORKER, WeaponsmithWorkMetrics.WEAPONSMITH_ROLE,
                                WeaponsmithWorkMetrics.WEAPONS_EQUIPPED, count)));
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.COMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true, () -> true, () -> 2L,
                        count -> state.increment(WORKER, WeaponsmithWorkMetrics.WEAPONSMITH_ROLE,
                                WeaponsmithWorkMetrics.WEAPONS_EQUIPPED, count)));
        assertEquals(2, state.read(WORKER, WeaponsmithWorkMetrics.WEAPONSMITH_ROLE,
                WeaponsmithWorkMetrics.WEAPONS_EQUIPPED));
    }

    @Test
    void invalidDistributionTargetNeverAttemptsOrRecords() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.TARGET_INVALID,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        false,
                        () -> { attempts.incrementAndGet(); return true; },
                        () -> 1,
                        ignored -> completions.incrementAndGet()));
        assertEquals(0, attempts.get());
        assertEquals(0, completions.get());
    }

    private static WeaponsmithRepairGoal.RepairInputView repair(Object itemType) {
        return new WeaponsmithRepairGoal.RepairInputView(itemType, true, true, true);
    }
}
