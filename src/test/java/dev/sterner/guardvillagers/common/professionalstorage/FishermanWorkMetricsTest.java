package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FishermanWorkMetricsTest {
    private static final UUID WORKER = UUID.fromString("92000000-0000-0000-0000-000000000001");

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
}
