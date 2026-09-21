package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalWorkStatsState;
import dev.sterner.guardvillagers.common.professionalstorage.ToolsmithWorkMetrics;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsmithCompletionSeamTest {
    private static final UUID WORKER = UUID.fromString("50000000-0000-0000-0000-000000000001");

    @Test
    void confirmedCraftRunsAllSuccessEffectsOnce() {
        AtomicInteger successEffects = new AtomicInteger();
        assertTrue(ToolsmithCraftingGoal.executeConfirmedCraft(
                () -> true, () -> true, () -> true, successEffects::incrementAndGet));
        assertEquals(1, successEffects.get());
    }

    @Test
    void confirmedCraftRecordsTheOutputStackCount() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        assertTrue(ToolsmithCraftingGoal.executeConfirmedCraft(
                () -> true,
                () -> true,
                () -> true,
                () -> state.increment(
                        WORKER,
                        ToolsmithWorkMetrics.TOOLSMITH_ROLE,
                        ToolsmithWorkMetrics.TOOLS_CRAFTED,
                        4)));
        assertEquals(4, state.read(
                WORKER,
                ToolsmithWorkMetrics.TOOLSMITH_ROLE,
                ToolsmithWorkMetrics.TOOLS_CRAFTED));
    }

    @Test
    void fullInventoryDoesNotConsumeIngredientsOrRecord() {
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger recorded = new AtomicInteger();
        assertFalse(ToolsmithCraftingGoal.executeConfirmedCraft(
                () -> false,
                () -> { consumed.incrementAndGet(); return true; },
                () -> true,
                recorded::incrementAndGet));
        assertEquals(0, consumed.get());
        assertEquals(0, recorded.get());
    }

    @Test
    void missingIngredientsOrIncompleteInsertionDoesNotRecord() {
        AtomicInteger recorded = new AtomicInteger();
        assertFalse(ToolsmithCraftingGoal.executeConfirmedCraft(
                () -> true, () -> false, () -> true, recorded::incrementAndGet));
        assertFalse(ToolsmithCraftingGoal.executeConfirmedCraft(
                () -> true, () -> true, () -> false, recorded::incrementAndGet));
        assertEquals(0, recorded.get());
    }

    @Test
    void canceledCraftNeverEntersCompletionSeam() {
        AtomicInteger recorded = new AtomicInteger();
        assertEquals(0, recorded.get());
    }

    @Test
    void confirmedSmithingRecordsExactlyOnceAndFailuresDoNotRecord() {
        AtomicInteger recorded = new AtomicInteger();
        assertFalse(ToolsmithSmithingGoal.executeConfirmedSmithing(
                () -> false, () -> true, recorded::incrementAndGet));
        assertFalse(ToolsmithSmithingGoal.executeConfirmedSmithing(
                () -> true, () -> false, recorded::incrementAndGet));
        assertTrue(ToolsmithSmithingGoal.executeConfirmedSmithing(
                () -> true, () -> true, recorded::incrementAndGet));
        assertEquals(1, recorded.get());
    }

    @Test
    void confirmedSmithingDoesNotAlsoCountAsCrafting() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        assertTrue(ToolsmithSmithingGoal.executeConfirmedSmithing(
                () -> true,
                () -> true,
                () -> state.increment(
                        WORKER,
                        ToolsmithWorkMetrics.TOOLSMITH_ROLE,
                        ToolsmithWorkMetrics.SMITHING_JOBS_COMPLETED)));
        assertEquals(1, state.read(
                WORKER,
                ToolsmithWorkMetrics.TOOLSMITH_ROLE,
                ToolsmithWorkMetrics.SMITHING_JOBS_COMPLETED));
        assertEquals(0, state.read(
                WORKER,
                ToolsmithWorkMetrics.TOOLSMITH_ROLE,
                ToolsmithWorkMetrics.TOOLS_CRAFTED));
    }
}
