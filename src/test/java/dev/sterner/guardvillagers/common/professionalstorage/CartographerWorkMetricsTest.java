package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CartographerWorkMetricsTest {
    private static final UUID FIRST = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("90000000-0000-0000-0000-000000000002");

    @Test
    void metricIdsAndRoleAreStable() {
        assertEquals("cartographer.maps_completed", CartographerWorkMetrics.MAPS_COMPLETED.value());
        assertEquals("cartographer.maps_copied", CartographerWorkMetrics.MAPS_COPIED.value());
        assertEquals("cartographer.materials_crafted", CartographerWorkMetrics.MATERIALS_CRAFTED.value());
        assertEquals("cartographer.maps_displayed", CartographerWorkMetrics.MAPS_DISPLAYED.value());
        assertEquals("minecraft:cartographer", CartographerWorkMetrics.CARTOGRAPHER_ROLE.toString());
    }

    @Test
    void allMetricIdsSurviveNbtRoundTrip() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_COMPLETED, 4, true);
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_COPIED, 2, true);
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MATERIALS_CRAFTED, 7, true);
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_DISPLAYED, 3, true);

        NbtCompound nbt = state.writeNbt(new NbtCompound(), null);
        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(nbt, null);
        assertEquals(4, restored.read(FIRST, CartographerWorkMetrics.CARTOGRAPHER_ROLE,
                CartographerWorkMetrics.MAPS_COMPLETED));
        assertEquals(2, restored.read(FIRST, CartographerWorkMetrics.CARTOGRAPHER_ROLE,
                CartographerWorkMetrics.MAPS_COPIED));
        assertEquals(7, restored.read(FIRST, CartographerWorkMetrics.CARTOGRAPHER_ROLE,
                CartographerWorkMetrics.MATERIALS_CRAFTED));
        assertEquals(3, restored.read(FIRST, CartographerWorkMetrics.CARTOGRAPHER_ROLE,
                CartographerWorkMetrics.MAPS_DISPLAYED));
    }

    @Test
    void zeroNegativeAndUnconfirmedAmountsAreIgnored() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_DISPLAYED, 0, true);
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_DISPLAYED, -3, true);
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_DISPLAYED, 5, false);
        assertEquals(0, state.read(FIRST, CartographerWorkMetrics.CARTOGRAPHER_ROLE,
                CartographerWorkMetrics.MAPS_DISPLAYED));
    }

    @Test
    void distinctAggregationSaturatesAtLongMaximum() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        CartographerWorkMetrics.record(state, FIRST, CartographerWorkMetrics.MAPS_COPIED,
                Long.MAX_VALUE, true);
        CartographerWorkMetrics.record(state, SECOND, CartographerWorkMetrics.MAPS_COPIED, 9, true);
        assertEquals(Long.MAX_VALUE, state.aggregate(
                List.of(FIRST, SECOND, FIRST),
                CartographerWorkMetrics.CARTOGRAPHER_ROLE,
                CartographerWorkMetrics.MAPS_COPIED));
    }
}
