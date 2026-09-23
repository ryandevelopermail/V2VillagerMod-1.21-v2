package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishermanWorkMetricsTest {
    private static final UUID WORKER = UUID.fromString("92000000-0000-0000-0000-000000000001");
    private static final UUID GUARD = UUID.fromString("92000000-0000-0000-0000-000000000002");

    @Test
    void metricIdsPersistAndIgnoreNonpositiveOrUnconfirmedValues() {
        assertEquals("fisherman.fishing_rods_crafted", FishermanWorkMetrics.FISHING_RODS_CRAFTED.value());
        assertEquals("fisherman.buckets_crafted", FishermanWorkMetrics.BUCKETS_CRAFTED.value());
        assertEquals("fisherman.boats_crafted", FishermanWorkMetrics.BOATS_CRAFTED.value());
        assertEquals("fisherman.fish_delivered", FishermanWorkMetrics.FISH_DELIVERED.value());
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        FishermanWorkMetrics.record(state, WORKER, FishermanWorkMetrics.FISHING_RODS_CRAFTED, 4, true);
        FishermanWorkMetrics.record(state, WORKER, FishermanWorkMetrics.BUCKETS_CRAFTED, 0, true);
        FishermanWorkMetrics.record(state, WORKER, FishermanWorkMetrics.BOATS_CRAFTED, -1, true);
        FishermanWorkMetrics.record(state, WORKER, FishermanWorkMetrics.FISH_DELIVERED, 8, false);
        assertEquals(4, state.read(WORKER, FishermanWorkMetrics.FISHERMAN_ROLE,
                FishermanWorkMetrics.FISHING_RODS_CRAFTED));
        assertEquals(0, state.read(WORKER, FishermanWorkMetrics.FISHERMAN_ROLE,
                FishermanWorkMetrics.FISH_DELIVERED));
    }

    @Test
    void confirmedConversionMovesOnlyFishermanMetricsAndMergesWithGuardTotals() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ProfessionalMetricId unrelatedMetric = new ProfessionalMetricId("test.unrelated");
        FishermanWorkMetrics.record(state, WORKER, FishermanWorkMetrics.FISHING_RODS_CRAFTED, 4, true);
        FishermanWorkMetrics.record(state, WORKER, FishermanWorkMetrics.BUCKETS_CRAFTED, 3, true);
        FishermanWorkMetrics.record(state, WORKER, FishermanWorkMetrics.BOATS_CRAFTED, 2, true);
        FishermanWorkMetrics.record(state, WORKER, FishermanWorkMetrics.FISH_DELIVERED, 8, true);
        state.increment(WORKER, FishermanWorkMetrics.FISHERMAN_ROLE, unrelatedMetric, 9);
        state.increment(WORKER, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.FISHING_RODS_CRAFTED, 11);
        state.increment(GUARD, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.FISHING_RODS_CRAFTED, 6);

        state.setDirty(false);
        assertTrue(FishermanWorkMetrics.transferToGuard(state, WORKER, GUARD));
        assertTrue(state.isDirty());
        assertEquals(0, state.read(WORKER, FishermanWorkMetrics.FISHERMAN_ROLE,
                FishermanWorkMetrics.FISHING_RODS_CRAFTED));
        assertEquals(10, state.read(GUARD, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.FISHING_RODS_CRAFTED));
        assertEquals(3, state.read(GUARD, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.BUCKETS_CRAFTED));
        assertEquals(2, state.read(GUARD, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.BOATS_CRAFTED));
        assertEquals(8, state.read(GUARD, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.FISH_DELIVERED));
        assertEquals(9, state.read(WORKER, FishermanWorkMetrics.FISHERMAN_ROLE, unrelatedMetric));
        assertEquals(11, state.read(WORKER, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.FISHING_RODS_CRAFTED));

        state.setDirty(false);
        assertFalse(FishermanWorkMetrics.transferToGuard(state, WORKER, GUARD));
        assertFalse(state.isDirty());
    }

    @Test
    void transferredTotalsSaturateAtSignedLongMaximum() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        FishermanWorkMetrics.record(state, WORKER, FishermanWorkMetrics.FISH_DELIVERED, 10, true);
        state.increment(GUARD, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.FISH_DELIVERED, Long.MAX_VALUE - 3);

        assertTrue(FishermanWorkMetrics.transferToGuard(state, WORKER, GUARD));
        assertEquals(Long.MAX_VALUE, state.read(GUARD, ProfessionalRoleId.FISHERMAN_GUARD,
                FishermanWorkMetrics.FISH_DELIVERED));
        assertEquals(0, state.read(WORKER, FishermanWorkMetrics.FISHERMAN_ROLE,
                FishermanWorkMetrics.FISH_DELIVERED));
    }
}
